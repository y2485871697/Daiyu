package app.daiyu.android.ui.screens.settings

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.app.DaiyuPalette
import app.daiyu.android.ui.app.daiyuSwitchColors
import app.daiyu.android.ui.haptics.material.Switch
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs
import kotlin.math.roundToInt

class SettingsSwitchOffStateTest : WiredActivityTestCase() {
    private fun verifyOff(compact: Boolean) {
        for (dark in listOf(false, true)) for (enabled in listOf(false, true))
            for (scale in listOf(1f, .8f)) for (direction in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
                var density = 1f
                var surface = Color.Unspecified
                var thumb = Color.Unspecified
                var calls = 0
                mainThread {
                    activity.setContent {
                        AgentAppTheme(AppearanceSettings(interfaceScale = scale,
                            themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT), true) {
                            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                                density = LocalDensity.current.density
                                surface = MiuixTheme.colorScheme.surface
                                val outline = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                thumb = if (enabled) outline else outline.copy(alpha = .38f).compositeOver(surface)
                                Column {
                                    for (interactive in listOf(false, true)) {
                                        Box(Modifier.requiredSize(80.dp, 48.dp).background(surface)
                                            .testTag("off-frame:$interactive"), contentAlignment = Alignment.Center) {
                                            val callback: ((Boolean) -> Unit)? = if (interactive) ({ calls++ }) else null
                                            val modifier = Modifier.testTag("off-control:$interactive")
                                            if (compact) {
                                                if (interactive) SettingsSwitch(false, callback, modifier, enabled,
                                                    daiyuSwitchColors(SwitchDefaults.colors(
                                                        checkedTrackColor = DaiyuPalette.switchTrack,
                                                        checkedBorderColor = DaiyuPalette.switchTrack)))
                                                else SettingsSwitch(false, null, modifier, enabled)
                                            } else {
                                                if (interactive) Switch(false, callback, modifier, enabled = enabled,
                                                    colors = daiyuSwitchColors(SwitchDefaults.colors(
                                                        checkedTrackColor = DaiyuPalette.switchTrack,
                                                        checkedBorderColor = DaiyuPalette.switchTrack)))
                                                else Switch(false, null, modifier, enabled = enabled)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                for (interactive in listOf(false, true)) {
                    val diagnostic = "compact=$compact dark=$dark enabled=$enabled scale=$scale direction=$direction interactive=$interactive"
                    val pixels = captureScreenNode(node("off-frame:$interactive")).toPixelMap()
                    val factor = density * if (compact) .7f else 1f
                    val sign = if (direction == LayoutDirection.Ltr) -1 else 1
                    val thumbX = (pixels.width / 2f + sign * 10f * factor).roundToInt()
                    val trackX = (pixels.width / 2f - sign * 10f * factor).roundToInt()
                    val y = pixels.height / 2
                    fun distance(actual: Color, expected: Color) = maxOf(abs(actual.red - expected.red),
                        abs(actual.green - expected.green), abs(actual.blue - expected.blue))
                    assertTrue("thumb interior: $diagnostic", distance(pixels[thumbX, y], thumb) < .035f)
                    assertTrue("track interior: $diagnostic", distance(pixels[trackX, y], surface) < .035f)
                    assertTrue("thumb differs from track: $diagnostic", distance(pixels[thumbX, y], pixels[trackX, y]) > .08f)
                }
                node("off-control:true").assertIsOff()
                if (!enabled) {
                    node("off-control:true").assertIsNotEnabled().performTouchInput { click(center) }
                    ui.runOnIdle { assertEquals(0, calls) }
                } else {
                    node("off-control:true").performTouchInput { click(center) }
                    ui.runOnIdle { assertEquals(1, calls) }
                }
                assertEquals(0, fixture.opens.get())
            }
    }

    fun testCompactOffThumbStaysVisibleForPassiveAndInteractiveSettings() = verifyOff(compact = true)

    fun testDefaultAndAccentMaterialOffThumbStayVisible() = verifyOff(compact = false)
}
