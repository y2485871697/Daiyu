package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.dp
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.components.*
import app.daiyu.android.ui.screens.settings.SettingsTextField

class ManagedInputActivityTest : WiredActivityTestCase() {
    private var fieldToolbar: androidx.compose.ui.platform.TextToolbar? = null
    private val cursorlessTag = mutableStateOf<String?>(null)
    private var fieldsInDialog = false
    private fun showFields(dialog: Boolean = false) {
        if (dialog) {
            dismissIme()
            awaitNoImeWindow(activity.window.decorView)
        }
        cursorlessTag.value = null
        fieldsInDialog = dialog
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    val toolbar = androidx.compose.ui.platform.LocalTextToolbar.current
                    androidx.compose.runtime.SideEffect { fieldToolbar = toolbar }
                    val fields: @androidx.compose.runtime.Composable () -> Unit = {
                        var first by remember { mutableStateOf(" 保留草稿😀 ") }
                        var second by remember { mutableStateOf("第二个输入框") }
                        var clicks by remember { mutableStateOf(0) }
                        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp)) {
                            SettingsTextField("第一项", first, { first = it }, tag = "managed-first")
                            OutlinedTextField(second, { second = it },
                                modifier = Modifier.managedInput().testTag("managed-second"),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                                    focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                                    unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                                    focusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                                    unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                                    cursorColor = if (cursorlessTag.value == "managed-second") androidx.compose.ui.graphics.Color.Transparent
                                        else materialImeCursorColor(MaterialTheme.colorScheme.primary),
                                    errorCursorColor = if (cursorlessTag.value == "managed-second") androidx.compose.ui.graphics.Color.Transparent
                                        else materialImeCursorColor(MaterialTheme.colorScheme.error)))
                            Spacer(Modifier.height(48.dp))
                            androidx.compose.material3.Text("外部标题", Modifier.testTag("managed-outside"))
                            app.daiyu.android.ui.haptics.material.TextButton(onClick = { clicks++ },
                                modifier = Modifier.testTag("managed-button")) {
                                androidx.compose.material3.Text("动作次数 $clicks")
                            }
                        }
                    }
                    if (dialog) DaiyuDialog(onDismissRequest = {}) { fields() }
                    else Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                        .systemBarsPadding().imePadding()) { fields() }
                }
            }
        }
    }

    private fun awaitNoImeWindow(view: android.view.View) {
        val automation = instrumentation.uiAutomation
        val oldFlags = automation.serviceInfo.flags
        try {
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            awaitStableWindowFrame(view) {
                val absent = automation.windows.none { window ->
                    val bounds = android.graphics.Rect()
                    window.getBoundsInScreen(bounds)
                    window.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD && !bounds.isEmpty
                }
                absent.takeIf { it }
            }
        } finally {
            automation.serviceInfo = automation.serviceInfo.apply { flags = oldFlags }
        }
    }

    private data class TouchFrame(
        val bounds: androidx.compose.ui.geometry.Rect,
        val windowBounds: androidx.compose.ui.geometry.Rect,
        val windowId: Int,
    )

    private fun stableScreenBounds(view: android.view.View,
        touchBounds: (androidx.compose.ui.geometry.Rect) -> androidx.compose.ui.geometry.Rect = { it },
        readBounds: () -> androidx.compose.ui.geometry.Rect?): androidx.compose.ui.geometry.Rect {
        val automation = instrumentation.uiAutomation
        val oldFlags = automation.serviceInfo.flags
        val token = view.windowToken
        try {
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            var lastSample = "not sampled"
            fun sample(): TouchFrame? {
                lastSample = "view attached=${view.isAttachedToWindow} focused=${view.hasWindowFocus()} " +
                    "layoutRequested=${view.isLayoutRequested} sameToken=${view.windowToken == token}"
                if (!view.isAttachedToWindow || !view.hasWindowFocus() || view.isLayoutRequested ||
                    view.windowToken != token) return null
                val bounds = readBounds() ?: run { lastSample = "target bounds unavailable"; return null }
                lastSample = "target=$bounds"
                if (bounds.width <= 0f || bounds.height <= 0f ||
                    !listOf(bounds.left, bounds.top, bounds.right, bounds.bottom).all { it.isFinite() }) return null
                val node = view.createAccessibilityNodeInfo() ?: return null
                val id = try { node.windowId } finally { node.recycle() }
                val windows = automation.windows
                lastSample = "target=$bounds windowId=$id windows=" + windows.joinToString { other ->
                    "id=${other.id},type=${other.type},layer=${other.layer},focused=${other.isFocused}"
                }
                val window = windows.singleOrNull { it.id == id && it.isFocused } ?: return null
                val nativeBounds = android.graphics.Rect()
                window.getBoundsInScreen(nativeBounds)
                val windowBounds = androidx.compose.ui.geometry.Rect(nativeBounds.left.toFloat(), nativeBounds.top.toFloat(),
                    nativeBounds.right.toFloat(), nativeBounds.bottom.toFloat())
                val contained = windowBounds.left <= bounds.left && windowBounds.top <= bounds.top &&
                    windowBounds.right >= bounds.right && windowBounds.bottom >= bounds.bottom
                val touch = touchBounds(bounds)
                check(touch.width > 0f && touch.height > 0f && bounds.contains(touch.topLeft) && touch.right <= bounds.right && touch.bottom <= bounds.bottom)
                val targetPixels = android.graphics.Rect()
                android.graphics.RectF(touch.left, touch.top, touch.right, touch.bottom).roundOut(targetPixels)
                val occluded = windows.any { other ->
                    if (other.id == id || other.layer <= window.layer) false else {
                        if (android.os.Build.VERSION.SDK_INT >= 30) {
                            val overlay = android.graphics.Region()
                            other.getRegionInScreen(overlay)
                            val region = overlay.toString()
                            overlay.op(targetPixels, android.graphics.Region.Op.INTERSECT)
                            if (!overlay.isEmpty) lastSample += " blocker=${other.id} region=$region intersection=$overlay"
                            !overlay.isEmpty
                        } else {
                            val overlay = android.graphics.Rect()
                            other.getBoundsInScreen(overlay)
                            !overlay.isEmpty && androidx.compose.ui.geometry.Rect(overlay.left.toFloat(), overlay.top.toFloat(),
                                overlay.right.toFloat(), overlay.bottom.toFloat()).overlaps(touch)
                        }
                    }
                }
                lastSample += " native=$windowBounds contained=$contained occluded=$occluded"
                return if (contained && !occluded) TouchFrame(bounds, windowBounds, id) else null
            }
            val deadline = android.os.SystemClock.uptimeMillis() + 5_000
            while (true) {
                val stable = try { awaitStableWindowFrame(view) { sample() } }
                    catch (failure: AssertionError) { throw AssertionError("Touch geometry rejected: $lastSample", failure) }
                var current: TouchFrame? = null
                instrumentation.runOnMainSync { current = sample() }
                if (current == stable) return stable.bounds
                assertTrue("Touch window changed before injection: stable=$stable current=$current",
                    android.os.SystemClock.uptimeMillis() < deadline)
            }
        } finally {
            automation.serviceInfo = automation.serviceInfo.apply { flags = oldFlags }
        }
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    private fun screenBounds(tag: String,
        touchBounds: (androidx.compose.ui.geometry.Rect) -> androidx.compose.ui.geometry.Rect = { it }): androidx.compose.ui.geometry.Rect {
        val interaction = node(tag).scrollToVisible()
        awaitVisualIdle()
        awaitNodeWindowFocus(interaction)
        val target = interaction.fetchSemanticsNode()
        val view = (target.root as androidx.compose.ui.platform.ViewRootForTest).view
        val stable = stableScreenBounds(view, touchBounds) {
            val coordinates = target.layoutInfo.coordinates
            if (!target.layoutInfo.isPlaced || !coordinates.isAttached) null else {
                val screen = IntArray(2)
                val window = IntArray(2)
                view.getLocationOnScreen(screen)
                view.getLocationInWindow(window)
                coordinates.boundsInWindow().translate(
                    androidx.compose.ui.geometry.Offset((screen[0] - window[0]).toFloat(),
                        (screen[1] - window[1]).toFloat()))
            }
        }
        android.util.Log.d("ManagedInputTest", "screen-bounds tag=$tag bounds=$stable token=${view.windowToken}")
        return stable
    }

    private fun injectGesture(points: List<Pair<Long, androidx.compose.ui.geometry.Offset>>) {
        require(points.size >= 2 && points.first().first == 0L)
        val down = android.os.SystemClock.uptimeMillis()
        points.forEachIndexed { index, (elapsed, point) ->
            if (elapsed > 0) ui.waitUntil(elapsed + 5_000) {
                android.os.SystemClock.uptimeMillis() >= down + elapsed
            }
            val action = when (index) {
                0 -> android.view.MotionEvent.ACTION_DOWN
                points.lastIndex -> android.view.MotionEvent.ACTION_UP
                else -> android.view.MotionEvent.ACTION_MOVE
            }
            val event = android.view.MotionEvent.obtain(down, down + elapsed,
                action, point.x, point.y, 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        ui.waitForIdle()
    }

    private fun realTap(tag: String) {
        val center = screenBounds(tag).center
        injectGesture(listOf(0L to center, 40L to center))
    }

    private fun realLongPress(tag: String) {
        val bounds = screenBounds(tag) { full ->
            val x = full.left + full.width * .2f
            androidx.compose.ui.geometry.Rect(x - 1f, full.center.y - 1f, x + 1f, full.center.y + 1f)
        }
        val point = androidx.compose.ui.geometry.Offset(bounds.left + bounds.width * .2f, bounds.center.y)
        injectGesture(listOf(0L to point, (android.view.ViewConfiguration.getLongPressTimeout() + 80L) to point))
    }

    private fun realDrag(tag: String) {
        val bounds = screenBounds(tag) { full ->
            androidx.compose.ui.geometry.Rect(full.left + full.width * .2f - 1f, full.center.y - 1f,
                full.right - full.width * .1f + 1f, full.center.y + 1f)
        }
        val hold = android.view.ViewConfiguration.getLongPressTimeout() + 80L
        val start = androidx.compose.ui.geometry.Offset(bounds.left + bounds.width * .2f, bounds.center.y)
        val end = androidx.compose.ui.geometry.Offset(bounds.right - bounds.width * .1f, bounds.center.y)
        injectGesture(listOf(0L to start, hold to start) + (1..10).map { step ->
            hold + step * 35L to androidx.compose.ui.geometry.Offset(
                start.x + (end.x - start.x) * step / 10f, start.y)
        } + listOf(hold + 400L to end))
    }

    private fun awaitFieldFocus(tag: String, focused: Boolean) {
        var observed: Boolean? = null
        try {
            ui.waitUntil(5_000) {
                observed = ui.onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired = false)
                    .singleOrNull()?.config?.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Focused)
                observed == focused
            }
        } catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("Field focus did not settle: tag=$tag expected=$focused observed=$observed", timeout)
        }
    }

    private fun awaitFieldIme(tag: String, visible: Boolean) {
        if (fieldsInDialog) awaitDialogImeFrame(node(tag), visible)
        else awaitImeFrame(node(tag), visible)
    }

    private fun tapField(tag: String) {
        realTap(tag)
        awaitFieldFocus(tag, true)
        node(tag).assertIsFocused()
        awaitFieldIme(tag, visible = true)
    }

    private fun exerciseFields(dialog: Boolean) {
        showFields(dialog)
        val database = fixture.database.exportBackup()
        tapField("managed-first")
        node("managed-first").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetSelection) {
            assertTrue(it(5, 1, true))
        }
        realTap("managed-outside")
        awaitFieldFocus("managed-first", false)
        node("managed-first").assertIsNotFocused()
        @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
        val inputView = (node("managed-first").fetchSemanticsNode().root as androidx.compose.ui.platform.ViewRootForTest).view
        mainThread { assertTrue("Dismissal must focus the non-editing window root", inputView.rootView.isFocused) }
        awaitFieldIme("managed-first", visible = false)
        node("managed-first").assertTextEquals(" 保留草稿😀 ")
        val selection = node("managed-first").fetchSemanticsNode().config[
            androidx.compose.ui.semantics.SemanticsProperties.TextSelectionRange]
        assertEquals(5, selection.start)
        assertEquals(1, selection.end)
        tapField("managed-first")
        tapField("managed-second")
        node("managed-first").assertIsNotFocused()
        node("managed-second").performTextInput("续写")
        realTap("managed-button")
        awaitFieldFocus("managed-second", false)
        node("managed-second").assertIsNotFocused()
        awaitFieldIme("managed-second", visible = false)
        ui.onNodeWithText("动作次数 1").assertExists()
        assertEquals(database, fixture.database.exportBackup())
        assertEquals(0, fixture.opens.get())
    }

    fun testActivityFieldsSwitchAndOutsideButtonRunsOnce() = exerciseFields(false)
    fun testOffsetDialogFieldsUseTheirOwnWindowAndScreenBounds() = exerciseFields(true)

    fun testHomeReturnDoesNotRestoreOrdinaryFieldKeyboard() {
        showFields()
        val original = activity
        val root = original.window.decorView as android.view.ViewGroup
        var focusOrder = 0
        mainThread { focusOrder = root.descendantFocusability }
        repeat(2) {
            tapField("managed-first")
            mainThread { assertEquals(focusOrder, root.descendantFocusability) }
            val stopped = java.util.concurrent.CountDownLatch(1)
            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) stopped.countDown()
            }
            mainThread { original.lifecycle.addObserver(observer) }
            try {
                assertTrue(instrumentation.uiAutomation.performGlobalAction(
                    android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME))
                assertTrue(stopped.await(5, java.util.concurrent.TimeUnit.SECONDS))
            } finally {
                instrumentation.runOnMainSync { original.lifecycle.removeObserver(observer) }
                restoreOriginalHost(original)
            }
            awaitHostWindowFocus(original)
            awaitFieldFocus("managed-first", false)
            node("managed-first").assertIsNotFocused()
            awaitFieldIme("managed-first", visible = false)
            mainThread {
                assertTrue(root.isFocused)
                assertEquals(android.view.ViewGroup.FOCUS_BEFORE_DESCENDANTS, root.descendantFocusability)
            }
            node("managed-first").assertTextEquals(" 保留草稿😀 ")
        }
        tapField("managed-first")
        mainThread { assertEquals(focusOrder, root.descendantFocusability) }
        realTap("managed-outside")
        awaitFieldFocus("managed-first", false)
        mainThread { assertEquals(android.view.ViewGroup.FOCUS_BEFORE_DESCENDANTS, root.descendantFocusability) }
        mainThread { original.setContent {} }
        mainThread { assertEquals(focusOrder, root.descendantFocusability) }
        assertEquals(0, fixture.opens.get())
    }

    fun testHiddenImeLeavesNoBlinkingOrSteadyCaretInOrdinaryFields() {
        showFields()
        fun pixels(tag: String): IntArray {
            val image = captureScreenNode(node(tag)).toPixelMap()
            // Exclude focus-dependent Material outline decoration, retaining the text and caret.
            val inset = (8 * activity.resources.displayMetrics.density).toInt()
            val width = image.width - 2 * inset
            val height = image.height - 2 * inset
            check(width > 0 && height > 0)
            return IntArray(width * height) { image[it % width + inset, it / width + inset].toArgb() }
        }
        for (tag in listOf("managed-first", "managed-second")) {
            tapField(tag)
            val shown = pixels(tag)
            val normalSamples = mutableListOf(shown)
            var changed = false
            repeat(3) {
                ui.mainClock.advanceTimeBy(550)
                val sample = pixels(tag)
                normalSamples += sample
                if (!shown.contentEquals(sample)) changed = true
            }
            assertTrue("Visible IME must show the caret to this test", changed)
            fun materialState(): List<Any?> {
                val field = node(tag).fetchSemanticsNode()
                val root = field.root as androidx.compose.ui.platform.ViewRootForTest
                return listOf(field.id, root.view, root.view.windowToken, field.boundsInRoot,
                    field.positionOnScreen, field.config[androidx.compose.ui.semantics.SemanticsProperties.EditableText],
                    field.config[androidx.compose.ui.semantics.SemanticsProperties.TextSelectionRange])
            }
            if (tag == "managed-second") {
                val before = materialState()
                try {
                    mainThread { cursorlessTag.value = tag }
                    awaitFieldIme(tag, visible = true)
                    node(tag).assertIsFocused()
                    assertEquals(before, materialState())
                    val transparent = pixels(tag)
                    repeat(3) {
                        ui.mainClock.advanceTimeBy(550)
                        assertTrue(transparent.contentEquals(pixels(tag)))
                    }
                    assertTrue("Explicit transparent cursor must differ from visible production cursor",
                        normalSamples.any { !it.contentEquals(transparent) })
                } finally { mainThread { cursorlessTag.value = null } }
                assertEquals(before, materialState())
                val restored = pixels(tag)
                var blinking = false
                repeat(3) {
                    ui.mainClock.advanceTimeBy(550)
                    if (!restored.contentEquals(pixels(tag))) blinking = true
                }
                assertTrue("Restored production cursor must blink", blinking)
            }
            // This test hides only IME, deliberately preserving input focus.
            dismissIme()
            awaitFieldIme(tag, visible = false)
            node(tag).assertIsFocused()
            val hidden = pixels(tag)
            repeat(3) {
                ui.mainClock.advanceTimeBy(550)
                node(tag).assertIsFocused()
                assertTrue(hidden.contentEquals(pixels(tag)))
            }
            if (tag == "managed-second") {
                val before = materialState()
                try {
                    mainThread { cursorlessTag.value = tag }
                    awaitFieldIme(tag, visible = false)
                    node(tag).assertIsFocused()
                    assertEquals(before, materialState())
                    val cursorlessBaseline = pixels(tag)
                    repeat(3) {
                        ui.mainClock.advanceTimeBy(550)
                        assertTrue(cursorlessBaseline.contentEquals(pixels(tag)))
                    }
                    assertTrue("Hidden IME must not draw a steady caret either: field=$tag",
                        hidden.contentEquals(cursorlessBaseline))
                } finally { mainThread { cursorlessTag.value = null } }
            }
            realTap("managed-outside")
            awaitFieldFocus(tag, false)
            node(tag).assertIsNotFocused()
            awaitFieldIme(tag, visible = false)
            val unfocused = pixels(tag)
            ui.mainClock.advanceTimeByFrame()
            assertTrue("Unfocused pixel baseline must be stable: field=$tag", unfocused.contentEquals(pixels(tag)))
            val firstDifference = hidden.indices.firstOrNull { it >= unfocused.size || hidden[it] != unfocused[it] }
            if (tag == "managed-first") assertTrue("Hidden IME must not draw a steady caret either: field=$tag " +
                "hidden=${hidden.size} unfocused=${unfocused.size} firstDifference=$firstDifference",
                hidden.contentEquals(unfocused))
        }
        assertEquals(0, fixture.opens.get())
    }

    fun testLongPressAndDragDoNotDismissEditing() {
        showFields()
        tapField("managed-first")
        val originalHost = activity
        val root = node("managed-first").fetchSemanticsNode().root as androidx.compose.ui.platform.ViewRootForTest
        val view = root.view
        val token = view.windowToken
        var mode: android.view.ActionMode? = null
        val originalCallback = originalHost.window.callback
        val callback = object : android.view.Window.Callback by originalCallback {
            override fun onActionModeStarted(started: android.view.ActionMode) {
                mode = started
                originalCallback.onActionModeStarted(started)
            }
            override fun onActionModeFinished(finished: android.view.ActionMode) {
                if (mode === finished) mode = null
                originalCallback.onActionModeFinished(finished)
            }
        }
        var baselineWindows = emptyList<android.view.View>()
        instrumentation.runOnMainSync {
            baselineWindows = androidx.test.platform.view.inspector.WindowInspectorCompat.getGlobalWindowViews().toList()
            originalHost.window.callback = callback
        }
        fun assertHost() {
            instrumentation.runOnMainSync {
                assertSame(originalHost, activity)
                assertFalse("Gesture finished the original Activity", originalHost.isFinishing || originalHost.isDestroyed)
                assertEquals(androidx.lifecycle.Lifecycle.State.RESUMED, originalHost.lifecycle.currentState)
                assertTrue("Original Compose root lost attachment", view.isAttachedToWindow)
                assertSame(token, view.windowToken)
                assertTrue("Original Compose root lost window focus", view.hasWindowFocus())
            }
        }
        try {
            realLongPress("managed-first")
            val selected = node("managed-first").fetchSemanticsNode().config[
                androidx.compose.ui.semantics.SemanticsProperties.TextSelectionRange]
            assertFalse("Real long press selects text", selected.collapsed)
            realDrag("managed-first")
            node("managed-first").assertIsFocused()
            val dragged = node("managed-first").fetchSemanticsNode().config[
                androidx.compose.ui.semantics.SemanticsProperties.TextSelectionRange]
            assertFalse("Real long-press drag retains a text selection", dragged.collapsed)
            instrumentation.runOnMainSync {
                checkNotNull(fieldToolbar).hide()
                mode?.finish()
            }
            val outside = screenBounds("managed-outside")
            ui.waitUntil(5_000) {
                var ready = false
                instrumentation.runOnMainSync {
                    val blocked = androidx.test.platform.view.inspector.WindowInspectorCompat.getGlobalWindowViews().any { popup ->
                        val params = popup.layoutParams as? android.view.WindowManager.LayoutParams
                        if (popup in baselineWindows || !popup.isShown || params == null ||
                            params.flags and android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0) false
                        else {
                            val origin = IntArray(2)
                            popup.getLocationOnScreen(origin)
                            val bounds = androidx.compose.ui.geometry.Rect(origin[0].toFloat(), origin[1].toFloat(),
                                (origin[0] + popup.width).toFloat(), (origin[1] + popup.height).toFloat())
                            bounds.overlaps(outside)
                        }
                    }
                    ready = mode == null && fieldToolbar?.status == androidx.compose.ui.platform.TextToolbarStatus.Hidden &&
                        view.isAttachedToWindow && view.windowToken === token && view.hasWindowFocus() && !blocked
                }
                ready
            }
            assertHost()
            node("managed-first").assertIsFocused()
            assertEquals(dragged, node("managed-first").fetchSemanticsNode().config[
                androidx.compose.ui.semantics.SemanticsProperties.TextSelectionRange])
            awaitFieldIme("managed-first", visible = true)
            realLongPress("managed-outside")
            assertHost()
            node("managed-first").assertIsFocused()
            realDrag("managed-outside")
            assertHost()
            node("managed-first").assertIsFocused()
            awaitFieldIme("managed-first", visible = true)
        } finally {
            instrumentation.runOnMainSync {
                mode?.finish()
                if (originalHost.window.callback === callback) originalHost.window.callback = originalCallback
            }
        }
    }

    fun testNativeFieldsKeepDraftAndSelectionWhenOutsideTapClearsFocus() {
        lateinit var first: android.widget.EditText
        lateinit var second: android.widget.EditText
        lateinit var outside: android.widget.TextView
        mainThread {
            first = android.widget.EditText(activity).apply { setText(" 保留原生草稿 "); manageInput() }
            second = android.widget.EditText(activity).apply { setText("另一项"); manageInput() }
            outside = android.widget.TextView(activity).apply { text = "原生外部标题"; setPadding(32, 32, 32, 32) }
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    Box(Modifier.fillMaxSize()
                        .background(androidx.compose.material3.MaterialTheme.colorScheme.background)
                        .systemBarsPadding()) {
                        androidx.compose.ui.viewinterop.AndroidView(factory = {
                            android.widget.LinearLayout(activity).apply {
                                orientation = android.widget.LinearLayout.VERTICAL
                                addView(first); addView(second); addView(outside)
                            }
                        })
                    }
                }
            }
        }
        fun tap(view: android.view.View) {
            val center = stableScreenBounds(view) {
                val screen = IntArray(2)
                view.getLocationOnScreen(screen)
                androidx.compose.ui.geometry.Rect(screen[0].toFloat(), screen[1].toFloat(),
                    (screen[0] + view.width).toFloat(), (screen[1] + view.height).toFloat())
            }.center
            injectGesture(listOf(0L to center, 40L to center))
        }
        fun awaitNativeIme(visible: Boolean) = awaitStableWindowFrame(first) {
            val insets = androidx.core.view.ViewCompat.getRootWindowInsets(first)
            if (insets != null && insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == visible) visible else null
        }
        tap(first)
        awaitNativeIme(true)
        mainThread { first.setSelection(5, 1) }
        tap(outside)
        awaitNativeIme(false)
        mainThread {
            assertFalse(first.hasFocus()); assertFalse(first.isCursorVisible)
            assertEquals(" 保留原生草稿 ", first.text.toString())
            assertEquals(5, first.selectionStart); assertEquals(1, first.selectionEnd)
        }
        tap(first); awaitNativeIme(true)
        tap(second); awaitNativeIme(true)
        mainThread { assertTrue(second.hasFocus()); assertFalse(first.hasFocus()) }
        assertEquals(0, fixture.opens.get())
    }

}
