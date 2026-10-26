package app.daiyu.android.storage

import app.daiyu.android.domain.*
import org.junit.Assert.*
import org.junit.Test

class MessageHistoryCutTest {
    private val conversation = ConversationId("conversation")
    private val run = RunId("run")
    private fun user(id: String) = Message(MessageId(id), conversation, run, Message.USER_ROLE,
        listOf(MessageBlock.Text("body", id)), RunStatus.COMPLETED)
    private fun segmented(status: RunStatus = RunStatus.COMPLETED): List<Message> {
        val owner = Message(MessageId("assistant"), conversation, run, Message.ASSISTANT_ROLE,
            listOf(MessageBlock.Text("old", "obsolete aggregate")), status, historySegments = listOf(
                MessageHistorySegment(MessageId("assistant"), Message.ASSISTANT_ROLE, listOf(MessageBlock.Text("first", "first"))),
                MessageHistorySegment(MessageId("supplement"), Message.USER_ROLE, user("supplement").blocks),
                MessageHistorySegment(MessageId("assistant"), Message.ASSISTANT_ROLE, listOf(MessageBlock.Text("second", "second")))))
        return listOf(user("initial"), owner)
    }
    @Test fun everyLogicalBoundaryIsAnExactPrefixWithAndWithoutStandaloneSupplement() {
        for (standalone in listOf(false, true)) {
            val history = segmented() + if (standalone) listOf(user("supplement")) else emptyList()
            val logical = MessageHistory.expand(history)
            for (count in 0..logical.size)
                assertEquals(logical.take(count), MessageHistory.expand(MessageHistoryCut.prefix(history, count)))
        }
    }
    @Test fun selectedAssistantDeleteKeepsItsPrecedingUser() {
        val history = segmented()
        val selected = MessageActionTarget(conversation, MessageId("assistant"), MessageId("assistant"), run,
            Message.ASSISTANT_ROLE, "revision", "default", segmentIndex = 1)
        assertEquals(3, MessageHistoryCut.index(history, selected))
        val retained = MessageHistoryCut.prefix(history, MessageHistoryCut.index(history, selected))
        assertEquals(listOf("initial", "assistant", "supplement"), MessageHistory.expand(retained).map { it.id.value })
        assertEquals(listOf(MessageBlock.Text("first", "first")), retained.last().blocks)
    }
    @Test fun truncatingFailedOwnerDoesNotRewriteRetainedAnswerStatus() {
        val history = segmented(RunStatus.FAILED)
        val retained = MessageHistoryCut.prefix(history, 2)
        assertEquals(RunStatus.FAILED, retained.last().status)
        assertEquals(MessageHistory.expand(history).take(2), MessageHistory.expand(retained))
    }
    @Test fun branchAtUserDoesNotPullInItsAssistant() {
        assertEquals(listOf(user("initial")), MessageHistoryCut.prefix(segmented(), 1))
    }
    @Test fun invalidCountOrMissingSegmentIsRejected() {
        try { MessageHistoryCut.prefix(segmented(), 5); fail("count must be bounded") }
        catch (_: IllegalArgumentException) { }
        val missing = MessageActionTarget(conversation, MessageId("assistant"), MessageId("assistant"), run,
            Message.ASSISTANT_ROLE, "revision", "default", segmentIndex = 2)
        try { MessageHistoryCut.index(segmented(), missing); fail("segment must exist") }
        catch (_: IllegalStateException) { }
    }
}
