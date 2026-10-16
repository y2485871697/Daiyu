package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.domain.*
import app.daiyu.android.ui.retainedSearchText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class MessageImageContractTest {
    private val owner = MessageImageOwner("historical-conversation", "original-user")
    private fun image(n: Int, imageOwner: MessageImageOwner = owner) = MessageBlock.Image(
        MessageImageReference(imageOwner, "00000000-0000-0000-0000-${n.toString().padStart(12, '0')}",
            "image/png", 24, "a".repeat(64), 2, 3)
    )
    private fun text(id: String) = MessageBlock.Text(id, id)
    private fun message(blocks: List<MessageBlock>, role: String = Message.USER_ROLE) = Message(
        MessageId(owner.messageId), ConversationId(owner.conversationId), RunId("run"), role,
        blocks, RunStatus.COMPLETED,
    )
    private fun content(turn: ChatTurnUi) = when (turn) {
        is ChatTurnUi.User -> turn.content
        is ChatTurnUi.Assistant -> turn.content
    }

    @Test fun mixedUserBlocksKeepEveryTextAndImageInSourceOrder() {
        val first = image(1); val second = image(2)
        val turn = ChatTurnMapper.map(listOf(message(listOf(text("before"), first, text("between"), second, text("after"))))).single()
        assertEquals(listOf("before", "image", "between", "image", "after"), content(turn).map {
            when (it) {
                is MessageContentUi.Text -> it.text
                is MessageContentUi.Image -> "image"
                is MessageContentUi.Tool -> "tool"
            }
        })
        val images = content(turn).filterIsInstance<MessageContentUi.Image>().map { it.image }
        assertEquals(listOf(first.reference, second.reference), images.map { it.reference })
        assertTrue(images.all { it.expectedOwner == owner })
        assertEquals("before\n\nbetween\n\nafter", (turn as ChatTurnUi.User).text)
    }

    @Test fun mixedAssistantPreservesNarrationToolsFinalAndTrailingImagesExactlyOnce() {
        val tool = MessageBlock.Tool(ToolInvocation(ToolCallId("call"), "lookup", "arguments"), "result")
        val turn = ChatTurnMapper.map(listOf(message(listOf(text("narration"), image(1), tool,
            text("final"), image(2)), Message.ASSISTANT_ROLE))).single() as ChatTurnUi.Assistant
        assertEquals(5, turn.content.size)
        assertEquals(listOf("narration", "final"), turn.content.filterIsInstance<MessageContentUi.Text>().map { it.text })
        val mappedTool = turn.content.filterIsInstance<MessageContentUi.Tool>().single()
        assertEquals("result", mappedTool.step.detail)
        assertEquals("arguments", mappedTool.arguments)
        assertEquals("result", mappedTool.result)
        assertEquals("call", turn.content.filterIsInstance<MessageContentUi.Tool>().single().step.id)
        assertEquals("final", turn.finalText)
        assertEquals(listOf(1, 4), turn.content.indices.filter { turn.content[it] is MessageContentUi.Image })
    }

    @Test fun historicalSupplementUsesSegmentOwnerNotAssistantOwnerAndHasUniqueListKeys() {
        val segment = MessageHistorySegment(MessageId(owner.messageId), Message.USER_ROLE, listOf(text("question"), image(1)))
        val assistant = message(emptyList(), Message.ASSISTANT_ROLE).copy(id = MessageId("assistant"),
            historySegments = listOf(
                MessageHistorySegment(MessageId("assistant"), Message.ASSISTANT_ROLE, listOf(text("before"))),
                segment,
                MessageHistorySegment(MessageId("assistant"), Message.ASSISTANT_ROLE, listOf(text("after"))),
            ))
        // The standalone supplement is validated and de-duplicated by MessageHistory.expand.
        val turns = ChatTurnMapper.map(listOf(assistant, message(segment.blocks)))
        assertEquals(listOf("assistant", owner.messageId, "assistant"), turns.map { it.id })
        assertEquals(3, turns.map { it.listKey }.distinct().size)
        val historical = (turns[1] as ChatTurnUi.User).content.filterIsInstance<MessageContentUi.Image>().single().image
        assertEquals(owner, historical.expectedOwner)
        assertEquals(owner, historical.reference.owner)
    }

    @Test fun imageMetadataNeverBecomesSearchableTextAndToolsRemainSearchable() {
        val tool = MessageBlock.Tool(ToolInvocation(ToolCallId("call"), "lookup", "arguments"), "result")
        val blocks = listOf(text("before"), image(1), tool, image(2), text("after"))
        assertEquals("before\nlookup arguments result\nafter", retainedSearchText(blocks))
        assertEquals("", retainedSearchText(listOf(image(1))))
    }

    @Test fun noLoaderMissingAssetAndResolverExceptionsFailClosedWithoutDiagnostics() = runTest {
        val item = MessageImageUi(owner, image(1).reference)
        assertEquals(MessageImageLoadResult.Unavailable, loadMessageImage(item, null, 1024))
        assertEquals(MessageImageLoadResult.Unavailable,
            loadMessageImage(item, MessageImageLoader { expected, ref, edge ->
                assertEquals(owner, expected); assertEquals(item.reference, ref); assertEquals(1024, edge)
                null
            }, 1024))
        assertEquals(MessageImageLoadResult.Unavailable,
            loadMessageImage(item, MessageImageLoader { _, _, _ -> error("content://private/path") }, 1024))
    }

    @Test fun forgedReferenceOwnerNeverInvokesLoaderOrReplacesPersistedIdentity() = runTest {
        val forged = image(1, MessageImageOwner("other", "other"))
        val mapped = ChatTurnMapper.map(listOf(message(listOf(forged)))).single() as ChatTurnUi.User
        val item = mapped.content.filterIsInstance<MessageContentUi.Image>().single().image
        assertEquals(owner, item.expectedOwner)
        var calls = 0
        assertEquals(MessageImageLoadResult.Unavailable,
            loadMessageImage(item, MessageImageLoader { _, _, _ -> calls++; null }, 1024))
        assertEquals(0, calls)
    }

    @Test fun cancellationIsNotReportedAsMissingAsset() = runTest {
        try {
            loadMessageImage(MessageImageUi(owner, image(1).reference),
                MessageImageLoader { _, _, _ -> throw CancellationException("cancelled") }, 1024)
            fail("cancellation was swallowed")
        } catch (_: CancellationException) { }
    }
}
