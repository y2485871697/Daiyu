package app.daiyu.android.integration.tools

import app.daiyu.android.domain.runtime.RunExecutionOwner
import app.daiyu.android.domain.tools.*
import app.daiyu.android.shizuku.*
import java.util.UUID

internal class RunOrdinaryShizukuTools(owner: RunExecutionOwner, authorization: TaskAuthorization,
    private val capabilities: SessionCapabilities, private val access: ShizukuAuthorization,
    private val uid: Int, private val mode: app.daiyu.android.runningmode.RunningMode,
    private val isolatedAccess: (() -> ShizukuAuthorization)? = null) : RunOwnedToolScope(owner, authorization) {
    private var attempted = false
    private var backend: ShizukuBackend? = null
    private val scope = UUID.randomUUID().toString()
    override val tools: List<RegisteredTool> = if (mode != app.daiyu.android.runningmode.RunningMode.SHIZUKU) emptyList() else listOf(object : RegisteredTool {
        override val definition = ToolDefinition(RunShizukuCommands.TOOL_NAME, 1,
            "使用已授权的真实Shizuku身份执行script；不请求系统权限、不回退Root或普通shell，UNKNOWN禁止重发。",
            RunShizukuCommands.CAPABILITY, ToolEffect.WRITE, RunShizukuCommands.SCHEMA)
        override fun prepare(arguments: ToolArguments): PreparedTool {
            val script = arguments.text("script")
            val timeout = if (arguments.contains("timeout_ms")) arguments.integer("timeout_ms") else 30_000L
            val effect = RunShizukuCommands.writeEffect(uid, script, timeout)
            return write(effect) { context ->
                val real = backendForCommands() ?: return@write ExecutionReceipt.NotExecuted("shizuku_backend_unavailable_no_retry")
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
    /** Both ordinary entrypoints share one run-owned connection; connect() must not rotate it per tool. */
    @Synchronized internal fun backendForCommands(): ShizukuBackend? {
        if (mode != app.daiyu.android.runningmode.RunningMode.SHIZUKU || !live()) return null
        if (!attempted) {
            beginCreation()
            attempted = true
            try {
                val runAccess = isolatedAccess?.invoke()?.let { retain(it) } ?: access
                val connection = retain(runAccess.connect(uid))
                val real = connection.openBackend(scope)
                backend = retain(real) { done -> real.closeWithResult { result -> done(result.catalogResult()) } }
            } finally { endCreation() }
        }
        return backend.takeIf { live() }
    }
    private fun ShizukuCloseResult.catalogResult(): RunCatalogCloseResult = when (this) {
        ShizukuCloseResult.Drained -> RunCatalogCloseResult.Drained
        is ShizukuCloseResult.Unknown -> RunCatalogCloseResult.Unknown(reason)
    }
}
