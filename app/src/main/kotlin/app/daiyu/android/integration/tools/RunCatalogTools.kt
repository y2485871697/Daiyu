package app.daiyu.android.integration.tools

import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.ReceiptBatchToolExecutor
import app.daiyu.android.domain.runtime.RunExecutionOwner
import app.daiyu.android.domain.tools.*
import app.daiyu.android.skills.SkillRunSession
import java.util.concurrent.atomic.AtomicBoolean

/** Runner reads definitions on its worker, after durable admission and before model.open. */
internal class RunCatalogTools(
    private val registry: ToolRegistry,
    private val capabilities: SessionCapabilities,
    private val ledger: ToolLedger,
    private val conversationId: ConversationId,
    private val runId: RunId,
    private val capture: (SessionCapabilities, TaskAuthorization) -> SkillRunSession
) : CatalogTool, ReceiptBatchToolExecutor, AutoCloseable {
    private val authorization = TaskAuthorization(capabilities.sessionId, runId.value)
    private val closed = AtomicBoolean(false)
    @Volatile private var skills: SkillRunSession? = null
    @Volatile private var bridge: CatalogModelBridge? = null

    @Volatile private var owner: RunExecutionOwner? = null
    @Synchronized override fun bindOwner(owner: RunExecutionOwner) {
        check(!closed.get()) { "run_closed" }
        require(owner.conversationId == conversationId && owner.runId == runId) { "tool_run_owner_mismatch" }
        check(this.owner == null || this.owner === owner) { "tool_owner_already_bound" }
        this.owner = owner
    }

    override val definitions: List<app.daiyu.android.domain.ToolDefinition> get() = initialized().definitions

    @Synchronized private fun initialized(): CatalogModelBridge {
        check(!closed.get()) { "run_closed" }
        bridge?.let { return it }
        val bound = checkNotNull(owner) { "tool_owner_not_bound" }
        val scope = try { capture(capabilities, authorization) } catch (failure: Exception) {
            closed.set(true)
            throw failure
        }
        skills = scope
        try {
            check(!closed.get()) { "run_closed" }
            ScopedSkillTools.create(scope, authorization).forEach { registry.register(it) }
            val result = CatalogModelBridge(registry.snapshot(), capabilities, ledger, conversationId, runId, authorization)
            result.bindOwner(bound)
            check(!closed.get()) { "run_closed" }
            bridge = result
            return result
        } catch (failure: Exception) {
            close()
            throw failure
        }
    }
    override fun execute(invocation: ToolInvocation): String = error("whole_batch_dispatch_required")
    override fun execute(message: Message, invocations: List<ToolInvocation>, cancelled: () -> Boolean): List<ToolDispatchResult> {
        require(message.conversationId == conversationId && message.runId == runId && message.role == Message.ASSISTANT_ROLE) {
            "tool_run_owner_mismatch"
        }
        require(checkNotNull(owner) { "tool_owner_not_bound" }.owns(message)) { "tool_run_owner_mismatch" }
        if (closed.get()) return invocations.map { ToolDispatchResult(it.id, DispatchOutcome.NotExecuted("run_closed")) }
        return checkNotNull(bridge) { "catalog_not_prepared" }.execute(message, invocations) { closed.get() || cancelled() }
    }
    override fun start(message: Message, invocations: List<ToolInvocation>, cancelled: () -> Boolean): ReceiptBatchStart {
        require(message.conversationId == conversationId && message.runId == runId && message.role == Message.ASSISTANT_ROLE) {
            "tool_run_owner_mismatch"
        }
        require(checkNotNull(owner) { "tool_owner_not_bound" }.owns(message)) { "tool_run_owner_mismatch" }
        if (closed.get()) return ReceiptBatchStart.Completed(
            invocations.map { ToolDispatchResult(it.id, DispatchOutcome.NotExecuted("run_closed")) })
        return checkNotNull(bridge) { "catalog_not_prepared" }.start(message, invocations) { closed.get() || cancelled() }
    }
    // Do not wait for capture or in-flight reads on the UI/control thread.
    override fun close() { closed.set(true); skills?.close() }
}
