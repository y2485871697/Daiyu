package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.components.UsagePalette

class VoiceToneSelectionActivityTest : WiredActivityTestCase() {
    private val first = "zh_female_first"
    private val second = "zh_female_second"

    private fun selectedIcon(id: String) = ui.onNode(
        hasContentDescription("已选择") and hasAnyAncestor(hasTestTag("voice-tone-indicator:$id")),
        useUnmergedTree = true,
    )

    private fun assertSelectedPaint(id: String, label: String, palette: UsagePalette) {
        val option = node("voice-tone-option:$id").scrollToVisible().assertIsSelected()
        selectedIcon(id).assertIsDisplayed()
        val row = captureScreenNode(option).toPixelMap()
        assertSelectionPaintColor(palette.selectionContainer, row[4, row.height / 2])
        val indicator = ui.onNodeWithTag("voice-tone-indicator:$id", useUnmergedTree = true)
        val pixels = captureScreenNode(indicator).toPixelMap()
        assertSelectionBadge(pixels, label, palette)
        val rowBounds = option.fetchSemanticsNode().boundsInRoot
        val checkBounds = indicator.fetchSemanticsNode().boundsInRoot
        assertTrue("Indicator stays on the right", checkBounds.left > rowBounds.center.x)
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    fun testToneTitleIsCenteredAndSelectionPaintUpdatesWithoutClosingInBothThemes() {
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
                        listOf(first to "第一音色", second to "第二音色", "zh_male_third" to "男声音色",
                            "S_personal" to "我的音色", "odd/raw id" to "其他音色名", first to "重复记录"),
                        onSelect = { selected.value = it; writes++ },
                        onDismiss = { open.value = false; dismisses++ })
                }
            } } }
            awaitUniqueTag("voice-tone-dialog")
            awaitDialogEnter(node("chat-selection-sheet"))
            awaitVisualIdle()
            val layouts = mutableListOf<TextLayoutResult>()
            val titleNode = ui.onNode(hasText(title, substring = false) and hasAnyAncestor(hasTestTag("chat-selection-sheet")),
                useUnmergedTree = true)
            titleNode.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(1, layouts.size)
            val titleLayout = layouts.single()
            assertEquals(1, titleLayout.lineCount)
            assertEquals(TextAlign.Center, titleLayout.layoutInput.style.textAlign)
            assertEquals(titleLayout.size.width / 2f,
                (titleLayout.getLineLeft(0) + titleLayout.getLineRight(0)) / 2f, 1f)
            val sheetCenter = node("chat-selection-sheet").fetchSemanticsNode().boundsInRoot.center.x
            assertEquals(sheetCenter, titleNode.fetchSemanticsNode().boundsInRoot.center.x, 1f)
            node("voice-tone-option:$first").assertIsSelected()
            node("voice-tone-option:$second").assertIsNotSelected()
            assertSelectedPaint(first, "第一音色", palette)
            selectedIcon(second).assertDoesNotExist()
            node("voice-tone-search").assertTextContains("搜索音色")
            node("voice-tone-group:女声").performClick()
            node("voice-tone-option:$first").assertDoesNotExist()
            node("voice-tone-group:女声").performClick()
            node("voice-tone-option:$first").assertIsSelected()
            ui.onAllNodesWithText("重复记录").assertCountEquals(0)
            node("voice-tone-group:个人音色").scrollToVisible().performClick()
            node("voice-tone-option:S_personal").assertIsDisplayed()
            val groupText = ui.onNodeWithTag("voice-tone-group-name:个人音色", useUnmergedTree = true)
            val groupLayout = mutableListOf<TextLayoutResult>()
            groupText.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(groupLayout) }
            assertEquals(TextAlign.Start, groupLayout.single().layoutInput.style.textAlign)
            assertEquals(0f, groupLayout.single().getLineLeft(0), 1f)
            assertTrue(groupText.fetchSemanticsNode().boundsInRoot.left <
                node("voice-tone-group:个人音色").fetchSemanticsNode().boundsInRoot.center.x)
            node("voice-tone-search").performTextReplacement("未命中")
            ui.onNodeWithText("没有找到匹配的音色").assertExists()
            node("voice-tone-search").performTextReplacement("odd/raw")
            node("voice-tone-option:odd/raw id").assertIsDisplayed()
            node("voice-tone-search").performTextReplacement("第二")
            node("voice-tone-option:$second").assertIsDisplayed()
            assertEquals(0, writes)
            node("voice-tone-option:$second").performClick()
            ui.waitForIdle()
            node("voice-tone-dialog").assertIsDisplayed()
            node("voice-tone-option:$first").assertDoesNotExist()
            node("voice-tone-option:$second").assertIsSelected()
            assertSelectedPaint(second, "第二音色", palette)
            node("voice-tone-search").performTextReplacement("")
            node("voice-tone-group:女声").scrollToVisible().performClick()
            node("voice-tone-option:$second").assertIsSelected()
            node("voice-tone-option:$first").assertIsNotSelected()
            selectedIcon(second).assertIsDisplayed()
            selectedIcon(first).assertDoesNotExist()
            node("voice-tone-option:$first").performClick()
            ui.waitForIdle()
            node("voice-tone-dialog").assertIsDisplayed()
            assertSelectedPaint(first, "第一音色", palette)
            node("voice-tone-option:$second").assertIsNotSelected()
            selectedIcon(second).assertDoesNotExist()
            assertEquals(2, writes)
            assertEquals(0, dismisses)
            val sheet = node("chat-selection-sheet")
            awaitNodeWindowFocus(sheet)
            val rootView = (sheet.fetchSemanticsNode().root as androidx.compose.ui.platform.ViewRootForTest).view
            dismissIme(rootView)
            awaitDialogImeFrame(sheet, visible = false)
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
