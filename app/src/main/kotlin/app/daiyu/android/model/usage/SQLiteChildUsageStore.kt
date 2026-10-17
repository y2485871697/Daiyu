package app.daiyu.android.model.usage

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import app.daiyu.android.context.DatabaseTransactions
import app.daiyu.android.domain.ModelEvent
import app.daiyu.android.domain.delegation.ChildTaskRecord
import app.daiyu.android.domain.runtime.RequestCheckpoint
import app.daiyu.android.domain.runtime.RequestOperation
import app.daiyu.android.domain.runtime.RunCheckpoint
import app.daiyu.android.domain.usage.*
import app.daiyu.android.storage.ConversationDatabase
import java.util.concurrent.CancellationException

/** The caller must hold the live SQLiteRuntimeStore lease for every write. */
internal class SQLiteChildUsageStore(database: ConversationDatabase) : ChildUsageRecorder {
    private val transactions = DatabaseTransactions { database.writableDatabase }

    override fun record(task: ChildTaskRecord, checkpoint: RunCheckpoint) {
        val records = childRequestUsage(task, checkpoint)
        if (records.isEmpty()) return
        transactions.run { db ->
            db.rawQuery("SELECT 1 FROM child_tasks WHERE task_id=? AND conversation_id=? AND parent_run_id=?" +
                " AND parent_message_id=? AND parent_owner_id=? AND task_group_id=? AND generation=?" +
                " AND assistant_message_id=? AND profile_id=? AND task_index=? AND status='RUNNING'",
                arrayOf(task.owner.childRunId, task.owner.conversationId, task.owner.parentRunId,
                    task.parentMessageId, task.parentOwnerId, task.owner.taskGroupId, task.owner.generation.toString(),
                    task.owner.assistantMessageId, task.profileId, task.index.toString())).use {
                check(it.moveToFirst()) { "child_usage_task_not_running" }
            }
            val priorIds = mutableSetOf<String>()
            val priorSources = mutableMapOf<String, String>()
            db.rawQuery("SELECT request_id,request_owner,checkpoint_revision,usage_source FROM $TABLE WHERE task_id=?",
                arrayOf(task.owner.childRunId)).use { rows ->
                while (rows.moveToNext()) {
                    check(rows.getString(1) == checkpoint.owner && rows.getLong(2) < checkpoint.revision) {
                        "child_usage_stale_owner_or_revision"
                    }
                    priorIds.add(rows.getString(0)); priorSources[rows.getString(0)] = rows.getString(3)
                }
            }
            check(records.map { it.request.requestId }.containsAll(priorIds)) { "child_usage_snapshot_lost_request" }
            records.forEach { record ->
                check(priorSources[record.request.requestId]?.let { it == record.source.name } != false) {
                    "child_usage_request_source_changed"
                }
                val values = ContentValues().apply {
                    put("task_id", task.owner.childRunId); put("request_id", record.request.requestId)
                    put("request_owner", record.request.owner); put("attempt_id", record.request.attemptId)
                    put("operation", record.request.operation.name); put("checkpoint_revision", checkpoint.revision)
                    put("input_tokens", record.snapshot.inputTokens); put("output_tokens", record.snapshot.outputTokens)
                    put("cached_tokens", record.snapshot.cachedTokens); put("usage_source", record.source.name)
                }
                if (record.request.requestId in priorIds) {
                    check(db.update(TABLE, values, "task_id=? AND request_id=?",
                        arrayOf(task.owner.childRunId, record.request.requestId)) == 1)
                } else db.insertOrThrow(TABLE, null, values)
            }
        }
    }

