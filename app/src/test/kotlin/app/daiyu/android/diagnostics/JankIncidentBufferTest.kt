package app.daiyu.android.diagnostics

import org.junit.Assert.*
import org.junit.Test

class JankIncidentBufferTest {
    private fun frame(t: Long, slow: Boolean = false, first: Boolean = false) =
        PerformanceFrame(t, if (slow) 30_000_000L else 4_000_000L, 8_333_333L, 120.0,
            LongArray(9) { 0L }, 0, first)

    @Test fun normalFramesAndFirstDrawDoNotTrigger() {
        var triggers = 0
        val buffer = JankIncidentBuffer({ _, _ -> fail("No incident expected") }, { triggers++ })
        repeat(500) { buffer.add("chat", frame(it * 8_333_333L)) }
        buffer.add("chat", frame(5_000_000_000L, true, true)); buffer.flush()
        assertEquals(0, triggers)
    }
    @Test fun incidentContainsPrerollSlowFrameAndPostrollOnce() {
        val incidents = mutableListOf<List<Pair<String, PerformanceFrame>>>()
        val complete = mutableListOf<Boolean>(); var triggers = 0
        val buffer = JankIncidentBuffer({ rows, done -> incidents.add(rows); complete.add(done) }, { triggers++ })
        repeat(4) { buffer.add("chat", frame(1_000_000_000L + it * 100_000_000L)) }
        buffer.add("chat/menu=entering", frame(1_400_000_000L, true))
        buffer.add("settings", frame(2_000_000_000L)); buffer.add("settings", frame(3_400_000_000L))
        assertEquals(1, triggers); assertEquals(1, incidents.size); assertEquals(listOf(true), complete)
        assertEquals(7, incidents.single().size)
        assertEquals("chat/menu=entering", incidents.single()[4].first)
        buffer.flush(); assertEquals(1, incidents.size)
    }
    @Test fun pauseFlushIsIncompleteAndTriggerStormIsCooledDown() {
        val complete = mutableListOf<Boolean>(); var triggers = 0
        val buffer = JankIncidentBuffer({ _, done -> complete.add(done) }, { triggers++ })
        buffer.add("chat", frame(1_000_000_000L, true)); buffer.flush()
        repeat(10) { buffer.add("chat", frame(2_000_000_000L + it * 50_000_000L, true)) }
        assertEquals(listOf(false), complete); assertEquals(1, triggers)
        buffer.add("chat", frame(17_000_000_000L, true)); buffer.flush()
        assertEquals(2, triggers); assertEquals(listOf(false, false), complete)
    }
    @Test fun historyAndIncidentHaveHardFrameCaps() {
        val incidents = mutableListOf<List<Pair<String, PerformanceFrame>>>()
        val complete = mutableListOf<Boolean>()
        val buffer = JankIncidentBuffer({ rows, done -> incidents.add(rows); complete.add(done) }, {})
        repeat(900) { buffer.add("settings", frame(it.toLong())) }
        buffer.add("settings", frame(901, true))
        repeat(1500) { buffer.add("settings", frame(902L + it)) }
        buffer.flush()
        assertEquals(1, incidents.size); assertTrue(incidents.single().size <= 960)
        assertTrue(incidents.single().first().second.vsyncNs > 0)
        assertEquals(listOf(false), complete)
    }
    @Test fun timedCheckpointKeepsLateLongFrameAndDoesNotClaimComplete() {
        val incidents = mutableListOf<List<Pair<String, PerformanceFrame>>>()
        val complete = mutableListOf<Boolean>()
        val buffer = JankIncidentBuffer({ rows, done -> incidents.add(rows); complete.add(done) }, {})
        buffer.add("chat", frame(1_000_000_000L, true))
        buffer.checkpoint()
        val late = frame(2_000_000_000L, true).copy(totalNs = 3_000_000_000L)
        buffer.add("chat/menu=exiting", late)
        buffer.add("chat", frame(5_000_000_000L))
        assertEquals(listOf(false, true), complete)
        assertEquals(1, incidents.first().size)
        assertTrue(incidents.last().any { it.second == late })
        buffer.flush()
        assertEquals(2, incidents.size)
    }
    @Test fun unknownBudgetCannotClaimASlowFrame() {
        var triggers = 0
        val buffer = JankIncidentBuffer({ _, _ -> fail("Unknown budget") }, { triggers++ })
        buffer.add("detail", frame(1, true).copy(budgetNs = -1)); assertEquals(0, triggers)
    }
}
