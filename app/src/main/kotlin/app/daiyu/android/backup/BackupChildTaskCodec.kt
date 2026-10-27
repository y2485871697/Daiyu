package app.daiyu.android.backup

import android.database.sqlite.SQLiteDatabase
import app.daiyu.android.domain.delegation.*
import org.json.JSONArray
import org.json.JSONObject

/** Historical observations and command receipts, not a runtime checkpoint or model binding. */
internal object BackupChildTaskCodec {
    private val legacyTaskColumns = listOf("task_id", "conversation_id", "parent_run_id", "parent_message_id", "parent_owner_id",
        "task_group_id", "generation", "task_index", "assistant_message_id", "profile_id", "prompt", "status", "text", "detail",
        "control_revision", "source_task_id", "command_kind")
    private val displayColumns = listOf("agent_name_snapshot", "model_id_snapshot", "model_name_snapshot")
    private val taskColumns = legacyTaskColumns + displayColumns
    private fun withDisplayColumns(row: JSONObject): JSONObject = JSONObject().apply {
        row.keys().forEach { key -> put(key, row.get(key)) }
        displayColumns.forEach { key -> if (!has(key)) put(key, JSONObject.NULL) }
    }
    private val commandColumns = listOf("call_id", "fingerprint", "source_id", "successor_id", "expected_revision", "acknowledged")
    private val legacyUsageColumns = listOf("task_id", "request_id", "request_owner", "attempt_id", "operation", "checkpoint_revision",
        "input_tokens", "output_tokens", "cached_tokens")
    private val typedUsageColumns = legacyUsageColumns + "usage_source"
    private val v4UsageColumns = typedUsageColumns + "cache_creation_tokens"
    private val usageColumns = v4UsageColumns + "has_usage"
    data class Validated(val tasks: List<JSONObject>, val commands: List<JSONObject>, val usage: List<JSONObject>)
    fun export(db: SQLiteDatabase, conversation: String?): JSONObject {
        require(listOf("child_tasks", "child_task_commands", "child_request_usage").all { BackupHistoryRows.exists(db, it) }) {
            "子任务历史备份 schema 未接入，不能声明完整导出"
        }
        val where = if (conversation == null) "" else " WHERE conversation_id=?"
        val args = conversation?.let { arrayOf(it) }
        val snapshots = BackupHistoryRows.history(db, "child_task", conversation)
        val tasks = BackupHistoryRows.read(db, "child_tasks", taskColumns, where, args, "seq").map { physical ->
            snapshots[physical.getString("task_id")]?.let(::withDisplayColumns)?.also { original ->
                require(BackupHistoryRows.same(terminal(original), physical)) { "已恢复子任务历史被更改" }
            } ?: physical
        }
        val taskIds = tasks.map { it.getString("task_id") }.toSet()
        require(snapshots.values.filter { conversation == null || it.getString("conversation_id") == conversation }
            .all { it.getString("task_id") in taskIds }) { "已恢复子任务历史缺少物理记录" }
        val commands = BackupHistoryRows.read(db, "child_task_commands", commandColumns,
            if (conversation == null) "" else " WHERE source_id IN (SELECT task_id FROM child_tasks WHERE conversation_id=?)", args)
        val usage = BackupHistoryRows.read(db, "child_request_usage", usageColumns,
            if (conversation == null) "" else " WHERE task_id IN (SELECT task_id FROM child_tasks WHERE conversation_id=?)", args)
        return JSONObject().put("version", 5).put("tasks", JSONArray(tasks)).put("commands", JSONArray(commands)).put("usage", JSONArray(usage))
    }
    fun validate(value: JSONObject?, messages: JSONArray): Validated {
        if (value == null) return Validated(emptyList(), emptyList(), emptyList())
        with(BackupHistoryRows) {
            fields(value, listOf("version", "tasks", "commands", "usage")); val version = number(value, "version"); require(version in 1L..5L)
            val tasks = rows(value.getJSONArray("tasks")).map { row ->
                fields(row, if (version < 3L) legacyTaskColumns else taskColumns)
                withDisplayColumns(row)
            }; val commands = rows(value.getJSONArray("commands"))
            val usage = rows(value.getJSONArray("usage")).map { row ->
                fields(row, if (version == 1L) legacyUsageColumns else if (version < 4L) typedUsageColumns else if (version == 4L) v4UsageColumns else usageColumns)
                // Copy values, not serialized JSON: numberToString could normalize 1.0 to
                // an integer before the strict storage-type validator gets to inspect it.
                JSONObject().apply {
                    row.keys().forEach { key -> put(key, row.get(key)) }
                    if (version == 1L) put("usage_source", "MAIN")
                    if (version < 4L) put("cache_creation_tokens", JSONObject.NULL)
                    if (version < 5L) put("has_usage", 1)
                }
            }
            val messagesById = messageMap(messages); val byId = linkedMapOf<String, ChildTaskRecord>(); val groups = mutableSetOf<Pair<String, Int>>()
            val groupOwners = mutableMapOf<String, List<String>>()
            tasks.forEach { row ->
                fields(row, taskColumns)
                owner(row, messagesById, "conversation_id", "parent_message_id", "parent_run_id")
                val record = record(row)
                require(byId.put(record.owner.childRunId, record) == null && groups.add(record.owner.taskGroupId to record.index))
                val groupOwner = listOf(record.owner.conversationId, record.owner.parentRunId, record.parentMessageId,
                    record.parentOwnerId, record.owner.generation.toString())
                val previousOwner = groupOwners.put(record.owner.taskGroupId, groupOwner)
                require(previousOwner == null || previousOwner == groupOwner) { "子任务组所有者不一致" }
            }
            val positions = tasks.mapIndexed { index, row -> text(row, "task_id") to index }.toMap()
            tasks.forEach { row ->
                val current = byId.getValue(text(row, "task_id"))
                current.sourceTaskId?.let { sourceId ->
                    val source = byId[sourceId] ?: error("子任务来源链缺失")
                    require(source.owner.conversationId == current.owner.conversationId && sourceId != current.owner.childRunId)
                    // All source links must precede their successor in persisted sequence order.
                    require(positions.getValue(sourceId) < positions.getValue(current.owner.childRunId)) { "子任务来源链倒置或成环" }
                }
            }
            val calls = mutableSetOf<String>(); val successors = mutableSetOf<String>()
            commands.forEach { row ->
                fields(row, commandColumns); require(calls.add(id(row, "call_id"))); hash(row, "fingerprint")
                val source = byId[id(row, "source_id")] ?: error("命令源子任务缺失")
                val successor = byId[id(row, "successor_id")] ?: error("命令后继缺失")
                require(successors.add(successor.owner.childRunId) && successor.sourceTaskId == source.owner.childRunId && successor.commandKind != null)
                val expected = number(row, "expected_revision"); val acknowledged = number(row, "acknowledged")
                require(expected >= 0 && expected < source.revision && acknowledged in 0..1)
                val command = ChildTaskCommand(text(row, "call_id"), source.copy(revision = expected),
                    checkNotNull(successor.commandKind), successor.profileId, successor.prompt, acknowledged == 1L)
                require(command.fingerprint() == text(row, "fingerprint")) { "子任务命令防重摘要不匹配" }
            }
            require(tasks.filter { !it.isNull("source_task_id") }.all { text(it, "task_id") in successors }) { "子任务来源缺少命令墓碑" }
            val requests = mutableSetOf<Pair<String, String>>()
            usage.forEach { row ->
                fields(row, usageColumns); require(text(row, "usage_source") in setOf("MAIN", "SUMMARY"))
                val task = id(row, "task_id"); require(task in byId && requests.add(task to id(row, "request_id")))
                id(row, "request_owner"); id(row, "attempt_id"); require(text(row, "operation") in setOf("OPEN", "NEXT", "RESUME"))
                require(number(row, "checkpoint_revision") > 0)
                val hasUsage = number(row, "has_usage"); require(hasUsage in 0..1)
                if (hasUsage == 0L) require(text(row, "usage_source") == "SUMMARY" &&
                    listOf("input_tokens", "output_tokens", "cached_tokens", "cache_creation_tokens").all(row::isNull))
                listOf("input_tokens", "output_tokens", "cached_tokens", "cache_creation_tokens").forEach { if (!row.isNull(it)) require(number(row, it) >= 0) }
                if (!row.isNull("input_tokens") && !row.isNull("cached_tokens"))
                    require(number(row, "cached_tokens") <= number(row, "input_tokens"))
                fun token(key: String): Long? = if (row.isNull(key)) null else number(row, key)
                app.daiyu.android.domain.ModelEvent.Usage(token("input_tokens"), token("output_tokens"),
                    token("cached_tokens"), token("cache_creation_tokens"))
            }
            historyBudget("child_task", tasks) { text(it, "task_id") }
            return Validated(tasks, commands, usage)
        }
    }
    private fun record(row: JSONObject): ChildTaskRecord = with(BackupHistoryRows) {
        val index = number(row, "task_index"); require(index in 0..7)
        ChildTaskRecord(ChildRunOwner(id(row, "conversation_id"), id(row, "parent_run_id"), id(row, "task_group_id"),
            number(row, "generation"), id(row, "task_id"), id(row, "assistant_message_id")), id(row, "parent_message_id"),
            id(row, "parent_owner_id"), index.toInt(), id(row, "profile_id"), text(row, "prompt"), ChildTaskStatus.valueOf(text(row, "status")),
            text(row, "text"), if (row.isNull("detail")) null else text(row, "detail"), number(row, "control_revision"),
            if (row.isNull("source_task_id")) null else id(row, "source_task_id"),
            if (row.isNull("command_kind")) null else ChildTaskCommandKind.valueOf(text(row, "command_kind")),
            if (displayColumns.all(row::isNull)) null else ChildTaskDisplay(
                text(row, "agent_name_snapshot"), text(row, "model_id_snapshot"), text(row, "model_name_snapshot")))
    }
    internal fun terminal(row: JSONObject): JSONObject = JSONObject(row.toString()).apply {
        when (getString("status")) {
            "QUEUED" -> { put("status", "NOT_EXECUTED"); put("detail", "backup_history_not_dispatched") }
            "RUNNING" -> { put("status", "UNKNOWN"); put("detail", "backup_history_not_resumable") }
        }
    }
    fun restore(db: SQLiteDatabase, value: Validated) {
        value.tasks.forEach { row ->
            BackupHistoryRows.insert(db, "child_tasks", terminal(row))
            BackupHistoryRows.saveHistory(db, "child_task", row.getString("task_id"), row)
        }
        value.commands.forEach { BackupHistoryRows.insert(db, "child_task_commands", it) }
        value.usage.forEach { BackupHistoryRows.insert(db, "child_request_usage", it) }
    }
    fun verifyRestored(db: SQLiteDatabase, value: Validated) {
        val history = if (value.tasks.isEmpty()) emptyMap() else BackupHistoryRows.history(db, "child_task")
        value.tasks.forEach { row ->
            BackupHistoryRows.verify(db, "child_tasks", terminal(row))
            val retained = history[row.getString("task_id")] ?: error("备份历史回执缺失")
            val keys = retained.keys().asSequence().toSet()
            BackupHistoryRows.fields(retained, if (keys == legacyTaskColumns.toSet()) legacyTaskColumns else taskColumns)
            val normalized = withDisplayColumns(retained)
            record(normalized)
            check(BackupHistoryRows.same(normalized, row)) { "备份历史回执不匹配" }
        }
        value.commands.forEach { BackupHistoryRows.verify(db, "child_task_commands", it) }
        value.usage.forEach { BackupUsageCodec.verifyRow(db, "child_request_usage", it, listOf("task_id", "request_id")) }
    }
}