    companion object {
        private const val TABLE = "child_request_usage"

        fun installSchema(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS $TABLE(" +
                "task_id TEXT NOT NULL REFERENCES child_tasks(task_id) ON DELETE CASCADE," +
                "request_id TEXT NOT NULL,request_owner TEXT NOT NULL,attempt_id TEXT NOT NULL," +
                "operation TEXT NOT NULL,checkpoint_revision INTEGER NOT NULL," +
                "input_tokens INTEGER,output_tokens INTEGER,cached_tokens INTEGER,usage_source TEXT NOT NULL DEFAULT 'MAIN'," +
                "PRIMARY KEY(task_id,request_id))")
        }

        /** Shares the caller's read transaction with parent checkpoint reading. */
        fun read(db: SQLiteDatabase, conversationId: String?, maxTasks: Int, maxRequests: Int):
            Pair<List<AttributedRequestUsage>, ChildUsageCoverage> {
            check(db.inTransaction()) { "child_usage_transaction_required" }
            require(maxTasks in 1..1000 && maxRequests in 0..20_000)
            val filter = if (conversationId == null) "" else " WHERE t.conversation_id=?"
            val args = conversationId?.let { arrayOf(it) } ?: emptyArray()
            val from = " FROM child_tasks t JOIN messages m ON m.id=t.parent_message_id" +
                " AND m.conversation_id=t.conversation_id AND m.run_id=t.parent_run_id AND m.role='assistant'"
            val total = db.rawQuery("SELECT COUNT(*)$from$filter", args).use { it.moveToFirst(); it.getInt(0) }
            val result = mutableListOf<AttributedRequestUsage>()
            var inspected = 0; var missing = 0; var notStarted = 0; var unreadable = 0; var deferred = 0
            db.rawQuery("SELECT t.task_id,t.conversation_id,t.parent_run_id,t.parent_message_id," +
                "t.assistant_message_id,t.profile_id,t.task_group_id,t.status$from$filter ORDER BY t.seq DESC LIMIT ?",
                args + maxTasks.toString()).use { tasks ->
                while (tasks.moveToNext()) {
                    inspected++
                    val status = tasks.getString(7)
                    val taskId = tasks.getString(0)
                    val count = db.rawQuery("SELECT COUNT(*) FROM $TABLE WHERE task_id=?", arrayOf(taskId))
                        .use { it.moveToFirst(); it.getInt(0) }
                    if (status == "QUEUED" || status == "NOT_EXECUTED") {
                        if (count == 0) notStarted++ else unreadable++
                        continue
                    }
                    if (status !in setOf("RUNNING", "COMPLETED", "UNKNOWN")) { unreadable++; continue }
                    if (count == 0) { missing++; continue }
                    if (count > 100) { unreadable++; continue }
                    if (count > maxRequests - result.size) { deferred++; continue }
                    val snapshots = try {
                        db.rawQuery("SELECT request_id,request_owner,attempt_id,operation,input_tokens,output_tokens,cached_tokens,usage_source" +
                            " FROM $TABLE WHERE task_id=? ORDER BY rowid", arrayOf(taskId)).use { usage ->
                            buildList {
                                while (usage.moveToNext()) {
                                    fun token(index: Int): Long? {
                                        if (usage.isNull(index)) return null
                                        require(usage.getType(index) == android.database.Cursor.FIELD_TYPE_INTEGER)
                                        return usage.getLong(index)
                                    }
                                    val request = RequestCheckpoint(usage.getString(0), usage.getString(2),
                                        usage.getString(1), RequestOperation.valueOf(usage.getString(3)))
                                    require(request.operation != RequestOperation.OPEN)
                                    add(AttributedRequestUsage(tasks.getString(1), tasks.getString(2), tasks.getString(3),
                                        taskId, tasks.getString(4), UsageOrigin.Child(taskId, tasks.getString(5), tasks.getString(6)),
                                        request, ModelEvent.Usage(token(4), token(5), token(6)),
                                        app.daiyu.android.domain.runtime.UsageRequestSource.valueOf(usage.getString(7))))
                                }
                            }
                        }.also { require(it.map { row -> row.request.owner }.distinct().size == 1) }
                    } catch (failure: Exception) {
                        if (failure is SQLiteException || failure is CancellationException) throw failure
                        unreadable++; continue
                    }
                    result.addAll(snapshots)
                }
            }
            return result to ChildUsageCoverage(total, inspected, missing, notStarted, unreadable, deferred)
        }
    }
}
