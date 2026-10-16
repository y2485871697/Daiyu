package app.daiyu.android

import androidx.compose.ui.test.*

/** The real activity, controlled top bar, controller preference and recreation share one value. */
class InteractiveModeActivityTest : WiredActivityTestCase() {
    fun testTopBarSavesExplicitChoiceAndRestoresAfterActivityRecreation() {
        mainThread { fixture.controller.interactiveMode.setEnabled(false) }
        node("interactive-mode-toggle").assertIsDisplayed().assertIsOff().performClick().assertIsOn()
        mainThread { assertTrue(fixture.controller.interactiveMode.snapshot().enabled) }
        activity = recreateActivity(activity)
        node("interactive-mode-toggle").assertIsDisplayed().assertIsOn()
        node("interactive-mode-toggle").performClick().assertIsOff()
        mainThread { assertFalse(fixture.controller.interactiveMode.snapshot().enabled) }
    }

    fun testExternalPreferenceChangeRefreshesLiveRootAfterRecreation() {
        activity = recreateActivity(activity)
        mainThread { fixture.controller.interactiveMode.setEnabled(true) }
        node("interactive-mode-toggle").assertIsOn()
        mainThread { fixture.controller.interactiveMode.setEnabled(false) }
        node("interactive-mode-toggle").assertIsOff()
    }
}
