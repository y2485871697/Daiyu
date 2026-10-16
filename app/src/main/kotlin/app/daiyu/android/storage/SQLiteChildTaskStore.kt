package app.daiyu.android.storage

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import app.daiyu.android.context.DatabaseTransactions
import app.daiyu.android.domain.delegation.*
import app.daiyu.android.domain.runtime.RunExecutionOwner

/** Used only behind SQLiteRuntimeStore's live OS lease. */
internal class SQLiteChildTaskStore(private val database: ConversationDatabase) : ChildTaskStore {
    private val transactions = DatabaseTransactions { database.writableDatabase }

    override fun enqueue(parent: RunExecutionOwner, tasks: List<ChildTaskRecord>) {
        require(tasks.size in 1..8 && tasks.map { it.owner.childRunId }.distinct().size == tasks.size)
        val first = tasks.first().owner
        require(tasks.map { it.index } == tasks.indices.toList())
        tasks.forEach { task ->
            require(task.owner.conversationId == parent.conversationId.value && task.owner.parentRunId == parent.runId.value &&
                task.parentMessageId == parent.assistantMessageId.value && task.parentOwnerId == parent.ownerId &&
                task.owner.taskGroupId == first.taskGroupId && task.owner.generation == first.generation &&
                task.status == ChildTaskStatus.QUEUED && task.text.isEmpty() && task.detail == null)
        }
        transactions.run { db ->
            db.rawQuery("SELECT 1 FROM messages WHERE id=? AND conversation_id=? AND run_id=? AND role='assistant' AND status='RUNNING'",
                arrayOf(parent.assistantMessageId.value, parent.conversationId.value, parent.runId.value)).use {
                check(it.moveToFirst()) { "child_parent_not_running" }
            }
            tasks.forEach { db.insertOrThrow(TABLE, null, values(it)) }
        }
    }

    override fun started(task: ChildTaskRecord) = change(task, ChildTaskStatus.RUNNING, "", null)

    override fun finished(task: ChildTaskRecord, status: ChildTaskStatus, text: String, detail: String?) {
        require(status in TERMINAL)
        change(task, status, text, detail)
    }

    private fun change(task: ChildTaskRecord, status: ChildTaskStatus, text: String, detail: String?) {
        val next = task.copy(status = status, text = text, detail = detail)
        transactions.run { db ->
            val prior = db.query(TABLE, COLUMNS, "task_id=?", arrayOf(task.owner.childRunId), null, null, null).use {
                check(it.moveToFirst()) { "child_task_missing" }; row(it)
            }
            check(prior.copy(status = ChildTaskStatus.QUEUED, text = "", detail = null) == task) { "child_task_owner_mismatch" }
            check(prior.status !in TERMINAL && (status != ChildTaskStatus.RUNNING || prior.status == ChildTaskStatus.QUEUED)) {
                "child_task_already_finished"
            }
            check(status != ChildTaskStatus.COMPLETED || prior.status == ChildTaskStatus.RUNNING)
            val update = ContentValues().apply { put("status", next.status.name); put("text", next.text); put("detail", next.detail) }
            check(db.update(TABLE, update, "task_id=? AND status=?", arrayOf(task.owner.childRunId, prior.status.name)) == 1)
        }
    }

    override fun list(conversationId: String, limit: Int): List<ChildTaskRecord> {
        require(conversationId.isNotBlank() && conversationId.length <= 200 && limit in 1..20)
        return database.readableDatabase.query(TABLE, COLUMNS, "conversation_id=?", arrayOf(conversationId),
            null, null, "seq DESC", limit.toString()).use { cursor ->
            buildList { while (cursor.moveToNext()) add(row(cursor)) }
        }
    }

    private fun values(task: ChildTaskRecord) = ContentValues().apply {
        put("task_id", task.owner.childRunId); put("conversation_id", task.owner.conversationId)
        put("parent_run_id", task.owner.parentRunId); put("parent_message_id", task.parentMessageId)
        put("parent_owner_id", task.parentOwnerId); put("task_group_id", task.owner.taskGroupId)
        put("generation", task.owner.generation); put("task_index", task.index)
        put("assistant_message_id", task.owner.assistantMessageId); put("profile_id", task.profileId)
        put("prompt", task.prompt); put("status", task.status.name); put("text", task.text); put("detail", task.detail)
    }

    private fun row(c: Cursor) = ChildTaskRecord(
        ChildRunOwner(c.getString(1), c.getString(2), c.getString(5), c.getLong(6), c.getString(0), c.getString(8)),
        c.getString(3), c.getString(4), c.getInt(7), c.getString(9), c.getString(10),
        ChildTaskStatus.valueOf(c.getString(11)), c.getString(12), if (c.isNull(13)) null else c.getString(13))

    companion object {
        private const val TABLE = "child_tasks"
        private val COLUMNS = arrayOf("task_id", "conversation_id", "parent_run_id", "parent_message_id", "parent_owner_id",
            "task_group_id", "generation", "task_index", "assistant_message_id", "profile_id", "prompt", "status", "text", "detail")
        private val TERMINAL = setOf(ChildTaskStatus.COMPLETED, ChildTaskStatus.NOT_EXECUTED, ChildTaskStatus.UNKNOWN)

        fun installSchema(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS child_tasks(seq INTEGER PRIMARY KEY AUTOINCREMENT,task_id TEXT UNIQUE NOT NULL," +
                "conversation_id TEXT NOT NULL,parent_run_id TEXT NOT NULL,parent_message_id TEXT NOT NULL REFERENCES messages(id) ON DELETE CASCADE," +
                "parent_owner_id TEXT NOT NULL,task_group_id TEXT NOT NULL,generation INTEGER NOT NULL,task_index INTEGER NOT NULL," +
                "assistant_message_id TEXT NOT NULL,profile_id TEXT NOT NULL,prompt TEXT NOT NULL,status TEXT NOT NULL," +
                "text TEXT NOT NULL,detail TEXT,UNIQUE(task_group_id,task_index))")
            db.execSQL("CREATE INDEX IF NOT EXISTS child_tasks_conversation ON child_tasks(conversation_id,seq)")
        }

        fun recoverPending(db: SQLiteDatabase) {
            db.execSQL("UPDATE child_tasks SET status='NOT_EXECUTED',text='',detail='interrupted_before_send' WHERE status='QUEUED'")
            db.execSQL("UPDATE child_tasks SET status='UNKNOWN',text='',detail='interrupted_after_admission' WHERE status='RUNNING'")
        }
    }
}
