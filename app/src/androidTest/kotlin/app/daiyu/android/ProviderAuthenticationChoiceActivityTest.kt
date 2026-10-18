package app.daiyu.android

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*

class ProviderAuthenticationChoiceActivityTest : WiredActivityTestCase() {
    fun testFutureOAuthIsInertAndApiKeyStillRegistersThroughTheExistingFlow() {
        openProviderManagement()
        val before = fixture.controller.providers.snapshot()
        reveal("provider-add", "提供商").performClick()
        awaitUniqueTag("prompt-choice:1")
        node("prompt-choice:1").assertIsEnabled().performClick()
        node("prompt-choice:0").assertExists()
        assertEquals(before, fixture.controller.providers.snapshot())
        assertEquals(0, fixture.opens.get())
        node("prompt-choice:0").performClick()
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

    fun testPausedAuthenticationChoiceCannotReopenTheNamePrompt() {
        openProviderManagement()
        val before = fixture.controller.providers.snapshot()
        reveal("provider-add", "提供商").performClick()
        awaitUniqueTag("prompt-choice:0")
        val choose = checkNotNull(node("prompt-choice:0").fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        realPauseResume(activity, atPause = { choose() })
        mainThread { choose() }
        node("prompt-input").assertDoesNotExist()
        node("prompt-choice:0").assertDoesNotExist()
        assertEquals(before, fixture.controller.providers.snapshot())
        assertEquals(0, fixture.opens.get())
    }
}
