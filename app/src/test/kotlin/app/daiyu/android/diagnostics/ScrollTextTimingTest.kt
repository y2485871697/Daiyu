package app.daiyu.android.diagnostics

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class ScrollTextTimingTest {
    @Test fun clipsTimedWorkToFrameAndReportsLostPrefix() {
        val times = ScrollTextTiming()
        times.add(ScrollTextTiming.Part.CANDIDATE, 10, 30)
        times.add(ScrollTextTiming.Part.MEASURE, 32, 50)
        assertArrayEquals(longArrayOf(0, 10, 0, 8, 0), times.overlap(20, 40))
        repeat(520) { times.add(ScrollTextTiming.Part.MEASURE, 100L + it, 101L + it) }
        assertArrayEquals(longArrayOf(0, 0, 0, 0, 1), times.overlap(20, 40))
    }
}
