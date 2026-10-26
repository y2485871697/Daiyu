package app.daiyu.android.ui.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootExitConfirmationTest {
    @Test fun firstBackNeverExitsIncludingAtClockZero() {
        assertFalse(RootExitConfirmation().shouldExit(0))
        assertFalse(RootExitConfirmation().shouldExit(50_000))
    }

    @Test fun secondBackWithinTwoSecondsExits() {
        val confirmation = RootExitConfirmation()
        assertFalse(confirmation.shouldExit(1_000))
        assertTrue(confirmation.shouldExit(2_999))
    }

    @Test fun exactTwoSecondBoundaryStillConfirms() {
        val confirmation = RootExitConfirmation()
        assertFalse(confirmation.shouldExit(1_000))
        assertTrue(confirmation.shouldExit(3_000))
    }

    @Test fun expiredBackStartsANewConfirmationWindow() {
        val confirmation = RootExitConfirmation()
        assertFalse(confirmation.shouldExit(1_000))
        assertFalse(confirmation.shouldExit(3_001))
        assertTrue(confirmation.shouldExit(4_000))
    }

    @Test fun navigationOrBackgroundResetRequiresTwoNewBacks() {
        val confirmation = RootExitConfirmation()
        assertFalse(confirmation.shouldExit(1_000))
        confirmation.reset()
        assertFalse(confirmation.shouldExit(1_100))
        assertTrue(confirmation.shouldExit(1_200))
    }

    @Test fun confirmedExitConsumesThePendingPress() {
        val confirmation = RootExitConfirmation()
        assertFalse(confirmation.shouldExit(1_000))
        assertTrue(confirmation.shouldExit(1_100))
        assertFalse(confirmation.shouldExit(1_200))
    }

    @Test fun backwardsClockDoesNotConfirm() {
        val confirmation = RootExitConfirmation()
        assertFalse(confirmation.shouldExit(1_000))
        assertFalse(confirmation.shouldExit(999))
        assertTrue(confirmation.shouldExit(1_001))
    }
}
