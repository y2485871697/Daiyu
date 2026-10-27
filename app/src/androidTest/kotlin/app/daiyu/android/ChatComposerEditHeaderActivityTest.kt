package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.app.resolveAppearanceActions
import app.daiyu.android.ui.screens.chat.conversation.*

class ChatComposerEditHeaderActivityTest : WiredActivityTestCase() {
    private var cancelled = 0
    private var modelClicked = 0
    private val attachmentClicks = mutableListOf<ChatMenuAction>()
    private var density = 1f
    private fun show(width: Dp, font: Float, dark: Boolean, editing: Boolean = true,
        primary: Long = 0xFF2266AA, enabled: Boolean = true) {
        val appearance = AppearanceSettings(primaryAccentColor = primary.toInt(), secondaryAccentColor = 0xFF845CB5.toInt(),
            themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT)
        mainThread { activity.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, font)) {
                AgentAppTheme(appearance, applyInterfaceScale = false) {
                    density = LocalDensity.current.density
                    ChatMenuLayer {
                        ChatComposer(ComposerUi(text = "编辑正文", modelLabel = "very-long-model-name".repeat(20),
                            enabled = enabled, contextUsageText = "100.0% 上下文", contextTokensText = "131072/131072"),
                            ChatScreenActions(editingMessageKey = if (editing) "owned-message" else null,
                                onCancelMessageEdit = { cancelled++ }, onModelClick = { modelClicked++ },
                                onMenuAction = { attachmentClicks.add(it) }),
                            Modifier.requiredWidth(width))
                    }
                }
            }
        } }
        ui.waitForIdle()
    }
    fun testEditingModelAndAttachmentsAcceptRealTouchesWithoutDisabledTint() {
        for (dark in listOf(false, true)) {
            show(320.dp, 1f, dark, editing = false)
            fun labelColor(): androidx.compose.ui.graphics.Color {
                val layouts = mutableListOf<TextLayoutResult>()
                ui.onNodeWithTag("composer-model-label", useUnmergedTree = true)
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
                return layouts.single().layoutInput.style.color
            }
            val normalColor = labelColor()
            show(320.dp, 1f, dark)
            assertCentered()
            assertEquals(normalColor, labelColor())
            val modelsBefore = modelClicked
            node("chat-model").assertIsEnabled().performTouchInput { click(center) }
            assertEquals(modelsBefore + 1, modelClicked)
            for (action in listOf(ChatMenuAction.ATTACH_IMAGE, ChatMenuAction.ATTACH_FILE, ChatMenuAction.ATTACH_FOLDER)) {
                node("chat-attach").assertIsEnabled().performTouchInput { click(center) }
                node("chat-attachment:${action.name}").performTouchInput { click(center) }
                assertEquals(action, attachmentClicks.last())
            }
            show(320.dp, 1f, dark, enabled = false)
            val attachmentsBefore = attachmentClicks.size
            val disabledModelsBefore = modelClicked
            node("chat-model").assertIsNotEnabled().performTouchInput { click(center) }
            node("chat-attach").assertIsNotEnabled().performTouchInput { click(center) }
            assertEquals(disabledModelsBefore, modelClicked)
            assertEquals(attachmentsBefore, attachmentClicks.size)
            node("chat-attachment:ATTACH_IMAGE").assertDoesNotExist()
        }
    }

    private fun assertCentered() {
        val cancel = node("message-edit-cancel").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val surface = node("chat-composer-surface").fetchSemanticsNode().boundsInRoot
        val divider = node("chat-composer-divider").fetchSemanticsNode().boundsInRoot
        val model = node("chat-model").fetchSemanticsNode().boundsInRoot
        val context = node("chat-context").fetchSemanticsNode().boundsInRoot
        assertEquals(surface.center.x, cancel.center.x, 1f)
        assertEquals(divider.center.x, cancel.center.x, 1f)
        assertTrue(cancel.bottom <= divider.top)
        assertTrue(model.right <= cancel.left)
        assertTrue(cancel.right <= context.left)
        assertTrue(cancel.height >= 40f * density - 1f)
        val results = mutableListOf<TextLayoutResult>()
        ui.onNodeWithText("取消编辑", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(results)) }
        val text = results.single()
        assertFalse(text.didOverflowHeight)
        assertFalse(text.isLineEllipsized(0))
        assertTrue(text.getLineLeft(0) >= -1f)
        assertTrue(text.getLineRight(0) <= text.size.width + 1f)
        assertEquals(text.layoutInput.text.length, text.getLineEnd(0, visibleEnd = true))
    }
    fun testCancelFitsAndStaysCenteredAcrossWidthsFontsAndThemes() {
        for (width in listOf(280.dp, 320.dp, 411.dp)) for (font in listOf(1f, 1.3f, 2f)) for (dark in listOf(false, true)) {
            show(width, font, dark)
            assertCentered()
            node("chat-context").performTouchInput { click(center) }
            ui.waitForIdle()
            assertCentered()
            val before = cancelled
            node("message-edit-cancel").performTouchInput { click(center) }
            assertEquals(before + 1, cancelled)
            assertEquals(0, modelClicked)
        }
    }
    fun testCancelUsesSecondaryRoleIndependentOfPrimaryAndIsAbsentOutsideEditing() {
        for (primary in listOf(0xFF2266AAL, 0xFFBB2244L)) for (dark in listOf(false, true)) {
            show(320.dp, 1.3f, dark, primary = primary)
            val layouts = mutableListOf<TextLayoutResult>()
            ui.onNodeWithText("取消编辑", useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
            val expected = resolveAppearanceActions(AppearanceSettings(primaryAccentColor = primary.toInt(),
                secondaryAccentColor = 0xFF845CB5.toInt()), dark).onSecondary
            assertEquals(expected, layouts.single().layoutInput.style.color)
        }
        show(320.dp, 1f, false, editing = false)
        node("message-edit-cancel").assertDoesNotExist()
    }
}
