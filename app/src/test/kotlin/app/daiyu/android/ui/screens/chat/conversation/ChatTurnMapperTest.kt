package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.domain.ConversationId
import app.daiyu.android.domain.Message
import app.daiyu.android.domain.MessageBlock
import app.daiyu.android.domain.MessageId
import app.daiyu.android.domain.MessageHistorySegment
import app.daiyu.android.domain.RunId
import app.daiyu.android.domain.RunStatus
import app.daiyu.android.domain.ToolCallId
import app.daiyu.android.domain.ToolInvocation
import org.junit.Assert.*
import org.junit.Test

class ChatTurnMapperTest {
    private fun message(vararg blocks: MessageBlock, status: RunStatus = RunStatus.COMPLETED) = Message(
        MessageId("assistant"), ConversationId("conversation"), RunId("run"),
        Message.ASSISTANT_ROLE, blocks.toList(), status,
    )
    private fun text(id: String, value: String) = MessageBlock.Text(id, value)
    private fun tool(id: String, result: String? = "done") = MessageBlock.Tool(
        ToolInvocation(ToolCallId(id), "search", "{\"q\":\"test\"}"), result,
    )
    private fun mapped(message: Message) = ChatTurnMapper.map(listOf(message)).single() as ChatTurnUi.Assistant

    @Test fun textOnlyHasNoWorkCardAndPreservesAllText() {
        val turn = mapped(message(text("a", "Hello"), text("b", "world")))
        assertNull(turn.workProcess)
        assertEquals("Hello\n\nworld", turn.finalText)
        assertEquals(RunStatus.COMPLETED, turn.status)
    }

    @Test fun oneCardOwnsNarrationAndAllToolsBeforeFinalText() {
        val original = message(text("a", "First I search"), tool("one"), tool("two"),
            text("b", "Then I check"), tool("three"), text("c", "The answer"))
        val turn = mapped(original)
        val work = turn.workProcess!!
        assertEquals("The answer", turn.finalText)
        assertEquals(listOf("First I search", "Then I check"), work.segments.map { it.narration })
        assertEquals(listOf("one", "two", "three"), work.segments.flatMap { it.steps }.map { it.id })
        assertEquals(listOf(2, 1), work.segments.map { it.steps.size })
        assertTrue(work.segments.flatMap { it.steps }.all { it.state == WorkStepState.UNKNOWN })
        assertEquals(6, original.blocks.size)
    }

    @Test fun pendingToolIsPendingOnlyWhileRunning() {
        for (status in RunStatus.entries) {
            val turn = mapped(message(tool("one", null), status = status))
            val work = turn.workProcess!!
            assertEquals(status == RunStatus.RUNNING, work.running)
            assertEquals(if (status == RunStatus.RUNNING) WorkStepState.PENDING else WorkStepState.UNKNOWN,
                work.segments.single().steps.single().state)
            assertEquals("", turn.finalText)
        }
    }

    @Test fun errorPrefixesAndEmptyResultsWithoutReceiptsStayUnknown() {
        listOf("ERROR: not found", "  error timeout", "失败：访问被拒绝", "", "  ").forEach { result ->
            val turn = mapped(message(tool("one", result), text("a", "Done")))
            assertEquals(WorkStepState.UNKNOWN, turn.workProcess!!.segments.single().steps.single().state)
            assertEquals("结果未确认", turn.workProcess.summary)
        }
    }

    @Test fun structuredFailureNeedsTrustedReceiptFlagAndDoesNotParseArbitraryJson() {
        val original = message(tool("one", "{\"ok\":false,\"error\":\"permission_denied\"}"), text("answer", "done"))
        assertEquals(WorkStepState.UNKNOWN, mapped(original).workProcess!!.segments.single().steps.single().state)
        val failed = ChatTurnMapper.map(listOf(original), confirmedToolError = { _, _ -> true })
            .single() as ChatTurnUi.Assistant
        assertEquals(WorkStepState.FAILED, failed.workProcess!!.segments.single().steps.single().state)
        assertEquals("失败", failed.workProcess.summary)
        val unknown = ChatTurnMapper.map(listOf(original), confirmedToolError = { _, _ -> null })
            .single() as ChatTurnUi.Assistant
        assertEquals(mapped(original), unknown)
    }

