package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.screens.chat.conversation.ChatAttachmentSheet
import app.daiyu.android.ui.screens.chat.conversation.ChatMenuAction
import app.daiyu.android.ui.screens.chat.conversation.ChatSelectionSheet

class ChatAttachmentSizingActivityTest : WiredActivityTestCase() {
    private fun textLayout(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        ui.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(results)) }
        assertEquals(1, results.size)
        return results.single().also { assertFalse("Text overflows: $text", it.hasVisualOverflow) }
    }

    private fun rawBounds(node: SemanticsNode) = Rect(node.positionInRoot,
        Size(node.size.width.toFloat(), node.size.height.toFloat()))

    fun testAttachmentSizingAndSingleDispatchInBothThemesAndFontScales() {
        val entries = listOf("图片" to ChatMenuAction.ATTACH_IMAGE, "视频" to ChatMenuAction.ATTACH_VIDEO,
            "文件引用" to ChatMenuAction.ATTACH_FILE, "目录引用" to ChatMenuAction.ATTACH_FOLDER)
        var sample = 0
        for (dark in listOf(false, true)) for (scale in listOf(1f, .8f)) for (fontScale in listOf(.9f, 1.3f)) {
            var dismissals = 0
            val actions = mutableListOf<ChatMenuAction>()
            mainThread {
                val open = mutableStateOf(true)
                activity.setContent {
                    val platformDensity = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(platformDensity.density, fontScale)) {
                        AgentAppTheme(AppearanceSettings(interfaceScale = scale,
                            themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT),
                            applyInterfaceScale = true) {
                            Box(Modifier.fillMaxSize()) {
                                if (open.value) ChatAttachmentSheet({ dismissals++; open.value = false }, { actions += it })
                            }
                        }
                    }
                }
            }
            awaitUniqueTag("chat-selection-sheet")
            awaitNodeWindowFocus(node("chat-selection-sheet"))
            ui.mainClock.advanceTimeBy(1_000)
            awaitVisualIdle()
            val title = textLayout("附件")
            assertEquals(20.sp, title.layoutInput.style.fontSize)
            assertEquals(26.sp, title.layoutInput.style.lineHeight)
            val subtitle = textLayout("选择你要添加的内容")
            assertEquals(14.sp, subtitle.layoutInput.style.fontSize)
            assertEquals(18.sp, subtitle.layoutInput.style.lineHeight)
            val surface = node("chat-selection-sheet").fetchSemanticsNode().boundsInRoot
            var previousTag: String? = null
            for ((label, action) in entries) {
                val text = textLayout(label)
                assertEquals(16.sp, text.layoutInput.style.fontSize)
                assertEquals(24.sp, text.layoutInput.style.lineHeight)
                assertEquals(fontScale, text.layoutInput.density.fontScale, .001f)
                val density = text.layoutInput.density.density
                val row = node("chat-attachment:${action.name}").performScrollTo().assertIsDisplayed().assertHasClickAction()
                val rowBounds = rawBounds(row.fetchSemanticsNode())
                assertTrue("Row has at least a 56dp touch target", rowBounds.height >= 56f * density - 1f)
                previousTag?.let {
                    assertTrue("Rows do not overlap", rowBounds.top >= rawBounds(node(it).fetchSemanticsNode()).bottom - 1f)
                }
                assertTrue("Whole row stays inside the sheet", rowBounds.top >= surface.top - 1f &&
                    rowBounds.bottom <= surface.bottom + 1f && rowBounds.left >= surface.left - 1f &&
                    rowBounds.right <= surface.right + 1f)
                previousTag = "chat-attachment:${action.name}"
                for ((tag, size) in listOf("chat-attachment-icon" to 24f, "chat-attachment-chevron" to 18f)) {
                    val icon = ui.onNodeWithTag("$tag:${action.name}", useUnmergedTree = true)
                        .assertIsDisplayed().fetchSemanticsNode()
                    assertEquals(size * density, icon.size.width.toFloat(), 1f)
                    assertEquals(size * density, icon.size.height.toFloat(), 1f)
                }
            }
            val action = entries[sample++ % entries.size].second
            node("chat-attachment:${action.name}").performScrollTo().performTouchInput { click(Offset(4f, center.y)) }
            node("chat-selection-sheet").assertDoesNotExist()
            assertEquals(1, dismissals)
            assertEquals(listOf(action), actions)
        }
        assertEquals(0, fixture.opens.get())
    }

    fun testOtherSelectionSheetsKeepTheirDefaultHeaderTypography() {
        mainThread {
            activity.setContent {
                val platformDensity = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(platformDensity.density, 1.3f)) {
                    AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                        ChatSelectionSheet("默认标题", {}, subtitle = "默认说明") { Box(Modifier.height(56.dp)) }
                    }
                }
            }
        }
        awaitUniqueTag("chat-selection-sheet")
        ui.mainClock.advanceTimeBy(1_000)
        awaitVisualIdle()
        val title = textLayout("默认标题")
        val subtitle = textLayout("默认说明")
        assertEquals(18.sp, title.layoutInput.style.fontSize)
        assertEquals(26.sp, title.layoutInput.style.lineHeight)
        assertEquals(12.sp, subtitle.layoutInput.style.fontSize)
        assertEquals(18.sp, subtitle.layoutInput.style.lineHeight)
        assertEquals(1.3f, title.layoutInput.density.fontScale, .001f)
        assertEquals(1.3f, subtitle.layoutInput.density.fontScale, .001f)
        assertEquals(0, fixture.opens.get())
    }
}
