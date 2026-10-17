package app.daiyu.android.domain

import org.junit.Assert.*
import org.junit.Test

class MessageTimeTest {
    private fun message(id: String, role: String, time: Long? = null) = Message(
        MessageId(id), ConversationId("conversation"), RunId("run"), role,
        listOf(MessageBlock.Text("body", id)), RunStatus.COMPLETED, createdAt = time)

    @Test fun unknownHistoryStaysUnknownAndCopiesKeepCreationTime() {
        val old = message("old", "assistant")
        assertNull(old.createdAt)
        assertNull(old.copy(blocks = emptyList(), status = RunStatus.INTERRUPTED).createdAt)
        val accepted = message("new", "assistant", 1_700_000_000_123L)
        assertEquals(accepted.createdAt, accepted.copy(blocks = emptyList(), status = RunStatus.RUNNING).createdAt)
    }

    @Test fun expansionUsesEachMessagesTimeNeverTheOwnersTimeForSupplements() {
        val supplement = message("supplement", "user", 200L)
        val owner = message("owner", "assistant", 100L).copy(historySegments = listOf(
            MessageHistorySegment(MessageId("owner"), "assistant", emptyList()),
            MessageHistorySegment(supplement.id, "user", supplement.blocks),
            MessageHistorySegment(MessageId("segment-only"), "user", emptyList()),
            MessageHistorySegment(MessageId("timed-segment"), "user", emptyList(), createdAt = 300L),
            MessageHistorySegment(MessageId("owner"), "assistant", emptyList())))
        assertEquals(listOf(100L, 200L, null, 300L, 100L),
            MessageHistory.expand(listOf(owner, supplement)).map { it.createdAt })
    }

    @Test(expected = IllegalArgumentException::class)
    fun conflictingSupplementAndRowTimesAreRejected() {
        val supplement = message("supplement", "user", 200L)
        val owner = message("owner", "assistant", 100L).copy(historySegments = listOf(
            MessageHistorySegment(supplement.id, "user", supplement.blocks, createdAt = 201L)))
        MessageHistory.expand(listOf(owner, supplement))
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeTimestampIsRejectedRatherThanDisplayedAsRealTime() {
        message("bad", "assistant", -1L)
    }
}
