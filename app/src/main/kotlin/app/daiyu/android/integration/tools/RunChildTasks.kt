package app.daiyu.android.integration.tools

import app.daiyu.android.domain.delegation.ChildModelTask
import app.daiyu.android.domain.delegation.ChildTaskAdmission
import app.daiyu.android.domain.delegation.ChildTaskBinding
import app.daiyu.android.domain.delegation.RetainedChildTasks
import app.daiyu.android.domain.tools.*
import org.json.JSONArray
import org.json.JSONObject

/** Opt-in background admission; parent closure seals submission, not the accepted children. */
internal class RunChildTasks(private val supervisor: RetainedChildTasks, private val binding: ChildTaskBinding,
    profiles: Set<String>) : AutoCloseable {
    private val profiles = profiles.toSet()
    init { require(this.profiles.isNotEmpty()) }

    fun tool(): RegisteredTool = object : RegisteredTool {
        override val definition = ToolDefinition(TOOL_NAME, 1,
            "提交后台审查子任务，可能产生模型费用。回执只确认排队持久化；任务可在主回复结束后继续，没有工具或写权限，不自动重试。",
            CAPABILITY, ToolEffect.READ_ONLY,
            ToolSchema(listOf(ArgumentField("tasks", ArgumentType.ArrayValue(
                ArgumentType.ObjectValue(ToolSchema(listOf(
                    ArgumentField("profile_id", ArgumentType.Text(200, profiles, 1)),
                    ArgumentField("prompt", ArgumentType.Text(4096, minimumLength = 1))
                ))), minimumItems = 1, maximumItems = 8)))))

        override fun prepare(arguments: ToolArguments): PreparedTool {
            val tasks = arguments.array("tasks").map {
                val task = it as ToolArguments
                ChildModelTask(task.text("profile_id"), task.text("prompt"))
            }
            require(tasks.all { it.prompt.isNotBlank() }) { "invalid_arguments" }
            return PreparedTool.contextual { context ->
                when (val result = supervisor.submit(binding, context, tasks)) {
                    is ChildTaskAdmission.Rejected -> ExecutionReceipt.NotExecuted(result.code)
                    is ChildTaskAdmission.Unconfirmed -> ExecutionReceipt.Unknown(result.code)
                    is ChildTaskAdmission.Accepted -> {
                        val tasks = JSONArray()
                        result.tasks.forEach { task ->
                            tasks.put(JSONObject().put("child_run_id", task.owner.childRunId)
                                .put("task_group_id", task.owner.taskGroupId).put("generation", task.owner.generation)
                                .put("profile_id", task.profileId))
                        }
                        ExecutionReceipt.Confirmed(JSONObject().put("accepted", true).put("completed", false)
                            .put("tasks", tasks).toString())
                    }
                }
            }
        }
    }

    fun snapshots() = supervisor.snapshots(binding)
    /** Safe host-map eviction boundary; neither a stop request nor a terminal journal row is enough. */
    fun isReleased(): Boolean = supervisor.isReleased(binding)
    fun stop(owner: app.daiyu.android.domain.delegation.ChildRunOwner) = supervisor.stop(binding, owner)
    fun revoke() = supervisor.revoke(binding)
    override fun close() = supervisor.finishParent(binding)

    companion object {
        const val TOOL_NAME = "start_child_tasks"
        const val CAPABILITY = "delegation.text.background_review"
    }
}
