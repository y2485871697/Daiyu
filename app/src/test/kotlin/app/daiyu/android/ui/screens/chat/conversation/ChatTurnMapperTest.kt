package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.domain.ConversationId
import app.daiyu.android.domain.Message
import app.daiyu.android.domain.MessageBlock
import app.daiyu.android.domain.MessageId
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
        assertTrue(work.segments.flatMap { it.steps }.all { it.state == WorkStepState.OK })
        assertEquals(6, original.blocks.size)
    }

    @Test fun pendingToolIsPendingOnlyWhileRunning() {
        for (status in RunStatus.entries) {
            val turn = mapped(message(tool("one", null), status = status))
            val work = turn.workProcess!!
            assertEquals(status == RunStatus.RUNNING, work.running)
            assertEquals(if (status == RunStatus.RUNNING) WorkStepState.PENDING else WorkStepState.FAILED,
                work.segments.single().steps.single().state)
            assertEquals("", turn.finalText)
        }
    }

    @Test fun errorPrefixesAndEmptyTerminalResultsFail() {
        listOf("ERROR: not found", "  error timeout", "失败：访问被拒绝", "", "  ").forEach { result ->
            val turn = mapped(message(tool("one", result), text("a", "Done")))
            assertEquals(WorkStepState.FAILED, turn.workProcess!!.segments.single().steps.single().state)
            assertEquals("失败", turn.workProcess.summary)
        }
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
        assertEquals(ChatTurnUi.User("user", "Question", "12:34"), turns.first())
        assertEquals("12:34", turns.last().timestamp)
        assertEquals("Offline", (turns.last() as ChatTurnUi.Assistant).error)
    }

    @Test fun emptyListIsEmptyAndToolFirstCreatesOneSegment() {
        assertTrue(ChatTurnMapper.map(emptyList()).isEmpty())
        val turn = mapped(message(tool("one"), text("answer", "Answer")))
        assertNull(turn.workProcess!!.segments.single().narration)
        assertEquals("Answer", turn.finalText)
    }
}
