package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.ExecutionPhase
import org.junit.Assert.*
import org.junit.Test

class ChatProjectionTest {
    private fun owner() = Message(
        MessageId("owner"), ConversationId("conversation"), RunId("run"), Message.ASSISTANT_ROLE,
        emptyList(), RunStatus.RUNNING,
        historySegments = listOf(
            MessageHistorySegment(MessageId("owner"), Message.ASSISTANT_ROLE,
                listOf(MessageBlock.Text("first", "Before"))),
            MessageHistorySegment(MessageId("supplement"), Message.USER_ROLE,
                listOf(MessageBlock.Text("question", "Question"))),
            MessageHistorySegment(MessageId("owner"), Message.ASSISTANT_ROLE,
                listOf(MessageBlock.Text("later", "After"))),
        ),
    )

    @Test fun supplementalMessageUsesConcreteIdentityNotDatabaseOwner() {
        val turns = ChatTurnMapper.map(listOf(owner()))
        assertEquals(listOf("owner", "supplement", "owner"), turns.map { it.id })
        assertTrue(turns.all { it.ownerMessageId == "owner" })
        assertEquals(1, ChatScrollRequest(1, "supplement", false, 0).targetIndex(turns))
        assertEquals(2, ChatScrollRequest(2, "owner", false, 1).targetIndex(turns))
        assertEquals(0, ChatScrollRequest(3, "owner", false).targetIndex(turns))
    }

    @Test fun exactTargetDoesNotFallBackAndSurvivesPrepending() {
        val turns = ChatTurnMapper.map(listOf(owner()))
        val request = ChatScrollRequest(1, "owner", false, 1)
        assertEquals(-1, request.targetIndex(turns.take(2)))
        val earlier = owner().copy(id = MessageId("earlier"), historySegments = emptyList())
        val prepended = ChatTurnMapper.map(listOf(earlier, owner()))
        assertEquals(3, request.targetIndex(prepended))
        assertEquals(turns.last().listKey, prepended.last().listKey)
        assertEquals(2, ChatScrollRequest(2, listKey = turns.last().listKey).targetIndex(turns))
        assertEquals(-1, ChatScrollRequest(3, "owner", false, listKey = "missing").targetIndex(turns))
    }

    @Test fun generationIndicatorUsesOnlyActualRunningPhase() {
        val active = setOf(ExecutionPhase.IDLE, ExecutionPhase.BOUNDARY, ExecutionPhase.STREAMING,
            ExecutionPhase.RECONNECTING, ExecutionPhase.TOOL_EXECUTING)
        for (composer in ComposerPhase.entries) for (execution in ExecutionPhase.entries.toList() + null) {
            val state = ComposerUi(phase = composer, executionPhase = execution)
            assertEquals("composer=$composer runtime=$execution", composer == ComposerPhase.RUNNING && execution in active,
                state.showGenerationLoading())
        }
    }
}
