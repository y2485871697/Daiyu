package app.daiyu.android.domain

import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationTurnMotionTest {
    @Test fun crossingIntermediateMessagesNeverRestartsOrStopsMotion() {
        val motion = ConversationTurnMotion(800f)
        val steps = List(200) { motion.advance(1f / 60, null) }
        assertTrue(steps.all { it > 0f })
        assertTrue(steps.zipWithNext().all { (a, b) -> b >= a })
    }
    @Test fun measuredTargetDeceleratesWithoutOvershoot() {
        val motion = ConversationTurnMotion(800f)
        repeat(30) { motion.advance(1f / 60, null) }
        var remaining = 1000f
        val steps = mutableListOf<Float>()
        repeat(90) {
            if (remaining > 0f) {
                val step = motion.advance(1f / 60, remaining)
                assertTrue(step > 0f && step <= remaining)
                steps += step
                remaining -= step
            }
        }
        assertEquals(0f, remaining, 0.01f)
        assertTrue(steps.zipWithNext().all { (a, b) -> b <= a })
    }
    @Test fun frameGapsCannotCauseScreenSizedJumps() {
        val motion = ConversationTurnMotion(800f)
        repeat(100) { assertTrue(motion.advance(1f, null) <= 400f) }
    }
    @Test fun changedMeasuredDistanceDoesNotResetVelocityToZero() {
        val motion = ConversationTurnMotion(800f)
        repeat(40) { motion.advance(1f / 60, null) }
        for (remaining in listOf(500f, 1200f, 200f, 900f, 50f)) {
            val step = motion.advance(1f / 60, remaining)
            assertTrue(step > 0f && step <= remaining)
        }
    }
    @Test fun arrivedTargetAndZeroTimeDoNotMove() {
        val motion = ConversationTurnMotion(800f)
        assertEquals(0f, motion.advance(0f, null), 0f)
        assertEquals(0f, motion.advance(0.016f, 0f), 0f)
    }
}