    @Test fun retainsTrailingToolInsteadOfDroppingIt() {
        val turn = mapped(message(tool("one"), text("answer", "Partial answer"), tool("two", null), status = RunStatus.RUNNING))
        assertEquals("Partial answer", turn.finalText)
        assertEquals(listOf("one", "two"), turn.workProcess!!.segments.flatMap { it.steps }.map { it.id })
        assertEquals(WorkStepState.PENDING, turn.workProcess.segments.last().steps.last().state)
    }

    @Test fun userUnknownRoleTimestampAndErrorAreMappedWithoutAndroid() {
        val user = message(text("user", "Question")).copy(id = MessageId("user"), role = Message.USER_ROLE)
        val assistant = message().copy(status = RunStatus.FAILED, error = "Offline")
        val ignored = user.copy(role = "system")
        val turns = ChatTurnMapper.map(listOf(user, assistant, ignored)) { "12:34" }
        assertEquals(2, turns.size)
        val userTurn = turns.first() as ChatTurnUi.User
        assertEquals("user", userTurn.id)
        assertEquals("Question", userTurn.text)
        assertEquals("12:34", userTurn.timestamp)
        assertEquals("turn:12:conversation3:run4:user1:0", userTurn.listKey)
        assertEquals("12:34", turns.last().timestamp)
        assertEquals("Offline", (turns.last() as ChatTurnUi.Assistant).error)
    }

    @Test fun rawIdsCannotCollideWithLegacyOrCurrentSyntheticKeys() {
        val owner = message(text("answer", "Answer"))
        val singleKey = mapped(owner).listKey
        // The first id collided with the old global-index key for the first owner segment.
        // The second guards against mixing raw ids with even the new encoded keys.
        val legacyAlias = owner.copy(id = MessageId("conversation:run:assistant:0"))
        val encodedAlias = owner.copy(id = MessageId(singleKey))
        val messages = listOf(owner, owner, legacyAlias, encodedAlias)
        val turns = ChatTurnMapper.map(messages)

        assertEquals(turns.size, turns.map { it.listKey }.toSet().size)
        assertEquals(singleKey, turns.first().listKey)
        assertEquals(messages.map { it.id.value }, turns.map { it.id })
        assertTrue(turns.all { it.listKey != it.id })
    }

    @Test fun identityEncodingIsUnambiguousForColonsAndLengthMarkers() {
        val identities = listOf(
            Triple("a:b", "c", "d"),
            Triple("a", "b:c", "d"),
            Triple("a", "b", "c:d"),
            Triple("1:a", "2:b", "3:c"),
            Triple("1", "a:2", "b:3:c"),
            Triple("会话:12:", "run:1:0", "turn:3:消息"),
        )
        val messages = identities.map { (conversation, run, id) ->
            message().copy(conversationId = ConversationId(conversation), runId = RunId(run), id = MessageId(id))
        }
        val turns = ChatTurnMapper.map(messages)

        assertEquals(messages.size, turns.map { it.listKey }.toSet().size)
        // Each different full identity starts at its own segment zero, even with shared ids.
        assertEquals(messages.map { mapped(it).listKey }, turns.map { it.listKey })
        assertEquals(messages.map { it.id.value }, turns.map { it.id })
    }

    private fun segmentedOwner() = message().copy(historySegments = listOf(
        MessageHistorySegment(MessageId("assistant"), Message.ASSISTANT_ROLE, listOf(text("before", "Before"))),
        MessageHistorySegment(MessageId("supplement"), Message.USER_ROLE, listOf(text("question", "Question"))),
        MessageHistorySegment(MessageId("assistant"), Message.ASSISTANT_ROLE, listOf(text("after", "After"))),
        MessageHistorySegment(MessageId("assistant"), Message.ASSISTANT_ROLE, listOf(text("final", "Final"))),
    ))

    @Test fun prependingEarlierOwnersKeepsExistingSegmentKeysStable() {
        val visible = listOf(segmentedOwner(), message().copy(id = MessageId("later")))
        val before = ChatTurnMapper.map(visible)
        val earlier = listOf(
            message().copy(runId = RunId("earlier:run")), // Same raw id, different owner identity.
            message().copy(conversationId = ConversationId("earlier:conversation")),
        )
        val after = ChatTurnMapper.map(earlier + visible)

        assertEquals(before.map { it.listKey }, after.drop(earlier.size).map { it.listKey })
        assertEquals(before.map { it.id }, after.drop(earlier.size).map { it.id })
        assertEquals(after.size, after.map { it.listKey }.toSet().size)
    }

