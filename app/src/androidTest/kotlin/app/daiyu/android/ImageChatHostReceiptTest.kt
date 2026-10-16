package app.daiyu.android

import android.test.InstrumentationTestCase
import app.daiyu.android.domain.*
import app.daiyu.android.media.ImageAttachment
import app.daiyu.android.media.ImageAttachmentOwner
import app.daiyu.android.ui.screens.chat.attachments.*
import app.daiyu.android.ui.screens.chat.conversation.ChatTurnMapper

@Suppress("DEPRECATION")
class ImageChatHostReceiptTest : InstrumentationTestCase() {
    private val owner = ImageAttachmentOwner("conversation", "draft")
    private fun image(id: String) = ImageAttachment(owner, id, "image/png", 12, "a".repeat(64), 1, 1)
    private fun accepted(images: List<ImageAttachment>, revision: Long = 9, receiptOwner: ImageAttachmentOwner = owner) =
        ImageSendResult.Accepted(receiptOwner, revision,
            Conversation(ConversationId(owner.conversationId), "title", "", 0, "", false),
            MessageId("user"), MessageId("assistant"), RunId("run"), images)

    fun testOnlyExactOwnerRevisionAndOrderedImagesAuthorizeConsumption() {
        val images = listOf(image("first"), image("second"))
        val state = ImageAttachmentsState(ImageAttachmentDraft(owner, 9), images)
        val submission = checkNotNull(state.beginSubmission())
        assertTrue(imageReceiptMatches(submission, accepted(images)))
        assertFalse(imageReceiptMatches(submission, accepted(images, revision = 10)))
        assertFalse(imageReceiptMatches(submission, accepted(images, receiptOwner = owner.copy(draftId = "new"))))
        assertFalse(imageReceiptMatches(submission, accepted(images.reversed())))
        assertFalse(imageReceiptMatches(submission, accepted(images.take(1))))
        assertTrue(imageReceiptMatches(submission, ImageSendResult.Unknown(owner, 9, null, null)))
        state.finishSubmission(submission, ImageAttachmentAcceptance.Unknown)
        assertEquals(images, state.state.value.ready.map { it.attachment })
    }

    fun testPureImagesHaveNoCopyOrSpeechTextAndMixedCopyPreservesTextOrder() {
        val reference = MessageImageReference(MessageImageOwner("conversation", "user"), "00000000-0000-0000-0000-000000000001", "image/png",
            12, "a".repeat(64), 1, 1)
        val message = Message(MessageId("user"), ConversationId("conversation"), RunId("run"), "user",
            listOf(MessageBlock.Image(reference)), RunStatus.COMPLETED)
        assertNull(copyableImageHostText(message))
        assertNull(voiceHostText(ChatTurnMapper.map(listOf(message)), message.id.value))
        val mixed = message.copy(blocks = listOf(MessageBlock.Text("before", "before"),
            MessageBlock.Image(reference), MessageBlock.Text("after", "after")))
        assertEquals("before\n[图片]\nafter", copyableImageHostText(mixed))
        assertFalse(checkNotNull(copyableImageHostText(mixed)).contains(reference.sha256))
    }
}
