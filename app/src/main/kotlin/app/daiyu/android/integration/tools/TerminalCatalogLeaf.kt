package app.daiyu.android.integration.tools

import app.daiyu.android.domain.ConversationId
import app.daiyu.android.domain.RunId
import app.daiyu.android.domain.runtime.RunExecutionOwner
import app.daiyu.android.domain.tools.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 将有限 PTY 计划的工具和关闭结果接入 RunCatalogTools，计划由 CatalogTerminalBinding 持有。
 * 准入、统一调度、账本及派发和创建中的资源计数由 RunCatalogTools 管理。
 * 此处通过父目录派发工具，不调用适配器的独立 execute/start 入口。
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
     * 父目录等派发、最终回执持久化或 UNKNOWN 记录，以及资源创建全部结束后调用一次。
     * close() 只封闭准入；此处用一次性守护线程等待资源退出，避免阻塞调用线程。
     * 等待有时限，超时后保留绑定和资源，由父目录缓存为 UNKNOWN；不轮询或自动重试。
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
