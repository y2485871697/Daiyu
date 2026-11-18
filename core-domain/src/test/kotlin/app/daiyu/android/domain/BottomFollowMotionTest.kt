package app.daiyu.android.domain

import org.junit.Assert.*
import org.junit.Test

class BottomFollowMotionTest {
    @Test fun firstFrameAndIdleRestartHaveNoInventedDelta() {
        val motion = BottomFollowMotion()
        assertEquals(0f, motion.step(200f, 0L, 1f), 0f)
        assertTrue(motion.step(200f, 16_666_667L, 1f) > 0f)
        motion.reset()
        assertEquals(0f, motion.velocityPxPerSecond, 0f)
        assertEquals(0f, motion.step(200f, 5_000_000_000L, 1f), 0f)
        assertTrue(motion.step(200f, 5_008_333_333L, 1f) < 1f)
    }

    @Test fun lineHeightJumpDoesNotInstantlyMultiplyVelocity() {
        val motion = BottomFollowMotion()
        var time = 0L
        var distance = 30f
        motion.step(distance, time, 3f)
        repeat(6) {
            time += 16_666_667L
            distance -= motion.step(distance, time, 3f)
        }
        val before = motion.velocityPxPerSecond
        time += 16_666_667L
        val step = motion.step(distance + 80f, time, 3f)
        assertTrue(motion.velocityPxPerSecond - before <= 120.01f)
        assertTrue(step <= (before + 120.01f) / 60f + 0.01f)
    }

    @Test fun refreshRatesIntegrateNearlyTheSameMotion() {
        fun simulate(hz: Int): Float {
            val motion = BottomFollowMotion()
            var remaining = 1500f
            motion.step(remaining, 0L, 3f)
            repeat(hz) { i ->
                remaining -= motion.step(remaining, ((i + 1) * 1_000_000_000L) / hz, 3f)
            }
            return 1500f - remaining
        }
        assertEquals(simulate(60), simulate(120), 2f)
    }

    @Test fun frameStallsHaveBoundedMovementAndNeverOvershoot() {
        for (ms in listOf(8L, 16L, 33L, 50L, 200L)) {
            val motion = BottomFollowMotion()
            motion.step(1000f, 0L, 2f)
            val moved = motion.step(1000f, ms * 1_000_000L, 2f)
            val seconds = (ms / 1000f).coerceAtMost(0.032f)
            assertTrue(moved > 0f)
            assertTrue(moved <= 0.5f * 4800f * seconds * seconds + 0.01f)
        }
    }

    @Test fun continuousRetargetPreservesAccelerationInsteadOfRestarting() {
        val motion = BottomFollowMotion()
        motion.step(500f, 0L, 1f)
        var before = 0f
        repeat(12) { i ->
            val step = motion.step(500f + i * 50f, (i + 1) * 16_666_667L, 1f)
            assertTrue(step > 0f)
            assertTrue(motion.velocityPxPerSecond >= before)
            assertTrue(motion.velocityPxPerSecond - before <= 40.01f)
            before = motion.velocityPxPerSecond
        }
        assertTrue(before > 400f)
    }

    @Test fun reducedTargetNeverOvershootsOrReverses() {
        val motion = BottomFollowMotion()
        motion.step(1000f, 0L, 1f)
        repeat(30) { motion.step(1000f, (it + 1) * 16_666_667L, 1f) }
        assertEquals(0.2f, motion.step(0.2f, 31 * 16_666_667L, 1f), 0.00001f)
        assertEquals(0f, motion.velocityPxPerSecond, 0f)
        assertEquals(0f, motion.step(-2f, 32 * 16_666_667L, 1f), 0f)
    }

    @Test fun finiteTailSettlesWithoutDroppingDistance() {
        for (target in listOf(0.1f, 1f, 30f, 500f)) {
            val motion = BottomFollowMotion()
            var distance = target
            var total = 0f
            motion.step(distance, 0L, 2f)
            repeat(360) { i ->
                val step = motion.step(distance, (i + 1) * 8_333_333L, 2f)
                assertTrue(step >= 0f && step <= distance)
                distance -= step
                total += step
            }
            assertEquals(0f, distance, 0f)
            assertEquals(target, total, 0.002f)
        }
    }

