"""Fast-repeat wiring guards; frozen-clock Android tests cover actual input."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
NAV = ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/navigation'
ANDROID = ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ui/navigation'


class PageBackRepeatContractTest(unittest.TestCase):
    def setUp(self):
        self.host = (NAV / 'PageBackGestures.kt').read_text()

    def test_new_swipe_survives_return_animation_but_not_forward_entry(self):
        registration = self.host.split('Box(modifier.fillMaxSize()', 1)[1].split('val owner = Any()', 1)[0]
        self.assertNotIn('&& !departing', registration)
        self.assertIn('val entering by rememberUpdatedState(activeDeparture && departure?.forward == true)', self.host)
        self.assertIn('if (state.active || entering || state.committing || state.dispatching)', registration)
        self.assertNotIn('state.transitionBlocked', registration)
        # A plain duplicate callback is still blocked; only a newly claimed swipe interrupts.
        self.assertIn('(transitionBlocked && swipeCommit == null) || committing', self.host)

    def test_only_claimed_horizontal_intent_interrupts_and_beats_departure_overlay(self):
        self.assertIn('val returning by rememberUpdatedState(showDeparture && departure?.forward == false)', self.host)
        self.assertIn('val interruptingReturn = returning', self.host)
        self.assertIn('if (claimed || interruptingReturn) PointerEventPass.Initial else PointerEventPass.Final', self.host)
        claim = self.host.index('claimed = true')
        interrupt = self.host.index('claimSwipe()', claim)
        update = self.host.index('state.update(owner, distance / gestureWidth', interrupt)
        self.assertLess(claim, interrupt)
        self.assertLess(interrupt, update)
        self.assertIn('val claimSwipe by rememberUpdatedState {', self.host)
        self.assertIn('departing = false\n        state.transitionBlocked = false', self.host)
        self.assertIn('if (consumedMovement) break', self.host)
        self.assertIn('if (owner != null && this.owner !== owner) return', self.host)

    def test_frozen_clock_regressions_attempt_second_swipe_before_settling(self):
        gestures = (ANDROID / 'PageBackGesturesActivityTest.kt').read_text()
        retry = gestures.split('fun testNewSwipeInterruptsCancelledReturnWithoutWaitingForSpring()', 1)[1].split('    fun ', 1)[0]
        self.assertIn('ui.mainClock.autoAdvance = false', retry)
        self.assertIn('cancel()', retry)
        self.assertIn('resetting.left > initial.left', retry)
        self.assertIn('assertEquals(1, backCount)', retry)
        self.assertNotIn('advanceTimeBy(1000)', retry)
        stack = (ANDROID / 'PageBackStackActivityTest.kt').read_text()
        repeat = stack.split('private fun assertRapidNestedSwipeReturns(', 1)[1].split('    fun testRapidNestedSwipeReturnsBeforeDepartureFinishes()', 1)[0]
        self.assertIn('ui.mainClock.autoAdvance = false', repeat)
        self.assertIn('for (expectedPage in listOf<Int?>(1, null))', repeat)
        self.assertIn('assertTrue("first departure is still running"', repeat)
        self.assertIn('assertTrue("second down hits outgoing overlay"', repeat)
        self.assertIn('assertEquals(previousCount + 1, backCount)', repeat)
        self.assertNotIn('advanceTimeBy(1000)', repeat)
        self.assertIn('fun testRapidNestedSwipeReturnsBeforeDepartureFinishes()', stack)
        self.assertIn('fun testRapidNestedSwipeReturnsInRtlBeforeDepartureFinishes()', stack)


if __name__ == '__main__':
    unittest.main()
