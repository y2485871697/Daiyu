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
        require(tasks.isNotEmpty() && tasks.map { it.owner.childRunId }.distinct().size == tasks.size)
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

    override fun commandReceipt(command: ChildTaskCommand): ChildTaskCommandReceipt? =
        receipt(database.readableDatabase, command)

    private fun receipt(db: SQLiteDatabase, command: ChildTaskCommand): ChildTaskCommandReceipt? =
        db.rawQuery("SELECT fingerprint,successor_id FROM child_task_commands WHERE call_id=?", arrayOf(command.callId)).use { c ->
            if (!c.moveToFirst()) null
            else if (c.getString(0) != command.fingerprint()) ChildTaskCommandReceipt.Rejected("child_request_conflict")
            else ChildTaskCommandReceipt.Accepted(checkNotNull(find(db, c.getString(1))))
        }

    private fun find(db: SQLiteDatabase, id: String): ChildTaskRecord? =
        db.query(TABLE, COLUMNS, "task_id=?", arrayOf(id), null, null, null).use { if (it.moveToFirst()) row(it) else null }

    private fun matchesWrittenTask(db: SQLiteDatabase, expected: ChildTaskRecord): Boolean =
        db.query(TABLE, COLUMNS, "task_id=?", arrayOf(expected.owner.childRunId), null, null, null).use { c ->
            // Check raw integers before row() can truncate fractions or wrap an out-of-range task_index.
            c.moveToFirst() &&
                c.getType(6) == Cursor.FIELD_TYPE_INTEGER && c.getLong(6) == expected.owner.generation &&
                c.getType(7) == Cursor.FIELD_TYPE_INTEGER && c.getLong(7) == expected.index.toLong() &&
                c.getType(14) == Cursor.FIELD_TYPE_INTEGER && c.getLong(14) == expected.revision &&
                row(c) == expected
        }

    override fun get(owner: ChildRunOwner): ChildTaskRecord? =
        find(database.readableDatabase, owner.childRunId)?.takeIf { it.owner == owner }

    override fun enqueueCommand(parent: RunExecutionOwner, command: ChildTaskCommand,
        successor: ChildTaskRecord): ChildTaskCommandReceipt = transactions.run { db ->
        receipt(db, command)?.let { return@run it }
        val source = find(db, command.source.owner.childRunId)
            ?: return@run ChildTaskCommandReceipt.Rejected("child_source_stale")
        command.validateSource(source)?.let { return@run ChildTaskCommandReceipt.Rejected(it) }
        require(source.owner.conversationId == parent.conversationId.value)
        require(successor.owner.conversationId == parent.conversationId.value && successor.owner.parentRunId == parent.runId.value &&
            successor.parentOwnerId == parent.ownerId && successor.parentMessageId == parent.assistantMessageId.value &&
            successor.status == ChildTaskStatus.QUEUED && successor.revision == 0L &&
            successor.sourceTaskId == source.owner.childRunId && successor.commandKind == command.kind &&
            successor.profileId == command.profileId && successor.prompt == command.instruction)
        db.rawQuery("SELECT 1 FROM messages WHERE id=? AND conversation_id=? AND run_id=? AND role='assistant' AND status='RUNNING'",
            arrayOf(parent.assistantMessageId.value, parent.conversationId.value, parent.runId.value)).use {
            check(it.moveToFirst()) { "child_parent_not_running" }
        }
        val nextRevision = Math.incrementExact(source.revision)
        val fingerprint = command.fingerprint()
        val acknowledged = if (command.acknowledgePossibleExecution) 1 else 0
        val update = ContentValues().apply { put("control_revision", nextRevision) }
        check(db.update(TABLE, update, "task_id=? AND control_revision=? AND status=?",
            arrayOf(source.owner.childRunId, source.revision.toString(), source.status.name)) == 1)
        check(db.insertOrThrow(TABLE, null, values(successor)) != -1L) { "child_successor_insert_ignored" }
        check(db.insertOrThrow("child_task_commands", null, ContentValues().apply {
            put("call_id", command.callId); put("fingerprint", fingerprint)
            put("source_id", source.owner.childRunId); put("successor_id", successor.owner.childRunId)
            put("expected_revision", source.revision); put("acknowledged", acknowledged)
        }) != -1L) { "child_command_insert_ignored" }
        // Triggers can ignore writes or rewrite any of these rows without failing the SQL statement.
        // Verify the entire durable acknowledgement after all writes, before committing the transaction.
        check(matchesWrittenTask(db, source.copy(revision = nextRevision))) { "child_source_write_mismatch" }
        check(matchesWrittenTask(db, successor)) { "child_successor_write_mismatch" }
        db.rawQuery("SELECT fingerprint,source_id,successor_id,expected_revision,acknowledged FROM child_task_commands WHERE call_id=?",
            arrayOf(command.callId)).use { c ->
            check(c.moveToFirst() && c.getString(0) == fingerprint && c.getString(1) == source.owner.childRunId &&
                c.getString(2) == successor.owner.childRunId &&
                c.getType(3) == Cursor.FIELD_TYPE_INTEGER && c.getLong(3) == source.revision &&
                c.getType(4) == Cursor.FIELD_TYPE_INTEGER && c.getLong(4) == acknowledged.toLong()) {
                "child_command_write_mismatch"
            }
        }
        ChildTaskCommandReceipt.Accepted(successor)
    }

    override fun started(task: ChildTaskRecord) = change(task, ChildTaskStatus.RUNNING, "", null)

    override fun selected(task: ChildTaskRecord, display: ChildTaskDisplay?) {
        transactions.run { db ->
            val prior = checkNotNull(find(db, task.owner.childRunId)) { "child_task_missing" }
            check(prior.copy(status = ChildTaskStatus.QUEUED, text = "", detail = null,
                revision = task.revision, display = task.display) == task && prior.status == ChildTaskStatus.RUNNING) {
                "child_task_owner_mismatch"
            }
            val values = ContentValues().apply {
                put("agent_name_snapshot", display?.agentName); put("model_id_snapshot", display?.modelId)
                put("model_name_snapshot", display?.modelName)
            }
            check(db.update(TABLE, values, "task_id=? AND status='RUNNING'", arrayOf(task.owner.childRunId)) == 1)
        }
    }

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
            check(prior.copy(status = ChildTaskStatus.QUEUED, text = "", detail = null, revision = task.revision, display = task.display) == task) { "child_task_owner_mismatch" }
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

    /** Exact call association; not constrained by the recent-twenty history window. */
    internal fun forCall(conversationId: String, runId: String, messageId: String, toolName: String, callId: String): List<ChildTaskRecord> {
        val group = childTaskCallGroup(conversationId, runId, messageId, toolName, callId)
        return database.readableDatabase.query(TABLE, COLUMNS,
            "conversation_id=? AND parent_run_id=? AND parent_message_id=? AND task_group_id=?",
            arrayOf(conversationId, runId, messageId, group), null, null, "task_index ASC").use { cursor ->
            val records = buildList { while (cursor.moveToNext()) add(row(cursor)) }
            records.takeIf { it.map { row -> row.index } == it.indices.toList() } ?: emptyList()
        }
    }

    private fun values(task: ChildTaskRecord) = ContentValues().apply {
        put("task_id", task.owner.childRunId); put("conversation_id", task.owner.conversationId)
        put("parent_run_id", task.owner.parentRunId); put("parent_message_id", task.parentMessageId)
        put("parent_owner_id", task.parentOwnerId); put("task_group_id", task.owner.taskGroupId)
        put("generation", task.owner.generation); put("task_index", task.index)
        put("assistant_message_id", task.owner.assistantMessageId); put("profile_id", task.profileId)
        put("prompt", task.prompt); put("status", task.status.name); put("text", task.text); put("detail", task.detail)
        put("agent_name_snapshot", task.display?.agentName); put("model_id_snapshot", task.display?.modelId)
        put("model_name_snapshot", task.display?.modelName)
        if (task.access != ChildToolAccess.NONE) put("tool_access_json", task.access.toJson()) else putNull("tool_access_json")
        put("control_revision", task.revision); put("source_task_id", task.sourceTaskId); put("command_kind", task.commandKind?.name)
    }

    private fun row(c: Cursor) = ChildTaskRecord(
        ChildRunOwner(c.getString(1), c.getString(2), c.getString(5), c.getLong(6), c.getString(0), c.getString(8)),
        c.getString(3), c.getString(4), c.getInt(7), c.getString(9), c.getString(10),
        ChildTaskStatus.valueOf(c.getString(11)), c.getString(12), if (c.isNull(13)) null else c.getString(13),
        c.getLong(14), if (c.isNull(15)) null else c.getString(15),
        if (c.isNull(16)) null else ChildTaskCommandKind.valueOf(c.getString(16)),
        if (c.isNull(17) && c.isNull(18) && c.isNull(19)) null else
            ChildTaskDisplay(c.getString(17), c.getString(18), c.getString(19)),
        if (c.isNull(20)) ChildToolAccess.NONE else ChildToolAccess.fromJson(c.getString(20)))

    companion object {
        private const val TABLE = "child_tasks"
        private val COLUMNS = arrayOf("task_id", "conversation_id", "parent_run_id", "parent_message_id", "parent_owner_id",
            "task_group_id", "generation", "task_index", "assistant_message_id", "profile_id", "prompt", "status", "text", "detail",
            "control_revision", "source_task_id", "command_kind", "agent_name_snapshot", "model_id_snapshot", "model_name_snapshot", "tool_access_json")
        private val TERMINAL = setOf(ChildTaskStatus.COMPLETED, ChildTaskStatus.NOT_EXECUTED, ChildTaskStatus.UNKNOWN)

        fun installSchema(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS child_tasks(seq INTEGER PRIMARY KEY AUTOINCREMENT,task_id TEXT UNIQUE NOT NULL," +
                "conversation_id TEXT NOT NULL,parent_run_id TEXT NOT NULL,parent_message_id TEXT NOT NULL REFERENCES messages(id) ON DELETE CASCADE," +
                "parent_owner_id TEXT NOT NULL,task_group_id TEXT NOT NULL,generation INTEGER NOT NULL,task_index INTEGER NOT NULL," +
                "assistant_message_id TEXT NOT NULL,profile_id TEXT NOT NULL,prompt TEXT NOT NULL,status TEXT NOT NULL," +
                "text TEXT NOT NULL,detail TEXT,UNIQUE(task_group_id,task_index))")
            db.execSQL("CREATE INDEX IF NOT EXISTS child_tasks_conversation ON child_tasks(conversation_id,seq)")
            installCommandSchema(db)
            installDisplaySchema(db)
            installAccessSchema(db)
        }

        fun installAccessSchema(db: SQLiteDatabase) {
            val exists = db.rawQuery("PRAGMA table_info(child_tasks)", null).use { c ->
                var found = false
                while (c.moveToNext()) if (c.getString(1) == "tool_access_json") found = true
                found
            }
            if (!exists) db.execSQL("ALTER TABLE child_tasks ADD COLUMN tool_access_json TEXT")
        }

        fun installDisplaySchema(db: SQLiteDatabase) {
            val columns = db.rawQuery("PRAGMA table_info(child_tasks)", null).use { c ->
                buildSet { while (c.moveToNext()) add(c.getString(1)) }
            }
            listOf("agent_name_snapshot", "model_id_snapshot", "model_name_snapshot").forEach { column ->
                if (column !in columns) db.execSQL("ALTER TABLE child_tasks ADD COLUMN $column TEXT")
            }
        }

        /** Invoke inside the parent's single version-upgrade transaction, after child_tasks exists. */
        fun installCommandSchema(db: SQLiteDatabase) {
            val columns = db.rawQuery("PRAGMA table_info(child_tasks)", null).use { c ->
                buildSet { while (c.moveToNext()) add(c.getString(1)) }
            }
            if ("control_revision" !in columns) db.execSQL("ALTER TABLE child_tasks ADD COLUMN control_revision INTEGER NOT NULL DEFAULT 0")
            if ("source_task_id" !in columns) db.execSQL("ALTER TABLE child_tasks ADD COLUMN source_task_id TEXT")
            if ("command_kind" !in columns) db.execSQL("ALTER TABLE child_tasks ADD COLUMN command_kind TEXT")
            db.execSQL("CREATE TABLE IF NOT EXISTS child_task_commands(call_id TEXT PRIMARY KEY NOT NULL,fingerprint TEXT NOT NULL," +
                "source_id TEXT NOT NULL REFERENCES child_tasks(task_id) ON DELETE CASCADE," +
                "successor_id TEXT NOT NULL REFERENCES child_tasks(task_id) ON DELETE CASCADE," +
                "expected_revision INTEGER NOT NULL,acknowledged INTEGER NOT NULL)")
        }

        fun recoverPending(db: SQLiteDatabase) {
            db.execSQL("UPDATE child_tasks SET status='NOT_EXECUTED',text='',detail='interrupted_before_send' WHERE status='QUEUED'")
            db.execSQL("UPDATE child_tasks SET status='UNKNOWN',text='',detail='interrupted_after_admission' WHERE status='RUNNING'")
        }
    }
}
