package app.daiyu.android.ui.screens.settings

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.components.subagent.ConversationAgentSwitch
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs

class SettingsSwitchAccentTest : WiredActivityTestCase() {
    private fun matchingPixels(image: ImageBitmap): Int {
        val pixels = image.toPixelMap()
        val expected = Color(0xFF82728F)
        var count = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val actual = pixels[x, y]
            if (maxOf(abs(actual.red - expected.red), abs(actual.green - expected.green),
                    abs(actual.blue - expected.blue)) < .01f) count++
        }
        return count
    }

    fun testSettingsCheckedTrackMatchesCollaborationInBothThemesAndOffStateDoesNotUseIt() {
        for (dark in listOf(false, true)) for (checked in listOf(false, true)) {
            var calls = 0
            mainThread {
                activity.setContent {
                    AgentAppTheme(AppearanceSettings(themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT), false) {
                        Column(Modifier.requiredWidth(320.dp).background(MiuixTheme.colorScheme.background)) {
                            SettingsCard {
                                SettingsSwitchRow("强调色开关", checked, tag = "accent-settings-switch") {
                                    assertEquals(!checked, it)
                                    calls++
                                }
                            }
                            Box(Modifier.testTag("accent-collaboration-switch")) {
                                ConversationAgentSwitch(checked = checked, enabled = true)
                            }
                        }
                    }
                }
            }
            val settings = node("accent-settings-switch").assertIsEnabled()
            if (checked) settings.assertIsOn() else settings.assertIsOff()
            val settingsMatches = matchingPixels(captureScreenNode(settings))
            val collaborationMatches = matchingPixels(captureScreenNode(node("accent-collaboration-switch")))
            if (checked) {
                assertTrue("settings track; dark=$dark", settingsMatches > 0)
                assertTrue("collaboration track; dark=$dark", collaborationMatches > 0)
            } else {
                assertEquals(0, settingsMatches)
                assertEquals(0, collaborationMatches)
            }
            settings.performClick()
            ui.runOnIdle { assertEquals(1, calls) }
        }
        assertEquals(0, fixture.opens.get())
    }
}
