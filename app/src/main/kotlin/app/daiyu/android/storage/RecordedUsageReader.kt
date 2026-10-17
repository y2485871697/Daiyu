package app.daiyu.android.storage

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import app.daiyu.android.data.repository.RecordedUsageStats
import app.daiyu.android.domain.ConversationId
import app.daiyu.android.domain.usage.*
import app.daiyu.android.model.usage.AttributedUsageStats
import app.daiyu.android.model.usage.SQLiteChildUsageStore
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
        var inspected = 0; var absent = 0; var empty = 0; var unreadable = 0; var deferred = 0
        var chargedBytes = 0L
        db.rawQuery("SELECT m.id,m.conversation_id,m.run_id,m.status,r.message_id,length(CAST(r.payload AS BLOB))" +
            from + " LEFT JOIN runtime_checkpoints r ON r.message_id=m.id" + where +
            " ORDER BY m.seq DESC LIMIT ?", args + budget.maxMessages.toString()).use { rows ->
            while (rows.moveToNext()) {
                inspected++
                if (rows.isNull(4)) { absent++; continue }
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
                    saved.message.runId.value != rows.getString(2) || saved.message.status.name != rows.getString(3)) {
                    unreadable++; continue
                }
                if (saved.usage.isEmpty()) { empty++; continue }
                if (saved.usage.size > budget.maxUsageRecords - snapshots.size) { deferred++; continue }
                saved.usage.forEach { usage ->
                    snapshots.add(AttributedRequestUsage(saved.message.conversationId.value, saved.message.runId.value,
                        saved.message.id.value, saved.message.runId.value, saved.message.id.value,
                        UsageOrigin.Main, usage.request, usage.snapshot, usage.source))
                }
            }
        }
        val preparing = app.daiyu.android.context.SQLitePreparationRuntime.readUsage(db, conversationId,
            minOf(budget.maxMessages, budget.maxUsageRecords - snapshots.size))
        snapshots.addAll(preparing.requests)
        return RecordedUsageStats(aggregateRecordedUsage(snapshots.asSequence().map { it.snapshot }), total, inspected,
            absent, empty, unreadable, deferred, preparing.total, preparing.inspected, preparing.missingUsage,
            preparing.unreadable) to snapshots.toList()
    }
}
