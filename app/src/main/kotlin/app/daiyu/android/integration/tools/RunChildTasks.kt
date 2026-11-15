package app.daiyu.android.integration.tools

import app.daiyu.android.delegation.SubAgentDescriptor

import app.daiyu.android.domain.ToolInvocation
import app.daiyu.android.domain.delegation.*
import app.daiyu.android.domain.tools.*
import org.json.JSONArray
import org.json.JSONObject

/** Opt-in background admission; parent closure seals submission, not the accepted children. */
internal class RunChildTasks(private val supervisor: RetainedChildTasks, private val binding: ChildTaskBinding,
    profiles: Set<String>, private val currentModels: (() -> Map<String, ChildModelDefinition>)? = null,
    private val currentProfiles: (() -> Set<String>)? = null,
    private val initialProfiles: List<SubAgentDescriptor> = emptyList()) : AutoCloseable {
    private val profiles = profiles.toSet()
    init { require(this.profiles.isNotEmpty() || currentProfiles != null) }

    fun tool(name: String = TOOL_NAME, capability: String = CAPABILITY): RegisteredTool = object : RegisteredTool {
        override val definition = ToolDefinition(name, if (name == TOOL_NAME) 1 else 2,
            "提交后台子任务，可能产生模型费用。仅等待准入持久化，不等待子任务结果；返回后主代理继续独立工作，主回复结束后子任务继续。需要当前子代理配置时调用list_subagents，任务总数和状态汇总使用inspect_child_tasks；需要具体任务的进度和结果时按完整身份调用get_child_task，不连续空轮询。执行角色使用父任务允许的可委派工具；tool_names可收窄，空数组禁用；browser_access为full/read_only/disabled，默认full但不超过父权限。审查角色仅文本审查，不递归委派或自动重试；继续和追加保留原工具范围。有暂停任务时，替代它须先停止并确认真实排空；只有确实无关的独立新任务才显式传independent_of_paused_tasks=true，不得用于替代绕过。" + SubAgentDescriptor.initialDescription(initialProfiles),
            capability, ToolEffect.READ_ONLY,
            ToolSchema(listOf(ArgumentField("tasks", ArgumentType.ArrayValue(
                ArgumentType.ObjectValue(ToolSchema(listOf(
                    ArgumentField("profile_id", ArgumentType.Text(200, minimumLength = 1)),
                    ArgumentField("prompt", ArgumentType.Text(4096, minimumLength = 1))
                ) + ChildToolAccessArguments.fields)), minimumItems = 1, maximumItems = null)),
                ArgumentField("independent_of_paused_tasks", ArgumentType.BooleanValue, required = false))))

        override fun prepare(arguments: ToolArguments): PreparedTool {
            val tasks = arguments.array("tasks").map {
                val task = it as ToolArguments
                ChildModelTask(task.text("profile_id"), task.text("prompt"), ChildToolAccessArguments.parse(task))
            }
            val available = currentProfiles?.invoke() ?: profiles
            require(tasks.all { it.prompt.isNotBlank() && it.profileId in available }) { "invalid_arguments" }
            return PreparedTool.contextual { context ->
                if (!refreshModels()) return@contextual ExecutionReceipt.NotExecuted("child_model_configuration_unavailable")
                when (val result = supervisor.submit(binding, context, tasks, independentOfPausedTasks =
                    arguments.contains("independent_of_paused_tasks") && arguments.boolean("independent_of_paused_tasks"))) {
                    is ChildTaskAdmission.Rejected -> ExecutionReceipt.NotExecuted(result.code)
                    is ChildTaskAdmission.Unconfirmed -> ExecutionReceipt.Unknown(result.code)
                    is ChildTaskAdmission.Accepted -> {
                        val tasks = JSONArray()
                        result.tasks.forEach { task ->
                            tasks.put(JSONObject().put("child_run_id", task.owner.childRunId)
                                .put("task_group_id", task.owner.taskGroupId).put("generation", task.owner.generation)
                                .put("profile_id", task.profileId).put("conversation_id", task.owner.conversationId)
                                .put("parent_run_id", task.owner.parentRunId).put("assistant_message_id", task.owner.assistantMessageId)
                                .put("parent_message_id", task.parentMessageId).put("parent_owner_id", task.parentOwnerId)
                                .put("expected_revision", task.revision).put("expected_status", task.status.name))
                        }
                        ExecutionReceipt.Confirmed(JSONObject().put("accepted", true).put("completed", false)
                            .put("tasks", tasks).toString())
                    }
                }
            }
        }
    }

    private fun refreshModels(): Boolean = try {
        currentModels?.let { supervisor.configure(binding, it()) } ?: true
    } catch (_: Exception) { false }

    fun command(invocation: ToolInvocation, command: ChildTaskCommand) =
        supervisor.command(binding, invocation, command, prepareAdmission = ::refreshModels)

    fun commandTool(): RegisteredTool = object : RegisteredTool {
        override val definition = ToolDefinition("direct_child_task", 1,
            "定向追加、替换或失败新派。必须给出完整子任务身份与修订。追加等待原任务成功且真正排空，再以原输入/结果发起新请求；不会修改旧请求。UNKNOWN 或替换活跃任务只能在任务记录 UI 经用户单次确认后新派，模型参数不能代替确认。放弃暂停任务而新派前，必须先用control_child_task停止对应旧任务，再inspect确认settled且journal_unconfirmed=false；REQUESTED仅代表停止已受理。随后用最新修订发起带source身份的REPLACE，不得用无source的新委派绕过。接手继续旧任务不停止、不新派。",
            CAPABILITY, ToolEffect.READ_ONLY, ToolSchema(listOf(
                ArgumentField("conversation_id", ArgumentType.Text(200, minimumLength = 1)),
                ArgumentField("parent_run_id", ArgumentType.Text(200, minimumLength = 1)),
                ArgumentField("task_group_id", ArgumentType.Text(200, minimumLength = 1)),
                ArgumentField("generation", ArgumentType.Text(20, minimumLength = 1)),
                ArgumentField("child_run_id", ArgumentType.Text(200, minimumLength = 1)),
                ArgumentField("assistant_message_id", ArgumentType.Text(200, minimumLength = 1)),
                ArgumentField("parent_message_id", ArgumentType.Text(200, minimumLength = 1)),
                ArgumentField("parent_owner_id", ArgumentType.Text(200, minimumLength = 1)),
                ArgumentField("expected_revision", ArgumentType.Text(20, minimumLength = 1)),
                ArgumentField("kind", ArgumentType.Text(20, setOf("APPEND", "REPLACE", "REDISPATCH"), 1)),
                ArgumentField("profile_id", ArgumentType.Text(200, minimumLength = 1)),
                ArgumentField("instruction", ArgumentType.Text(4096, minimumLength = 1)),
                ArgumentField("acknowledge_possible_execution", ArgumentType.Text(5, setOf("true", "false"), 1))
            )))
        override fun prepare(arguments: ToolArguments): PreparedTool {
            require(arguments.text("profile_id") in (currentProfiles?.invoke() ?: profiles)) { "invalid_child_profile" }
            val owner = ChildRunOwner(arguments.text("conversation_id"),
                arguments.text("parent_run_id"), arguments.text("task_group_id"), arguments.text("generation").toLong(),
                arguments.text("child_run_id"), arguments.text("assistant_message_id"))
            val revision = arguments.text("expected_revision").toLong().also { require(it >= 0) }
            return PreparedTool.contextual { context ->
                if (!refreshModels()) return@contextual ExecutionReceipt.NotExecuted("child_model_configuration_unavailable")
                val source = supervisor.source(owner)
                if (source == null || source.parentMessageId != arguments.text("parent_message_id") || source.parentOwnerId != arguments.text("parent_owner_id"))
                    ExecutionReceipt.NotExecuted("child_source_stale")
                else {
                    val expected = source.copy(revision = revision)
                    val command = ChildTaskCommand(
                        "${context.owner.ownerId}:${context.invocation.id.value}".let { key ->
                            // Host owner and provider call ids are separately bounded; hash the composite namespace.
                            java.security.MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
                        }, expected, ChildTaskCommandKind.valueOf(arguments.text("kind")),
                        arguments.text("profile_id"), arguments.text("instruction"), arguments.text("acknowledge_possible_execution") == "true")
                    when (val result = supervisor.command(binding, context, command)) {
                        is ChildTaskCommandReceipt.Accepted -> ExecutionReceipt.Confirmed(
                            JSONObject().put("child_run_id", result.task.owner.childRunId).put("source_task_id", source.owner.childRunId)
                                .put("new_request", true).toString())
                        is ChildTaskCommandReceipt.Rejected -> ExecutionReceipt.NotExecuted(result.code)
                        is ChildTaskCommandReceipt.Unconfirmed -> ExecutionReceipt.Unknown(result.code)
                    }
                }
            }
        }
    }

    fun pauseGroup() = supervisor.pauseGroup(binding)
    fun resumeGroup(group: ChildTaskPauseGroup) = supervisor.resumeGroup(group)
    fun hasCaptured(group: ChildTaskPauseGroup) = supervisor.hasCaptured(group)
    fun finishGroup(group: ChildTaskPauseGroup, stop: Boolean) = supervisor.finishGroup(group, stop)
    fun snapshots() = supervisor.snapshots(binding)
    /** Safe host-map eviction boundary; neither a stop request nor a terminal journal row is enough. */
    fun isReleased(): Boolean = supervisor.isReleased(binding)
    fun stop(owner: ChildRunOwner) = supervisor.stop(binding, owner)
    fun pause(owner: ChildRunOwner) = supervisor.pause(binding, owner)
    fun resume(owner: ChildRunOwner) = supervisor.resume(binding, owner)
    fun takeOver(source: ChildTaskRecord, profileId: String, next: ChildModelDefinition) =
        supervisor.takeOver(binding, source, profileId, next)
    fun revoke() = supervisor.revoke(binding)
    override fun close() = supervisor.finishParent(binding)

    companion object {
        const val TOOL_NAME = "start_child_tasks"
        const val CAPABILITY = "delegation.text.background_review"
    }
}
