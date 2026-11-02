package app.daiyu.android.ui.screens.chat.conversation

import org.junit.Assert.*
import org.junit.Test

class ChatInitialPositionPolicyTest {
    @Test fun firstMeasureStartsAtTailIncludingTrailingNotices() {
        assertEquals(12, initialChatListIndex(ChatScrollRequest(1), -1, 12))
        assertEquals(12, initialChatListIndex(null, -1, 12))
        assertEquals(0, initialChatListIndex(null, -1, -1))
    }

    @Test fun exactNavigationWinsAndMissingExactTargetNeverFallsBackToTail() {
        val exact = ChatScrollRequest(1, messageId = "message", segmentIndex = 3)
        assertEquals(4, initialChatListIndex(exact, 4, 20))
        assertEquals(0, initialChatListIndex(exact, -1, 20))
        assertEquals(0, initialChatListIndex(ChatScrollRequest(2, listKey = "exact"), -1, 20))
        assertEquals(0, initialChatListIndex(ChatScrollRequest(3, followBottom = false), -1, 20))
    }

    @Test fun firstVisitWithoutRequestStillNeedsMeasuredAlignment() {
        assertTrue(initialChatPositionPending(null, null, true))
        assertTrue(initialChatPositionPending(ChatScrollRequest(1), null, true))
        assertTrue(initialChatPositionPending(ChatScrollRequest(2), 1, false))
    }

    @Test fun consumedNavigationAndRestoredHistoryAreNotRepositioned() {
        assertFalse(initialChatPositionPending(ChatScrollRequest(1), 1, false))
        assertFalse(initialChatPositionPending(null, null, false))
        assertFalse(initialChatPositionPending(ChatScrollRequest(2, messageId = "history"), 1, true))
        assertFalse(initialChatPositionPending(ChatScrollRequest(2, listKey = "segment"), 1, true))
        assertFalse(initialChatPositionPending(ChatScrollRequest(2, followBottom = false), 1, true))
    }

    @Test fun emptySnapshotsAndColdContentDoNotConsumeFirstPlacement() {
        var pending = initialChatPositionPending(ChatScrollRequest(1), null, true)
        // No measured tail yet: empty snapshots and in-flight formatting cannot finish it.
        repeat(3) { pending = retainInitialChatPosition(pending, followBottom = true) }
        assertTrue(pending)
        pending = retainInitialChatPosition(pending, followBottom = true, aligned = true)
        assertFalse(pending)
        // Later output or prepending older history must never reopen the first placement.
        assertFalse(retainInitialChatPosition(pending, followBottom = true))
    }

    @Test fun navigationAwayCancelsPendingPlacementWithoutLockingTheReader() {
        assertFalse(retainInitialChatPosition(true, followBottom = false))
        assertFalse(retainInitialChatPosition(false, followBottom = true))
    }
}
