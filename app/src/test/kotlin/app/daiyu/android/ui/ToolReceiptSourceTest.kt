package app.daiyu.android.ui

import app.daiyu.android.domain.*
import org.junit.Assert.*
import org.junit.Test

class ToolReceiptSourceTest {
    private val call = ToolInvocation(ToolCallId("call"), "screen", "{}")
    private val message = Message(MessageId("message"), ConversationId("conversation"), RunId("run"),
        Message.ASSISTANT_ROLE, listOf(MessageBlock.Tool(call)), RunStatus.RUNNING)
    private fun source(value: Message) = RetainedToolOutcomes.sources(listOf(value))

    @Test fun bodyGrowthDoesNotInvalidateDurableReceiptEvidence() {
        assertEquals(source(message), source(message.copy(blocks = message.blocks + MessageBlock.Text("reply", "数🙂".repeat(1000)))))
    }
    @Test fun exactInvocationResultAndStatusChangesRequireFreshEvidence() {
        val block = message.blocks.single() as MessageBlock.Tool
        for (next in listOf(message.copy(status = RunStatus.COMPLETED), message.copy(runId = RunId("other")),
            message.copy(blocks = listOf(block.copy(result = "done"))),
            message.copy(blocks = listOf(block.copy(invocation = call.copy(arguments = "{\"changed\":true}")))))) {
            assertNotEquals(source(message), source(next))
        }
    }
    @Test fun historyOnlyToolsAreIncludedOnceWithoutTextInTheSignature() {
        val segment = MessageHistorySegment(message.id, Message.ASSISTANT_ROLE, message.blocks)
        val historical = message.copy(blocks = emptyList(), historySegments = listOf(segment))
        assertEquals(source(message), source(historical))
        assertEquals(1, source(message.copy(historySegments = listOf(segment))).single().tools.size)
        assertTrue(source(message.copy(role = Message.USER_ROLE)).isEmpty())
    }
}
