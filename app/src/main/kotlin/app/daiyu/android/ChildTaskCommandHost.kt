package app.daiyu.android

import app.daiyu.android.domain.*
import app.daiyu.android.domain.delegation.*
import app.daiyu.android.domain.runtime.*
import app.daiyu.android.domain.tools.*
import app.daiyu.android.integration.tools.RunChildTasks

/** A real, local-only Runner owns each records-page dispatch. No main-model request is made. */
internal object ChildTaskCommandHost {
    fun run(initial: Message, command: ChildTaskCommand, supervisor: RetainedChildTasks,
        models: Map<String, ChildModelDefinition>, store: ConversationStore, runtime: RuntimeStore,
        cancelled: () -> Boolean, onChanged: () -> Unit, retain: (RunChildTasks) -> Unit,
        capabilities: Set<String> = emptySet(), toolAssembly: ChildToolAssemblyFactory? = null): ChildTaskCommandReceipt {
        var tasks: RunChildTasks? = null
        var result: ChildTaskCommandReceipt = ChildTaskCommandReceipt.Unconfirmed("child_command_unconfirmed")
        val session = SessionCapabilities(initial.runId.value, capabilities + RunChildTasks.CAPABILITY)
        val authorization = TaskAuthorization(session.sessionId, initial.runId.value)
        val receiver = object : ReceiptBatchToolExecutor {
            override fun bindOwner(owner: RunExecutionOwner) {
                check(!cancelled())
                val accepted = RunChildTasks(supervisor, supervisor.bind(owner, session, authorization, models, toolAssembly, onChanged), models.keys)
                tasks = accepted
                retain(accepted)
            }
            override fun execute(message: Message, invocations: List<ToolInvocation>, cancelled: () -> Boolean): List<ToolDispatchResult> =
                error("local_child_command_has_no_model_tools")
        }
        val local = ModelPort {
            object : ModelSession {
                override fun cancel() {}
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                    check(!cancelled())
                    val invocation = ToolInvocation(ToolCallId(command.callId), "direct_child_task", "{}")
                    result = checkNotNull(tasks).command(invocation, command)
                    val text = when (val receipt = result) {
                        is ChildTaskCommandReceipt.Accepted -> "已持久排队：${receipt.task.owner.childRunId}；来源：${command.source.owner.childRunId}。这是新请求，不修改旧请求。"
                        is ChildTaskCommandReceipt.Rejected -> "未新派：${receipt.code}"
                        is ChildTaskCommandReceipt.Unconfirmed -> "新派回执未确认；不要自动重试：${receipt.code}"
                    }
                    sink(ModelEvent.TextFinal("child-command-receipt", text))
                }
            }
        }
        val runner = ChatRunner(local, store, BatteryTool { error("local_child_command_has_no_tools") },
            RunnerOptions(runtimeStore = runtime, receiptBatchExecutor = receiver)) {}
        try { runner.run(initial, emptyList()) }
        finally { tasks?.close() }
        return result
    }
}
