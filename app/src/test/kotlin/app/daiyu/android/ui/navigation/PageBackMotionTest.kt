package app.daiyu.android.ui.navigation

import androidx.compose.runtime.BroadcastFrameClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PageBackMotionTest {
    @Test fun preservesStationaryRelease() = assertEquals(0f, pageBackInitialVelocity(.6f, 0f), .000001f)
    @Test fun preservesMeasuredForwardVelocity() = assertEquals(2f, pageBackInitialVelocity(.6f, 2f), .000001f)
    @Test fun preservesReverseVelocity() = assertEquals(-.8f, pageBackInitialVelocity(.6f, -.8f), .000001f)
    @Test fun capsFastFlingAtCriticalDampingBoundary() = assertEquals(3.0207615f, pageBackInitialVelocity(.75f, 30f), .000001f)
    @Test fun remainingDistanceDefinesVelocityCap() = assertEquals(.12083046f, pageBackInitialVelocity(.99f, 2f), .000001f)
    @Test fun finishedTransitionCannotOvershoot() = assertEquals(0f, pageBackInitialVelocity(1f, 2f), .000001f)

    @Test fun preparedMotionRegistersClockBeforeDestinationAndFirstElapsedFrameAdvances() = runTest {
        val clock = BroadcastFrameClock()
        val motion = PageBackCompletionMotion(CoroutineScope(backgroundScope.coroutineContext + clock), .7f, 2f)
        val animation = motion.animation
        assertTrue("commit registers a frame without destination composition", clock.hasAwaiters)
        assertEquals(.7f, animation.value, .000001f)
        clock.sendFrame(1_000_000_000L)
        runCurrent()
        assertEquals("the one clock origin preserves release position", .7f, animation.value, .000001f)

        // Destination adoption is only a join, never a new animateTo / t=0 callback.
        var completed = false
        val destination = backgroundScope.launch { motion.awaitCompletion(); completed = true }
        runCurrent()
        assertSame(animation, motion.animation)
        assertFalse(completed)
        clock.sendFrame(1_016_000_000L)
        runCurrent()
        assertTrue("first positive time must preserve momentum", animation.value - .7f > .3f * .08f)
        clock.sendFrame(3_000_000_000L)
        runCurrent()
        assertEquals(1f, animation.value, .000001f)
        assertTrue(completed)
        destination.cancel()
    }

    @Test fun stationaryAndReverseReleasesUseTheSameClockWithoutInventingForwardVelocity() = runTest {
        for (velocity in listOf(0f, -.8f)) {
            val clock = BroadcastFrameClock()
            val motion = PageBackCompletionMotion(CoroutineScope(backgroundScope.coroutineContext + clock), .6f, velocity)
            clock.sendFrame(1_000_000_000L)
            runCurrent()
            assertEquals(.6f, motion.animation.value, .000001f)
            assertEquals(velocity, motion.animation.velocity, .000001f)
            clock.sendFrame(1_016_000_000L)
            runCurrent()
            if (velocity < 0f) assertTrue(motion.animation.value < .6f)
            else assertTrue(motion.animation.value > .6f)
            motion.cancel()
            runCurrent()
        }
    }

    @Test fun abandonedMotionCannotAdvanceOrAffectTheNextRequest() = runTest {
        val clock = BroadcastFrameClock()
        val scope = CoroutineScope(backgroundScope.coroutineContext + clock)
        val abandoned = PageBackCompletionMotion(scope, .7f, 2f)
        abandoned.cancel()
        runCurrent()
        val next = PageBackCompletionMotion(scope, .55f, 1f)
        clock.sendFrame(1_000_000_000L)
        runCurrent()
        clock.sendFrame(1_016_000_000L)
        runCurrent()
        assertEquals(.7f, abandoned.animation.value, .000001f)
        assertTrue(next.animation.value > .55f)
        next.cancel()
        runCurrent()
    }

    @Test fun removedDestinationCancelsOnlyItsOwnCompletion() = runTest {
        val clock = BroadcastFrameClock()
        val scope = CoroutineScope(backgroundScope.coroutineContext + clock)
        val outgoing = PageBackCompletionMotion(scope, .6f, 1f)
        clock.sendFrame(1_000_000_000L)
        runCurrent()
        outgoing.cancel()
        runCurrent()
        val next = PageBackCompletionMotion(scope, .8f, 1f)
        clock.sendFrame(1_016_000_000L)
        runCurrent()
        outgoing.cancel() // stale owner cleanup must not cancel the newer request
        clock.sendFrame(1_032_000_000L)
        runCurrent()
        assertEquals(.6f, outgoing.animation.value, .000001f)
        assertTrue(next.animation.value > .8f)
        next.cancel()
    }
}
