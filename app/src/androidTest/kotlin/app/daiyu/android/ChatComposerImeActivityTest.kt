package app.daiyu.android

import androidx.compose.ui.semantics.getOrNull

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import app.daiyu.android.ui.components.hideChatInputIme
import app.daiyu.android.ui.components.clearManagedInputFocus
import app.daiyu.android.ui.screens.chat.conversation.ChatScreenActions
import app.daiyu.android.ui.screens.chat.conversation.ChatScreenState
import app.daiyu.android.ui.screens.chat.conversation.ComposerPhase
import app.daiyu.android.ui.screens.chat.conversation.ComposerUi
import app.daiyu.android.ui.screens.chat.conversation.DaiyuChatScreen
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.ui.app.AgentAppTheme
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

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    private fun rawFrame(): Frame {
        val surface = node("chat-composer-surface").fetchSemanticsNode()
        val header = node("chat-top-bar").fetchSemanticsNode()
        val view = (surface.root as ViewRootForTest).view
        var result: Frame? = null
        mainThread {
            val origin = IntArray(2)
            view.rootView.getLocationOnScreen(origin)
            result = Frame(origin[1] + view.rootView.height,
                ViewCompat.getRootWindowInsets(view)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0,
                surface.positionOnScreen.y + surface.size.height,
                header.positionOnScreen.y + header.size.height)
        }
        return checkNotNull(result)
    }

    private fun assertBackgroundDraftDoesNotOwnFocus() {
        node("chat-draft").assert(androidx.compose.ui.test.SemanticsMatcher(
            "Background draft is unfocused, or disabled without focus semantics") {
            when (it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Focused)) {
                true -> false
                false -> true
                null -> it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
            }
        })
    }

    fun testDrawerSearchImeDoesNotLiftBackgroundComposer() {
        dismissIme()
        val draft = " 侧栏不改聊天草稿 "
        setDraft(draft)
        val database = fixture.database.exportBackup()
        repeat(2) {
            ui.onNodeWithContentDescription("打开侧边栏").performClick()
            node("conversation-drawer").assertIsDisplayed()
            assertBackgroundDraftDoesNotOwnFocus()
            val baseline = settledFrame(visible = false)
            node("drawer-search").performTouchInput { click() }
            awaitImeFrame(node("drawer-search-input"), visible = true)
            node("drawer-search-input").assertIsFocused()
            assertBackgroundDraftDoesNotOwnFocus()
            node("drawer-search-input").performTextReplacement("查询")
            node("drawer-search-input").assertEditableText("查询")
            node("drawer-search-input").assertIsFocused()
            val searching = rawFrame()
            assertTrue(searching.imeBottom > 0)
            assertEquals(baseline.composerBottom, searching.composerBottom, 2f)
            assertEquals(baseline.headerBottom, searching.headerBottom, 2f)
            assertBackgroundDraftDoesNotOwnFocus()
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            awaitImeFrame(node("drawer-search-input"), visible = false)
            assertEquals(baseline.composerBottom, rawFrame().composerBottom, 2f)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            awaitControl("drawer closed") {
                MainActivity::class.java.getDeclaredMethod("getSidebarVisible").apply { isAccessible = true }
                    .invoke(activity) == false
            }
            node("chat-draft").performTouchInput {
                advanceEventTime(android.view.ViewConfiguration.getDoubleTapTimeout().toLong() + 1)
                click()
            }
            awaitImeFrame(node("chat-draft"), visible = true)
            settledFrame(visible = true, before = baseline)
            assertDraft(draft)
            dismissIme()
            settledFrame(visible = false, before = baseline)
        }
        assertEquals(database, fixture.database.exportBackup())
        assertEquals(0, fixture.opens.get())
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    fun testRepeatedImeOpeningIncludesIntermediateComposerFrames() {
        // API26 has no platform insets-animation dispatch; terminal geometry is covered separately.
        if (Build.VERSION.SDK_INT < 30) return
        fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText().trim() }
        val scales = listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")
            .associateWith { shell("settings get global $it") }
        val anchorField = MainActivity::class.java.getDeclaredField("floatingCallAnchor").apply { isAccessible = true }
        try {
            scales.keys.forEach { shell("settings put global $it 1") }
            repeat(2) {
                dismissIme()
                val before = settledFrame(visible = false)
                val surface = node("chat-composer-surface").fetchSemanticsNode()
                val header = node("chat-top-bar").fetchSemanticsNode()
                val view = (surface.root as ViewRootForTest).view
                var surfaceTopOffset = 0f
                mainThread {
                    val anchor = checkNotNull(anchorField.get(activity) as?
                        app.daiyu.android.ui.screens.chat.conversation.DoubaoCallAnchor)
                    surfaceTopOffset = surface.positionOnScreen.y - anchor.composerTop
                }
                val samples = mutableListOf<Pair<Float, Float>>()
                val anchorSamples = mutableListOf<Pair<Float, Float>>()
                val imeSamples = mutableListOf<app.daiyu.android.ui.screens.chat.conversation.DoubaoCallImeInsets>()
                val listener = android.view.ViewTreeObserver.OnPreDrawListener {
                    if (surface.layoutInfo.isPlaced && header.layoutInfo.isPlaced && samples.size < 240) {
                        samples += (surface.positionOnScreen.y + surface.size.height) to
                            (header.positionOnScreen.y + header.size.height)
                        val anchor = anchorField.get(activity) as? app.daiyu.android.ui.screens.chat.conversation.DoubaoCallAnchor
                        anchorSamples += (surface.positionOnScreen.y - surfaceTopOffset) to (anchor?.composerTop ?: Float.NaN)
                        anchor?.imeInsets?.let { imeSamples += it }
                    }
                    true
                }
                mainThread { view.viewTreeObserver.addOnPreDrawListener(listener) }
                val shown: Frame
                try {
                    node("chat-draft").performTouchInput {
                        advanceEventTime(android.view.ViewConfiguration.getDoubleTapTimeout().toLong() + 1)
                        click()
                    }
                    awaitImeFrame(node("chat-draft"), visible = true)
                    shown = settledFrame(visible = true, before = before)
                } finally {
                    mainThread { view.viewTreeObserver.removeOnPreDrawListener(listener) }
                }
                val frames = samples.toList()
                assertTrue("Opening must move the composer", before.composerBottom - shown.composerBottom > 48f)
                assertTrue("Actual intermediate composer motion missing: $frames", frames.any {
                    it.first < before.composerBottom - 2f && it.first > shown.composerBottom + 2f
                })
                frames.forEach { assertEquals(before.headerBottom, it.second, 2f) }
                assertTrue("opening forwards actual IME animation endpoints", imeSamples.any { it.target > it.source })
                anchorSamples.forEach { (composerTop, anchorTop) ->
                    assertEquals("opening anchor must come from this drawn composer frame", composerTop, anchorTop, 2f)
                }
                mainThread {
                    samples.clear()
                    anchorSamples.clear()
                    imeSamples.clear()
                    view.viewTreeObserver.addOnPreDrawListener(listener)
                }
                try {
                    dismissIme()
                    settledFrame(visible = false, before = before)
                } finally {
                    mainThread { view.viewTreeObserver.removeOnPreDrawListener(listener) }
                }
                val closingFrames = samples.toList()
                assertTrue("Actual intermediate dismissal motion missing: $closingFrames", closingFrames.any {
                    it.first > shown.composerBottom + 2f && it.first < before.composerBottom - 2f
                })
                assertTrue("dismissal must produce anchor samples", anchorSamples.size > 2)
                assertTrue("dismissal forwards actual IME animation endpoints", imeSamples.any { it.source > it.target })
                assertTrue("dismissal forwards intermediate IME occlusion", imeSamples.any {
                    it.bottom > it.target && it.bottom < it.source
                })
                anchorSamples.forEach { (composerTop, anchorTop) ->
                    assertEquals("dismissal anchor must not jump ahead or lag behind the composer", composerTop, anchorTop, 2f)
                }
                anchorSamples.zipWithNext().forEach { (previous, current) ->
                    assertEquals("dismissal uses the composer's actual per-frame displacement",
                        current.first - previous.first, current.second - previous.second, 2f)
                }
            }
        } finally {
            scales.forEach { (key, value) ->
                shell(if (value == "null") "settings delete global $key" else "settings put global $key $value")
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
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            settledFrame(visible = false, before = before)
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
        node("chat-draft").assertIsFocused()
        val returned = settledFrame(visible = !hideBeforeLeaving, before = before)
        node("chat-draft").assertIsFocused()
        assertEquals(before.headerBottom, returned.headerBottom, 2f)
        assertDraft(draft)
        assertSelection(1, 4)
        assertHostSelection(1, 4)
        assertEquals(database, fixture.database.exportBackup())
        assertEquals(0, fixture.opens.get())
    }

    fun testReturningFromHomePreservesVisibleKeyboardAndSelection() {
        repeat(2) { keyboardBackgroundRoundTrip(hideBeforeLeaving = false) }
    }

    fun testReturningFromHomePreservesHiddenFocusedKeyboard() {
        repeat(2) { keyboardBackgroundRoundTrip(hideBeforeLeaving = true) }
    }

    fun testRealPauseWithoutStopPreservesDraftAndSelection() {
        dismissIme()
        setDraft(" 暂时遮盖的草稿 ")
        node("chat-draft").performTouchInput {
            advanceEventTime(android.view.ViewConfiguration.getDoubleTapTimeout().toLong() + 1)
            click()
        }
        awaitImeFrame(node("chat-draft"), visible = true)
        setSelection(1, 4, relativeToOriginalText = true)
        node("chat-draft").assertIsFocused()
        assertDraft(" 暂时遮盖的草稿 ")
        assertHostSelection(1, 4)
        val database = fixture.database.exportBackup()
        realPauseResume(activity)
        // The translucent cover also takes window focus; only draft/selection must survive it.
        assertDraft(" 暂时遮盖的草稿 ")
        assertSelection(1, 4)
        assertHostSelection(1, 4)
        assertEquals(database, fixture.database.exportBackup())
        assertEquals(0, fixture.opens.get())
    }

    fun testPauseWithoutStopDoesNotClearDraftFocusOrSelection() {
        dismissIme()
        val draft = " 仅生命周期暂停的草稿 "
        val database = fixture.database.exportBackup()
        val state = androidx.compose.runtime.mutableStateOf(ComposerUi(text = draft))
        val owner = object : androidx.lifecycle.LifecycleOwner {
            val registry = androidx.lifecycle.LifecycleRegistry(this)
            override val lifecycle: androidx.lifecycle.Lifecycle get() = registry
        }
        mainThread {
            owner.registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED
            activity.setContent {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.lifecycle.compose.LocalLifecycleOwner provides owner) {
                    AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                        DaiyuChatScreen(ChatScreenState("生命周期焦点回归", composer = state.value),
                            ChatScreenActions(onSelectionChange = { start, end ->
                                state.value = state.value.copy(selectionStart = start, selectionEnd = end)
                            }, onProjectedDraftEdit = { expected, next, start, end ->
                                if (state.value.text != expected) false else {
                                    state.value = state.value.copy(text = next,
                                        selectionStart = start, selectionEnd = end)
                                    true
                                }
                            }))
                    }
                }
            }
        }
        ui.waitForIdle()
        node("chat-draft").performTouchInput {
            advanceEventTime(android.view.ViewConfiguration.getDoubleTapTimeout().toLong() + 1)
            click()
        }
        awaitImeFrame(node("chat-draft"), visible = true)
        setSelection(1, 4, relativeToOriginalText = true)
        fun assertRetainedDraft() {
            assertDraft(draft)
            assertSelection(1, 4)
            mainThread {
                assertEquals(draft, state.value.text)
                assertEquals(1, state.value.selectionStart)
                assertEquals(4, state.value.selectionEnd)
                assertTrue(activity.window.decorView.hasWindowFocus())
                assertTrue(activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
            }
        }
        node("chat-draft").assertIsFocused()
        assertRetainedDraft()
        mainThread { owner.registry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_PAUSE) }
        node("chat-draft").assertIsFocused()
        assertRetainedDraft()
        mainThread { owner.registry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_RESUME) }
        node("chat-draft").assertIsFocused()
        assertRetainedDraft()
        mainThread { owner.registry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_PAUSE) }
        node("chat-draft").assertIsFocused()
        mainThread { owner.registry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_STOP) }
        node("chat-draft").assertIsFocused()
        assertRetainedDraft()
        mainThread {
            owner.registry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_START)
            owner.registry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_RESUME)
        }
        node("chat-draft").assertIsFocused()
        assertRetainedDraft()
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
                first.setSelection(5, 1)
                assertEquals(5, first.selectionStart)
                assertEquals(1, first.selectionEnd)
                android.util.Log.d("DaiyuTestStep", "transfer-before selection=${first.selectionStart},${first.selectionEnd}")
                hideChatInputIme(focus, keyboard, first)
                android.util.Log.d("DaiyuTestStep", "transfer-return selection=${first.selectionStart},${first.selectionEnd}")
                assertEquals(5, first.selectionStart)
                assertEquals(1, first.selectionEnd)
                assertEquals(1, clearCalls)
                assertEquals(1, transfers)
                assertEquals("Outer hide must respect the managed focus-transfer veto", 0, keyboardHideCalls)
                assertFalse(first.hasFocus())
                assertTrue(second.hasFocus())
                assertSame(second, activity.window.currentFocus)
                assertSame(second, activity.window.decorView.findFocus())
                assertFalse(activity.window.decorView.isFocused)
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
                android.util.Log.d("DaiyuTestStep", "transfer-stable selection=${first.selectionStart},${first.selectionEnd} cursor=${first.isCursorVisible}")
                assertEquals(0, keyboardHideCalls)
                assertSame(second, activity.window.currentFocus)
                assertEquals("保留第一个编辑器", first.text.toString())
                assertEquals(5, first.selectionStart)
                assertEquals(1, first.selectionEnd)
                assertEquals("继续编辑第二项", second.text.toString())
            }
            // Exercise the native managed-entry path with an actual outside touch too.
            mainThread {
                first.onFocusChangeListener = null
                assertTrue(first.requestFocus())
                first.setSelection(5, 1)
                transfers = 0
                first.onFocusChangeListener = android.view.View.OnFocusChangeListener { _, focused ->
                    if (!focused) {
                        transfers++
                        assertTrue(second.requestFocus())
                    }
                }
            }
            val outside = awaitStableWindowFrame(first) {
                val location = IntArray(2)
                first.rootView.getLocationOnScreen(location)
                val local = IntArray(2)
                first.getLocationOnScreen(local)
                androidx.compose.ui.geometry.Offset(location[0] + 4f, local[1] + first.height / 2f)
            }
            val down = android.os.SystemClock.uptimeMillis()
            for ((action, elapsed) in listOf(android.view.MotionEvent.ACTION_DOWN to 0L,
                                           android.view.MotionEvent.ACTION_UP to 40L)) {
                val event = android.view.MotionEvent.obtain(down, down + elapsed, action, outside.x, outside.y, 0)
                event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
            }
            ui.waitForIdle()
            mainThread {
                assertEquals(1, transfers)
                assertSame(second, activity.window.currentFocus)
                assertSame(second, activity.window.decorView.findFocus())
                assertFalse(activity.window.decorView.isFocused)
                assertEquals(5, first.selectionStart)
                assertEquals(1, first.selectionEnd)
                assertEquals(0, keyboardHideCalls)
            }
            awaitImeFrame(node("ime-focus-transfer-fixture"), visible = true)
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

    fun testManagedAndFallbackDismissDispatchHideExactlyOnceAfterClear() {
        val events = mutableListOf<String>()
        lateinit var field: android.widget.EditText
        mainThread {
            field = android.widget.EditText(activity).apply {
                setText("恰一次隐藏")
                manageInput(activity.window)
            }
            activity.setContent {
                androidx.compose.ui.viewinterop.AndroidView(modifier = Modifier.fillMaxSize().testTag("single-hide-field"),
                    factory = { field })
            }
        }
        awaitNodeWindowFocus(node("single-hide-field"))
        awaitStableWindowFrame(field) { field.windowToken?.takeIf { field.isAttachedToWindow } }
        mainThread {
            assertTrue(field.requestFocus())
            assertTrue(clearManagedInputFocus(field, hideIme = { events += "hide" }) {
                events += "clear"
                field.clearFocus()
            })
            assertFalse(field.hasFocus())
            assertEquals(listOf("clear", "hide"), events)
            events.clear()
            val unattached = android.view.View(activity)
            assertTrue(clearManagedInputFocus(unattached, hideIme = { events += "hide" }) { events += "clear" })
            assertEquals(listOf("clear", "hide"), events)
        }
    }

    fun testSendAndAppendReleaseImeBeforeDispatchAndPreserveDraft() {
        val database = fixture.database.exportBackup()
        val draft = (1..6).joinToString("\n") { "保留消息 $it" }
        for (phase in listOf(ComposerPhase.IDLE, ComposerPhase.RUNNING)) {
            dismissIme()
            val dismissEvents = mutableListOf<String>()
            var sends = 0
            var appends = 0
            var observedDraft = ""
            mainThread {
                activity.setContent {
                    AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                        val realFocus = androidx.compose.ui.platform.LocalFocusManager.current
                        val realKeyboard = checkNotNull(androidx.compose.ui.platform.LocalSoftwareKeyboardController.current)
                        val focus = androidx.compose.runtime.remember(realFocus, phase) {
                            object : androidx.compose.ui.focus.FocusManager by realFocus {
                                override fun clearFocus(force: Boolean) {
                                    realFocus.clearFocus(force)
                                    dismissEvents += "clear"
                                }
                            }
                        }
                        val keyboard = androidx.compose.runtime.remember(realKeyboard, phase) {
                            object : androidx.compose.ui.platform.SoftwareKeyboardController by realKeyboard {
                                override fun hide() { dismissEvents += "hide"; realKeyboard.hide() }
                            }
                        }
                        val actions = ChatScreenActions(
                            onSend = { assertEquals(listOf("clear", "hide"), dismissEvents); observedDraft = it; sends++ },
                            onSupplement = { assertEquals(listOf("clear", "hide"), dismissEvents); observedDraft = it; appends++ })
                        androidx.compose.runtime.CompositionLocalProvider(
                            androidx.compose.ui.platform.LocalFocusManager provides focus,
                            androidx.compose.ui.platform.LocalSoftwareKeyboardController provides keyboard) {
                            DaiyuChatScreen(ChatScreenState("IME动作回归", composer = ComposerUi(
                                text = draft, phase = phase, supplementAvailable = true)), actions)
                        }
                    }
                }
            }
            ui.waitForIdle()
            val before = settledFrame(visible = false)
            node("chat-draft").performTouchInput {
                advanceEventTime(android.view.ViewConfiguration.getDoubleTapTimeout().toLong() + 1)
                click()
            }
            awaitImeFrame(node("chat-draft"), visible = true)
            node("chat-draft").assertIsFocused()
            settledFrame(visible = true, before = before)
            mainThread { dismissEvents.clear() }
            node("chat-send").performTouchInput { click() }
            node("chat-draft").assertIsNotFocused()
            settledFrame(visible = false, before = before)
            assertEquals(draft, observedDraft)
            assertEquals(if (phase == ComposerPhase.IDLE) 1 else 0, sends)
            assertEquals(if (phase == ComposerPhase.RUNNING) 1 else 0, appends)
            node("chat-draft").assertTextEquals(draft)
        }
        assertEquals(database, fixture.database.exportBackup())
        assertEquals(0, fixture.opens.get())
    }

}
