package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.components.UsagePalette
import app.daiyu.android.ui.screens.chat.conversation.ChatSelectionSheet
import app.daiyu.android.ui.screens.settings.SettingsRadioRow
import kotlin.math.roundToInt

class SettingsSelectionSheetActivityTest : WiredActivityTestCase() {
    private fun assertColor(expected: Color, actual: Color) {
        assertEquals(expected.red, actual.red, .035f)
        assertEquals(expected.green, actual.green, .035f)
        assertEquals(expected.blue, actual.blue, .035f)
    }

    private fun assertSelectedPaint(label: String, palette: UsagePalette, density: Float) {
        val pixels = node("settings-radio:$label").captureToImage().toPixelMap()
        assertColor(palette.selectionContainer, pixels[4, pixels.height / 2])
        val left = pixels.width - (34.dp.value * density).roundToInt()
        val right = pixels.width - (12.dp.value * density).roundToInt()
        val top = pixels.height / 2 - (11.dp.value * density).roundToInt()
        val bottom = pixels.height / 2 + (11.dp.value * density).roundToInt()
        assertColor(palette.accent, pixels[(left + right) / 2, top + 2])
        var checkPixels = 0
        for (y in top until bottom) for (x in left until right) {
            val color = pixels[x, y]
            if (kotlin.math.abs(color.red - palette.onPrimary.red) < .035f &&
                kotlin.math.abs(color.green - palette.onPrimary.green) < .035f &&
                kotlin.math.abs(color.blue - palette.onPrimary.blue) < .035f) checkPixels++
        }
        assertTrue("The trailing accent circle contains a contrasting check", checkPixels > 3)
    }

    fun testSheetRowsShareReferencePaintKeepSummariesAndRespectSelectionAndDisabledState() {
        for (dark in listOf(false, true)) {
            val selected = mutableStateOf(0)
            val open = mutableStateOf(true)
            val visit = Any()
            var calls = 0
            var dismisses = 0
            var density = 1f
            val palette = UsagePalette(dark)
            mainThread { activity.setContent { key(visit) {
                AgentAppTheme(AppearanceSettings(themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT),
                    applyInterfaceScale = false) {
                    if (open.value) ChatSelectionSheet("识别服务", onDismiss = { open.value = false; dismisses++ },
                        showCloseButton = false) {
                        density = LocalDensity.current.density
                        Column(Modifier.fillMaxWidth().selectableGroup()) {
                            listOf("第一项", "第二项", "禁用项").forEachIndexed { index, title ->
                                SettingsRadioRow(title, selected.value == index, enabled = index != 2,
                                    summary = if (index == 0) "保留选项自身说明" else null) {
                                    selected.value = index; calls++
                                }
                            }
                        }
                    }
                }
            } } }
            awaitUniqueTag("chat-selection-sheet")
            awaitNodeWindowFocus(node("chat-selection-sheet"))
            awaitVisualIdle()
            node("settings-radio:第一项").assertIsSelected().assertHeightIsAtLeast(56.dp)
            node("settings-radio:第二项").assertIsNotSelected().assertHeightIsAtLeast(56.dp)
            ui.onNodeWithText("保留选项自身说明", useUnmergedTree = true).assertIsDisplayed()
            assertSelectedPaint("第一项", palette, density)
            node("settings-radio:第二项").performTouchInput { click() }
            awaitVisualIdle()
            node("settings-radio:第二项").assertIsSelected()
            node("settings-radio:第一项").assertIsNotSelected()
            assertSelectedPaint("第二项", palette, density)
            node("settings-radio:禁用项").assertIsNotEnabled().performTouchInput { click() }
            node("chat-selection-sheet").assertIsDisplayed()
            assertEquals(1, calls)
            assertEquals(0, dismisses)
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            ui.waitUntil(5_000) { ui.onAllNodesWithTag("chat-selection-sheet").fetchSemanticsNodes().isEmpty() }
            assertEquals(1, calls)
            assertEquals(1, dismisses)
        }
        assertEquals(0, fixture.opens.get())
    }
}
