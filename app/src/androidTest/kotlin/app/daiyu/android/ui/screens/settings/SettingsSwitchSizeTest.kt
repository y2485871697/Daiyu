package app.daiyu.android.ui.screens.settings

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.app.DaiyuPalette
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs

class SettingsSwitchSizeTest : WiredActivityTestCase() {
    private var pixelsPerDp = 1f

    private fun assertCompactTrack(image: ImageBitmap) {
        val pixels = image.toPixelMap()
        val expected = Color(0xFF82728F)
        var left = pixels.width
        var right = -1
        var top = pixels.height
        var bottom = -1
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val actual = pixels[x, y]
            if (maxOf(abs(actual.red - expected.red), abs(actual.green - expected.green),
                    abs(actual.blue - expected.blue)) < .015f) {
                left = minOf(left, x)
                right = maxOf(right, x)
                top = minOf(top, y)
                bottom = maxOf(bottom, y)
            }
        }
        assertTrue("checked track pixels must be present", right >= left && bottom >= top)
        assertEquals("visible track width", 36.4f * pixelsPerDp, (right - left + 1).toFloat(), 2f)
        assertEquals("visible track height", 22.4f * pixelsPerDp, (bottom - top + 1).toFloat(), 2f)
    }

    fun testIndependentSwitchKeeps48DpTargetAndSingleCallbackOutsideCompactTrack() {
        for (dark in listOf(false, true)) for (scale in listOf(1f, .8f))
            for (direction in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
                val checked = mutableStateOf(true)
                val enabled = mutableStateOf(true)
                var calls = 0
                mainThread {
                    activity.setContent {
                        val platform = LocalDensity.current
                        CompositionLocalProvider(LocalDensity provides Density(platform.density, 1.3f),
                            LocalLayoutDirection provides direction) {
                            AgentAppTheme(AppearanceSettings(interfaceScale = scale,
                                themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT), true) {
                                pixelsPerDp = LocalDensity.current.density
                                SettingsSwitch(checked.value, onCheckedChange = { checked.value = it; calls++ },
                                    enabled = enabled.value, modifier = Modifier.testTag("compact-interactive-switch"),
                                    colors = SwitchDefaults.colors(checkedTrackColor = DaiyuPalette.switchTrack,
                                        checkedBorderColor = DaiyuPalette.switchTrack, checkedThumbColor = Color.White))
                            }
                        }
                    }
                }
                val target = node("compact-interactive-switch").assertIsOn().assertIsEnabled()
                val bounds = target.fetchSemanticsNode().boundsInRoot
                assertEquals(48f * pixelsPerDp, bounds.width, 1f)
                assertEquals(48f * pixelsPerDp, bounds.height, 1f)
                assertCompactTrack(captureScreenNode(target))
                ui.onAllNodes(hasClickAction() and (hasTestTag("compact-interactive-switch") or
                    hasAnyAncestor(hasTestTag("compact-interactive-switch"))), useUnmergedTree = true).assertCountEquals(1)
                // Above the painted track, but within the unscaled switch target.
                target.performTouchInput { click(Offset(centerX, 2f * pixelsPerDp)) }
                target.assertIsOff()
                ui.runOnIdle { assertEquals(1, calls) }
                mainThread { enabled.value = false }
                target.assertIsNotEnabled().performTouchInput { click(Offset(centerX, 2f * pixelsPerDp)) }
                ui.runOnIdle { assertEquals(1, calls); assertFalse(checked.value) }
                mainThread { enabled.value = true }
                target.performClick().assertIsOn()
                ui.runOnIdle { assertEquals(2, calls) }
            }
        assertEquals(0, fixture.opens.get())
    }

    fun testRowUsesCompactOccupancyWithoutGrowingAndPassiveSwitchHasNoAction() {
        for (dark in listOf(false, true)) for (scale in listOf(1f, .8f)) {
            var calls = 0
            mainThread {
                activity.setContent {
                    val platform = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(platform.density, 1.3f)) {
                        AgentAppTheme(AppearanceSettings(interfaceScale = scale,
                            themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT), true) {
                            pixelsPerDp = LocalDensity.current.density
                            Column(Modifier.requiredWidth(280.dp).background(MiuixTheme.colorScheme.background)) {
                                SettingsCard {
                                    SettingsSwitchRow("开关", true, tag = "compact-row") { calls++ }
                                }
                                SettingsSwitch(true, onCheckedChange = null,
                                    modifier = Modifier.testTag("compact-passive-switch"))
                            }
                        }
                    }
                }
            }
            val row = node("compact-row").assertIsEnabled().assertIsOn()
            assertEquals(64f * pixelsPerDp, row.fetchSemanticsNode().boundsInRoot.height, 1f)
            assertCompactTrack(captureScreenNode(row))
            row.performClick()
            ui.runOnIdle { assertEquals(1, calls) }
            val passive = node("compact-passive-switch").assertHasNoClickAction()
            val bounds = passive.fetchSemanticsNode().boundsInRoot
            assertEquals(36.4f * pixelsPerDp, bounds.width, 1f)
            assertEquals(22.4f * pixelsPerDp, bounds.height, 1f)
            ui.onAllNodes(hasAnyAncestor(hasTestTag("compact-passive-switch")) and hasClickAction(),
                useUnmergedTree = true).assertCountEquals(0)
            passive.performTouchInput { click() }
            ui.runOnIdle { assertEquals(1, calls) }
        }
        assertEquals(0, fixture.opens.get())
    }
}
