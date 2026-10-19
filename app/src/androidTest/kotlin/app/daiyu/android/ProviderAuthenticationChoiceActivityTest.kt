package app.daiyu.android

import androidx.compose.ui.test.*

class ProviderAuthenticationChoiceActivityTest : WiredActivityTestCase() {
    override val engineeringUi = true
    fun testAddOpensNamePromptWithoutUnsupportedAuthenticationOptions() {
        openProviderManagement()
        val before = fixture.controller.providers.snapshot()
        reveal("provider-add", "提供商").performClick()
        node("prompt-choice:0").assertDoesNotExist()
        node("prompt-choice:1").assertDoesNotExist()
        assertEquals(before, fixture.controller.providers.snapshot())
        assertEquals(0, fixture.opens.get())
        awaitUniqueTag("prompt-input")
        node("prompt-input").performTextReplacement("API Key fixture")
        ui.onNodeWithText("保存").performClick()
        awaitControl("provider registered") {
            fixture.controller.providers.snapshot().providers.any { it.name == "API Key fixture" }
        }
        val after = fixture.controller.providers.snapshot()
        assertEquals(before.providers.size + 1, after.providers.size)
        assertEquals(before.activeSlotId, after.activeSlotId)
        assertEquals(0, fixture.opens.get())
    }

    fun testPausedAddCannotRegisterOrRestoreTheNamePrompt() {
        openProviderManagement()
        val before = fixture.controller.providers.snapshot()
        reveal("provider-add", "提供商").performClick()
        awaitUniqueTag("prompt-input")
        node("prompt-input").performTextReplacement("must not register")
        realPauseResume(activity, atPause = { })
        node("prompt-input").assertDoesNotExist()
        node("prompt-choice:0").assertDoesNotExist()
        assertEquals(before, fixture.controller.providers.snapshot())
        assertEquals(0, fixture.opens.get())
    }
}
