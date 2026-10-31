package app.daiyu.android.integration.tools

import app.daiyu.android.domain.delegation.ChildModelBatch
import app.daiyu.android.domain.delegation.ChildModelTask
import app.daiyu.android.domain.runtime.RunExecutionOwner
import app.daiyu.android.domain.tools.*

/** One parent-owned, text-only batch. No child tools or recursive delegation. */
internal class RunChildModels(private val batch: ChildModelBatch, profiles: Set<String>,
    private val currentProfiles: (() -> Set<String>)? = null) : AutoCloseable {
    private val profiles = profiles.toSet()
    init { require(this.profiles.isNotEmpty() || currentProfiles != null) }

    fun bind(owner: RunExecutionOwner, capabilities: SessionCapabilities, authorization: TaskAuthorization) =
        batch.bind(owner, capabilities, authorization)

    fun tool(): RegisteredTool = object : RegisteredTool {
        override val definition = ToolDefinition(TOOL_NAME, 1,
            "运行一批独立的审查／总结子模型请求，可能产生模型费用。子任务没有工具或写权限；父暂停在本批结束后生效，不自动重试。",
            CAPABILITY, ToolEffect.READ_ONLY,
            ToolSchema(listOf(ArgumentField("tasks", ArgumentType.ArrayValue(
                ArgumentType.ObjectValue(ToolSchema(listOf(
                    ArgumentField("profile_id", ArgumentType.Text(200, minimumLength = 1)),
                    ArgumentField("prompt", ArgumentType.Text(4096, minimumLength = 1))
                ))), minimumItems = 1, maximumItems = null)))))

        override fun prepare(arguments: ToolArguments): PreparedTool {
            val tasks = arguments.array("tasks").map {
                val task = it as ToolArguments
                ChildModelTask(task.text("profile_id"), task.text("prompt"))
            }
            val available = currentProfiles?.invoke() ?: profiles
            require(tasks.all { it.prompt.isNotBlank() && it.profileId in available }) { "invalid_arguments" }
            return PreparedTool.contextual { context -> batch.execute(context, tasks) }
        }
    }

    override fun close() = batch.close()
    fun closeWhenIdle(onClosed: () -> Unit) = batch.closeWhenIdle(onClosed)

    companion object {
        const val TOOL_NAME = "run_child_models"
        const val CAPABILITY = "delegation.text.review"
    }
}
