package app.daiyu.android.timeline

import org.junit.Assert.*
import org.junit.Test

class CompressionPlacementTest {
    private val anchor = TimelineAnchor("conversation", "run", "message", 1)
    private fun activity(id: String = "one", at: TimelineAnchor? = anchor) =
        CompressionActivity(id, "conversation", 4, null, anchor = at)
    @Test fun runningStaysAtItsFrozenBoundaryRatherThanFollowingNewMessages() {
        val live = activity()
        val projection = compressionActivitiesByAnchor(listOf(live), emptyList())
        assertEquals(listOf(live), projection[anchor.listKey])
        assertNull(projection[null])
        assertNull(projection[anchor.copy(segment = 2).listKey])
        assertEquals(projection, compressionActivitiesByAnchor(listOf(live), emptyList()))
    }
    @Test fun acceptedNoticeReplacesTheSameActivityWithoutDuplicateSpinner() {
        val live = activity()
        val completed = ChatTimelineEntry.Compression(live.id, anchor, 1, "summary", null, 4, null, null)
        assertTrue(compressionActivitiesByAnchor(listOf(live), listOf(completed)).isEmpty())
        assertEquals(anchor, ChatTimelineStore.decode(completed.id, completed.created,
            ChatTimelineStore.encode(completed)).anchor)
    }
    @Test fun differentCompactionsAndExactSegmentsRemainIndependent() {
        val first = activity(); val second = activity("two", anchor.copy(segment = 2))
        val projection = compressionActivitiesByAnchor(listOf(first, first, second), emptyList())
        assertEquals(listOf(first), projection[anchor.listKey])
        assertEquals(listOf(second), projection[second.anchor!!.listKey])
        assertEquals(listOf(anchor.listKey, second.anchor.listKey), projection.keys.toList())
    }
    @Test fun absentAnchorAloneUsesTailFallbackButUnloadedAnchorDoesNot() {
        val anchored = activity(); val legacy = activity("legacy", null)
        val projection = compressionActivitiesByAnchor(listOf(anchored, legacy), emptyList())
        assertEquals(listOf(legacy), projection[null])
        assertEquals(listOf(anchored), projection[anchor.listKey])
    }
    @Test fun stoppedActivitiesAreHiddenAndUnrelatedEventsDoNotHideRunningCompression() {
        val live = activity()
        val reconnect = ChatTimelineEntry.Reconnect(live.id, anchor, 0, "running", 0, "NETWORK", 0, "main")
        assertEquals(listOf(live), compressionActivitiesByAnchor(listOf(live, activity("stopped").copy(running = false)),
            listOf(reconnect))[anchor.listKey])
    }
}
