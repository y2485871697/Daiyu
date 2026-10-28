package app.daiyu.android.ui.screens.settings

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.ui.app.AgentAppTheme

private class SettingsTestMotionScale : MotionDurationScale {
    var factor = mutableStateOf(1f)
    override val scaleFactor: Float get() = factor.value
}

class SettingsDisclosureMotionTest private constructor(
    private val motion: SettingsTestMotionScale,
) : WiredActivityTestCase(motion) {
    constructor() : this(SettingsTestMotionScale())

    private val fieldFocus = FocusRequester()

    private fun mount(scale: Float = 1f): MutableState<Boolean> {
        val expanded = mutableStateOf(false)
        val text = mutableStateOf("")
        mainThread {
            motion.factor.value = scale
            activity.setContent {
                key(expanded) {
                    AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                        Column(Modifier.width(300.dp)) {
                            SettingsCard(Modifier.testTag("motion-card")) {
                                SettingsRow("展开设置", tag = "motion-header",
                                    modifier = Modifier.semantics {
                                        stateDescription = if (expanded.value) "已展开" else "已收起"
                                    }, onClick = { expanded.value = !expanded.value })
                                SettingsExpandableContent(expanded.value) {
                                    Box(Modifier.focusRequester(fieldFocus)) {
                                        SettingsTextField("字段", text.value, { text.value = it }, tag = "motion-field")
                                    }
                                    SettingsSwitchRow("开关", false, tag = "motion-detail") {}
                                }
                            }
                            Text("后续设置", Modifier.testTag("motion-after"))
                        }
                    }
                }
            }
        }
        ui.mainClock.advanceTimeBy(32)
        return expanded
    }

    private fun height() = ui.onNodeWithTag("motion-card").getUnclippedBoundsInRoot().height.value
    private fun afterTop() = ui.onNodeWithTag("motion-after").getUnclippedBoundsInRoot().top.value
    private fun toggle() = ui.onNodeWithTag("motion-header").performClick()

    fun testBothDirectionsMoveCardAndFollowingContent() {
        ui.mainClock.autoAdvance = false
        try {
            mount()
            val collapsed = height()
            val startTop = afterTop()
            toggle()
            ui.mainClock.advanceTimeBy(80)
            val opening = height()
            val openingTop = afterTop()
            ui.mainClock.advanceTimeBy(300)
            val expanded = height()
            val endTop = afterTop()
            assertTrue(opening > collapsed + 1f && opening < expanded - 1f)
            assertTrue(openingTop > startTop + 1f && openingTop < endTop - 1f)
            ui.onNodeWithTag("motion-field").assertIsDisplayed()
            toggle()
            ui.mainClock.advanceTimeBy(80)
            assertTrue(height() > collapsed + 1f && height() < expanded - 1f)
            assertTrue(afterTop() > startTop + 1f && afterTop() < endTop - 1f)
            ui.mainClock.advanceTimeBy(300)
            assertEquals(collapsed, height(), 1f)
            assertEquals(startTop, afterTop(), 1f)
            ui.onNodeWithTag("motion-field").assertDoesNotExist()
        } finally { ui.mainClock.autoAdvance = true }
    }

    fun testRapidReversalFollowsTheLatestTarget() {
        ui.mainClock.autoAdvance = false
        try {
            val expanded = mount()
            val collapsed = height()
            toggle()
            ui.mainClock.advanceTimeBy(96)
            val opening = height()
            assertTrue("opening must be in flight", opening > collapsed)
            mainThread { expanded.value = false }
            val closingStartTime = ui.mainClock.currentTime
            val closingStart = height()
            val closingTop = afterTop()
            assertEquals(closingStartTime, ui.mainClock.currentTime)
            ui.mainClock.advanceTimeBy(48)
            val reversing = height()
            assertTrue("closing must move down: start=$closingStart actual=$reversing collapsed=$collapsed",
                reversing < closingStart)
            assertTrue("closing must remain in flight", reversing > collapsed)
            assertTrue("following row must move up while closing", afterTop() < closingTop)
            mainThread { expanded.value = true }
            val reopeningStartTime = ui.mainClock.currentTime
            val reopeningStart = height()
            val reopeningTop = afterTop()
            assertEquals(reopeningStartTime, ui.mainClock.currentTime)
            assertTrue("reverse before fully collapsed", reopeningStart > collapsed)
            ui.mainClock.advanceTimeBy(48)
            assertTrue("reopening must move up: start=$reopeningStart actual=${height()}", height() > reopeningStart)
            assertTrue("following row must move down while reopening", afterTop() > reopeningTop)
            ui.mainClock.advanceTimeBy(400)
            ui.onAllNodesWithTag("motion-field").assertCountEquals(1)
            toggle()
            ui.mainClock.advanceTimeBy(400)
            assertEquals(collapsed, height(), 1f)
            ui.onNodeWithTag("motion-field").assertDoesNotExist()
        } finally { ui.mainClock.autoAdvance = true }
    }

    fun testZeroAndDoubleDurationScale() {
        ui.mainClock.autoAdvance = false
        try {
            mount(1f)
            toggle()
            ui.mainClock.advanceTimeBy(96)
            val ordinaryMidpoint = height()
            ui.mainClock.advanceTimeBy(400)
            val fullHeight = height()
            mount(2f)
            toggle()
            ui.mainClock.advanceTimeBy(96)
            assertTrue(height() < ordinaryMidpoint - 1f)
            ui.mainClock.advanceTimeBy(600)
            assertEquals(fullHeight, height(), 1f)
            mount(0f)
            val collapsed = height()
            toggle()
            ui.mainClock.advanceTimeBy(32)
            assertEquals(fullHeight, height(), 1f)
            toggle()
            ui.mainClock.advanceTimeBy(32)
            assertEquals(collapsed, height(), 1f)
            ui.onNodeWithTag("motion-field").assertDoesNotExist()
        } finally { ui.mainClock.autoAdvance = true }
    }

    fun testCollapsingFieldsLoseFocusAndActions() {
        ui.mainClock.autoAdvance = false
        try {
            mount()
            toggle()
            ui.mainClock.advanceTimeBy(400)
            ui.onNodeWithTag("motion-field").performClick().assertIsFocused()
            toggle()
            ui.mainClock.advanceTimeBy(48)
            ui.onNodeWithTag("motion-field").assertDoesNotExist()
            ui.onNodeWithTag("motion-field", useUnmergedTree = true).assertIsNotFocused()
            mainThread { assertFalse(fieldFocus.requestFocus()) }
            ui.onNodeWithTag("motion-field", useUnmergedTree = true).assertIsNotFocused()
            ui.onNodeWithTag("motion-detail").assertDoesNotExist()
            ui.mainClock.advanceTimeBy(400)
            ui.onNodeWithTag("motion-field", useUnmergedTree = true).assertDoesNotExist()
        } finally { ui.mainClock.autoAdvance = true }
    }
}
