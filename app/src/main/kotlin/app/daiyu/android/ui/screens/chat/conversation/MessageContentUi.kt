package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.domain.MessageImageOwner
import app.daiyu.android.domain.MessageImageReference

/** Ordered presentation for mixed messages; never flatten images into text/URI placeholders. */
sealed interface MessageContentUi {
    data class Text(val itemId: String, val text: String) : MessageContentUi
    data class Image(val image: MessageImageUi) : MessageContentUi
    data class Reasoning(val reported: app.daiyu.android.domain.ReportedReasoning) : MessageContentUi
    data class Tool(
        val step: WorkStepUi,
        val arguments: String = "",
        val result: String? = null,
    ) : MessageContentUi
}

/**
 * [expectedOwner] comes from the persisted row/expanded history segment, NOT the reference.
 * Neither field is an authorization grant. Only the host's asset resolver may authorize and
 * validate the manifest, digest and bytes. These values must not be rendered as labels/URIs.
 */
data class MessageImageUi(
    val expectedOwner: MessageImageOwner,
    val reference: MessageImageReference,
)
