package app.daiyu.android

import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import app.daiyu.android.assistants.FileAssistantAssets
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.delegation.ReasoningEffort
import app.daiyu.android.model.catalog.CatalogModelEdit
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.components.DaiyuChoiceSheet
import app.daiyu.android.ui.components.UsageGlassDialog
import app.daiyu.android.ui.components.subagent.ConversationThinkingPicker
import app.daiyu.android.ui.screens.assistants.AssistantPickerDialog
import app.daiyu.android.ui.screens.chat.conversation.ChatAttachmentSheet
import app.daiyu.android.ui.screens.chat.conversation.ChatMenuAction
import app.daiyu.android.ui.screens.settings.CatalogModelEditorDialog

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class BottomSheetEdgeActivityTest : WiredActivityTestCase() {
    private var dismissals = 0

    private fun show(dark: Boolean = false, scale: Float = 1f, content: @Composable (() -> Unit) -> Unit) {
        dismissals = 0
        mainThread {
            val open = mutableStateOf(true)
            activity.setContent {
                AgentAppTheme(AppearanceSettings(interfaceScale = scale,
                    themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT), applyInterfaceScale = true) {
                    Box(Modifier.fillMaxSize()) {
                        if (open.value) content { dismissals++; open.value = false }
                    }
                }
            }
        }
        awaitVisualIdle()
    }

    private fun rawBounds(node: androidx.compose.ui.semantics.SemanticsNode): androidx.compose.ui.geometry.Rect =
        androidx.compose.ui.geometry.Rect(node.positionInRoot,
            androidx.compose.ui.geometry.Size(node.size.width.toFloat(), node.size.height.toFloat()))

    private fun assertAttached(surfaceTag: String, controlTag: String? = null, ime: Boolean = false) {
        awaitUniqueTag(surfaceTag)
        ui.mainClock.advanceTimeBy(1_000)
        awaitVisualIdle()
        val surface = node(surfaceTag).assertIsDisplayed().fetchSemanticsNode()
        val view = (surface.root as ViewRootForTest).view
        val viewportTag = if (surfaceTag == "daiyu-choice-sheet") "choice-sheet-viewport" else "preview-dialog-viewport"
        val viewport = node(viewportTag).fetchSemanticsNode()
        assertSame(surface.root, viewport.root)
        val control = controlTag?.let { node(it).assertIsDisplayed().fetchSemanticsNode() }
        var surfaceBottom = 0f
        var windowBottom = 0f
        var safeBottom = 0f
        var controlBottom = 0f
        instrumentation.runOnMainSync {
            val viewportBottom = viewport.boundsInRoot.bottom + viewport.positionOnScreen.y - viewport.positionInRoot.y
            val screenOffset = surface.positionOnScreen - surface.positionInRoot
            surfaceBottom = rawBounds(surface).bottom + screenOffset.y
            windowBottom = viewportBottom
            safeBottom = viewportBottom
            if (control != null) {
                assertSame(surface.root, control.root)
                controlBottom = rawBounds(control).bottom + control.positionOnScreen.y - control.positionInRoot.y
            }
            val decor = view.rootView
            val location = IntArray(2)
            decor.getLocationOnScreen(location)
            assertEquals("Viewport fills the dialog window", location[1] + decor.height.toFloat(), viewportBottom, 2f)
            val insets = checkNotNull(ViewCompat.getRootWindowInsets(view))
            assertEquals(ime, insets.isVisible(WindowInsetsCompat.Type.ime()))
            if (ime) {
                // The visible frame works for both resize and edge-to-edge dialogs: do not
                // subtract the IME height a second time from an already resized viewport.
                val visible = android.graphics.Rect()
                view.getWindowVisibleDisplayFrame(visible)
                windowBottom = minOf(viewportBottom, visible.bottom.toFloat())
                safeBottom = windowBottom
            } else {
                safeBottom -= insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()).bottom
            }
        }
        assertEquals("The painted surface must meet the window/keyboard edge", windowBottom, surfaceBottom, 2f)
        if (control != null) {
            assertTrue("Controls stay above the navigation gesture area", controlBottom <= safeBottom + 2f)
        }
    }

    private fun close(tag: String) {
        awaitNodeWindowFocus(node(tag))
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        ui.waitForIdle()
        node(tag).assertDoesNotExist()
        assertEquals(1, dismissals)
    }

    fun testAttachmentAndThinkingSheetsReachBottomInBothThemesAndScales() {
        for (dark in listOf(false, true)) for (scale in listOf(1f, .8f)) {
            var action: ChatMenuAction? = null
            show(dark, scale) { dismiss -> ChatAttachmentSheet(dismiss) { action = it } }
            assertAttached("chat-selection-sheet", "chat-attachment:ATTACH_FOLDER")
            node("chat-attachment:ATTACH_IMAGE").performClick()
            assertEquals(ChatMenuAction.ATTACH_IMAGE, action)
            assertEquals(1, dismissals)
            var changes = 0
            show(dark, scale) { dismiss ->
                ConversationThinkingPicker(true, ReasoningEffort.MEDIUM,
                    listOf(ReasoningEffort.OFF, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH), dismiss, { changes++ }, null)
            }
            assertAttached("conversation-agent-sheet", "thinking-depth-slider")
            close("conversation-agent-sheet")
            assertEquals(0, changes)
        }
        assertEquals(0, fixture.opens.get())
    }

    fun testAssistantUsageChoiceAndModelEditorShareBottomAttachment() {
        val assets = FileAssistantAssets(instrumentation.targetContext)
        show { dismiss -> AssistantPickerDialog(true, emptyList(), null, {}, {}, dismiss,
            onCopy = {}, onDelete = {}, assets = assets) }
        assertAttached("assistant-sheet", "assistant-done")
        close("assistant-sheet")
        show { dismiss -> UsageGlassDialog("底部用量弹窗", dismiss) { Box(Modifier.height(60.dp)) } }
        assertAttached("usage-glass-dialog")
        close("usage-glass-dialog")
        show { dismiss -> DaiyuChoiceSheet("选择", listOf("一", "二"), {}, dismiss) }
        assertAttached("daiyu-choice-sheet", "choice-sheet-cancel")
        close("daiyu-choice-sheet")
        show { dismiss -> CatalogModelEditorDialog(CatalogModelEdit("model", "模型"), true, true, {}, dismiss) }
        assertAttached("catalog-model-dialog", "dialog-confirm")
        close("catalog-model-dialog")
        assertEquals(0, fixture.opens.get())
    }

    fun testCompactUsageDialogRemainsCenteredRatherThanBecomingASheet() {
        show { dismiss -> UsageGlassDialog("居中用量", dismiss, compact = true) { Box(Modifier.size(40.dp)) } }
        ui.mainClock.advanceTimeBy(1_000)
        awaitVisualIdle()
        val surface = node("usage-glass-dialog").fetchSemanticsNode()
        assertTrue(surface.boundsInRoot.top > 0f)
        assertTrue(surface.boundsInRoot.bottom < node("preview-dialog-viewport").fetchSemanticsNode().boundsInRoot.bottom - 20f)
        close("usage-glass-dialog")
    }

    fun testEditorSurfaceFollowsImeWithoutExtraBottomGapAndReturnsToWindowEdge() {
        show { dismiss -> CatalogModelEditorDialog(CatalogModelEdit("model", "模型"), true, true, {}, dismiss) }
        assertAttached("catalog-model-dialog", "dialog-confirm")
        val sheet = node("catalog-model-dialog").fetchSemanticsNode()
        val view = (sheet.root as ViewRootForTest).view
        var originalBottom = 0f
        instrumentation.runOnMainSync {
            val location = IntArray(2)
            view.rootView.getLocationOnScreen(location)
            originalBottom = location[1] + view.rootView.height.toFloat()
        }
        node("settings-field:展示名称").performClick()
        mainThread {
            val focus = checkNotNull(view.rootView.findFocus())
            activity.getSystemService(InputMethodManager::class.java).showSoftInput(focus, InputMethodManager.SHOW_IMPLICIT)
        }
        ui.waitUntil(5_000) {
            var visible = false
            instrumentation.runOnMainSync {
                visible = ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true
            }
            visible
        }
        assertAttached("catalog-model-dialog", "dialog-confirm", ime = true)
        val restoredFrames = java.util.concurrent.atomic.AtomicInteger()
        val observer = view.viewTreeObserver
        var lastRestoredFrame = Long.MIN_VALUE
        var currentFrameNanos = Long.MIN_VALUE
        lateinit var choreographer: android.view.Choreographer
        val frameCallback = object : android.view.Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                currentFrameNanos = frameTimeNanos
                view.postInvalidateOnAnimation()
                choreographer.postFrameCallback(this)
            }
        }
        val listener = android.view.ViewTreeObserver.OnPreDrawListener {
            val insets = ViewCompat.getRootWindowInsets(view)
            val location = IntArray(2)
            view.rootView.getLocationOnScreen(location)
            val bottom = rawBounds(sheet).bottom + sheet.positionOnScreen.y - sheet.positionInRoot.y
            val restored = view.isAttachedToWindow && insets != null &&
                !insets.isVisible(WindowInsetsCompat.Type.ime()) &&
                insets.getInsets(WindowInsetsCompat.Type.ime()).bottom == 0 &&
                kotlin.math.abs(location[1] + view.rootView.height - originalBottom) <= 2f &&
                kotlin.math.abs(bottom - originalBottom) <= 2f
            val frame = currentFrameNanos
            if (!restored) {
                restoredFrames.set(0)
                lastRestoredFrame = Long.MIN_VALUE
            } else if (frame != Long.MIN_VALUE && frame != lastRestoredFrame) {
                restoredFrames.incrementAndGet()
                lastRestoredFrame = frame
            }
            if (restoredFrames.get() < 2) view.postInvalidateOnAnimation()
            true
        }
        instrumentation.runOnMainSync {
            choreographer = android.view.Choreographer.getInstance()
            observer.addOnPreDrawListener(listener)
            choreographer.postFrameCallback(frameCallback)
        }
        try {
            dismissIme()
            ui.waitUntil(5_000) { restoredFrames.get() >= 2 }
        } finally {
            instrumentation.runOnMainSync {
                choreographer.removeFrameCallback(frameCallback)
                if (observer.isAlive) observer.removeOnPreDrawListener(listener)
            }
        }
        assertAttached("catalog-model-dialog", "dialog-confirm")
        close("catalog-model-dialog")
        assertEquals(0, fixture.opens.get())
    }
}
