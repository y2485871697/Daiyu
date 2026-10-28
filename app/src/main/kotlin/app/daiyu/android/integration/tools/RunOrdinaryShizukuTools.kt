package app.daiyu.android.integration.tools

import app.daiyu.android.domain.runtime.RunExecutionOwner
import app.daiyu.android.domain.tools.*
import app.daiyu.android.shizuku.*
import java.util.UUID

internal class RunOrdinaryShizukuTools(owner: RunExecutionOwner, authorization: TaskAuthorization,
    private val capabilities: SessionCapabilities, private val access: ShizukuAuthorization,
    private val uid: Int) : RunOwnedToolScope(owner, authorization) {
    private var attempted = false
    private var backend: ShizukuBackend? = null
    private val scope = UUID.randomUUID().toString()
    override val tools = listOf(object : RegisteredTool {
        override val definition = ToolDefinition(RunShizukuCommands.TOOL_NAME, 1,
            "使用已授权的真实Shizuku身份执行script；不请求系统权限、不回退Root或普通shell，UNKNOWN禁止重发。",
            RunShizukuCommands.CAPABILITY, ToolEffect.WRITE, RunShizukuCommands.SCHEMA)
        override fun prepare(arguments: ToolArguments): PreparedTool {
            val script = arguments.text("script")
            val timeout = if (arguments.contains("timeout_ms")) arguments.integer("timeout_ms") else 30_000L
            val effect = RunShizukuCommands.writeEffect(uid, script, timeout)
            return write(effect) { context ->
                if (!attempted) {
                    attempted = true
                    val connection = access.connect(uid)
                    retain(connection)
                    val real = connection.openBackend(scope)
                    backend = retain(real) { done -> real.closeWithResult { result -> done(result.catalogResult()) } }
                }
                val real = backend ?: return@write ExecutionReceipt.NotExecuted("shizuku_backend_unavailable_no_retry")
                if (real.identity.uid != uid) return@write ExecutionReceipt.NotExecuted("shizuku_identity_changed")
                val commands = RunShizukuCommands(real, admission = ::live,
                    commandApproved = { it == effect }, expectedSessionId = authorization.sessionId,
                    expectedRunId = owner.runId.value)
                retain(commands) { done -> commands.closeWithResult { result -> done(result.catalogResult()) } }
                commands.bind(owner, capabilities, context.authorization)
                commands.execute(context, script, timeout, effect)
            }
        }
    })
    private fun ShizukuCloseResult.catalogResult(): RunCatalogCloseResult = when (this) {
        ShizukuCloseResult.Drained -> RunCatalogCloseResult.Drained
        is ShizukuCloseResult.Unknown -> RunCatalogCloseResult.Unknown(reason)
    }
}
