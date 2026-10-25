package app.daiyu.android.integration.tools

import app.daiyu.android.domain.delegation.*
import app.daiyu.android.domain.tools.*
import org.json.JSONArray
import org.json.JSONObject

internal class RunChildTaskAccess(
    private val conversationId: String,
    private val records: () -> List<ChildTaskRecord>,
    private val source: (ChildRunOwner) -> ChildTaskRecord?,
    private val live: () -> List<RetainedChildTask>,
    private val progress: (ChildRunOwner) -> String,
    private val resume: (ChildTaskRecord) -> ChildTaskControlReceipt,
    private val pause: (ChildTaskRecord) -> ChildTaskControlReceipt,
    private val stop: (ChildTaskRecord) -> ChildTaskStop,
    private val currentProfiles: () -> Set<String>,
) {
    fun tools(): List<RegisteredTool> = listOf(object : RegisteredTool {
        override val definition = ToolDefinition("inspect_child_tasks", 1,
            "读取本会话已有子任务、进度和结果（包括旧配置、暂停或停止的任务），以及新委派可用的当前配置。只读，不自动继续或重派。进度仅保留于当前进程。",
            CAPABILITY, ToolEffect.READ_ONLY, ToolSchema(listOf(
                ArgumentField("offset", ArgumentType.Integer(0, 65536), required = false),
                ArgumentField("max_chars", ArgumentType.Integer(1, 1024), required = false))))
        override fun prepare(arguments: ToolArguments) = PreparedTool.contextual { context ->
            val offset = if (arguments.contains("offset")) arguments.integer("offset").toInt() else 0
            val maxChars = if (arguments.contains("max_chars")) arguments.integer("max_chars").toInt() else 1024
            if (context.owner.conversationId.value != conversationId || context.cancelled())
                return@contextual ExecutionReceipt.NotExecuted("child_conversation_mismatch")
            val active = live().associateBy { it.record.owner }
            val rows = (active.values.map { it.record } + records()).distinctBy { it.owner }.take(20)
            val result = JSONArray()
            rows.forEach { row ->
                val state = active[row.owner]
                val partial = progress(row.owner)
                result.put(JSONObject().put("conversation_id", conversationId).put("parent_run_id", row.owner.parentRunId)
                    .put("task_group_id", row.owner.taskGroupId).put("generation", row.owner.generation)
                    .put("child_run_id", row.owner.childRunId).put("assistant_message_id", row.owner.assistantMessageId)
                    .put("parent_message_id", row.parentMessageId).put("parent_owner_id", row.parentOwnerId)
                    .put("expected_revision", row.revision).put("profile_id", row.profileId).put("status", row.status.name)
                    .put("pause_state", state?.pauseState?.name).put("stop_requested", state?.stopRequested ?: false)
                    .put("settled", state?.settled ?: true).put("offset", offset)
                    .put("progress", partial.drop(offset).take(maxChars))
                    .put("next_progress_offset", if (partial.length > offset + maxChars) offset + maxChars else JSONObject.NULL)
                    .put("text", row.text.drop(offset).take(maxChars))
                    .put("next_text_offset", if (row.text.length > offset + maxChars) offset + maxChars else JSONObject.NULL)
                    .put("detail", row.detail))
            }
            val profiles = runCatching { currentProfiles() }.getOrNull()
            ExecutionReceipt.Confirmed(JSONObject().put("tasks", result)
                .put("current_configuration_available", profiles != null)
                .put("available_profiles", JSONArray(profiles?.toList() ?: emptyList<String>())).toString())
        }
    }, object : RegisteredTool {
        override val definition = ToolDefinition("control_child_task", 1,
            "定向继续、暂停或停止本会话的现存子任务，允许控制旧主回复和旧配置的任务。继续不新建任务，不更换任务ID；已停止或排空任务不能重启。必须先inspect取得完整身份与修订，恢复主代理本身不会自动调用。",
            CAPABILITY, ToolEffect.READ_ONLY, ToolSchema(listOf(
                ArgumentField("parent_run_id", ArgumentType.Text(200, minimumLength = 1)),
                ArgumentField("task_group_id", ArgumentType.Text(200, minimumLength = 1)),
                ArgumentField("generation", ArgumentType.Text(20, minimumLength = 1)),
                ArgumentField("child_run_id", ArgumentType.Text(200, minimumLength = 1)),
                ArgumentField("assistant_message_id", ArgumentType.Text(200, minimumLength = 1)),
                ArgumentField("parent_message_id", ArgumentType.Text(200, minimumLength = 1)),
                ArgumentField("parent_owner_id", ArgumentType.Text(200, minimumLength = 1)),
                ArgumentField("expected_revision", ArgumentType.Text(20, minimumLength = 1)),
                ArgumentField("action", ArgumentType.Text(20, setOf("continue", "pause", "stop"), 1)))))
        override fun prepare(arguments: ToolArguments): PreparedTool {
            val owner = ChildRunOwner(conversationId, arguments.text("parent_run_id"), arguments.text("task_group_id"),
                arguments.text("generation").toLong(), arguments.text("child_run_id"), arguments.text("assistant_message_id"))
            val revision = arguments.text("expected_revision").toLong().also { require(it >= 0) }
            return PreparedTool.contextual { context ->
                if (context.owner.conversationId.value != conversationId || context.cancelled())
                    return@contextual ExecutionReceipt.NotExecuted("child_conversation_mismatch")
                val row = source(owner)
                if (row == null || row.revision != revision || row.parentMessageId != arguments.text("parent_message_id") ||
                    row.parentOwnerId != arguments.text("parent_owner_id"))
                    return@contextual ExecutionReceipt.NotExecuted("child_source_stale")
                val outcome = when (arguments.text("action")) {
                    "continue" -> resume(row).name
                    "pause" -> pause(row).name
                    else -> stop(row).name
                }
                if (outcome in setOf("STALE", "NOT_FOUND", "ALREADY_SETTLED")) ExecutionReceipt.NotExecuted("child_not_live")
                else ExecutionReceipt.Confirmed(JSONObject().put("child_run_id", owner.childRunId)
                    .put("outcome", outcome).put("new_request", false).toString())
            }
        }
    }, object : RegisteredTool {
        override val definition = ToolDefinition("get_child_task", 1,
            "按完整身份精确读取本会话任意已有子任务（不限最近20条）。配置变更、暂停或停止不影响读取；offset为字符偏移，可分页读取结果和当前进程保留的进度。",
            CAPABILITY, ToolEffect.READ_ONLY, ToolSchema(listOf(
                ArgumentField("parent_run_id", ArgumentType.Text(200, minimumLength = 1)),
                ArgumentField("task_group_id", ArgumentType.Text(200, minimumLength = 1)),
                ArgumentField("generation", ArgumentType.Text(20, minimumLength = 1)),
                ArgumentField("child_run_id", ArgumentType.Text(200, minimumLength = 1)),
                ArgumentField("assistant_message_id", ArgumentType.Text(200, minimumLength = 1)),
                ArgumentField("offset", ArgumentType.Integer(0, 65536), required = false),
                ArgumentField("max_chars", ArgumentType.Integer(1, 4096), required = false))))
        override fun prepare(arguments: ToolArguments): PreparedTool {
            val owner = ChildRunOwner(conversationId, arguments.text("parent_run_id"), arguments.text("task_group_id"),
                arguments.text("generation").toLong(), arguments.text("child_run_id"), arguments.text("assistant_message_id"))
            val offset = if (arguments.contains("offset")) arguments.integer("offset").toInt() else 0
            val maxChars = if (arguments.contains("max_chars")) arguments.integer("max_chars").toInt() else 4096
            return PreparedTool.contextual { context ->
                if (context.owner.conversationId.value != conversationId || context.cancelled())
                    return@contextual ExecutionReceipt.NotExecuted("child_conversation_mismatch")
                val row = source(owner) ?: return@contextual ExecutionReceipt.NotExecuted("child_source_stale")
                val state = live().firstOrNull { it.record.owner == owner }
                val partial = progress(owner)
                ExecutionReceipt.Confirmed(JSONObject().put("child_run_id", owner.childRunId)
                    .put("parent_message_id", row.parentMessageId).put("parent_owner_id", row.parentOwnerId)
                    .put("expected_revision", row.revision).put("status", row.status.name)
                    .put("pause_state", state?.pauseState?.name).put("settled", state?.settled ?: true)
                    .put("stop_requested", state?.stopRequested ?: false)
                    .put("journal_unconfirmed", state?.journalUnconfirmed ?: false)
                    .put("prompt", row.prompt).put("offset", offset).put("text", row.text.drop(offset).take(maxChars))
                    .put("next_text_offset", if (row.text.length > offset + maxChars) offset + maxChars else JSONObject.NULL)
                    .put("progress", partial.drop(offset).take(maxChars))
                    .put("next_progress_offset", if (partial.length > offset + maxChars) offset + maxChars else JSONObject.NULL)
                    .put("detail", row.detail).toString())
            }
        }
    })
    companion object { const val CAPABILITY = "delegation.text.retained_control" }
}
