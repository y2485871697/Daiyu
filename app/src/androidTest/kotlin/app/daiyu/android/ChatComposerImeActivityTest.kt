package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import app.daiyu.android.ui.components.hideChatInputIme
import app.daiyu.android.ui.components.manageInput
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.KeyEvent
import android.view.WindowManager
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.abs

class ChatComposerImeActivityTest : WiredActivityTestCase() {
    private data class Frame(val decorBottom: Int, val imeBottom: Int,
        val composerBottom: Float, val headerBottom: Float)

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    private fun settledFrame(visible: Boolean, before: Frame? = null): Frame {
        val surface = node("chat-composer-surface").fetchSemanticsNode()
        val header = node("chat-top-bar").fetchSemanticsNode()
        assertSame(surface.root, header.root)
        val view = (surface.root as ViewRootForTest).view
        val decor = view.rootView
        val token = view.windowToken
        val density = activity.resources.displayMetrics.density
        return awaitStableWindowFrame(view) {
            val insets = ViewCompat.getRootWindowInsets(view)
            if (insets == null || view.windowToken != token || !surface.layoutInfo.isPlaced ||
                !header.layoutInfo.isPlaced || insets.isVisible(WindowInsetsCompat.Type.ime()) != visible) null
            else {
                val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                val location = IntArray(2)
                decor.getLocationOnScreen(location)
                fun bottom(node: androidx.compose.ui.semantics.SemanticsNode) =
                    node.positionOnScreen.y + node.size.height
                val frame = Frame(location[1] + decor.height, imeBottom, bottom(surface), bottom(header))
                val valid = if (visible) {
                    val keyboardTop = checkNotNull(before).decorBottom - imeBottom
                    val gap = keyboardTop - frame.composerBottom
                    imeBottom > 0 && gap >= -2f && gap <= 32f * density
                } else imeBottom == 0 && (before == null ||
                    abs(frame.composerBottom - before.composerBottom) <= 2f)
                if (valid) frame else null
            }
        }
    }

    private fun assertRealApi26ImeBinding() {
        if (Build.VERSION.SDK_INT != 26) return
        val expected = "jp.co.omronsoft.openwnn/.OpenWnnJAJP"
        var state = ""
        try {
            ui.waitUntil(5_000) {
                state = ParcelFileDescriptor.AutoCloseInputStream(
                    instrumentation.uiAutomation.executeShellCommand("dumpsys -t 2 input_method")
                ).bufferedReader().use { it.readText() }
                state.contains("mCurId=$expected mHaveConnect=true mBoundToMethod=true") &&
                    state.contains("mInputShown=true") && state.contains("packageName=app.daiyu.android ")
            }
        } finally {
            android.util.Log.i("DaiyuImeFixture", state.lineSequence().filter {
                it.contains("mCurId=") || it.contains("mCurMethodId=") ||
                    it.contains("mInputShown=") || it.contains("mServedView=") ||
                    it.contains("packageName=app.daiyu.android ")
            }.joinToString("\n"))
        }
    }

    fun testKeyboardKeepsComposerAboveImeAndDoesNotPanHeader() {
        assertEquals(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
            activity.window.attributes.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST)
        dismissIme()
        val emptyWindow = settledFrame(visible = false)
        setDraft("键盘位置回归草稿")
        // Text replacement can keep focus without showing a previously hidden IME.
        node("chat-draft").performTouchInput {
            advanceEventTime(android.view.ViewConfiguration.getDoubleTapTimeout().toLong() + 1)
            click()
        }
        awaitImeFrame(node("chat-draft"), visible = true)
        dismissIme()
        val before = settledFrame(visible = false, before = emptyWindow)
        val database = fixture.database.exportBackup()
        repeat(2) {
            // Native IME frames do not advance the injected pointer clock past double-tap time.
            node("chat-draft").performTouchInput {
                advanceEventTime(android.view.ViewConfiguration.getDoubleTapTimeout().toLong() + 1)
                click()
            }
            node("chat-draft").assertIsFocused()
            assertRealApi26ImeBinding()
            val shown = settledFrame(visible = true, before = before)
            val keyboardTop = before.decorBottom - shown.imeBottom
            val bottom = shown.composerBottom
            val density = activity.resources.displayMetrics.density
            val gap = keyboardTop - bottom
            android.util.Log.d("DaiyuImeGeometry", "round=$it before=$before shown=$shown")
            assertTrue("Composer must sit just above IME, gap=$gap", gap >= -2f && gap <= 32f * density)
            assertTrue("Keyboard must lift composer: before=$before shown=$shown", bottom < before.composerBottom - 48f * density)
            assertEquals("Header must not pan off-screen", before.headerBottom, shown.headerBottom, 2f)
            node("chat-draft").assertIsDisplayed()
            if (it == 0) {
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            } else dismissIme()
            val restored = settledFrame(visible = false, before = before)
            assertEquals("Composer returns after IME hides", before.composerBottom, restored.composerBottom, 2f)
            assertFalse(activity.isFinishing)
            assertFalse(activity.isDestroyed)
            node("chat-top-bar").assertIsDisplayed()
            assertDraft("键盘位置回归草稿")
            assertEquals(database, fixture.database.exportBackup())
            assertEquals(0, fixture.opens.get())
        }
    }

