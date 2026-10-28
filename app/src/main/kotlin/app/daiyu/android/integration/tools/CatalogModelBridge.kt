package app.daiyu.android.integration.tools

import app.daiyu.android.domain.CatalogTool
import app.daiyu.android.domain.ConversationId
import app.daiyu.android.domain.Message
import app.daiyu.android.domain.RunId
import app.daiyu.android.domain.ToolInvocation
import app.daiyu.android.domain.ToolDefinition as ModelDefinition
import app.daiyu.android.domain.runtime.ReceiptBatchToolExecutor
import app.daiyu.android.domain.runtime.RunExecutionOwner
import app.daiyu.android.domain.tools.*
import java.util.Collections

/** Advertising and whole-batch dispatch use the same host-owned catalog source and ledger. */
internal class CatalogModelBridge(
    catalog: ToolCatalog,
    private val session: SessionCapabilities,
    private val ledger: ToolLedger,
    private val conversationId: ConversationId,
    private val runId: RunId,
    private val task: TaskAuthorization,
    private val currentCatalog: () -> ToolCatalog = { catalog }
) : CatalogTool, ReceiptBatchToolExecutor {
    init { require(task.sessionId == session.sessionId && task.taskId == runId.value) { "tool_run_owner_mismatch" } }
    @Volatile private var owner: RunExecutionOwner? = null
    @Synchronized override fun bindOwner(owner: RunExecutionOwner) {
        require(owner.conversationId == conversationId && owner.runId == runId) { "tool_run_owner_mismatch" }
        check(this.owner == null || this.owner === owner) { "tool_owner_already_bound" }
        this.owner = owner
    }
    private fun dispatcher() = ToolDispatcher(currentCatalog(), session, ledger)
    private var advertisedCatalog: ToolCatalog? = null
    private var advertisedDefinitions: List<ModelDefinition> = emptyList()
    override val definitions: List<ModelDefinition>
        @Synchronized get() {
            val catalog = currentCatalog()
            if (catalog !== advertisedCatalog) {
                advertisedDefinitions = Collections.unmodifiableList(catalog.advertisements(session).map {
                    ModelDefinition(it.name, it.description, it.parameters, it.effect != ToolEffect.READ_ONLY)
                })
                advertisedCatalog = catalog
            }
            return advertisedDefinitions
        }

    override fun execute(invocation: ToolInvocation): String = error("whole_batch_dispatch_required")

    override fun execute(message: Message, invocations: List<ToolInvocation>, cancelled: () -> Boolean): List<ToolDispatchResult> {
        require(message.conversationId == conversationId && message.runId == runId &&
            message.role == Message.ASSISTANT_ROLE) { "tool_run_owner_mismatch" }
        val bound = checkNotNull(owner) { "tool_owner_not_bound" }
        require(bound.owns(message)) { "tool_run_owner_mismatch" }
        return dispatcher().dispatchOwned(task, invocations, bound, cancelled)
    }
    override fun start(message: Message, invocations: List<ToolInvocation>, cancelled: () -> Boolean): ReceiptBatchStart {
        require(message.conversationId == conversationId && message.runId == runId &&
            message.role == Message.ASSISTANT_ROLE) { "tool_run_owner_mismatch" }
        val bound = checkNotNull(owner) { "tool_owner_not_bound" }
        require(bound.owns(message)) { "tool_run_owner_mismatch" }
        return dispatcher().start(task, invocations, bound, cancelled)
    }

}
