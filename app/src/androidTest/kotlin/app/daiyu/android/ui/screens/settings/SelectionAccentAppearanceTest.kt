package app.daiyu.android.ui.screens.settings

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.app.DaiyuPalette
import app.daiyu.android.ui.haptics.material.Checkbox
import app.daiyu.android.ui.haptics.material.RadioButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs

class SelectionAccentAppearanceTest : WiredActivityTestCase() {
    private fun matchingPixels(image: ImageBitmap, expected: Color): Int {
        val pixels = image.toPixelMap()
        var count = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val actual = pixels[x, y]
            if (maxOf(abs(actual.red - expected.red), abs(actual.green - expected.green),
                    abs(actual.blue - expected.blue)) < .01f) count++
        }
        return count
    }

    fun testRadioCheckboxAndSettingsCheckShareDefaultAccentInBothThemes() {
        for (dark in listOf(false, true)) {
            val selected = mutableStateOf(true)
            val enabled = mutableStateOf(true)
            var radioCalls = 0
            var checkboxCalls = 0
            var rowCalls = 0
            var resolved = Color.Transparent
            val expected = Color(if (dark) 0xFFC6B6D5.toInt() else 0xFF82718F.toInt())
            try {
                mainThread { activity.setContent {
                    AgentAppTheme(AppearanceSettings(
                        themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT), applyInterfaceScale = false) {
                        resolved = DaiyuPalette.selectionAccent
                        Column(Modifier.width(320.dp).background(MiuixTheme.colorScheme.surface).statusBarsPadding()) {
                            RadioButton(selected.value, onClick = { radioCalls++ }, enabled = enabled.value,
                                modifier = Modifier.testTag("selection-radio"))
                            Checkbox(selected.value, onCheckedChange = { checkboxCalls++ }, enabled = enabled.value,
                                modifier = Modifier.testTag("selection-checkbox"))
                            SettingsRadioRow("选中标记", selected.value, enabled.value) { rowCalls++ }
                        }
                    }
                } }
                ui.runOnIdle { assertEquals(expected, resolved) }
                for (tag in listOf("selection-radio", "selection-checkbox", "settings-radio:选中标记")) {
                    val target = node(tag)
                    val image = target.captureToImage()
                    val matches = matchingPixels(image, expected)
                    val diagnostic = "selected accent: $tag; dark=$dark expected=$expected " +
                        "resolved=$resolved matches=$matches image=${image.width}x${image.height} " +
                        "colorSpace=${image.colorSpace} bounds=${target.fetchSemanticsNode().boundsInRoot}"
                    if (matches == 0) {
                        val pixels = image.toPixelMap()
                        var closest = Color.Transparent
                        var difference = Float.POSITIVE_INFINITY
                        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
                            val actual = pixels[x, y]
                            val delta = maxOf(abs(actual.red - expected.red), abs(actual.green - expected.green),
                                abs(actual.blue - expected.blue))
                            if (delta < difference) { difference = delta; closest = actual }
                        }
                        val directory = java.io.File(activity.getExternalFilesDir(null), "visual-diagnostics").apply { mkdirs() }
                        java.io.File(directory, "selection-$tag-dark-$dark.png").outputStream().use {
                            check(image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                        }
                        android.util.Log.e("DaiyuSelectionRaster", "$diagnostic closest=$closest difference=$difference")
                    }
                    assertTrue(diagnostic, matches > 0)
                }
                node("selection-radio").performTouchInput { click() }
                node("selection-checkbox").performTouchInput { click() }
                node("settings-radio:选中标记").performTouchInput { click() }
                ui.runOnIdle {
                    assertEquals(1, radioCalls); assertEquals(1, checkboxCalls); assertEquals(1, rowCalls)
                    enabled.value = false
                }
                for (tag in listOf("selection-radio", "selection-checkbox", "settings-radio:选中标记")) {
                    node(tag).assertIsNotEnabled().performTouchInput { click() }
                }
                ui.runOnIdle {
                    assertEquals(1, radioCalls); assertEquals(1, checkboxCalls); assertEquals(1, rowCalls)
                    enabled.value = true; selected.value = false
                }
                node("selection-radio").assertIsNotSelected()
                node("selection-checkbox").assertIsOff()
                node("settings-radio:选中标记").assertIsNotSelected()
                for (tag in listOf("selection-radio", "selection-checkbox", "settings-radio:选中标记")) {
                    assertEquals("unselected remains neutral: $tag", 0,
                        matchingPixels(node(tag).captureToImage(), expected))
                }
            } finally { mainThread { activity.setContent {} } }
        }
        assertEquals(0, fixture.opens.get())
    }
}
