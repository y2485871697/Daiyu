package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.components.UsagePalette
import kotlin.math.abs

class VoiceToneSelectionActivityTest : WiredActivityTestCase() {
    private val first = "zh_female_first"
    private val second = "zh_female_second"

    private fun assertColor(expected: Color, actual: Color) {
        assertEquals(expected.red, actual.red, .035f)
        assertEquals(expected.green, actual.green, .035f)
        assertEquals(expected.blue, actual.blue, .035f)
    }

    private fun assertSelectedPaint(id: String, label: String, palette: UsagePalette) {
        val row = node("settings-radio:$label").captureToImage().toPixelMap()
        assertColor(palette.selectionContainer, row[4, row.height / 2])
        val indicator = ui.onNodeWithTag("voice-tone-indicator:$id", useUnmergedTree = true)
        val pixels = indicator.captureToImage().toPixelMap()
        assertColor(palette.accent, pixels[pixels.width / 2, 2])
        var checkPixels = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val pixel = pixels[x, y]
            if (abs(pixel.red - palette.onPrimary.red) < .035f &&
                abs(pixel.green - palette.onPrimary.green) < .035f &&
                abs(pixel.blue - palette.onPrimary.blue) < .035f) checkPixels++
        }
        assertTrue("Selected indicator contains the contrasting check", checkPixels > 3)
        val rowBounds = node("settings-radio:$label").fetchSemanticsNode().boundsInRoot
        val checkBounds = indicator.fetchSemanticsNode().boundsInRoot
        assertTrue("Indicator stays on the right", checkBounds.left > rowBounds.center.x)
    }

    fun testToneTitleStartsLeftAndSelectionPaintUpdatesWithoutClosingInBothThemes() {
        for (dark in listOf(false, true)) for (title in listOf("音色", "选择音色")) {
            val selected = mutableStateOf(first)
            val open = mutableStateOf(true)
            var writes = 0
            var dismisses = 0
            val visit = Any()
            val palette = UsagePalette(dark)
            mainThread { activity.setContent { key(visit) {
                AgentAppTheme(AppearanceSettings(themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT),
                    applyInterfaceScale = false) {
                    if (open.value) VoiceToneDialog(title, selected.value,
                        listOf(first to "第一音色", second to "第二音色", "zh_male_third" to "男声音色"),
                        onSelect = { selected.value = it; writes++ },
                        onDismiss = { open.value = false; dismisses++ })
                }
            } } }
            awaitUniqueTag("voice-tone-dialog")
            awaitVisualIdle()
            val layouts = mutableListOf<TextLayoutResult>()
            val titleNode = ui.onNode(hasText(title, substring = false) and hasAnyAncestor(hasTestTag("chat-selection-sheet")),
                useUnmergedTree = true)
            titleNode.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(1, layouts.size)
            assertEquals(TextAlign.Start, layouts.single().layoutInput.style.textAlign)
            assertEquals(0f, layouts.single().getLineLeft(0), 1f)
            val sheetLeft = node("voice-tone-dialog").fetchSemanticsNode().boundsInRoot.left
            assertEquals(sheetLeft, titleNode.fetchSemanticsNode().boundsInRoot.left, 1f)
            node("settings-radio:第一音色").assertIsSelected()
            node("settings-radio:第二音色").assertIsNotSelected()
            assertSelectedPaint(first, "第一音色", palette)
            node("settings-radio:第二音色").performClick()
            ui.waitForIdle()
            node("voice-tone-dialog").assertIsDisplayed()
            node("settings-radio:第一音色").assertIsNotSelected()
            node("settings-radio:第二音色").assertIsSelected()
            assertSelectedPaint(second, "第二音色", palette)
            node("settings-radio:第一音色").performClick()
            ui.waitForIdle()
            node("voice-tone-dialog").assertIsDisplayed()
            assertSelectedPaint(first, "第一音色", palette)
            assertEquals(2, writes)
            assertEquals(0, dismisses)
            awaitNodeWindowFocus(node("chat-selection-sheet"))
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            ui.waitUntil(timeoutMillis = 5000) {
                ui.onAllNodesWithTag("chat-selection-sheet").fetchSemanticsNodes().isEmpty()
            }
            node("voice-tone-dialog").assertDoesNotExist()
            assertEquals(2, writes)
            assertEquals(1, dismisses)
        }
        assertEquals(0, fixture.opens.get())
    }
}
