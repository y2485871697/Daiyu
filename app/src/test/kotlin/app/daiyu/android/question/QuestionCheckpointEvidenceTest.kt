package app.daiyu.android.question

import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.*
import org.junit.Assert.*
import org.junit.Test

class QuestionCheckpointEvidenceTest {
    private val call = ToolInvocation(ToolCallId("question"), "ask_user", "{}")
    private val message = Message(MessageId("m"), ConversationId("c"), RunId("r"),
        Message.ASSISTANT_ROLE, listOf(MessageBlock.Tool(call)), RunStatus.RUNNING)
    private val checkpoint = RunCheckpoint(message, history = emptyList(), phase = ExecutionPhase.TOOL_EXECUTING, owner = "owner", tools = listOf(ToolRecord(call, ToolDisposition.IN_FLIGHT)))

    @Test fun aThousandBodyAppendsReuseSmallImmutableEvidence() {
        val evidence = QuestionCheckpointEvidence.from(checkpoint, null)
        repeat(1000) { index ->
            assertTrue(evidence.matches(message.copy(blocks = message.blocks + MessageBlock.Text("body", "$index")), call))
        }
        assertEquals("owner", evidence.owner)
        assertNull(evidence.recoveryOwner)
    }
    @Test fun exactMessageRunConversationRoleAndInvocationRemainRequired() {
        val evidence = QuestionCheckpointEvidence.from(checkpoint, null)
        for (other in listOf(message.copy(id = MessageId("other")), message.copy(runId = RunId("other")),
            message.copy(conversationId = ConversationId("other")), message.copy(role = Message.USER_ROLE))) {
            assertFalse(evidence.matches(other, call))
        }
        assertFalse(evidence.matches(message, call.copy(id = ToolCallId("other"))))
        assertFalse(evidence.matches(message, call.copy(arguments = "{\"changed\":true}")))
        assertFalse(evidence.matches(message, call.copy(name = "other")))
    }
    @Test fun recoveryOwnerAndMissingDurableCallAreNotInvented() {
        val recovered = QuestionCheckpointEvidence.from(checkpoint, "original-owner")
        assertEquals("original-owner", recovered.recoveryOwner)
        assertTrue(recovered.matches(message, call))
        assertFalse(QuestionCheckpointEvidence.from(checkpoint.copy(tools = emptyList()), null).matches(message, call))
    }
}
