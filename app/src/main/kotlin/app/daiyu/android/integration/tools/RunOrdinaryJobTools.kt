package app.daiyu.android.integration.tools

import android.content.Context
import app.daiyu.android.domain.runtime.RunExecutionOwner
import app.daiyu.android.domain.tools.*
import app.daiyu.android.terminal.TerminalConfiguration

internal class RunOrdinaryJobTools(private val context: Context, owner: RunExecutionOwner,
    authorization: TaskAuthorization, private val capabilities: SessionCapabilities,
    private val configurations: Map<String, TerminalConfiguration>,
    private val launch: (String) -> TerminalPtyLaunch?,
    private val pinWorkspace: () -> AutoCloseable,
    private val retainJob: (TerminalJobSession) -> Boolean) : RunOwnedToolScope(owner, authorization) {
    private val jobs = linkedMapOf<String, TerminalJobSession>()
    override val tools = listOf(object : RegisteredTool {
        override val definition = ToolDefinition(TerminalJobSession.START, 1,
            "启动当前工作区的一条async/daemon命令。job派发不等于完成，UNKNOWN不重发，使用原job_id诊断。daemon保留于原会话。",
            TerminalJobSession.CAPABILITY, ToolEffect.WRITE, ToolSchema(listOf(
                ArgumentField("command", ArgumentType.Text(32768, minimumLength = 1)),
                ArgumentField("environment", ArgumentType.Text(choices = configurations.keys)),
                ArgumentField("mode", ArgumentType.Text(choices = setOf("async", "daemon"))))))
        override fun prepare(arguments: ToolArguments): PreparedTool {
            val environment = arguments.text("environment")
            val command = arguments.text("command")
            val mode = arguments.text("mode")
            require(command.isNotBlank() && '\u0000' !in command)
            // A launch is captured by the trusted host before advertisement, never by model paths.
            val frozen = configurations.getValue(environment)
            require(mode != "daemon" || environment == "android" || frozen.linux?.rootScope != null) { "linux_daemon_not_supported" }
            val effect = TerminalJobPlan.effectFor(owner.conversationId, owner.runId, frozen, command, mode)
            return write(effect) { context ->
                val real = launch(environment) ?: return@write ExecutionReceipt.NotExecuted("terminal_environment_unavailable")
                val pin = pinWorkspace()
                val plan = try {
                    TerminalJobPlan(owner.conversationId, owner.runId, real, command, mode, pin).also {
                        check(it.effect == effect) { "terminal_launch_changed" }
                    }
                } catch (failure: Throwable) {
                    pin.close(); (real as? TerminalPtyLaunch.Linux)?.close(); throw failure
                }
                val session = try { TerminalJobSession.approve(this@RunOrdinaryJobTools.context, plan, context.authorization) }
                catch (failure: Throwable) { plan.releaseWorkspace(); (real as? TerminalPtyLaunch.Linux)?.close(); throw failure }
                retain(session, session::observeClose)
                if (!retainJob(session)) {
                    session.close()
                    return@write ExecutionReceipt.NotExecuted("terminal_job_limit_or_run_closed")
                }
                session.bind(owner, capabilities, context.authorization)
                val result = session.start(context)
                session.jobId?.let { synchronized(jobs) { jobs[it] = session } }
                result
            }
        }
    }, object : RegisteredTool {
        override val definition = ToolDefinition(TerminalJobSession.READ, 1,
            "只读查询同一run已受理job的首个16KiB日志和实际状态，不重放命令。",
            TerminalJobSession.CAPABILITY, ToolEffect.READ_ONLY, ToolSchema(listOf(
                ArgumentField("job_id", ArgumentType.Text(36, minimumLength = 36)))))
        override fun prepare(arguments: ToolArguments): PreparedTool {
            val id = arguments.text("job_id")
            require(id.matches(Regex("[a-f0-9-]{36}")))
            return diagnosticRead {
                synchronized(jobs) { jobs[id] }?.let { ExecutionReceipt.Confirmed(it.inspectOnWorker().toString()) }
                    ?: ExecutionReceipt.NotExecuted("terminal_job_not_owned")
            }
        }
    })
}
