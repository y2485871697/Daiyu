package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Box
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
    private var fieldsInDialog = false
    private fun showFields(dialog: Boolean = false) {
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
                                    cursorColor = materialImeCursorColor(androidx.compose.material3.MaterialTheme.colorScheme.primary),
                                    errorCursorColor = materialImeCursorColor(androidx.compose.material3.MaterialTheme.colorScheme.error)))
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

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    private fun screenBounds(tag: String): androidx.compose.ui.geometry.Rect {
        val interaction = node(tag).scrollToVisible()
        awaitVisualIdle()
        awaitNodeWindowFocus(interaction)
        val target = interaction.fetchSemanticsNode()
        val view = (target.root as androidx.compose.ui.platform.ViewRootForTest).view
        val token = view.windowToken
        return awaitStableWindowFrame(view) {
            if (view.windowToken != token || !target.layoutInfo.isPlaced ||
                !target.layoutInfo.coordinates.isAttached) null else {
                val bounds = target.boundsInRoot.translate(target.positionOnScreen - target.positionInRoot)
                bounds.takeIf { it.width > 0f && it.height > 0f &&
                    listOf(it.left, it.top, it.right, it.bottom).all { value -> value.isFinite() } }
            }
        }
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
            val event = android.view.MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(),
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
        val bounds = screenBounds(tag)
        val point = androidx.compose.ui.geometry.Offset(bounds.left + bounds.width * .2f, bounds.center.y)
        injectGesture(listOf(0L to point, (android.view.ViewConfiguration.getLongPressTimeout() + 80L) to point))
    }

    private fun realDrag(tag: String) {
        val bounds = screenBounds(tag)
        val hold = android.view.ViewConfiguration.getLongPressTimeout() + 80L
        val start = androidx.compose.ui.geometry.Offset(bounds.left + bounds.width * .2f, bounds.center.y)
        val end = androidx.compose.ui.geometry.Offset(bounds.right - bounds.width * .1f, bounds.center.y)
        injectGesture(listOf(0L to start, hold to start) + (1..10).map { step ->
            hold + step * 35L to androidx.compose.ui.geometry.Offset(
                start.x + (end.x - start.x) * step / 10f, start.y)
        } + listOf(hold + 400L to end))
    }

    private fun awaitFieldFocus(tag: String, focused: Boolean) {
        ui.waitUntil(5_000) {
            ui.onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired = false)
                .singleOrNull()?.config?.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Focused) == focused
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
        tapField("managed-first")
        val original = activity
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
        node("managed-first").assertTextEquals(" 保留草稿😀 ")
        tapField("managed-first")
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
            var changed = false
            repeat(3) {
                ui.mainClock.advanceTimeBy(550)
                if (!shown.contentEquals(pixels(tag))) changed = true
            }
            assertTrue("Visible IME must show the caret to this test", changed)
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
            realTap("managed-outside")
            awaitFieldFocus(tag, false)
            node(tag).assertIsNotFocused()
            val unfocused = pixels(tag)
            ui.mainClock.advanceTimeByFrame()
            assertTrue("Unfocused pixel baseline must be stable: field=$tag", unfocused.contentEquals(pixels(tag)))
            val firstDifference = hidden.indices.firstOrNull { it >= unfocused.size || hidden[it] != unfocused[it] }
            assertTrue("Hidden IME must not draw a steady caret either: field=$tag " +
                "hidden=${hidden.size} unfocused=${unfocused.size} firstDifference=$firstDifference",
                hidden.contentEquals(unfocused))
        }
        assertEquals(0, fixture.opens.get())
    }

    fun testLongPressAndDragDoNotDismissEditing() {
        showFields()
        tapField("managed-first")
        realLongPress("managed-first")
        val selected = node("managed-first").fetchSemanticsNode().config[
            androidx.compose.ui.semantics.SemanticsProperties.TextSelectionRange]
        assertFalse("Real long press selects text", selected.collapsed)
        realDrag("managed-first")
        node("managed-first").assertIsFocused()
        val dragged = node("managed-first").fetchSemanticsNode().config[
            androidx.compose.ui.semantics.SemanticsProperties.TextSelectionRange]
        assertFalse("Real long-press drag retains a text selection", dragged.collapsed)
        mainThread { checkNotNull(fieldToolbar).hide() }
        ui.waitUntil(5_000) {
            var hidden = false
            instrumentation.runOnMainSync {
                hidden = fieldToolbar?.status == androidx.compose.ui.platform.TextToolbarStatus.Hidden
            }
            hidden
        }
        realLongPress("managed-outside")
        node("managed-first").assertIsFocused()
        realDrag("managed-outside")
        node("managed-first").assertIsFocused()
        awaitFieldIme("managed-first", visible = true)
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
            val center = java.util.concurrent.atomic.AtomicReference<androidx.compose.ui.geometry.Offset>()
            mainThread {
                val screen = IntArray(2); view.getLocationOnScreen(screen)
                center.set(androidx.compose.ui.geometry.Offset(screen[0] + view.width / 2f, screen[1] + view.height / 2f))
            }
            injectGesture(listOf(0L to center.get(), 40L to center.get()))
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
