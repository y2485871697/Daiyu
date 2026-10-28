package app.daiyu.android

import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.components.WorkspaceCreateFolderDialog
import app.daiyu.android.ui.components.WorkspaceEntryDialogs
import app.daiyu.android.ui.screens.assistants.AssistantPreviewPalette
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class WorkspaceNameDialogsActivityTest : WiredActivityTestCase() {
    private val appearance = mutableStateOf(AppearanceSettings())
    private val active = AtomicBoolean(true)
    private val confirmations = AtomicInteger()
    private val dismissals = AtomicInteger()
    private var confirmedName: String? = null

    private fun mount(rename: Boolean, dark: Boolean = false) {
        val visit = Any()
        val open = mutableStateOf(true)
        active.set(true)
        confirmedName = null
        mainThread {
            appearance.value = AppearanceSettings(
                themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT)
            activity.setContent {
                AgentAppTheme(appearance.value, applyInterfaceScale = false) { key(visit) {
                    if (open.value) {
                        val confirm: (String) -> Unit = { confirmedName = it; confirmations.incrementAndGet(); open.value = false }
                        val dismiss: () -> Unit = { dismissals.incrementAndGet(); open.value = false }
                        if (rename) WorkspaceEntryDialogs("keep.txt", false, active::get, confirm, {}, dismiss)
                        else WorkspaceCreateFolderDialog(confirm, dismiss)
                    }
                } }
            }
        }
        if (rename) { awaitUniqueTag("workspace-entry-action:rename"); node("workspace-entry-action:rename").performClick() }
        awaitUniqueTag("prompt-input")
        awaitNodeWindowFocus(node("prompt-input"))
        ui.waitForIdle()
    }

    fun testCreationAndRenameShareFilledFieldHintGeometryAndNoClose() {
        for (dark in listOf(false, true)) {
            var creationWidth = 0f
            var creationHeight = 0f
            for (rename in listOf(false, true)) {
                mount(rename, dark)
                node("chat-selection-sheet").assertExists()
                ui.onAllNodesWithContentDescription("关闭").assertCountEquals(0)
                if (rename) node("prompt-input").assertTextContains("keep.txt")
                node("prompt-input").performTextClearance()
                ui.onAllNodesWithText("文件夹名称").assertCountEquals(1)
                ui.onNodeWithText("名称").assertDoesNotExist()
                val field = node("prompt-input").fetchSemanticsNode().boundsInRoot
                if (!rename) { creationWidth = field.width; creationHeight = field.height }
                else { assertEquals(creationWidth, field.width, 1f); assertEquals(creationHeight, field.height, 1f) }
                val cancel = node("dialog-cancel").assertHeightIsAtLeast(48.dp).fetchSemanticsNode().boundsInRoot
                val confirm = node("dialog-confirm").assertHeightIsAtLeast(48.dp).fetchSemanticsNode().boundsInRoot
                assertEquals(cancel.width, confirm.width, 1f)
                assertEquals(cancel.height, confirm.height, 1f)
                assertEquals(cancel.top, confirm.top, 1f)
                assertTrue(cancel.right < confirm.left)
            }
        }
    }

    private fun assertColor(expected: Color, actual: Color) {
        assertEquals(expected.red, actual.red, .02f)
        assertEquals(expected.green, actual.green, .02f)
        assertEquals(expected.blue, actual.blue, .02f)
    }

    private fun assertButtonRoles(rename: Boolean, dark: Boolean) {
        ui.waitForIdle()
        val colors = AssistantPreviewPalette(dark)
        for ((tag, text, background, foreground) in listOf(
            ButtonRole("dialog-cancel", "取消", colors.secondaryContainer, colors.onSecondary),
            ButtonRole("dialog-confirm", if (rename) "保存" else "确定", colors.primary, colors.onPrimary))) {
            val image = captureScreenNode(node(tag)).toPixelMap()
            assertColor(background, image[(image.width * .2f).toInt(), image.height / 2])
            val layouts = mutableListOf<TextLayoutResult>()
            ui.onNode(hasText(text) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(1, layouts.size)
            assertColor(foreground, layouts.single().layoutInput.style.color)
        }
    }

    private data class ButtonRole(val tag: String, val text: String, val background: Color, val foreground: Color)

    fun testDefaultActionRolesRemainStableWhenPureBlackChangesWhileOpen() {
        for (dark in listOf(false, true)) for (rename in listOf(false, true)) {
            mount(rename, dark)
            assertButtonRoles(rename, dark)
            repeat(2) {
                mainThread { appearance.value = appearance.value.copy(pureBlackEnabled = !appearance.value.pureBlackEnabled) }
                assertButtonRoles(rename, dark)
            }
        }
    }

    fun testInvalidNameRetainsDraftAndDismissalsNeverConfirm() {
        for (rename in listOf(false, true)) {
            for (action in listOf("cancel", "back", "outside", "confirm")) {
                val confirmsBefore = confirmations.get()
                val dismissalsBefore = dismissals.get()
                mount(rename)
                node("prompt-input").performTextReplacement("../invalid")
                node("dialog-confirm").performClick()
                node("prompt-input").assertTextContains("../invalid")
                assertEquals(confirmsBefore, confirmations.get())
                assertEquals(dismissalsBefore, dismissals.get())
                node("prompt-input").performTextReplacement("新 名称.txt")
                dismissIme()
                when (action) {
                    "cancel" -> node("dialog-cancel").performClick()
                    "back" -> { awaitNodeWindowFocus(node("dialog-cancel")); instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK) }
                    "outside" -> node("preview-dialog-viewport").performTouchInput { click(Offset(2f, 2f)) }
                    "confirm" -> node("dialog-confirm").performClick()
                }
                ui.waitUntil(5_000) {
                    ui.mainClock.advanceTimeByFrame()
                    ui.onAllNodesWithTag("chat-selection-sheet").fetchSemanticsNodes().isEmpty()
                }
                assertEquals(if (action == "confirm") 1 else 0, confirmations.get() - confirmsBefore)
                assertEquals(if (action == "confirm") 0 else 1, dismissals.get() - dismissalsBefore)
                assertEquals(if (action == "confirm") "新 名称.txt" else null, confirmedName)
            }
        }
        assertEquals(0, fixture.opens.get())
    }

    fun testRetainedRenameInputButtonsAndSystemDismissRejectRevokedOwner() {
        mount(rename = true)
        node("prompt-input").performTextReplacement("not-committed")
        val confirm = checkNotNull(node("dialog-confirm").fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        val cancel = checkNotNull(node("dialog-cancel").fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        val edit = checkNotNull(node("prompt-input").fetchSemanticsNode().config[SemanticsActions.SetText].action)
        val confirmsBefore = confirmations.get()
        val dismissalsBefore = dismissals.get()
        mainThread { active.set(false); edit(AnnotatedString("rejected")); confirm(); cancel() }
        node("prompt-input").assertTextContains("not-committed")
        assertEquals(confirmsBefore, confirmations.get())
        assertEquals(dismissalsBefore, dismissals.get())
        assertNull(confirmedName)
        node("prompt-input").assertExists()
        for (action in listOf("back", "outside")) {
            mount(rename = true)
            dismissIme()
            val beforeConfirm = confirmations.get()
            val beforeDismiss = dismissals.get()
            mainThread { active.set(false) }
            if (action == "back") instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            else node("preview-dialog-viewport").performTouchInput { click(Offset(2f, 2f)) }
            ui.waitForIdle()
            assertEquals(beforeConfirm, confirmations.get())
            assertEquals(beforeDismiss, dismissals.get())
            assertNull(confirmedName)
        }
    }
}
