package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.domain.MessageImageOwner
import app.daiyu.android.domain.MessageImageReference

/** Ordered presentation for mixed messages; never flatten images into text/URI placeholders. */
sealed interface MessageContentUi {
    data class Text(val itemId: String, val text: String) : MessageContentUi
    data class Image(val image: MessageImageUi) : MessageContentUi
    data class Video(val video: MessageVideoUi) : MessageContentUi
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

/** Read-only physical-row projection. The host must still authorize every original open. */
data class MessageVideoUi(
    val expectedOwner: app.daiyu.android.media.video.MessageVideoOwner,
    val reference: app.daiyu.android.media.video.MessageVideoReference,
)

// Length prefixes avoid collisions between arbitrary persisted identifiers. Keys grant no authority.
private fun mediaPreviewKey(kind: String, vararg values: String): String =
    values.joinToString("", prefix = "$kind:") { "${it.length}:$it" }

internal fun messageImagePreviewKey(image: MessageImageUi): String = with(image) {
    mediaPreviewKey("image", expectedOwner.conversationId, expectedOwner.messageId,
        reference.owner.conversationId, reference.owner.messageId, reference.id, reference.sha256)
}

internal fun messageVideoPreviewKey(video: MessageVideoUi): String = with(video) {
    mediaPreviewKey("video", expectedOwner.conversationId, expectedOwner.messageId,
        reference.owner.conversationId, reference.owner.messageId, reference.id, reference.sha256)
}
