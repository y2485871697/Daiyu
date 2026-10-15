package app.daiyu.android.integration.tools

import app.daiyu.android.domain.CatalogTool
import app.daiyu.android.domain.ConversationId
import app.daiyu.android.domain.Message
import app.daiyu.android.domain.RunId
import app.daiyu.android.domain.ToolInvocation
import app.daiyu.android.domain.ToolDefinition as ModelDefinition
import app.daiyu.android.domain.runtime.ReceiptBatchToolExecutor
import app.daiyu.android.domain.tools.*
import java.util.Collections

/** One run shares its frozen catalog between provider advertising and whole-batch dispatch. */
internal class CatalogModelBridge(
    catalog: ToolCatalog,
    session: SessionCapabilities,
    ledger: ToolLedger,
    private val conversationId: ConversationId,
    private val runId: RunId
) : CatalogTool, ReceiptBatchToolExecutor {
    private val task = TaskAuthorization(session.sessionId, runId.value)
    private val dispatcher = ToolDispatcher(catalog, session, ledger)
    override val definitions: List<ModelDefinition> = Collections.unmodifiableList(
        catalog.advertisements(session).map {
            ModelDefinition(it.name, it.description, it.parameters, it.effect != ToolEffect.READ_ONLY)
        })

    override fun execute(invocation: ToolInvocation): String = error("whole_batch_dispatch_required")

    override fun execute(message: Message, invocations: List<ToolInvocation>, cancelled: () -> Boolean): List<ToolDispatchResult> {
        require(message.conversationId == conversationId && message.runId == runId &&
            message.role == Message.ASSISTANT_ROLE) { "tool_run_owner_mismatch" }
        return dispatcher.dispatch(task, invocations, cancelled)
    }
}
