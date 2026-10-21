package app.daiyu.android.context

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.*
import app.daiyu.android.domain.usage.AttributedRequestUsage
import app.daiyu.android.domain.usage.UsageOrigin
import java.util.UUID

/** Part of SQLiteRuntimeStore, not another lease/writer. Every mutating entry is invoked under
 * that store's monitor and OS lease. Each observation replaces a whole nullable snapshot via CAS.
 * No recovery entry can open a transport or turn an admitted attempt into permission to retry. */
internal class SQLitePreparationRuntime(
    private val transactions: DatabaseTransactions,
    private val processOwner: String,
) {
    private data class Row(val conversation: String, val run: String, val message: String,
        val owner: String, val process: String, val revision: Long, val state: String,
        val request: RequestCheckpoint, val usage: ModelEvent.Usage?) {
        fun snapshots() = usage?.let { listOf(RequestUsageCheckpoint(request, it, UsageRequestSource.SUMMARY)) }.orEmpty()
    }

    fun create(conversation: ConversationId, run: RunId, message: MessageId,
        cancellation: ContextPreparationCancellation, access: (() -> Unit) -> Unit): RuntimeSummaryPreparation {
        val owner = UUID.randomUUID().toString()
        val request = RequestCheckpoint(UUID.randomUUID().toString(), UUID.randomUUID().toString(), owner, RequestOperation.NEXT)
        val parent = ParentSummaryUsage(conversation.value, run.value, message.value, request)
        var admitted = false
        var revoked = false
        var revision = 0L
        fun live(db: SQLiteDatabase): Row {
            check(!revoked && !cancellation.isCancelled) { "summary_preparation_cancelled" }
            val row = requireNotNull(read(db, message.value))
            check(row.conversation == conversation.value && row.run == run.value && row.request == request &&
                row.owner == owner && row.process == processOwner && row.revision == revision && row.state == "ADMITTED") {
                "summary_preparation_stale_owner_attempt_or_revision"
            }
            return row
        }
        val port = BoundSummaryUsagePort(parent, beforeClaim = {
            access {
                check(!admitted && !revoked && !cancellation.isCancelled) { "summary_preparation_not_fresh" }
                transactions.run { db ->
                    db.rawQuery("SELECT id FROM conversations WHERE id=?", arrayOf(conversation.value)).use {
                        check(it.moveToFirst()) { "summary_preparation_conversation_missing" }
                    }
                    db.rawQuery("SELECT 1 FROM messages WHERE conversation_id=? AND status='RUNNING' LIMIT 1",
                        arrayOf(conversation.value)).use { check(!it.moveToFirst()) { "summary_preparation_not_idle" } }
                    db.rawQuery("SELECT 1 FROM messages WHERE id=? OR (conversation_id=? AND run_id=?) LIMIT 1",
                        arrayOf(message.value, conversation.value, run.value)).use {
                        check(!it.moveToFirst()) { "summary_preparation_parent_already_exists" }
                    }
                    db.rawQuery("SELECT 1 FROM runtime_preparations WHERE conversation_id=? AND state IN ('ADMITTED','READY') LIMIT 1",
                        arrayOf(conversation.value)).use { check(!it.moveToFirst()) { "summary_preparation_already_active" } }
                    check(db.insertOrThrow(TABLE, null, ContentValues().apply {
                        put("message_id", message.value); put("conversation_id", conversation.value); put("run_id", run.value)
                        put("owner", owner); put("process_owner", processOwner); put("revision", 1L); put("state", "ADMITTED")
                        put("request_id", request.requestId); put("request_owner", owner); put("attempt_id", request.attemptId)
                        put("has_usage", 0)
                    }) != -1L) { "summary_preparation_admission_not_persisted" }
                }
                admitted = true; revision = 1L
            }
        }) { supplied, usage ->
            access {
                check(supplied == parent && usage.request == request && usage.source == UsageRequestSource.SUMMARY)
                val committedRevision = transactions.run { db ->
                    val row = live(db)
                    // Even identical replay validates persisted owner/attempt/revision and cancellation.
                    if (row.usage == usage.snapshot) row.revision else {
                        update(db, row, ContentValues().apply {
                            put("revision", revision + 1); put("has_usage", 1)
                            put("input_tokens", usage.snapshot.inputTokens); put("output_tokens", usage.snapshot.outputTokens)
                            put("cached_tokens", usage.snapshot.cachedTokens); put("cache_creation_tokens", usage.snapshot.cacheCreationTokens)
                        })
                        row.revision + 1
                    }
                }
                revision = committedRevision // no optimistic receipt on failed write
            }
        }
        // BoundSummaryUsagePort must not hide late duplicate observations behind an in-memory cache.
        return RuntimeSummaryPreparation(port, cancellation, sealReservation = {
            access {
                check(!revoked && !cancellation.isCancelled) { "summary_preparation_cancelled" }
                if (admitted) {
                    transactions.run { db ->
                        val row = live(db)
                        update(db, row, ContentValues().apply { put("state", "READY"); put("revision", revision + 1) })
                    }
                    revision++
                }
            }
        }, revokeReservation = {
            access {
                if (!revoked) {
                    if (admitted) transactions.run { db ->
                        val row = requireNotNull(read(db, message.value))
                        check(row.conversation == conversation.value && row.run == run.value && row.request == request)
                        if (row.state == "ADMITTED" || row.state == "READY") {
                            check(row.owner == owner && row.process == processOwner && row.revision == revision)
                            update(db, row, ContentValues().apply {
                                put("state", "TERMINAL"); put("owner", UUID.randomUUID().toString()); put("revision", row.revision + 1)
                            })
                        }
                    }
                    revoked = true
                }
            }
        })
    }

    /** Seed is read, not ownership transfer. First checkpoint repeats all checks in its transaction. */
    fun seed(db: SQLiteDatabase, initial: Message): List<RequestUsageCheckpoint> {
        val row = read(db, initial.id.value) ?: return emptyList()
        identity(row, initial)
        check(row.process == processOwner && row.state == "READY") { "summary_preparation_not_sealed_or_cancelled" }
        return row.snapshots()
    }

    fun transfer(db: SQLiteDatabase, checkpoint: RunCheckpoint) {
        val row = read(db, checkpoint.message.id.value)
        val supplied = checkpoint.usage.filter { it.source == UsageRequestSource.SUMMARY }
        if (row == null) { check(supplied.isEmpty()) { "summary_usage_without_admission" }; return }
        identity(row, checkpoint.message)
        check(row.process == processOwner && row.state == "READY" && supplied == row.snapshots()) {
            "summary_preparation_transfer_conflict"
        }
        update(db, row, ContentValues().apply {
            put("state", "HANDED"); put("owner", checkpoint.owner); put("revision", row.revision + 1)
            // Keep the report-presence bit as coverage evidence after transferring the tokens.
            // HANDED+has_usage=0 remains a missing SUMMARY request, even if MAIN reports later.
            putNull("input_tokens"); putNull("output_tokens"); putNull("cached_tokens"); putNull("cache_creation_tokens")
        })
    }

    /** Later main-run snapshots cannot drop, change, or impersonate sealed summary usage. */
    fun preserve(prior: RunCheckpoint, next: RunCheckpoint) {
        check(prior.usageRequests.all { old -> next.usageRequests.any {
            it.owner == old.owner && it.requestId == old.requestId
        } }) { "main_request_coverage_dropped" }
        check(prior.usage.filter { it.source == UsageRequestSource.SUMMARY } ==
            next.usage.filter { it.source == UsageRequestSource.SUMMARY }) { "summary_usage_changed_after_handoff" }
    }

    fun recover(db: SQLiteDatabase) {
        db.rawQuery("SELECT message_id FROM $TABLE WHERE state IN ('ADMITTED','READY')", null).use { rows ->
            val ids = buildList { while (rows.moveToNext()) add(rows.getString(0)) }
            ids.forEach { id ->
                val row = requireNotNull(read(db, id))
                check(row.process != processOwner) { "summary_live_owner_cannot_recover" }
                update(db, row, ContentValues().apply {
                    put("state", "TERMINAL"); put("owner", UUID.randomUUID().toString()); put("process_owner", processOwner)
                    put("revision", row.revision + 1)
                })
            }
        }
    }

    private fun identity(row: Row, message: Message) {
        check(row.message == message.id.value && row.conversation == message.conversationId.value && row.run == message.runId.value &&
            message.role == Message.ASSISTANT_ROLE) { "summary_preparation_parent_mismatch" }
    }
    private fun update(db: SQLiteDatabase, row: Row, values: ContentValues) {
        check(db.update(TABLE, values, "message_id=? AND owner=? AND process_owner=? AND revision=? AND state=? AND attempt_id=?",
            arrayOf(row.message, row.owner, row.process, row.revision.toString(), row.state, row.request.attemptId)) == 1) {
            "summary_preparation_cas_conflict"
        }
    }

    companion object {
        private const val TABLE = "runtime_preparations"
        private fun read(db: SQLiteDatabase, id: String): Row? = db.rawQuery(
            "SELECT conversation_id,run_id,message_id,owner,process_owner,revision,state,request_id,request_owner,attempt_id," +
                "has_usage,input_tokens,output_tokens,cached_tokens,cache_creation_tokens FROM $TABLE WHERE message_id=?", arrayOf(id)).use { c ->
            if (!c.moveToFirst()) null else {
                require((listOf(0, 1, 2, 3, 4, 6, 7, 8, 9)).all {
                    c.getType(it) == android.database.Cursor.FIELD_TYPE_STRING &&
                        c.getString(it).isNotBlank() && c.getString(it).length <= 200
                })
                require(c.getType(5) == android.database.Cursor.FIELD_TYPE_INTEGER &&
                    c.getType(10) == android.database.Cursor.FIELD_TYPE_INTEGER)
                require(c.getLong(5) >= 1 && c.getString(6) in setOf("ADMITTED", "READY", "TERMINAL", "HANDED"))
                val hasUsage = c.getLong(10)
                require(hasUsage in 0L..1L)
                fun token(i: Int): Long? {
                    if (c.isNull(i)) return null
                    require(c.getType(i) == android.database.Cursor.FIELD_TYPE_INTEGER)
                    return c.getLong(i)
                }
                val request = RequestCheckpoint(c.getString(7), c.getString(9), c.getString(8), RequestOperation.NEXT)
                Row(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getLong(5), c.getString(6),
                    request, if (hasUsage == 0L) null else ModelEvent.Usage(token(11), token(12), token(13), token(14)))
            }
        }

        /** Bounded read in the SAME reader transaction. Only HANDED with a valid report-presence
         * bit is excluded; malformed presence must reach strict per-row validation below.
         * HANDED without a report remains missing SUMMARY coverage, not a zero snapshot.
         * Counts are preparation coverage, not fictitious assistant messages. */
        fun readUsage(db: SQLiteDatabase, conversationId: ConversationId?, limit: Int): PreparationUsageRead {
            check(db.inTransaction())
            val filter = "NOT (p.state='HANDED' AND typeof(p.has_usage)='integer' AND p.has_usage=1)" +
                if (conversationId == null) "" else " AND p.conversation_id=?"
            val args = conversationId?.let { arrayOf(it.value) } ?: emptyArray()
            val from = " FROM $TABLE p JOIN conversations c ON c.id=p.conversation_id WHERE $filter"
            val total = db.rawQuery("SELECT COUNT(*)$from", args).use { it.moveToFirst(); it.getInt(0) }
            val result = mutableListOf<AttributedRequestUsage>()
            var inspected = 0; var missing = 0; var unreadable = 0
            db.rawQuery("SELECT p.message_id$from ORDER BY p.rowid DESC LIMIT ?", args + limit.toString()).use { c ->
                while (c.moveToNext()) {
                    inspected++
                    try {
                        val row = requireNotNull(read(db, c.getString(0)))
                        // Validate identity even when Usage is missing. Conversion belongs INSIDE
                        // the per-row boundary too; a malformed parent must not abort all stats.
                        require(listOf(row.conversation, row.run, row.message, row.owner, row.process)
                            .all { it.isNotBlank() && it.length <= 200 })
                        if (row.usage == null) missing++ else result.add(AttributedRequestUsage(
                            row.conversation, row.run, row.message, row.run, row.message,
                            UsageOrigin.Main, row.request, row.usage, UsageRequestSource.SUMMARY))
                    } catch (failure: Exception) {
                        // Storage/cancellation is not row corruption and must still propagate.
                        if (failure is android.database.sqlite.SQLiteException ||
                            failure is java.util.concurrent.CancellationException) throw failure
                        if (failure !is IllegalArgumentException && failure !is IllegalStateException) throw failure
                        unreadable++
                    }
                }
            }
            return PreparationUsageRead(result, total, inspected, missing, unreadable)
        }
    }
}

internal data class PreparationUsageRead(val requests: List<AttributedRequestUsage>, val total: Int,
    val inspected: Int, val missingUsage: Int, val unreadable: Int)
