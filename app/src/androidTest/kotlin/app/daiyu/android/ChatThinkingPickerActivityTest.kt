package app.daiyu.android

import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import app.daiyu.android.delegation.ReasoningEffort

class ChatThinkingPickerActivityTest : WiredActivityTestCase() {
    private fun openPicker() {
        node("chat-reasoning").performClick()
        ui.onNodeWithText("调整思考深度").assertIsDisplayed()
        ui.onNodeWithText("默认", useUnmergedTree = true).assertDoesNotExist()
        for (text in listOf("下一次主请求生效", "Responses", "max_tokens", "隐藏内部推理")) {
            ui.onNodeWithText(text, substring = true, useUnmergedTree = true).assertDoesNotExist()
        }
    }

    private fun closePicker() {
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        ui.waitForIdle()
        ui.onNodeWithText("调整思考深度").assertDoesNotExist()
    }

    fun testLegacyDefaultOpensUnselectedAndCancelDoesNotWrite() {
        setDraft("keep thinking draft")
        fixture.controller.reasoningPreference.set(null, ReasoningEffort.DEFAULT)
        val before = fixture.application.getSharedPreferences("conversation-ui", 0).all.toMap()
        openPicker()
        node("chat-thinking-value").assertTextEquals("选择思考深度")
        node("thinking-depth-slider").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ProgressBarRangeInfo))
        closePicker()
        assertEquals(before, fixture.application.getSharedPreferences("conversation-ui", 0).all.toMap())
        assertEquals(ReasoningEffort.DEFAULT, fixture.controller.reasoningPreference.snapshot(null))
        assertDraft("keep thinking draft")
        assertEquals(0, fixture.opens.get())
    }

    fun testTouchSelectionPersistsBeforeReleaseAndKeepsPickerOpen() {
        openPicker()
        val slider = node("thinking-depth-slider")
        slider.performTouchInput { down(center) }
        try {
            node("chat-thinking-value").assertTextEquals("中")
            assertEquals(ReasoningEffort.MEDIUM, fixture.controller.reasoningPreference.snapshot(null))
        } finally {
            slider.performTouchInput { up() }
        }
        ui.onNodeWithText("调整思考深度").assertIsDisplayed()
        closePicker()
        assertEquals(0, fixture.opens.get())
    }

    fun testSevenExplicitStopsPersistWithoutClosingOrSending() {
        setDraft("seven stops draft")
        openPicker()
        val choices = listOf(ReasoningEffort.OFF, ReasoningEffort.MINIMAL, ReasoningEffort.LOW,
            ReasoningEffort.MEDIUM, ReasoningEffort.HIGH, ReasoningEffort.XHIGH, ReasoningEffort.MAX)
        choices.forEachIndexed { index, effort ->
            node("thinking-depth-slider").performSemanticsAction(SemanticsActions.SetProgress) {
                assertTrue(it(index.toFloat()))
            }
            node("chat-thinking-value").assertTextEquals(effort.displayName)
            val range = node("thinking-depth-slider").fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
            assertEquals(0f..6f, range.range)
            assertEquals(5, range.steps)
            assertEquals(effort, fixture.controller.reasoningPreference.snapshot(null))
        }
        closePicker()
        openPicker()
        node("chat-thinking-value").assertTextEquals("极高")
        closePicker()
        assertDraft("seven stops draft")
        assertEquals(0, fixture.opens.get())
    }
}
