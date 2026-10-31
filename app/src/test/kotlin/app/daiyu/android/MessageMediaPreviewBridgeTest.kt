package app.daiyu.android

import app.daiyu.android.domain.ConversationId
import app.daiyu.android.domain.Message
import app.daiyu.android.domain.MessageBlock
import app.daiyu.android.domain.MessageHistorySegment
import app.daiyu.android.domain.MessageId
import app.daiyu.android.domain.MessageImageOwner
import app.daiyu.android.domain.MessageImageReference
import app.daiyu.android.domain.RunId
import app.daiyu.android.domain.RunStatus
import app.daiyu.android.media.video.MessageVideoOwner
import app.daiyu.android.media.video.MessageVideoReference
import app.daiyu.android.ui.screens.chat.conversation.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class MessageMediaPreviewBridgeTest {
    private val imageOwner = MessageImageOwner("conversation", "user")
    private val image = MessageImageUi(imageOwner, MessageImageReference(imageOwner,
        "00000000-0000-0000-0000-000000000001", "image/png", 24, "a".repeat(64), 10, 20))
    private val videoOwner = MessageVideoOwner("conversation", "user")
    private val video = MessageVideoUi(videoOwner, MessageVideoReference(videoOwner,
        "00000000-0000-0000-0000-000000000002", "video/mp4", 123, "b".repeat(64), 1000, 20, 10))
    private val row = Message(MessageId("user"), ConversationId("conversation"), RunId("run"),
        Message.USER_ROLE, listOf(MessageBlock.Text("body", "before"), MessageBlock.Image(image.reference)),
        RunStatus.COMPLETED)

    @Test fun mixedSequenceUsesSourceOrderAndClickKeysIdentifyTheCorrectInitialItem() {
        val turns = ChatTurnMapper.map(listOf(row), messageVideos = { listOf(video) })
        val content = (turns.single() as ChatTurnUi.User).content
        assertTrue(content[0] is MessageContentUi.Text)
        assertEquals(listOf(MessageContentUi.Image(image), MessageContentUi.Video(video)), messageMediaSequence(turns))
        val keys = messageMediaSequence(turns).map {
            when (it) {
                is MessageContentUi.Image -> messageImagePreviewKey(it.image)
                is MessageContentUi.Video -> messageVideoPreviewKey(it.video)
                else -> error("not media")
            }
        }
        assertEquals(0, keys.indexOf(messageImagePreviewKey(image)))
        assertEquals(1, keys.indexOf(messageVideoPreviewKey(video)))
    }

    @Test fun videoOnlyMessageIsNotFlattenedToTextAndWrongOwnerProjectionIsRejected() {
        val textRow = row.copy(blocks = listOf(MessageBlock.Text("body", "caption")))
        val turn = ChatTurnMapper.map(listOf(textRow), messageVideos = { listOf(video) }).single() as ChatTurnUi.User
        assertEquals(listOf(MessageContentUi.Text("body", "caption"), MessageContentUi.Video(video)), turn.content)
        val forged = video.copy(expectedOwner = MessageVideoOwner("elsewhere", "user"))
        val denied = ChatTurnMapper.map(listOf(textRow), messageVideos = { listOf(forged) }).single() as ChatTurnUi.User
        assertTrue(denied.content.isEmpty())
    }

    @Test fun expandedSupplementVideoKeepsOriginalOwnerNotPhysicalAssistantRow() {
        val physical = row.copy(id = MessageId("assistant"), role = Message.ASSISTANT_ROLE, blocks = emptyList(),
            historySegments = listOf(
                MessageHistorySegment(MessageId("assistant"), Message.ASSISTANT_ROLE, listOf(MessageBlock.Text("a", "thought"))),
                MessageHistorySegment(row.id, Message.USER_ROLE, row.blocks),
                MessageHistorySegment(MessageId("assistant"), Message.ASSISTANT_ROLE, listOf(MessageBlock.Text("b", "answer")))))
        val binding = MessageMediaVideoBinding("assistant", "run", video)
        val snapshot = ControllerMessageMediaSnapshot(row.conversationId, listOf(binding))
        val turns = ChatTurnMapper.map(listOf(physical), messageVideos = snapshot::videos)
        val projected = (turns[1] as ChatTurnUi.User).content.filterIsInstance<MessageContentUi.Video>().single().video
        assertEquals(videoOwner, projected.expectedOwner)
        assertEquals("assistant", snapshot.binding(projected)?.physicalMessageId)
        assertEquals("assistant", turns[1].ownerMessageId)
        assertNull(snapshot.binding(video.copy(reference = video.reference.copy(sha256 = "c".repeat(64)))))
    }

    @Test fun keyEncodingCannotCollideAcrossOwnersOrMediaKinds() {
        assertNotEquals(messageImagePreviewKey(image), messageVideoPreviewKey(video))
        val changedOwner = MessageImageOwner("conversation:user", "other")
        assertNotEquals(messageImagePreviewKey(image), messageImagePreviewKey(image.copy(expectedOwner = changedOwner)))
        assertNotEquals(messageVideoPreviewKey(video), messageVideoPreviewKey(video.copy(
            expectedOwner = MessageVideoOwner("conversation", "different"))))
    }

    private class Tracked(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = 0
        override fun close() { closed++; super.close() }
    }

    @Test fun downloadCopiesOriginalBytesNotDisplayDerivativeAndCallerCloseReleasesExactlyOnce() {
        val original = ByteArray(9000) { (it % 251).toByte() }
        val source = Tracked(original)
        val lease = MessageMediaOriginals()
        val output = ByteArrayOutputStream()
        lease.retain(source).use { it.copyTo(output) }
        assertArrayEquals(original, output.toByteArray())
        assertEquals(1, source.closed)
        lease.close()
        assertEquals(1, source.closed)
    }

    @Test fun leavingRouteClosesAllOpenOriginalsAndRejectsLateDelivery() {
        val lease = MessageMediaOriginals()
        val first = Tracked(byteArrayOf(1, 2))
        val second = Tracked(byteArrayOf(3, 4))
        val held = lease.retain(first)
        lease.retain(second)
        lease.close(); lease.close()
        assertEquals(1, first.closed)
        assertEquals(1, second.closed)
        try { held.read(); fail("revoked original remained readable") } catch (_: IOException) { }
        val late = Tracked(byteArrayOf(5))
        try { lease.retain(late); fail("late open accepted") } catch (_: IOException) { }
        assertEquals(1, late.closed)
    }
}