    private fun assertHostSelection(start: Int, end: Int) {
        mainThread {
            fun endpoint(name: String) = MainActivity::class.java.getDeclaredField(name)
                .apply { isAccessible = true }.getInt(activity)
            assertEquals(start, endpoint("selectionStart"))
            assertEquals(end, endpoint("selectionEnd"))
        }
    }

    private fun keyboardBackgroundRoundTrip(hideBeforeLeaving: Boolean) {
        dismissIme()
        val before = settledFrame(visible = false)
        val draft = " 后台返回\n保留草稿 "
        setDraft(draft)
        node("chat-draft").performTouchInput {
            advanceEventTime(android.view.ViewConfiguration.getDoubleTapTimeout().toLong() + 1)
            click()
        }
        awaitImeFrame(node("chat-draft"), visible = true)
        setSelection(1, 4, relativeToOriginalText = true)
        assertHostSelection(1, 4)
        if (hideBeforeLeaving) {
            dismissIme()
            settledFrame(visible = false)
        }
        node("chat-draft").assertIsFocused()
        val database = fixture.database.exportBackup()
        val original = activity
        val task = original.taskId
        val stopped = java.util.concurrent.CountDownLatch(1)
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) stopped.countDown()
        }
        mainThread { original.lifecycle.addObserver(observer) }
        try {
            assertTrue(instrumentation.uiAutomation.performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME))
            assertTrue("HOME must stop the original Activity", stopped.await(5, java.util.concurrent.TimeUnit.SECONDS))
        } finally {
            instrumentation.runOnMainSync { original.lifecycle.removeObserver(observer) }
            restoreOriginalHost(original)
        }
        awaitHostWindowFocus(original)
        assertSame("HOME must retain the original host", original, activity)
        assertFalse(original.isDestroyed)
        assertEquals(task, original.taskId)
        ui.waitUntil(5_000) {
            ui.onNodeWithTag("chat-draft").fetchSemanticsNode().config[SemanticsProperties.Focused] == false
        }
        node("chat-draft").assertIsNotFocused()
        // No dismissIme here: a return-triggered keyboard would be a product failure.
        val returned = settledFrame(visible = false, before = before)
        node("chat-draft").assertIsNotFocused()
        assertEquals(before.headerBottom, returned.headerBottom, 2f)
        assertDraft(draft)
        assertSelection(1, 4)
        assertHostSelection(1, 4)
        assertEquals(database, fixture.database.exportBackup())
        assertEquals(0, fixture.opens.get())
        node("chat-draft").performTouchInput {
            advanceEventTime(android.view.ViewConfiguration.getDoubleTapTimeout().toLong() + 1)
            click()
        }
        node("chat-draft").assertIsFocused()
        awaitImeFrame(node("chat-draft"), visible = true)
        val reopened = settledFrame(visible = true, before = returned)
        assertTrue(reopened.composerBottom < returned.composerBottom - 48f * activity.resources.displayMetrics.density)
        assertEquals(returned.headerBottom, reopened.headerBottom, 2f)
        dismissIme()
        settledFrame(visible = false, before = returned)
        assertDraft(draft)
        assertEquals(database, fixture.database.exportBackup())
        assertEquals(0, fixture.opens.get())
    }

    fun testReturningFromHomeDoesNotReopenVisibleKeyboardOrLoseSelection() {
        repeat(2) { keyboardBackgroundRoundTrip(hideBeforeLeaving = false) }
    }

    fun testReturningFromHomeDoesNotReopenHiddenFocusedKeyboard() {
        repeat(2) { keyboardBackgroundRoundTrip(hideBeforeLeaving = true) }
    }

    fun testPauseWithoutStopDoesNotClearDraftFocusOrSelection() {
        dismissIme()
        setDraft(" 暂时遮盖的草稿 ")
        node("chat-draft").performTouchInput {
            advanceEventTime(android.view.ViewConfiguration.getDoubleTapTimeout().toLong() + 1)
            click()
        }
        awaitImeFrame(node("chat-draft"), visible = true)
        setSelection(1, 4, relativeToOriginalText = true)
        val database = fixture.database.exportBackup()
        realPauseResume(activity)
        node("chat-draft").assertIsFocused()
        assertDraft(" 暂时遮盖的草稿 ")
        assertSelection(1, 4)
        assertHostSelection(1, 4)
        assertEquals(database, fixture.database.exportBackup())
        assertEquals(0, fixture.opens.get())
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    fun testDismissDoesNotHideImeWhenBlurListenerTransfersToAnotherEditor() {
        dismissIme()
        lateinit var first: android.widget.EditText
        lateinit var second: android.widget.EditText
        var transfers = 0
        var clearCalls = 0
        var keyboardHideCalls = 0
        val database = fixture.database.exportBackup()
        val imm = checkNotNull(activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java))
        mainThread {
            first = android.widget.EditText(activity).apply {
                setText("保留第一个编辑器")
                setSingleLine(true)
                manageInput(activity.window)
            }
            second = android.widget.EditText(activity).apply {
                setText("继续编辑第二项")
                setSingleLine(true)
                manageInput(activity.window)
            }
            activity.setContent {
                androidx.compose.ui.viewinterop.AndroidView(
                    modifier = Modifier.fillMaxSize().testTag("ime-focus-transfer-fixture"),
                    factory = {
                        android.widget.LinearLayout(activity).apply {
                            orientation = android.widget.LinearLayout.VERTICAL
                            val padding = (24 * resources.displayMetrics.density).toInt()
                            setPadding(padding, padding, padding, padding)
                            addView(first)
                            addView(second)
                        }
                    })
            }
        }
        awaitNodeWindowFocus(node("ime-focus-transfer-fixture"))
        mainThread {
            assertTrue(first.requestFocus())
            imm.showSoftInput(first, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
        awaitImeFrame(node("ime-focus-transfer-fixture"), visible = true)
        mainThread {
            assertSame(first, activity.window.currentFocus)
            first.setSelection(5, 1)
            first.onFocusChangeListener = android.view.View.OnFocusChangeListener { _, focused ->
                if (!focused) {
                    transfers++
                    assertTrue(second.requestFocus())
                }
            }
        }
        val focus = object : androidx.compose.ui.focus.FocusManager {
            override fun clearFocus(force: Boolean) {
                clearCalls++
                assertTrue(force)
                first.clearFocus()
            }
            override fun moveFocus(focusDirection: androidx.compose.ui.focus.FocusDirection): Boolean =
                error("Dismissal must not use moveFocus")
        }
        val keyboard = object : androidx.compose.ui.platform.SoftwareKeyboardController {
            override fun show() = error("Dismissal must not request IME")
            override fun hide() {
                keyboardHideCalls++
                imm.hideSoftInputFromWindow(first.windowToken, 0)
            }
        }
        try {
            mainThread {
                hideChatInputIme(focus, keyboard, first)
                assertEquals(1, clearCalls)
                assertEquals(1, transfers)
                assertEquals("Outer hide must respect the managed focus-transfer veto", 0, keyboardHideCalls)
                assertFalse(first.hasFocus())
                assertTrue(second.hasFocus())
                assertSame(second, activity.window.currentFocus)
            }
            awaitStableWindowFrame(second) {
                val insets = ViewCompat.getRootWindowInsets(second)
                val bottom = insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom
                if (second.isFocused && activity.window.currentFocus === second &&
                    insets?.isVisible(WindowInsetsCompat.Type.ime()) == true && bottom != null && bottom > 0)
                    bottom else null
            }
            awaitImeFrame(node("ime-focus-transfer-fixture"), visible = true)
            mainThread {
                assertEquals(0, keyboardHideCalls)
                assertSame(second, activity.window.currentFocus)
                assertEquals("保留第一个编辑器", first.text.toString())
                assertEquals(5, first.selectionStart)
                assertEquals(1, first.selectionEnd)
                assertEquals("继续编辑第二项", second.text.toString())
            }
            assertEquals(database, fixture.database.exportBackup())
            assertEquals(0, fixture.opens.get())
        } finally {
            mainThread { first.onFocusChangeListener = null }
        }
    }

    fun testOutsideBlankAndTitleTapsReleaseDraftAndImeWithoutChangingDraft() {
        dismissIme()
        val before = settledFrame(visible = false)
        val draft = " 保留草稿😀\n不发送 "
        setDraft(draft)
        val database = fixture.database.exportBackup()
        for (target in listOf("chat-messages", "chat-title")) {
            node("chat-draft").performTouchInput {
                advanceEventTime(android.view.ViewConfiguration.getDoubleTapTimeout().toLong() + 1)
                click()
            }
            node("chat-draft").assertIsFocused()
            awaitImeFrame(node("chat-draft"), visible = true)
            setSelection(8, 2, relativeToOriginalText = true)
            assertHostSelection(8, 2)
            node(target).performTouchInput {
                if (target == "chat-messages") click(androidx.compose.ui.geometry.Offset(8f, 8f)) else click()
            }
            // The product tap must hide IME; the test does not dismiss it here.
            node("chat-draft").assertIsNotFocused()
            val hidden = settledFrame(visible = false, before = before)
            assertEquals(before.headerBottom, hidden.headerBottom, 2f)
            assertSelection(8, 2)
            assertHostSelection(8, 2)
            assertDraft(draft)
            assertEquals(database, fixture.database.exportBackup())
            assertEquals(0, fixture.opens.get())
        }
        node("chat-draft").performTouchInput {
            advanceEventTime(android.view.ViewConfiguration.getDoubleTapTimeout().toLong() + 1)
            click()
        }
        node("chat-draft").assertIsFocused()
        awaitImeFrame(node("chat-draft"), visible = true)
        val selection = node("chat-draft").fetchSemanticsNode().config[
            androidx.compose.ui.semantics.SemanticsProperties.TextSelectionRange]
        node("chat-draft").performTextInput("续写")
        assertDraft(draft.replaceRange(selection.min, selection.max, "续写"))
        assertEquals(0, fixture.opens.get())
    }

    fun testDraftAndComposerBlankTapsKeepEditingButTopButtonStillWorks() {
        dismissIme()
        setDraft(" 点输入栏不收起键盘 ")
        node("chat-draft").performTouchInput {
            advanceEventTime(android.view.ViewConfiguration.getDoubleTapTimeout().toLong() + 1)
            click()
        }
        awaitImeFrame(node("chat-draft"), visible = true)
        val database = fixture.database.exportBackup()
        node("chat-draft").performTouchInput {
            advanceEventTime(android.view.ViewConfiguration.getDoubleTapTimeout().toLong() + 1)
            click()
        }
        node("chat-draft").assertIsFocused()
        awaitImeFrame(node("chat-draft"), visible = true)
        node("chat-composer-divider").performTouchInput { click() }
        node("chat-draft").assertIsFocused()
        awaitImeFrame(node("chat-draft"), visible = true)
        // A real outside button retains its ordinary action while releasing the editor.
        ui.onNodeWithContentDescription("更多").performTouchInput { click() }
        node("chat-draft").assertIsNotFocused()
        awaitImeFrame(node("chat-draft"), visible = false)
        node("chat-menu:NEW_CHAT").assertIsDisplayed()
        assertDraft(" 点输入栏不收起键盘 ")
        assertEquals(database, fixture.database.exportBackup())
        assertEquals(0, fixture.opens.get())
    }

    fun testHiddenImeStopsCaretDrawingEvenWhenDraftRemainsFocused() {
        fun draftPixels(): IntArray {
            val image = captureScreenNode(node("chat-draft")).toPixelMap()
            return IntArray(image.width * image.height) { index ->
                image[index % image.width, index / image.width].toArgb()
            }
        }
        dismissIme()
        val draft = "收起键盘后不闪烁"
        setDraft(draft)
        node("chat-draft").performTouchInput {
            advanceEventTime(android.view.ViewConfiguration.getDoubleTapTimeout().toLong() + 1)
            click()
        }
        awaitImeFrame(node("chat-draft"), visible = true)
        // Move the range before collapsing: tapping beyond a short draft may already select its end.
        setSelection(0, draft.length, relativeToOriginalText = true)
        setSelection(draft.length, draft.length, relativeToOriginalText = true)
        assertHostSelection(draft.length, draft.length)
        val visible = draftPixels()
        var cursorChanged = false
        repeat(3) {
            ui.mainClock.advanceTimeBy(550)
            if (!visible.contentEquals(draftPixels())) cursorChanged = true
        }
        assertTrue("Visible IME must expose cursor blinking to the screenshot test", cursorChanged)
        val database = fixture.database.exportBackup()
        dismissIme()
        awaitImeFrame(node("chat-draft"), visible = false)
        settledFrame(visible = false)
        node("chat-draft").assertIsFocused()
        assertSelection(draft.length, draft.length)
        assertHostSelection(draft.length, draft.length)
        val expected = draftPixels()
        repeat(3) {
            ui.mainClock.advanceTimeBy(550)
            node("chat-draft").assertIsFocused()
            awaitImeFrame(node("chat-draft"), visible = false)
            val pixels = draftPixels()
            assertTrue("Hidden IME must not leave a blinking caret, sample=$it", expected.contentEquals(pixels))
        }
        node("chat-title").performTouchInput { click() }
        node("chat-draft").assertIsNotFocused()
        val withoutFocus = draftPixels()
        assertTrue("Hidden IME must draw no caret, including a steady caret", expected.contentEquals(withoutFocus))
        assertSelection(draft.length, draft.length)
        assertHostSelection(draft.length, draft.length)
        assertDraft(draft)
        assertEquals(database, fixture.database.exportBackup())
        assertEquals(0, fixture.opens.get())
    }
}
