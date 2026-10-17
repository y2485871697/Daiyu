package app.daiyu.android.integration.tools

import app.daiyu.android.domain.ConversationId
import app.daiyu.android.domain.RunId
import app.daiyu.android.domain.runtime.RunExecutionOwner
import app.daiyu.android.domain.tools.*
import app.daiyu.android.platform.actions.*

/** One exact user approval; the central dispatcher is the ONLY ledger/dispatcher for this leaf. */
internal class PlatformActionScope(
    val conversationId: ConversationId,
    val runId: RunId,
    val action: PlatformAction,
    val authorization: TaskAuthorization,
    private val backend: PlatformActionBackend
) : AutoCloseable {
    private var owner: RunExecutionOwner? = null
    private var closed = false
    private var unknown = false
    private var active = 0
    private val observers = mutableListOf<(RunCatalogCloseResult) -> Unit>()
    init {
        require(authorization.sessionId == runId.value && authorization.taskId == runId.value &&
            authorization.allowedWrites == setOf(action.effect)) { "platform_exact_approval_required" }
    }
    @Synchronized fun bind(real: RunExecutionOwner, grants: SessionCapabilities, task: TaskAuthorization) {
        require(real.conversationId == conversationId && real.runId == runId && task === authorization &&
            grants.sessionId == authorization.sessionId && action.kind.capability in grants.enabled) { "platform_owner_mismatch" }
        check(!closed && !unknown && (owner == null || owner === real)) { "platform_scope_unavailable" }
        owner = real
    }
    @Synchronized private fun denial(context: ToolExecutionContext): ExecutionReceipt.NotExecuted? = when {
        closed -> ExecutionReceipt.NotExecuted("platform_scope_closed")
        unknown -> ExecutionReceipt.NotExecuted("platform_outcome_unknown_no_retry")
        owner !== context.owner || context.authorization !== authorization -> ExecutionReceipt.NotExecuted("platform_owner_mismatch")
        context.cancelled() -> ExecutionReceipt.NotExecuted("platform_action_cancelled")
        else -> null
    }

    fun tools(): List<RegisteredTool> {
        // Unavailable backends are NOT advertised, even with a capability switch or forged grant.
        if (backend.unavailable(action.kind) != null) return emptyList()
        return listOf(object : RegisteredTool {
            override val definition = ToolDefinition(action.kind.toolName, 1,
                "仅执行用户已批准的本次精确${action.kind.title}操作；Intent/媒体提交为 UNKNOWN，不自动重试。",
                action.kind.capability, ToolEffect.WRITE, action.kind.schema)
            override fun prepare(arguments: ToolArguments): PreparedTool {
                val requested = PlatformAction.parse(action.kind, arguments.canonicalJson())
                require(requested.effect == action.effect) { "platform_action_not_approved" }
                return PreparedTool.contextual(requested.effect, admit = ::denial) { context ->
                    synchronized(this@PlatformActionScope) {
                        denial(context)?.let { return@contextual it }
                        active++
                    }
                    try {
                        val receipt = try { backend.execute(action) { context.cancelled() || isClosed() } }
                            catch (_: Exception) { ExecutionReceipt.Unknown("platform_backend_unconfirmed") }
                        if (receipt is ExecutionReceipt.Unknown) markUnknown()
                        receipt
                    } finally {
                        synchronized(this@PlatformActionScope) { active-- }
                        deliverClose()
                    }
                }
            }
        })
    }
    @Synchronized private fun isClosed() = closed
    @Synchronized fun markUnknown() { unknown = true }
    override fun close() { synchronized(this) { closed = true }; deliverClose() }
    fun observeClose(done: (RunCatalogCloseResult) -> Unit) {
        synchronized(this) { observers.add(done); closed = true }
        deliverClose()
    }
    private fun deliverClose() {
        val delivery = synchronized(this) {
            if (!closed || active != 0 || observers.isEmpty()) return
            val result = if (unknown) RunCatalogCloseResult.Unknown("platform_effect_unverified_no_retry") else RunCatalogCloseResult.Drained
            observers.toList().also { observers.clear() } to result
        }
        delivery.first.forEach { it(delivery.second) }
    }
}
