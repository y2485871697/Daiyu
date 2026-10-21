package app.daiyu.android

import androidx.compose.ui.test.*

class InteractiveModeActivityTest : WiredActivityTestCase() {
    private fun openInteractiveEntry(): SemanticsNodeInteraction {
        ui.onNodeWithContentDescription("更多").performClick()
        return node("chat-menu:INTERACTIVE_MODE").scrollToVisible().assertIsDisplayed().assertIsEnabled()
    }
    fun testTopBarSavesExplicitChoiceAndRestoresAfterActivityRecreation() {
        mainThread { fixture.controller.interactiveMode.setEnabled(false) }
        openInteractiveEntry().assertIsOff().performClick()
        node("chat-menu:INTERACTIVE_MODE").assertIsOn()
        mainThread { assertTrue(fixture.controller.interactiveMode.snapshot().enabled) }
        activity = recreateActivity(activity)
        openInteractiveEntry().assertIsOn().performClick()
        node("chat-menu:INTERACTIVE_MODE").assertIsOff()
        mainThread { assertFalse(fixture.controller.interactiveMode.snapshot().enabled) }
        assertEquals(0, fixture.opens.get())
    }
    fun testExternalPreferenceChangeRefreshesLiveRootAfterRecreation() {
        activity = recreateActivity(activity)
        openInteractiveEntry()
        mainThread { fixture.controller.interactiveMode.setEnabled(true) }
        node("chat-menu:INTERACTIVE_MODE").assertIsOn()
        mainThread { fixture.controller.interactiveMode.setEnabled(false) }
        node("chat-menu:INTERACTIVE_MODE").assertIsOff()
        assertEquals(0, fixture.opens.get())
    }
}
