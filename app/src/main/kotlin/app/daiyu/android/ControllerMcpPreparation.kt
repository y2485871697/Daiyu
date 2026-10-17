package app.daiyu.android

import app.daiyu.android.assistants.AssistantProfile
import app.daiyu.android.domain.Conversation
import app.daiyu.android.domain.MessageId
import app.daiyu.android.mcp.McpRunScope
import app.daiyu.android.mcp.McpSelection
import app.daiyu.android.model.ProviderSnapshot

/** Pure one-turn preview, no HTTP connection and no synthetic Runner owner. */
internal class ControllerMcpPreparation internal constructor(
    val conversation: Conversation?,
    val editor: ChatController.TextSendOwner,
    val text: String,
    val assistant: AssistantProfile,
    val provider: ProviderSnapshot,
    val selection: McpSelection,
    val scope: McpRunScope,
    val assistantMessageId: MessageId,
) {
    @Volatile private var cancelled = false
    @Volatile private var approved = false
    val confirmed: Boolean get() = approved && !cancelled && scope.isCurrent()
    fun confirm(displayed: ControllerMcpPreparation, owner: ChatController.TextSendOwner, draft: String): Boolean = synchronized(this) {
        if (displayed !== this || owner != editor || draft != text || cancelled || approved || !scope.isCurrent()) false
        else { approved = true; true }
    }
    fun cancel() { cancelled = true; scope.close() }
    override fun toString() = "ControllerMcpPreparation(redacted)"
}
