package app.daiyu.android

import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import app.daiyu.android.delegation.ReasoningEffort
import app.daiyu.android.model.GptSpeedMode

class ChatGptSpeedActivityTest : WiredActivityTestCase() {
    override fun createFixture() = super.createFixture().also {
        it.settings.save(it.settings.load().copy(model = "gpt-5"))
    }

    private fun awaitSpeed(mode: GptSpeedMode) {
        val label = when (mode) {
            GptSpeedMode.NORMAL -> "标准速度"
            GptSpeedMode.FAST -> "快速速度"
            GptSpeedMode.ULTRA_FAST -> "极速速度"
        }
        ui.waitUntil(5_000) {
            ui.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, label),
                useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(mode, fixture.settings.load().requestOptions.speedMode)
    }

    fun testLongPressAndPopupClickSharePersistedCycleWithoutChangingDepthOrDraft() {
        setDraft("speed keeps draft")
        fixture.controller.reasoningPreference.set(null, ReasoningEffort.HIGH)
        ui.waitUntil(5_000) {
            ui.onAllNodesWithTag("chat-reasoning").fetchSemanticsNodes().any {
                it.config.contains(androidx.compose.ui.semantics.SemanticsActions.OnLongClick)
            }
        }
        node("chat-reasoning").performTouchInput { longClick() }
        awaitSpeed(GptSpeedMode.FAST)
        ui.onNodeWithText("调整思考深度").assertDoesNotExist()
        node("chat-reasoning").performClick()
        node("chat-thinking-speed").performClick()
        awaitSpeed(GptSpeedMode.ULTRA_FAST)
        node("chat-thinking-value").assertTextEquals("高")
        node("chat-thinking-speed").performClick()
        awaitSpeed(GptSpeedMode.NORMAL)
        assertEquals(ReasoningEffort.HIGH, fixture.controller.reasoningPreference.snapshot(null))
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        assertDraft("speed keeps draft")
        assertEquals(0, fixture.opens.get())
    }

    fun testPopupReferenceGeometryRetainsSliderAndReopeningDoesNotWriteSpeed() {
        node("chat-reasoning").performClick()
        node("chat-thinking-speed").assertWidthIsEqualTo(36.dp).assertHeightIsEqualTo(36.dp)
        node("thinking-depth-slider").assertHeightIsEqualTo(28.dp)
        awaitDialogEnter(node("chat-thinking-title"))
        val title = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        node("chat-thinking-title").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) {
            assertTrue(it(title))
        }
        assertEquals(14f, title.single().layoutInput.style.fontSize.value, .001f)
        assertEquals(22f, title.single().layoutInput.style.lineHeight.value, .001f)
        val layout = title.single()
        assertFalse("title size=${layout.size} constraints=${layout.layoutInput.constraints} " +
            "lines=${layout.lineCount} widthOverflow=${layout.didOverflowWidth} heightOverflow=${layout.didOverflowHeight} " +
            "bounds=${node("chat-thinking-title").fetchSemanticsNode().boundsInRoot}", layout.hasVisualOverflow)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        node("chat-reasoning").performClick()
        assertNull(fixture.settings.load().requestOptions.speedMode)
        assertEquals(0, fixture.opens.get())
    }
}
