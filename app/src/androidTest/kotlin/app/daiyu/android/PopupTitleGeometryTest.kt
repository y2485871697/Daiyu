package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.components.ConversationCollaborationDialog
import app.daiyu.android.ui.components.DaiyuChoiceSheet
import app.daiyu.android.ui.components.UsageGlassDialog
import app.daiyu.android.ui.screens.assistants.AssistantPreviewDialog
import app.daiyu.android.ui.screens.chat.conversation.ChatSelectionSheet
import app.daiyu.android.ui.screens.chat.conversation.SubAgentStatusDialog

class PopupTitleGeometryTest : WiredActivityTestCase() {
    private var pixelDensity = 1f

    private fun show(surface: String, dark: Boolean = false, fontScale: Float = 1f,
        rtl: Boolean = false, content: @Composable () -> Unit) {
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT),
                applyInterfaceScale = false) {
                val density = LocalDensity.current
                pixelDensity = density.density
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale),
                    LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                    content = content)
            }
        } }
        awaitUniqueTag(surface)
        awaitNodeWindowFocus(node(surface))
        ui.waitForIdle()
    }

    private data class HeadingFrame(
        val surface: Rect, val heading: Rect, val body: Rect?,
        val lineBounds: List<Pair<Float, Float>>, val textAlign: TextAlign, val overflow: Boolean,
    )

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    private fun assertCentered(title: String, surface: String, body: String? = null,
        expectedGap: Float? = null, wrapped: Boolean = false) {
        val sheet = node(surface).fetchSemanticsNode()
        val heading = ui.onNodeWithText(title, useUnmergedTree = true).fetchSemanticsNode()
        val firstBody = body?.let { node(it).fetchSemanticsNode() }
        assertSame(sheet.root, heading.root)
        if (firstBody != null) assertSame(sheet.root, firstBody.root)
        val view = (sheet.root as ViewRootForTest).view
        val frame = awaitStableWindowFrame(view) {
            val nodes = listOfNotNull(sheet, heading, firstBody)
            if (nodes.any { !it.layoutInfo.isPlaced || !it.layoutInfo.coordinates.isAttached })
                return@awaitStableWindowFrame null
            val layouts = mutableListOf<TextLayoutResult>()
            assertTrue(checkNotNull(heading.config[SemanticsActions.GetTextLayoutResult].action)(layouts))
            val text = layouts.single()
            HeadingFrame(sheet.boundsInRoot, heading.boundsInRoot, firstBody?.boundsInRoot,
                (0 until text.lineCount).map { line -> text.getLineLeft(line) to text.getLineRight(line) },
                text.layoutInput.style.textAlign, text.hasVisualOverflow)
        }
        val titleBounds = frame.heading
        val sheetBounds = frame.surface
        assertEquals(TextAlign.Center, frame.textAlign)
        assertFalse("the title must remain readable", frame.overflow)
        if (wrapped) assertTrue("the long title fixture must exercise actual line wrapping", frame.lineBounds.size > 1)
        for ((left, right) in frame.lineBounds) {
            val lineCenter = titleBounds.left + (left + right) / 2f
            assertEquals("actual title glyphs must be centered on the sheet", sheetBounds.center.x, lineCenter, 2f)
        }
        if (expectedGap != null) {
            assertEquals("moderate heading/body spacing", expectedGap * pixelDensity,
                checkNotNull(frame.body).top - titleBounds.bottom, 2f)
        }
    }

    fun testAssistantHeadingCentersWithEveryNavigationButtonCombination() {
        val title = "选择助手"
        for (dark in listOf(false, true)) for (fontScale in listOf(1f, 1.3f))
            for (back in listOf(false, true)) for (close in listOf(false, true)) {
                show("assistant-sheet", dark, fontScale) {
                    AssistantPreviewDialog(true, title, {}, onBack = if (back) ({}) else null, showCloseButton = close) {
                        Box(Modifier.height(44.dp).testTag("title-body"))
                    }
                }
                assertCentered(title, "assistant-sheet", "title-body", if (!back && !close) 12f else null)
                ui.onAllNodesWithContentDescription("返回").assertCountEquals(if (back) 1 else 0)
                ui.onAllNodesWithContentDescription("关闭").assertCountEquals(if (close) 1 else 0)
            }
    }

    fun testSelectionHeadingAndSubtitleKeepIndependentAlignmentAndSpacing() {
        for (close in listOf(false, true)) for (rtl in listOf(false, true)) {
            show("chat-selection-sheet", fontScale = 1.3f, rtl = rtl) {
                ChatSelectionSheet("选择模型", {}, showCloseButton = close, subtitle = "副标题仍按原位置显示") {
                    Box(Modifier.height(44.dp).testTag("title-body"))
                }
            }
            assertCentered("选择模型", "chat-selection-sheet")
            val subtitle = ui.onNodeWithText("副标题仍按原位置显示").fetchSemanticsNode().boundsInRoot
            val body = node("title-body").fetchSemanticsNode().boundsInRoot
            assertEquals(12f * pixelDensity, body.top - subtitle.bottom, 2f)
        }
    }

    fun testLongTitleWrapsAndStaysCenteredWithOneSidedButtons() {
        val title = "当前会话的模型工具与子代理协作设置及可用配置选择"
        for (rtl in listOf(false, true)) {
            show("assistant-sheet", fontScale = 1.3f, rtl = rtl) {
                AssistantPreviewDialog(true, title, {}, onBack = {}, showCloseButton = false) {}
            }
            assertCentered(title, "assistant-sheet", wrapped = true)
            show("chat-selection-sheet", fontScale = 1.3f, rtl = rtl) {
                ChatSelectionSheet(title, {}, showCloseButton = true) {}
            }
            assertCentered(title, "chat-selection-sheet", wrapped = true)
        }
    }

    fun testCompressionStyleBottomHeadingUsesModerateGap() {
        for (dark in listOf(false, true)) for (fontScale in listOf(1f, 1.3f)) {
            show("usage-glass-dialog", dark, fontScale) {
                UsageGlassDialog("压缩会话", {}) { Box(Modifier.height(44.dp).testTag("title-body")) }
            }
            assertCentered("压缩会话", "usage-glass-dialog", "title-body", 12f)
        }
    }

    fun testNonPreviewChoiceTitleIsAlsoCentered() {
        show("daiyu-choice-sheet") { DaiyuChoiceSheet("选择操作", listOf("一个选项"), {}, {}) }
        assertCentered("选择操作", "daiyu-choice-sheet")
    }

    fun testConversationCollaborationHeadingIsCentered() {
        for (dark in listOf(false, true)) for (fontScale in listOf(1f, 1.3f)) {
            show("subagent-collaboration-card", dark, fontScale) { ConversationCollaborationDialog(true, null, {}) }
            assertCentered("本会话协作", "subagent-collaboration-card")
        }
        assertEquals(0, fixture.opens.get())
    }

    fun testVoiceRemovalHeadingIsCenteredWithoutExtraDividerGap() {
        show("voice-local-removal-sheet") { VoiceLocalRemovalSheet({}, {}) }
        assertCentered("确认本次操作", "voice-local-removal-sheet")
    }

    fun testSubAgentStatusHeadingIsCentered() {
        show("subagent-status", fontScale = 1.3f) { SubAgentStatusDialog(emptyList(), false, false, {}) }
        assertCentered("子代理状态", "subagent-status")
        assertEquals(0, fixture.opens.get())
    }
}
