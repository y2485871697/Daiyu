package app.daiyu.android.storage

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import app.daiyu.android.data.repository.RecordedUsageStats
import app.daiyu.android.domain.ConversationId
import app.daiyu.android.domain.usage.*
import app.daiyu.android.model.usage.AttributedUsageStats
import app.daiyu.android.model.usage.SQLiteChildUsageStore
import app.daiyu.android.model.usage.SQLiteAdditionalUsageStore
import app.daiyu.android.domain.runtime.RequestOperation
import app.daiyu.android.domain.runtime.UsageRequestSource
import java.util.concurrent.CancellationException

internal data class RecordedUsageBudget(
    val maxMessages: Int = 500,
    val maxPayloadBytes: Long = 2L * 1024 * 1024,
    val maxTotalPayloadBytes: Long = 8L * 1024 * 1024,
    val maxUsageRecords: Int = 20_000,
) {
    init {
        require(maxMessages in 1..1000 && maxUsageRecords in 1..20_000)
        require(maxPayloadBytes in 1..(4L * 1024 * 1024))
        require(maxTotalPayloadBytes in 1..(16L * 1024 * 1024))
    }
}

/** Call inside one transaction: metadata, payload chunks and counts share a committed snapshot.
 * Reads only the latest persisted checkpoint; never acquires runtime ownership or performs recovery. */
internal object RecordedUsageReader {
    fun read(db: SQLiteDatabase, conversationId: ConversationId? = null,
        budget: RecordedUsageBudget = RecordedUsageBudget()): RecordedUsageStats = readParents(db, conversationId, budget).first

    fun readAttributed(db: SQLiteDatabase, conversationId: ConversationId? = null,
        budget: RecordedUsageBudget = RecordedUsageBudget()): AttributedUsageStats {
        val (parentCoverage, parents) = readParents(db, conversationId, budget)
        val (children, childCoverage) = SQLiteChildUsageStore.read(db, conversationId?.value,
            budget.maxMessages, budget.maxUsageRecords - parents.size)
        val requests = parents + children
        return AttributedUsageStats(aggregateAttributedUsage(requests), requests, parentCoverage, childCoverage)
    }