    @Test fun multipleSegmentsOfOneOwnerHaveUniqueStableKeysAndOriginalActionIds() {
        val owner = segmentedOwner()
        val turns = ChatTurnMapper.map(listOf(owner))
        val assistantTurns = turns.filterIsInstance<ChatTurnUi.Assistant>()

        assertEquals(listOf("assistant", "supplement", "assistant", "assistant"), turns.map { it.id })
        assertEquals(4, turns.map { it.listKey }.toSet().size)
        assertEquals(listOf("0", "1", "2").map { "turn:12:conversation3:run9:assistant1:$it" },
            assistantTurns.map { it.listKey })
        assertEquals(turns.map { it.listKey }, ChatTurnMapper.map(listOf(owner)) { "different timestamp" }.map { it.listKey })
    }

    @Test fun emptyListIsEmptyAndToolFirstCreatesOneSegment() {
        assertTrue(ChatTurnMapper.map(emptyList()).isEmpty())
        val turn = mapped(message(tool("one"), text("answer", "Answer")))
        assertNull(turn.workProcess!!.segments.single().narration)
        assertEquals("Answer", turn.finalText)
    }
    private fun question(id: String) = MessageBlock.Tool(
        ToolInvocation(ToolCallId(id), "ask_user", "{}"), null,
    )

    @Test fun dedicatedQuestionUsesSourceOrderWithoutImageAndMissingProjectionKeepsFallback() {
        val original = message(text("before", "Before"), question("q"), tool("ordinary"), text("after", "After"))
        val fallback = mapped(original)
        assertTrue(fallback.content.isEmpty())
        assertNull(fallback.workProcess!!.segments.flatMap { it.steps }.first().dedicatedKey)
        val turn = ChatTurnMapper.map(listOf(original), dedicatedTool = { _, block ->
            if (block.invocation.id.value == "q") "validated-question" else null
        }).single() as ChatTurnUi.Assistant
        assertEquals(listOf("Before", "q", "ordinary", "After"), turn.content.map { block -> when (block) {
            is MessageContentUi.Text -> block.text
            is MessageContentUi.Tool -> block.step.toolCallId
            is MessageContentUi.Image -> "image"
        } })
        val steps = turn.content.filterIsInstance<MessageContentUi.Tool>().map { it.step }
        assertEquals("validated-question", steps.first().dedicatedKey)
        assertNull(steps.last().dedicatedKey)
        assertEquals(WorkStepOwnerUi("conversation", "run", "assistant"), steps.first().owner)
        assertEquals(original, message(text("before", "Before"), question("q"), tool("ordinary"), text("after", "After")))
    }

    @Test fun dedicatedProjectionOnlyMatchesExactOwnerAndCallAcrossHistorySegments() {
        val original = message().copy(historySegments = listOf(
            MessageHistorySegment(MessageId("assistant"), Message.ASSISTANT_ROLE, listOf(question("first"))),
            MessageHistorySegment(MessageId("supplement"), Message.USER_ROLE, listOf(text("u", "Supplement"))),
            MessageHistorySegment(MessageId("assistant"), Message.ASSISTANT_ROLE, listOf(question("second"))),
        ))
        val otherRun = message(question("first")).copy(runId = RunId("other-run"))
        val turns = ChatTurnMapper.map(listOf(original, otherRun), dedicatedTool = { owner, block ->
            if (owner.conversationId.value == "conversation" && owner.runId.value == "run" &&
                owner.id.value == "assistant") "key-${block.invocation.id.value}" else null
        }).filterIsInstance<ChatTurnUi.Assistant>()
        assertEquals(listOf("first", "second"), turns.take(2).flatMap { it.content }
            .filterIsInstance<MessageContentUi.Tool>().map { it.step.toolCallId })
        assertEquals(listOf("key-first", "key-second"), turns.take(2).flatMap { it.content }
            .filterIsInstance<MessageContentUi.Tool>().map { it.step.dedicatedKey })
        assertEquals(listOf(0, 1), turns.take(2).map { it.segmentIndex })
        assertTrue(turns.last().content.isEmpty())
        assertNull(turns.last().workProcess!!.segments.single().steps.single().dedicatedKey)
    }

}
