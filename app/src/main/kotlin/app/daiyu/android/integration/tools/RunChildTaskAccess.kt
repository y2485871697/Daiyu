package app.daiyu.android.integration.tools

import app.daiyu.android.delegation.SubAgentDescriptor
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
    private val currentProfileMetadata: (() -> List<SubAgentDescriptor>)? = null,
    private val initialProfiles: List<SubAgentDescriptor> = emptyList(),
    private val takeOver: ((ChildTaskRecord, String) -> ChildTaskControlReceipt)? = null,
    private val recordedStatuses: () -> Map<ChildRunOwner, ChildTaskStatus> = {
        records().associate { it.owner to it.status }
    },
) {
    fun tools(): List<RegisteredTool> = listOf(object : RegisteredTool {
        override val definition = ToolDefinition("list_subagents", 1,
            "查询新委派可用的当前子代理配置，仅返回配置及可用性，不查询已有任务。任务总数和状态汇总使用inspect_child_tasks，具体进度和结果使用get_child_task。无参数，只读，不委派、继续或重派任务。" + SubAgentDescriptor.initialDescription(initialProfiles),
            CAPABILITY, ToolEffect.READ_ONLY, ToolSchema(emptyList()))
        override fun prepare(arguments: ToolArguments) = PreparedTool.contextual { context ->
            if (context.owner.conversationId.value != conversationId || context.cancelled())
                return@contextual ExecutionReceipt.NotExecuted("child_conversation_mismatch")
            val metadata = runCatching { currentProfileMetadata?.invoke() }.getOrNull()
            val profiles = if (currentProfileMetadata != null) metadata?.map { it.profileId }?.toSet()
                else runCatching { currentProfiles() }.getOrNull()
            ExecutionReceipt.Confirmed(JSONObject()
                .put("current_configuration_available", profiles != null)
                .put("configuration_scope", "current_new_text_requests")
                .put("available_profiles", JSONArray(profiles?.toList() ?: emptyList<String>()))
                .put("available_profile_metadata", JSONArray(metadata.orEmpty().map { it.toJson() }))
                .toString())
        }
    }, object : RegisteredTool {
        override val definition = ToolDefinition("inspect_child_tasks", 1,
            "查询本会话已有子任务的总数和状态汇总，附近期任务的完整身份供定向读取或控制。总数与状态覆盖完整会话，不限身份列表的近期窗口。snapshot_available=false表示没有可用运行快照，available_actions为空时仅可读取历史，不要反复停止或自动重派。不能据此确认远端请求已停止。parent_next_step=continue_independent_work_no_replay 时不等待此记录，不要求用户重跑；主代理继续无重叠的工作，只对依赖旧任务副作用的步骤核对回执。不返回子代理配置，配置使用list_subagents；不读取单任务进度、结果正文或诊断详情，这些内容使用get_child_task。无参数，只读，不自动继续或重派。",
            CAPABILITY, ToolEffect.READ_ONLY, ToolSchema(emptyList()))
        override fun prepare(arguments: ToolArguments) = PreparedTool.contextual { context ->
            if (context.owner.conversationId.value != conversationId || context.cancelled())
                return@contextual ExecutionReceipt.NotExecuted("child_conversation_mismatch")
            val active = live().filter { it.record.owner.conversationId == conversationId }.associateBy { it.record.owner }
            val statuses = recordedStatuses().filterKeys { it.conversationId == conversationId }
                .mapValues { it.value.name }.toMutableMap()
            active.forEach { (owner, state) ->
                statuses[owner] = when {
                    state.journalUnconfirmed -> "UNKNOWN"
                    state.record.status in setOf(ChildTaskStatus.UNKNOWN, ChildTaskStatus.COMPLETED,
                        ChildTaskStatus.FAILED, ChildTaskStatus.NOT_EXECUTED) -> state.record.status.name
                    state.settled -> state.record.status.name
                    state.stopRequested -> "STOPPING"
                    state.pauseState == ChildTaskPauseState.PAUSED -> "PAUSED"
                    state.pauseState == ChildTaskPauseState.PENDING -> "PAUSING"
                    state.queueState == ChildTaskQueueState.PENDING || state.record.status == ChildTaskStatus.QUEUED -> "QUEUED"
                    else -> "RUNNING"
                }
            }
            // An old running/queued row without a live owner is not evidence of current activity.
            statuses.entries.forEach { entry ->
                if (entry.key !in active && entry.value in setOf("QUEUED", "RUNNING")) entry.setValue("UNKNOWN")
            }
            val rows = (active.values.map { it.record } + records()).filter { it.owner.conversationId == conversationId }
                .distinctBy { it.owner }.take(20)
            val result = JSONArray()
            rows.forEach { row ->
                val state = active[row.owner]
                result.put(JSONObject().put("conversation_id", conversationId).put("parent_run_id", row.owner.parentRunId)
                    .put("task_group_id", row.owner.taskGroupId).put("generation", row.owner.generation)
                    .put("child_run_id", row.owner.childRunId).put("assistant_message_id", row.owner.assistantMessageId)
                    .put("parent_message_id", row.parentMessageId).put("parent_owner_id", row.parentOwnerId)
                    .withControlState(row, state)
                    .put("expected_revision", row.revision).put("original_role", row.originalRole?.name ?: JSONObject.NULL)
                    .put("assigned_profile_id", row.assignedProfileId).put("agent_name", row.display?.agentName ?: JSONObject.NULL).put("profile_id", row.profileId).put("status", row.status.name)
                    .put("queue_state", state?.let { if (it.settled) ChildTaskQueueState.FINISHED.name else it.queueState.name }).put("pause_state", state?.let { if (it.settled) ChildTaskPauseState.FINISHED.name else it.pauseState.name }).put("snapshot_available", state != null).put("live_state_available", state != null && !state.settled)
                    .put("configuration_paused", state?.configurationPaused ?: JSONObject.NULL)
                    .put("stop_requested", state?.stopRequested ?: JSONObject.NULL)
                    .put("journal_unconfirmed", state?.journalUnconfirmed ?: JSONObject.NULL)
                    .put("settled", state?.settled ?: JSONObject.NULL))
            }
            ExecutionReceipt.Confirmed(JSONObject().put("tasks", result)
                .put("total_count", statuses.size)
                .put("status_counts", JSONObject(statuses.values.groupingBy { it }.eachCount()))
                .put("task_identities_truncated", statuses.size > rows.size)
                .put("historical_metadata_policy", "Existing tasks keep their own snapshots. original_role is recorded for new tasks; null means unknown. Historical tier is unavailable. Do not infer missing metadata from current profiles.")
                .toString())
        }
    }, object : RegisteredTool {
        override val definition = ToolDefinition("control_child_task", 1,
            "定向继续、暂停或停止本会话的现存子任务，允许控制旧主回复和旧配置的任务。继续不新建任务，不更换任务ID；已停止或排空任务不能重启。必须先inspect取得完整身份与修订，恢复主代理本身不会自动调用。配置暂停后可在continue中指定当前配置内同原职责的target_profile_id接手；无同职责候选则保持暂停。图片和视频生成不支持继续或接手，切组后即使模型相同也只能明确重新委派。若放弃暂停任务而重新委派，先stop对应旧任务；无运行句柄时返回child_owner_handle_unavailable，表示记录仍在但本次未发送停止，不能确认历史请求已停止，不得自动重派。REQUESTED不是停止完成，须inspect确认settled且journal_unconfirmed=false后，才可用最新修订新派。不得留下暂停旧任务；同职责接手则保留原任务，不停止。",
            CAPABILITY, ToolEffect.READ_ONLY, ToolSchema(listOf(
                ArgumentField("parent_run_id", ArgumentType.Text(200, minimumLength = 1)),
                ArgumentField("task_group_id", ArgumentType.Text(200, minimumLength = 1)),
                ArgumentField("generation", ArgumentType.Text(20, minimumLength = 1)),
                ArgumentField("child_run_id", ArgumentType.Text(200, minimumLength = 1)),
                ArgumentField("assistant_message_id", ArgumentType.Text(200, minimumLength = 1)),
                ArgumentField("parent_message_id", ArgumentType.Text(200, minimumLength = 1)),
                ArgumentField("parent_owner_id", ArgumentType.Text(200, minimumLength = 1)),
                ArgumentField("expected_revision", ArgumentType.Text(20, minimumLength = 1)),
                ArgumentField("target_profile_id", ArgumentType.Text(200, minimumLength = 1), required = false),
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
                val target = if (arguments.contains("target_profile_id")) arguments.text("target_profile_id") else null
                if (target != null && arguments.text("action") != "continue")
                    return@contextual ExecutionReceipt.NotExecuted("child_takeover_requires_continue")
                val outcome = when (arguments.text("action")) {
                    "continue" -> if (target == null) resume(row).name
                        else (takeOver?.invoke(row, target) ?: ChildTaskControlReceipt.STALE).name
                    "pause" -> pause(row).name
                    else -> stop(row).name
                }
                if (outcome in setOf("STALE", "NOT_FOUND", "ALREADY_SETTLED", "CONFIGURATION_REQUIRED", "ROLE_MISMATCH")) ExecutionReceipt.NotExecuted(when (outcome) {
                    "STALE" -> "child_control_unavailable"
                    "NOT_FOUND" -> "child_owner_handle_unavailable"
                    "CONFIGURATION_REQUIRED" -> "child_matching_profile_required"
                    "ROLE_MISMATCH" -> "child_takeover_role_mismatch"
                    else -> "child_task_already_settled"
                })
                else {
                    val updated = source(owner) ?: row
                    ExecutionReceipt.Confirmed(JSONObject().put("child_run_id", owner.childRunId)
                    .put("assigned_profile_id", updated.assignedProfileId)
                    .put("expected_revision", updated.revision)
                    .put("profile_id", row.profileId).put("agent_name", row.display?.agentName ?: JSONObject.NULL)
                    .put("outcome", outcome).put("new_request", false).toString())
                }
            }
        }
    }, object : RegisteredTool {
        override val definition = ToolDefinition("get_child_task", 1,
            "按完整身份精确读取本会话任意已有子任务的具体进度、结果正文和诊断详情（不限总览20条）。当前可用子代理配置使用list_subagents，任务总数和状态汇总使用inspect_child_tasks。配置变更、暂停或停止不影响读取；offset为字符偏移，可分页读取结果和当前进程保留的进度。max_chars为可选正整数，默认结果、进度和诊断详情各4096字符；超过4096时按4096读取，使用返回的next偏移继续分页。若 parent_next_step=continue_independent_work_no_replay，读取已有结果后继续无重叠的主任务，不等待、不重派、不重放副作用。",
            CAPABILITY, ToolEffect.READ_ONLY, ToolSchema(listOf(
                ArgumentField("parent_run_id", ArgumentType.Text(200, minimumLength = 1)),
                ArgumentField("task_group_id", ArgumentType.Text(200, minimumLength = 1)),
                ArgumentField("generation", ArgumentType.Text(20, minimumLength = 1)),
                ArgumentField("child_run_id", ArgumentType.Text(200, minimumLength = 1)),
                ArgumentField("assistant_message_id", ArgumentType.Text(200, minimumLength = 1)),
                ArgumentField("offset", ArgumentType.Integer(0, 65536), required = false),
                ArgumentField("max_chars", ArgumentType.Integer(1, Long.MAX_VALUE), required = false))))
        override fun prepare(arguments: ToolArguments): PreparedTool {
            val owner = ChildRunOwner(conversationId, arguments.text("parent_run_id"), arguments.text("task_group_id"),
                arguments.text("generation").toLong(), arguments.text("child_run_id"), arguments.text("assistant_message_id"))
            val offset = if (arguments.contains("offset")) arguments.integer("offset").toInt() else 0
            val maxChars = if (arguments.contains("max_chars")) arguments.integer("max_chars").coerceAtMost(4096L).toInt() else 4096
            return PreparedTool.contextual { context ->
                if (context.owner.conversationId.value != conversationId || context.cancelled())
                    return@contextual ExecutionReceipt.NotExecuted("child_conversation_mismatch")
                val row = source(owner) ?: return@contextual ExecutionReceipt.NotExecuted("child_source_stale")
                val state = live().firstOrNull { it.record.owner == owner }
                val partial = progress(owner)
                ExecutionReceipt.Confirmed(JSONObject().put("child_run_id", owner.childRunId)
                    .put("parent_message_id", row.parentMessageId).put("parent_owner_id", row.parentOwnerId)
                    .put("expected_revision", row.revision).put("profile_id", row.profileId)
                    .withControlState(row, state)
                    .put("assigned_profile_id", row.assignedProfileId).put("original_role", row.originalRole?.name ?: JSONObject.NULL)
                    .put("agent_name", row.display?.agentName ?: JSONObject.NULL).put("status", row.status.name)
                    .put("queue_state", state?.let { if (it.settled) ChildTaskQueueState.FINISHED.name else it.queueState.name }).put("pause_state", state?.let { if (it.settled) ChildTaskPauseState.FINISHED.name else it.pauseState.name }).put("settled", state?.settled ?: JSONObject.NULL)
                    .put("snapshot_available", state != null).put("live_state_available", state != null && !state.settled)
                    .put("configuration_paused", state?.configurationPaused ?: JSONObject.NULL)
                    .put("stop_requested", state?.stopRequested ?: JSONObject.NULL)
                    .put("journal_unconfirmed", state?.journalUnconfirmed ?: JSONObject.NULL)
                    .put("prompt", row.prompt).put("offset", offset).put("text", row.text.drop(offset).take(maxChars))
                    .put("next_text_offset", if (row.text.length > offset + maxChars) offset + maxChars else JSONObject.NULL)
                    .put("progress", partial.drop(offset).take(maxChars))
                    .put("next_progress_offset", if (partial.length > offset + maxChars) offset + maxChars else JSONObject.NULL)
                    .put("error_summary", row.detail?.lineSequence()?.firstOrNull())
                    .put("detail", row.detail?.drop(offset)?.take(maxChars))
                    .put("next_detail_offset", if ((row.detail?.length ?: 0) > offset + maxChars) offset + maxChars else JSONObject.NULL).toString())
            }
        }
    })
    private fun JSONObject.withControlState(row: ChildTaskRecord, state: RetainedChildTask?): JSONObject {
        val reason = when {
            state == null -> "child_owner_handle_unavailable"
            state.settled -> "child_task_already_settled"
            else -> null
        }
        put("control_unavailable_reason", reason ?: JSONObject.NULL)
        if (reason != null) put("available_actions", JSONArray())
        val unconfirmed = row.status == ChildTaskStatus.UNKNOWN || state?.journalUnconfirmed == true ||
            (state == null && row.status in setOf(ChildTaskStatus.QUEUED, ChildTaskStatus.RUNNING))
        put("execution_unconfirmed", unconfirmed)
        if (unconfirmed && (state == null || state.settled))
            put("parent_next_step", "continue_independent_work_no_replay")
        return this
    }
    companion object { const val CAPABILITY = "delegation.text.retained_control" }
}
