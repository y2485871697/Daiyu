package app.daiyu.android

import app.daiyu.android.domain.*
import app.daiyu.android.gui.*
import app.daiyu.android.integration.tools.RunCatalogTools
import java.util.UUID

/** Retained by Controller before opening the real screen lease or overlay. These IDs are also
 * used by database acceptance and Runner; approval never creates a parallel task or dispatcher. */
internal class ControllerGuiEntry(val conversation: Conversation?, val editor: ChatController.TextSendOwner,
    val text: String, val operation: GuiOperation, val input: String?) {
    val conversationId = conversation?.id ?: ConversationId(UUID.randomUUID().toString())
    val runId = RunId(UUID.randomUUID().toString())
    val assistantId = MessageId(UUID.randomUUID().toString())
    val sessionId = runId.value
    var session: GuiRunSession? = null
    var delivery: GuiConfirmedDelivery? = null
    var submitted = false
    var revoked = false
    var accepted = false
    var persistenceUnknown = false
    var textTicket: ChatController.TextSendTicket? = null
    var catalog: RunCatalogTools? = null
    init {
        require(text.isNotBlank() && editor.conversationId == conversation?.id)
        require(editor.editorGeneration >= 0 && editor.draftRevision >= 0)
        require((operation == GuiOperation.INPUT) == (input != null))
        require(input == null || input.length <= 4096)
    }
}