    @Test fun protectedFrontiersSettleWithoutSerialExponentialWaits() {
        for (density in listOf(1f, 2f, 3f)) for (hz in listOf(60, 90, 120)) {
            val motion = BottomFollowMotion()
            var frame = 0L
            for (frontierDp in listOf(38f, 19f, 24f, 26f)) {
                var distance = frontierDp * density
                var moved = 0f
                motion.step(distance, frame * 1_000_000_000L / hz, density)
                repeat(hz * 2 / 5) {
                    frame++
                    val step = motion.step(distance, frame * 1_000_000_000L / hz, density)
                    assertTrue(step >= 0f && step <= distance)
                    assertTrue(step <= 14f * density)
                    distance -= step
                    moved += step
                }
                assertEquals("${frontierDp}dp at ${hz}Hz must release its protected frontier", 0f, distance, 0f)
                assertEquals(frontierDp * density, moved, 0.002f)
                assertEquals(0f, motion.velocityPxPerSecond, 0f)
            }
        }
    }

    @Test fun steadyFollowerKeepsStalledFramesWithinFourteenDp() {
        for (density in listOf(1f, 2f, 3f)) for (stallMs in listOf(33L, 50L, 200L)) {
            val motion = BottomFollowMotion()
            val distance = 10_000f * density
            motion.step(distance, 0L, density)
            repeat(60) { motion.step(distance, (it + 1) * 16_666_667L, density) }
            val stalledAt = 60 * 16_666_667L + stallMs * 1_000_000L
            val stalled = motion.step(distance, stalledAt, density)
            assertTrue(stalled > 0f && stalled <= 14f * density)
            val resumed = motion.step(distance - stalled, stalledAt + 16_666_667L, density)
            assertTrue(resumed > 0f && resumed <= 14f * density)
        }
    }

    @Test fun activeTypingKeepsMomentumAcrossSettledLineFrontiers() {
        for (density in listOf(1f, 2f, 3f)) for (hz in listOf(60, 90, 120)) {
            val motion = BottomFollowMotion()
            var frame = 0L
            fun tick(distance: Float): Float {
                frame++
                val delta = motion.step(distance, frame * 1_000_000_000L / hz, density, continuous = true)
                assertTrue(delta >= 0f && delta <= minOf(distance, 14f * density))
                return delta
            }
            var first = 26f * density
            repeat(hz / 2) { if (first > 0f) first -= tick(first) }
            assertEquals(0f, first, 0f)
            assertTrue(motion.velocityPxPerSecond > 0f)
            repeat(6) {
                val momentum = motion.velocityPxPerSecond
                // Revealing an already arrived line can take longer than a fixed idle timeout.
                repeat(hz / 2) { assertEquals(0f, tick(0f), 0f) }
                assertEquals(momentum, motion.velocityPxPerSecond, 0f)
                var distance = 26f * density
                var frames = 0
                while (distance > 0f && frames++ < hz) distance -= tick(distance)
                assertEquals(0f, distance, 0f)
                assertTrue("A warm line must not restart its cold acceleration: ${frames} frames at ${hz}Hz",
                    frames * 1_000f / hz <= 100f)
            }
            assertEquals(0f, motion.step(0f, ++frame * 1_000_000_000L / hz, density), 0f)
            assertEquals(0f, motion.velocityPxPerSecond, 0f)
            assertEquals(0f, motion.step(26f * density, ++frame * 1_000_000_000L / hz, density), 0f)
        }
    }

    @Test fun activeTypingStillBoundsStalledFramesAndResetStopsIt() {
        for (density in listOf(1f, 2f, 3f)) {
            val motion = BottomFollowMotion()
            var time = 0L
            motion.step(1_000f * density, time, density, continuous = true)
            repeat(60) { time += 16_666_667L; motion.step(1_000f * density, time, density, continuous = true) }
            repeat(20) { time += 16_666_667L; assertEquals(0f, motion.step(0f, time, density, continuous = true), 0f) }
            time += 200_000_000L
            val step = motion.step(26f * density, time, density, continuous = true)
            assertTrue(step > 0f && step <= 14f * density)
            motion.reset()
            assertEquals(0f, motion.velocityPxPerSecond, 0f)
            assertEquals(0f, motion.step(26f * density, time + 16_666_667L, density, continuous = true), 0f)
            assertEquals(0f, motion.step(-1f, time + 33_333_334L, density, continuous = true), 0f)
            assertEquals(0f, motion.velocityPxPerSecond, 0f)
        }
    }

    @Test fun stopAndInvalidInputsClearMomentum() {
        val motion = BottomFollowMotion()
        motion.step(200f, 0L, 1f)
        motion.step(200f, 16_666_667L, 1f)
        assertEquals(0f, motion.step(0f, 33_333_334L, 1f), 0f)
        assertEquals(0f, motion.velocityPxPerSecond, 0f)
        assertEquals(0f, motion.step(Float.NaN, 50_000_001L, 1f), 0f)
        assertEquals(0f, motion.step(10f, 60_000_000L, 0f), 0f)
    }
}