    private fun readParents(db: SQLiteDatabase, conversationId: ConversationId?, budget: RecordedUsageBudget):
        Pair<RecordedUsageStats, List<AttributedRequestUsage>> {
        check(db.inTransaction()) { "recorded_usage_transaction_required" }
        val where = " WHERE m.role='assistant'" + if (conversationId == null) "" else " AND m.conversation_id=?"
        val args = conversationId?.let { arrayOf(it.value) } ?: emptyArray()
        val from = " FROM messages m JOIN conversations c ON c.id=m.conversation_id"
        val total = db.rawQuery("SELECT COUNT(*)$from$where", args).use { it.moveToFirst(); it.getInt(0) }
        val snapshots = ArrayList<AttributedRequestUsage>()
        val historySnapshots = ArrayList<AttributedRequestUsage>()
        var historyRecords = 0
        val liveIdentities = mutableSetOf<Pair<String, String>>()
        var inspected = 0; var absent = 0; var empty = 0; var unreadable = 0; var deferred = 0
        var mainMissing = 0
        var chargedBytes = 0L
        db.rawQuery("SELECT m.id,m.conversation_id,m.run_id,m.status,r.message_id,length(CAST(r.payload AS BLOB)),h.message_id,h.conversation_id,h.run_id" +
            from + " LEFT JOIN runtime_checkpoints r ON r.message_id=m.id" +
            " LEFT JOIN recorded_usage_checkpoints h ON h.message_id=m.id" + where +
            " ORDER BY m.seq DESC LIMIT ?", args + budget.maxMessages.toString()).use { rows ->
            while (rows.moveToNext()) {
                inspected++
                if (rows.isNull(4)) {
                    if (rows.isNull(6)) { absent++; continue }
                    try {
                        require(rows.getString(7) == rows.getString(1) && rows.getString(8) == rows.getString(2))
                        val count = db.rawQuery("SELECT count(*) FROM recorded_usage_requests WHERE kind='CHECKPOINT' AND message_id=?",
                            arrayOf(rows.getString(0))).use { it.moveToFirst(); it.getInt(0) }
                        if (count > budget.maxUsageRecords - snapshots.size - historyRecords) { deferred++; continue }
                        val observations = db.rawQuery("SELECT * FROM recorded_usage_requests WHERE kind='CHECKPOINT' AND message_id=? ORDER BY request_owner,request_id",
                            arrayOf(rows.getString(0))).use { c -> buildList {
                                while (c.moveToNext()) {
                                    val observation = RecordedUsageHistory.observation(c)
                                    require(observation.usage.conversationId == rows.getString(1) &&
                                        observation.usage.parentRunId == rows.getString(2) &&
                                        c.getString(c.getColumnIndexOrThrow("parent_message_id")) == rows.getString(0))
                                    add(observation)
                                }
                            } }
                        historyRecords += observations.size
                        if (observations.none { it.hasUsage && it.usage.source == UsageRequestSource.MAIN }) empty++
                        mainMissing += observations.count { !it.hasUsage && it.usage.source == UsageRequestSource.MAIN }
                        historySnapshots.addAll(observations.filter { it.hasUsage }.map { it.usage })
                    } catch (failure: Exception) {
                        if (failure is SQLiteException || failure is CancellationException) throw failure
                        if (failure !is IllegalArgumentException && failure !is IllegalStateException) throw failure
                        unreadable++
                    }
                    continue
                }
                val size = rows.getLong(5)
                if (size !in 1..RunCheckpointCodec.MAX_BYTES.toLong()) { unreadable++; continue }
                if (size > budget.maxPayloadBytes || size > budget.maxTotalPayloadBytes - chargedBytes) {
                    deferred++; continue
                }
                chargedBytes += size
                val saved = try { SQLiteRuntimeStore.readCheckpointSnapshot(db, rows.getString(0)) }
                catch (failure: Exception) {
                    if (failure is SQLiteException || failure is CancellationException) throw failure
                    null
                }
                if (saved == null || saved.message.conversationId.value != rows.getString(1) ||
                    saved.message.runId.value != rows.getString(2) || saved.message.status.name != rows.getString(3) ||
                    saved.usage.any { it.source == UsageRequestSource.MANUAL_SUMMARY }) {
                    unreadable++; continue
                }
                if (saved.usage.size > budget.maxUsageRecords - snapshots.size - historyRecords) { deferred++; continue }
                // A summary report is not evidence that any MAIN request reported Usage.
                val main = saved.usage.filter { it.source == UsageRequestSource.MAIN }
                if (main.isEmpty()) empty++
                val admitted = saved.usageRequests.ifEmpty {
                    listOfNotNull(saved.request?.takeIf { it.operation != RequestOperation.OPEN })
                }
                liveIdentities.addAll((admitted + saved.usage.map { it.request }).map { it.owner to it.requestId })
                mainMissing += admitted.count { request -> main.none {
                    it.request.owner == request.owner && it.request.requestId == request.requestId
                } }
                saved.usage.forEach { usage ->
                    snapshots.add(AttributedRequestUsage(saved.message.conversationId.value, saved.message.runId.value,
                        saved.message.id.value, saved.message.runId.value, saved.message.id.value,
                        UsageOrigin.Main, usage.request, usage.snapshot, usage.source))
                }
            }
        }
        val preparing = app.daiyu.android.context.SQLitePreparationRuntime.readUsage(db, conversationId,
            minOf(budget.maxMessages, budget.maxUsageRecords - snapshots.size - historyRecords))
        snapshots.addAll(preparing.requests)
        // A live preparation or checkpoint takes precedence over an imported observation.
        val historyFilter = "kind='PREPARATION' AND NOT EXISTS (SELECT 1 FROM runtime_preparations p WHERE " +
            "p.message_id=recorded_usage_requests.message_id OR (p.request_owner=recorded_usage_requests.request_owner AND p.request_id=recorded_usage_requests.request_id))" +
            " AND NOT EXISTS (SELECT 1 FROM runtime_checkpoints r WHERE r.message_id=recorded_usage_requests.message_id)" +
            if (conversationId == null) "" else " AND conversation_id=?"
        val historyTotal = db.rawQuery("SELECT count(*) FROM recorded_usage_requests WHERE $historyFilter", args).use { it.moveToFirst(); it.getInt(0) }
        var historyInspected = 0; var historyMissing = 0; var historyUnreadable = 0
        db.rawQuery("SELECT * FROM recorded_usage_requests WHERE $historyFilter ORDER BY message_id LIMIT ?",
            args + minOf(budget.maxMessages, budget.maxUsageRecords - snapshots.size - historyRecords).toString()).use { c ->
            while (c.moveToNext()) {
                historyInspected++; historyRecords++
                try {
                    val observation = RecordedUsageHistory.observation(c)
                    val usage = observation.usage
                    db.rawQuery("SELECT conversation_id,run_id,role FROM messages WHERE id=?", arrayOf(requireNotNull(usage.messageId))).use { parent ->
                        if (parent.moveToFirst()) require(parent.getString(0) == usage.conversationId && parent.getString(1) == usage.runId &&
                            parent.getString(2) == "assistant" && !c.isNull(c.getColumnIndexOrThrow("parent_message_id")))
                        else require(c.isNull(c.getColumnIndexOrThrow("parent_message_id")))
                    }
                    if (observation.hasUsage) historySnapshots.add(usage) else historyMissing++
                } catch (failure: Exception) {
                    if (failure is SQLiteException || failure is CancellationException) throw failure
                    if (failure !is IllegalArgumentException && failure !is IllegalStateException) throw failure
                    historyUnreadable++
                }
            }
        }
        val auxiliary = SQLiteAdditionalUsageStore.read(db, conversationId, UsageRequestSource.AUXILIARY_VISION,
            minOf(budget.maxMessages, budget.maxUsageRecords - snapshots.size - historyRecords))
        snapshots.addAll(auxiliary.requests)
        val title = SQLiteAdditionalUsageStore.read(db, conversationId, UsageRequestSource.TITLE,
            minOf(budget.maxMessages, budget.maxUsageRecords - snapshots.size - historyRecords))
        snapshots.addAll(title.requests)
        val manual = SQLiteAdditionalUsageStore.read(db, conversationId, UsageRequestSource.MANUAL_SUMMARY,
            minOf(budget.maxMessages, budget.maxUsageRecords - snapshots.size - historyRecords))
        snapshots.addAll(manual.requests)
        liveIdentities.addAll(snapshots.map { it.request.owner to it.request.requestId })
        snapshots.addAll(historySnapshots.filter { liveIdentities.add(it.request.owner to it.request.requestId) })
        return RecordedUsageStats(aggregateRecordedUsage(snapshots.asSequence().map { it.snapshot }), total, inspected,
            absent, empty, unreadable, deferred, preparing.total + historyTotal, preparing.inspected + historyInspected,
            preparing.missingUsage + historyMissing, preparing.unreadable + historyUnreadable, mainMissing, auxiliary.total, auxiliary.inspected, auxiliary.missing, auxiliary.unreadable,
            title.total, title.inspected, title.missing, title.unreadable,
            manual.total, manual.inspected, manual.missing, manual.unreadable) to snapshots.toList()
    }
}
