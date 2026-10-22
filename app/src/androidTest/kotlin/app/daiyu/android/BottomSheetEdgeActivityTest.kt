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

    private fun assertHorizontalInsets(surfaceTag: String,
        surface: androidx.compose.ui.semantics.SemanticsNode,
        viewport: androidx.compose.ui.semantics.SemanticsNode,
        insets: WindowInsetsCompat) {
        val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val viewportBounds = rawBounds(viewport)
        val safeLeft = viewportBounds.left + safe.left
        val safeRight = viewportBounds.right - safe.right
        val available = safeRight - safeLeft
        val density = surface.layoutInfo.density
        val margin = with(density) { (if (surfaceTag in listOf("daiyu-choice-sheet", "catalog-model-dialog")) 12.dp else 10.dp).toPx() }
        val width = with(density) {
            when (surfaceTag) {
                "daiyu-choice-sheet" -> minOf(available, 640.dp.toPx()) - 2 * margin
                "catalog-model-dialog" -> minOf(available - 2 * margin, 560.dp.toPx())
                "usage-glass-dialog" -> minOf(available - 2 * margin, 550.dp.toPx())
                else -> available - 2 * margin
            }
        }
        val gap = (available - width) / 2
        val surfaceLeft = rawBounds(surface).left
        val surfaceRight = rawBounds(surface).right
        assertEquals("Original left inset remains outside the surface", safeLeft + gap, surfaceLeft, 2f)
        assertEquals("Original right inset remains outside the surface", safeRight - gap, surfaceRight, 2f)
    }

    private fun assertFloating(surfaceTag: String, controlTag: String? = null, ime: Boolean = false,
        originalWindowBottom: Float? = null) {
        awaitUniqueTag(surfaceTag)
        awaitNodeWindowFocus(node(surfaceTag))
        if (!ime) {
            val root = (node(surfaceTag).fetchSemanticsNode().root as ViewRootForTest).view.rootView
            dismissIme(root)
        }
        ui.mainClock.advanceTimeBy(1_000)
        awaitVisualIdle()
        val surface = node(surfaceTag).assertIsDisplayed().fetchSemanticsNode()
        val view = (surface.root as ViewRootForTest).view
        val viewportTag = if (surfaceTag == "daiyu-choice-sheet") "choice-sheet-viewport" else "preview-dialog-viewport"
        val viewport = node(viewportTag).fetchSemanticsNode()
        assertSame(surface.root, viewport.root)
        val control = controlTag?.let { node(it).assertIsDisplayed().fetchSemanticsNode() }
        var surfaceBottom = 0f
        var safeBottom = 0f
        var controlBottom = 0f
        val margin = with(surface.layoutInfo.density) { (if (surfaceTag in listOf("daiyu-choice-sheet", "catalog-model-dialog")) 12.dp else 10.dp).toPx() }
        instrumentation.runOnMainSync {
            val viewportBottom = viewport.boundsInRoot.bottom + viewport.positionOnScreen.y - viewport.positionInRoot.y
            val screenOffset = surface.positionOnScreen - surface.positionInRoot
            surfaceBottom = rawBounds(surface).bottom + screenOffset.y
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
            assertHorizontalInsets(surfaceTag, surface, viewport, insets)
            assertEquals(ime, insets.isVisible(WindowInsetsCompat.Type.ime()))
            if (ime) {
                // ADJUST_NOTHING may retain the full frame. Subtract from the original
                // window, never from a viewport that may already have been resized.
                val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                assertTrue("Visible IME has a positive bottom inset", imeBottom > 0)
                val keyboardTop = checkNotNull(originalWindowBottom) - imeBottom
                assertTrue("Viewport must reach the independent keyboard edge", viewportBottom >= keyboardTop - 2f)
                val systemBottom = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()).bottom
                safeBottom = checkNotNull(originalWindowBottom) - maxOf(imeBottom, systemBottom)
            } else {
                safeBottom -= insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()).bottom
            }
        }
        assertEquals("The painted surface keeps its original bottom margin", safeBottom - margin, surfaceBottom, 2f)
        if (control != null) {
            assertTrue("Controls stay within the floating surface", controlBottom <= surfaceBottom + 2f)
        }
    }

    private fun close(tag: String) {
        awaitNodeWindowFocus(node(tag))
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        ui.waitForIdle()
        node(tag).assertDoesNotExist()
        assertEquals(1, dismissals)
    }

    fun testAttachmentAndThinkingSheetsKeepFloatingInsetsInBothThemesAndScales() {
        for (dark in listOf(false, true)) for (scale in listOf(1f, .8f)) {
            var action: ChatMenuAction? = null
            show(dark, scale) { dismiss -> ChatAttachmentSheet(dismiss) { action = it } }
            assertFloating("chat-selection-sheet", "chat-attachment:ATTACH_FOLDER")
            node("chat-attachment:ATTACH_IMAGE").performClick()
            assertEquals(ChatMenuAction.ATTACH_IMAGE, action)
            assertEquals(1, dismissals)
            var changes = 0
            show(dark, scale) { dismiss ->
                ConversationThinkingPicker(true, ReasoningEffort.MEDIUM,
                    listOf(ReasoningEffort.OFF, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH), dismiss, { changes++ }, null)
            }
            assertFloating("conversation-agent-sheet", "thinking-depth-slider")
            close("conversation-agent-sheet")
            assertEquals(0, changes)
        }
        assertEquals(0, fixture.opens.get())
    }

    fun testAssistantUsageChoiceAndModelEditorShareFloatingInsets() {
        val assets = FileAssistantAssets(instrumentation.targetContext)
        show { dismiss -> AssistantPickerDialog(true, emptyList(), null, {}, {}, dismiss,
            onCopy = {}, onDelete = {}, assets = assets) }
        assertFloating("assistant-sheet", "assistant-done")
        close("assistant-sheet")
        show { dismiss -> UsageGlassDialog("底部用量弹窗", dismiss) { Box(Modifier.height(60.dp)) } }
        assertFloating("usage-glass-dialog")
        close("usage-glass-dialog")
        show { dismiss -> DaiyuChoiceSheet("选择", listOf("一", "二"), {}, dismiss) }
        assertFloating("daiyu-choice-sheet", "choice-sheet-cancel")
        close("daiyu-choice-sheet")
        show { dismiss -> CatalogModelEditorDialog(CatalogModelEdit("model", "模型"), true, true, {}, dismiss) }
        assertFloating("catalog-model-dialog", "dialog-confirm")
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

    fun testEditorSurfaceKeepsBottomMarginAboveImeAndReturnsToSafeEdge() {
        show { dismiss -> CatalogModelEditorDialog(CatalogModelEdit("model", "模型"), true, true, {}, dismiss) }
        assertFloating("catalog-model-dialog", "dialog-confirm")
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
        assertFloating("catalog-model-dialog", "dialog-confirm", ime = true,
            originalWindowBottom = originalBottom)
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
            val safeInset = insets?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())?.bottom ?: 0
            val bottomMargin = with(sheet.layoutInfo.density) { 12.dp.toPx() }
            val restored = view.isAttachedToWindow && insets != null &&
                !insets.isVisible(WindowInsetsCompat.Type.ime()) &&
                insets.getInsets(WindowInsetsCompat.Type.ime()).bottom == 0 &&
                kotlin.math.abs(location[1] + view.rootView.height - originalBottom) <= 2f &&
                kotlin.math.abs(bottom - (originalBottom - safeInset - bottomMargin)) <= 2f
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
        assertFloating("catalog-model-dialog", "dialog-confirm")
        close("catalog-model-dialog")
        assertEquals(0, fixture.opens.get())
    }
}
