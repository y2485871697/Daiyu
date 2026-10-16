package app.daiyu.android.integration.tools

import app.daiyu.android.domain.ConversationId
import app.daiyu.android.domain.RunId
import app.daiyu.android.domain.runtime.RunExecutionOwner
import app.daiyu.android.domain.tools.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lifecycle glue only. RunCatalogTools owns admission, the single central dispatcher/ledger,
 * and the parent dispatch/creation barrier. The reviewed adapter owns the finite PTY plan.
 * Never call the adapter's standalone execute/start route from this leaf.
 */
internal class TerminalCatalogLeaf(
    binding: CatalogTerminalBinding,
    capabilities: SessionCapabilities,
    ledger: ToolLedger,
    authorization: TaskAuthorization,
    conversationId: ConversationId,
    runId: RunId,
) : AutoCloseable {
    init {
        require(binding.authorization === authorization) { "tool_run_authorization_mismatch" }
        require(binding.plan.conversationId == conversationId && binding.plan.runId == runId.value &&
            binding.plan.sessionId == capabilities.sessionId) { "tool_run_owner_mismatch" }
    }
    private val tools = RunTerminalTools(binding, capabilities, ledger)
    private val observingClose = AtomicBoolean(false)

    val registeredTools: List<RegisteredTool> get() = tools.registeredTools
    fun bindOwner(owner: RunExecutionOwner) = tools.bindOwner(owner)
    fun observeOutcomes(results: List<ToolDispatchResult>) = tools.observeOutcomes(results)
    override fun close() = tools.close()

    /**
     * Called once by the parent's existing close observation protocol, AFTER parent dispatches
     * (including final receipt persistence/UNKNOWN observation) and creations have drained.
     * close() only seals. Like the existing Shizuku cleanup worker, a one-shot daemon performs
     * the bounded join off the caller thread. No polling, sleeping, or automatic retry.
     * A timeout retains the binding/resources and is cached as UNKNOWN by the parent.
     */
    fun observeClose(done: (RunCatalogCloseResult) -> Unit) {
        check(observingClose.compareAndSet(false, true)) { "terminal_close_already_observed" }
        Thread({
            val result = try {
                when (val terminal = tools.awaitClose(30_000)) {
                    TerminalToolsCloseResult.Drained -> RunCatalogCloseResult.Drained
                    is TerminalToolsCloseResult.Unknown -> RunCatalogCloseResult.Unknown(terminal.reason)
                }
            } catch (failure: Exception) {
                if (failure is InterruptedException) Thread.currentThread().interrupt()
                RunCatalogCloseResult.Unknown("terminal_termination_unconfirmed")
            }
            done(result)
        }, "terminal-catalog-close").apply { isDaemon = true; start() }
    }
}
