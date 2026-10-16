package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.screens.chat.conversation.InteractiveModeToggle

/** Controlled component contract only; the existing fixture owns all chat and question lifetimes. */
class InteractiveModeToggleTest : WiredActivityTestCase() {
    private fun show(enabled: Boolean, checked: Boolean, changed: (Boolean) -> Unit) {
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    InteractiveModeToggle(enabled, checked, changed)
                }
            }
        }
        ui.waitForIdle()
    }

    fun testRenderDoesNotEmitAndClicksOnlyRequestChangeUntilOwnerUpdatesChecked() {
        val events = mutableListOf<Boolean>()
        show(enabled = true, checked = false, changed = events::add)
        val toggle = node("interactive-mode-toggle")
        toggle.assertIsDisplayed().assertIsEnabled().assertIsOff()
            .assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
            .assertContentDescriptionEquals("交互模式")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已关闭"))
        assertTrue(events.isEmpty())
        toggle.performClick().assertIsOff()
        toggle.performClick().assertIsOff()
        mainThread { assertEquals(listOf(true, true), events) }

        show(enabled = true, checked = true, changed = events::add)
        toggle.assertIsOn()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已开启"))
        mainThread { assertEquals(listOf(true, true), events) }
        toggle.performClick().assertIsOn()
        mainThread { assertEquals(listOf(true, true, false), events) }
    }

    fun testDisabledControlKeepsCheckedValueAndNeverDispatchesTouch() {
        val events = mutableListOf<Boolean>()
        for (checked in listOf(false, true)) {
            show(enabled = false, checked = checked, changed = events::add)
            val toggle = node("interactive-mode-toggle").assertIsDisplayed().assertIsNotEnabled()
            if (checked) toggle.assertIsOn() else toggle.assertIsOff()
            toggle.performTouchInput { click(center) }
            ui.waitForIdle()
            mainThread { assertTrue(events.isEmpty()) }
        }
    }

    fun testUnrelatedRecompositionDoesNotDispatchAndOwnerCanApplyRequestedValue() {
        val checked = mutableStateOf(false)
        val unrelated = mutableStateOf(0)
        val events = mutableListOf<Boolean>()
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    Row {
                        InteractiveModeToggle(enabled = true, checked = checked.value,
                            onCheckedChange = { events += it; checked.value = it })
                        androidx.compose.material3.Text(unrelated.value.toString())
                    }
                }
            }
        }
        ui.waitForIdle()
        mainThread { unrelated.value++ }
        ui.waitForIdle()
        mainThread { assertTrue(events.isEmpty()) }
        node("interactive-mode-toggle").performClick().assertIsOn()
        mainThread { assertEquals(listOf(true), events); unrelated.value++ }
        ui.waitForIdle()
        mainThread { assertEquals(listOf(true), events) }
        node("interactive-mode-toggle").performClick().assertIsOff()
        mainThread { assertEquals(listOf(true, false), events) }
    }
}
