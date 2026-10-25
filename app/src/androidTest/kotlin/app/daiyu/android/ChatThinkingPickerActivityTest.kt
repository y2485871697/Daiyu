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

    fun testLegacyDefaultOpensWithOffSelectedAndCancelDoesNotWrite() {
        setDraft("keep thinking draft")
        fixture.application.getSharedPreferences("conversation-ui", 0).edit().putString("reasoning:draft", "default").commit()
        val before = fixture.application.getSharedPreferences("conversation-ui", 0).all.toMap()
        openPicker()
        node("chat-thinking-value").assertTextEquals("关闭")
        val range = node("thinking-depth-slider").fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(0f, range.current)
        assertEquals(0f..6f, range.range)
        node("thinking-depth-slider").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
        closePicker()
        assertEquals(before, fixture.application.getSharedPreferences("conversation-ui", 0).all.toMap())
        assertEquals(ReasoningEffort.OFF, fixture.controller.reasoningPreference.snapshot(null))
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
                assertEquals(index != 0, it(index.toFloat()))
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
    fun testMissingPreferenceIsOffAndNewChatInheritsCurrentWithoutChangingOtherConversations() {
        val preferences = fixture.application.getSharedPreferences("conversation-ui", 0)
        preferences.edit().remove("reasoning:draft").commit()
        val before = preferences.all.toMap()
        openPicker()
        node("chat-thinking-value").assertTextEquals("关闭")
        closePicker()
        assertEquals(before, preferences.all.toMap())
        val source = fixture.database.create("default")
        fixture.controller.reasoningPreference.set(source.id.value, ReasoningEffort.HIGH)
        mainThread { activity.select(source) }
        awaitControl("source selected") { !fixture.controller.dataBusy }
        mainThread { activity.select(null) }
        awaitControl("new draft selected") { !fixture.controller.dataBusy }
        openPicker()
        node("chat-thinking-value").assertTextEquals("高")
        closePicker()
        fixture.controller.reasoningPreference.set(null, ReasoningEffort.LOW)
        mainThread { activity.select(source) }
        awaitControl("source restored") { !fixture.controller.dataBusy }
        openPicker()
        node("chat-thinking-value").assertTextEquals("高")
        closePicker()
        assertEquals(0, fixture.opens.get())
    }

}
