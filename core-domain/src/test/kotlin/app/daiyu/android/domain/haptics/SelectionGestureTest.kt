package app.daiyu.android.domain.haptics

import org.junit.Assert.*
import org.junit.Test

class SelectionGestureTest {
    private class Clock(var now: Long = 1000L) : HapticClock { override fun nowMillis() = now }

    @Test fun stationaryLongPressFiresOnceAndReleaseBlocksLinksForThreeHundredMillis() {
        val clock = Clock()
        var pulses = 0
        val gesture = SelectionGesture(clock, 500L, 10f) { pulses++ }
        gesture.down(1f, 1f)
        clock.now += 499
        gesture.timeout()
        assertEquals(0, pulses)
        clock.now++
        gesture.timeout()
        gesture.timeout()
        assertEquals(1, pulses)
        assertFalse(gesture.canOpenLink())
        gesture.move(100f, 100f)
        gesture.timeout()
        assertEquals(1, pulses)
        gesture.up()
        clock.now += 299
        assertFalse(gesture.canOpenLink())
        clock.now++
        assertTrue(gesture.canOpenLink())
    }

    @Test fun dragOrCancelBeforeTimeoutNeverVibratesOrConsumesSelection() {
        val clock = Clock()
        var pulses = 0
        val gesture = SelectionGesture(clock, 500L, 10f) { pulses++ }
        gesture.down(0f, 0f)
        gesture.move(11f, 0f)
        assertNull(gesture.deadlineMillis())
        clock.now += 500
        gesture.timeout()
        assertEquals(0, pulses)
        gesture.cancel()
        assertTrue(gesture.canOpenLink())
        gesture.down(0f, 0f)
        gesture.cancel()
        clock.now += 1000
        gesture.timeout()
        assertEquals(0, pulses)
    }

    @Test fun shortTapKeyboardAndAccessibilityLinksRemainAvailable() {
        val clock = Clock()
        val gesture = SelectionGesture(clock, 500L, 10f) { fail("short tap") }
        assertTrue(gesture.canOpenLink())
        gesture.down(0f, 0f)
        clock.now += 499
        gesture.up()
        assertTrue(gesture.canOpenLink())
    }

    @Test fun delayedTimeoutReleaseStillBlocksNavigationAndNewDownResetsGuard() {
        val clock = Clock()
        val gesture = SelectionGesture(clock, 500L, 10f) { fail("no timeout delivered") }
        gesture.down(0f, 0f)
        clock.now += 500
        gesture.up()
        assertFalse(gesture.canOpenLink())
        gesture.down(0f, 0f)
        assertTrue(gesture.canOpenLink())
    }

    @Test fun cancellationAfterSelectionClearsGestureButRetainsShortLinkGuard() {
        val clock = Clock()
        var pulses = 0
        val gesture = SelectionGesture(clock, 500L, 10f) { pulses++ }
        gesture.down(0f, 0f)
        clock.now += 500
        gesture.timeout()
        gesture.cancel()
        assertEquals(1, pulses)
        assertFalse(gesture.canOpenLink())
        clock.now += 300
        assertTrue(gesture.canOpenLink())
    }

    @Test fun selectionStartHasEightyMillisThrottleAndObeysTouchSwitch() {
        val clock = Clock()
        var pulses = 0
        val port = object : HapticPort {
            override fun play(request: HapticRequest): HapticDelivery {
                assertEquals(HapticKind.LONG_PRESS, request.kind)
                pulses++
                return HapticDelivery.PLAYED
            }
            override fun cancel() = Unit
        }
        val engine = HapticEngine(clock)
        val owner = engine.attach("a", port, resumed = true)
        engine.selectionStart(owner)
        clock.now += 79
        engine.selectionStart(owner)
        assertEquals(1, pulses)
        clock.now++
        engine.selectionStart(owner)
        assertEquals(2, pulses)
        engine.updateSettings(HapticSettings(touch = false))
        clock.now += 80
        engine.selectionStart(owner)
        assertEquals(2, pulses)
    }
}
