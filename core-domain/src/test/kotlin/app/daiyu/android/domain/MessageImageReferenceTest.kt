package app.daiyu.android.domain

import app.daiyu.android.domain.context.ContextRevision
import app.daiyu.android.domain.context.estimateTokens
import org.junit.Assert.*
import org.junit.Test

class MessageImageReferenceTest {
    private val owner = MessageImageOwner("conversation", "user")
    private fun reference(index: Int = 0) = MessageImageReference(
        owner, "00000000-0000-0000-0000-${index.toString().padStart(12, '0')}",
        "image/png", 128, "a".repeat(64), 8, 4
    )
    private fun message(blocks: List<MessageBlock>) = Message(
        MessageId("user"), ConversationId("conversation"), RunId("run"), Message.USER_ROLE,
        blocks, RunStatus.COMPLETED
    )
    private fun rejected(block: () -> Unit) { assertThrows(IllegalArgumentException::class.java) { block() } }

    @Test fun referenceIsAValidatedImmutableValue() {
        val original = reference()
        assertEquals(original, original.copy())
        assertEquals(MessageBlock.Image(original), MessageBlock.Image(reference()))
        assertEquals(original, (message(listOf(MessageBlock.Image(original))).blocks.single() as MessageBlock.Image).reference)
        assertEquals(MessageImageReference.MAX_IMAGE_BYTES,
            original.copy(bytes = MessageImageReference.MAX_IMAGE_BYTES).bytes)
        assertEquals(40_000_000, original.copy(width = 40_000_000, height = 1).width)
        listOf("image/jpeg", "image/png", "image/webp").forEach { assertEquals(it, original.copy(mime = it).mime) }
    }

    @Test fun identitiesAndMetadataRejectPathsCredentialsNoncanonicalAndOverflowValues() {
        rejected { owner.copy(conversationId = " ") }
        rejected { owner.copy(messageId = "x".repeat(201)) }
        listOf("content://provider/image?token=secret", "../image", "a".repeat(36),
            "ABCDEF00-0000-0000-0000-000000000000").forEach { id -> rejected { reference().copy(id = id) } }
        listOf("data:image/png;base64,secret", "image/svg+xml", "IMAGE/PNG", "image/png;token=secret").forEach {
            mime -> rejected { reference().copy(mime = mime) }
        }
        listOf("A".repeat(64), "a".repeat(63), "g".repeat(64)).forEach { hash -> rejected { reference().copy(sha256 = hash) } }
        listOf(Int.MIN_VALUE, -1, 0, MessageImageReference.MAX_IMAGE_BYTES + 1).forEach {
            bytes -> rejected { reference().copy(bytes = bytes) }
        }
        rejected { reference().copy(width = 0) }
        rejected { reference().copy(height = -1) }
        rejected { reference().copy(width = 40_000_001, height = 1) }
        rejected { reference().copy(width = Int.MAX_VALUE, height = Int.MAX_VALUE) }
    }

    @Test fun messageOwnershipDuplicatesAndBatchLimitsFailClosed() {
        val image = MessageBlock.Image(reference())
        MessageHistory.validateOwner(message(listOf(image)))
        rejected { MessageHistory.validateOwner(message(listOf(image.copy(reference = reference().copy(owner = owner.copy(messageId = "other")))))) }
        rejected { MessageHistory.expand(listOf(message(listOf(image)).copy(conversationId = ConversationId("other")))) }
        rejected { MessageHistory.validate(listOf(message(listOf(image, image)))) }
        MessageHistory.validate(listOf(message((0 until 8).map { MessageBlock.Image(reference(it)) })))
        rejected { MessageHistory.validate(listOf(message((0 until 9).map { MessageBlock.Image(reference(it)) }))) }
        rejected {
            MessageHistory.validate(listOf(message((0 until 4).map {
                MessageBlock.Image(reference(it).copy(bytes = MessageImageReference.MAX_IMAGE_BYTES))
            })))
        }
    }

    @Test fun unsegmentedExpansionValidatesImageLimitsWithoutReplacingValidHistory() {
        val image = MessageBlock.Image(reference())
        rejected { MessageHistory.expand(listOf(message(listOf(image, image)))) }
        rejected { MessageHistory.expand(listOf(message((0 until 9).map { MessageBlock.Image(reference(it)) }))) }
        rejected {
            MessageHistory.expand(listOf(message((0 until 4).map {
                MessageBlock.Image(reference(it).copy(bytes = MessageImageReference.MAX_IMAGE_BYTES))
            })))
        }
        val eightImages = listOf(message((0 until 8).map { MessageBlock.Image(reference(it)) }))
        assertSame(eightImages, MessageHistory.expand(eightImages))
        val exactByteLimit = listOf(message((0 until 4).map {
            MessageBlock.Image(reference(it).copy(bytes = if (it < 3) MessageImageReference.MAX_IMAGE_BYTES else 4 * 1024 * 1024))
        }))
        assertSame(exactByteLimit, MessageHistory.expand(exactByteLimit))
    }

    @Test fun segmentOwnershipUsesTheUserSegmentNotTheAssistantRowAndPreservesOrder() {
        val blocks = listOf(MessageBlock.Image(reference()), MessageBlock.Text("text", "question"), MessageBlock.Image(reference(1)))
        val segment = MessageHistorySegment(MessageId("user"), Message.USER_ROLE, blocks)
        val assistant = Message(MessageId("assistant"), ConversationId("conversation"), RunId("run"),
            Message.ASSISTANT_ROLE, emptyList(), RunStatus.COMPLETED, historySegments = listOf(segment))
        val expanded = MessageHistory.expand(listOf(message(blocks), assistant))
        assertEquals(1, expanded.size)
        assertEquals(MessageId("user"), expanded.single().id)
        assertEquals(blocks, expanded.single().blocks)
        assertEquals(blocks, segment.blocks)
        rejected { MessageHistory.validateOwner(assistant.copy(conversationId = ConversationId("other"))) }
        rejected { MessageHistory.validateSegment(segment.copy(messageId = MessageId("other"))) }
        rejected {
            MessageHistory.validateSegment(segment.copy(blocks = listOf(
                blocks.first(), MessageBlock.Image(reference(1).copy(owner = owner.copy(conversationId = "other")))
            )))
        }
    }

    @Test fun revisionCoversEveryReferenceFieldAndOrderingAndEstimateDoesNotCountOnlyTheHandle() {
        val original = reference()
        val baseline = ContextRevision.digest(listOf(message(listOf(MessageBlock.Image(original)))))
        val changed = listOf(
            original.copy(owner = owner.copy(conversationId = "other")),
            original.copy(owner = owner.copy(messageId = "other")),
            reference(1), original.copy(mime = "image/webp"), original.copy(bytes = 129),
            original.copy(sha256 = "b".repeat(64)), original.copy(width = 9), original.copy(height = 5)
        )
        changed.forEach { image -> assertNotEquals(baseline, ContextRevision.digest(listOf(message(listOf(MessageBlock.Image(image)))))) }
        val first = MessageBlock.Image(original)
        val second = MessageBlock.Image(reference(1))
        assertNotEquals(ContextRevision.digest(listOf(message(listOf(first, second)))),
            ContextRevision.digest(listOf(message(listOf(second, first)))))
        assertEquals(16L + original.bytes, estimateTokens(listOf(message(listOf(first)))))
    }
}
