package app.daiyu.android

import androidx.activity.compose.setContent
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.dp
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.components.*
import app.daiyu.android.ui.screens.settings.SettingsTextField

class ManagedInputActivityTest : WiredActivityTestCase() {
    private fun showFields(dialog: Boolean = false) {
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    val fields: @androidx.compose.runtime.Composable () -> Unit = {
                        var first by remember { mutableStateOf(" 保留草稿😀 ") }
                        var second by remember { mutableStateOf("第二个输入框") }
                        var clicks by remember { mutableStateOf(0) }
                        Column(Modifier.fillMaxWidth().padding(24.dp)) {
                            SettingsTextField("第一项", first, { first = it }, tag = "managed-first")
                            OutlinedTextField(second, { second = it },
                                modifier = Modifier.managedInput().testTag("managed-second"),
                                colors = OutlinedTextFieldDefaults.colors(
                                    cursorColor = materialImeCursorColor(androidx.compose.material3.MaterialTheme.colorScheme.primary),
                                    errorCursorColor = materialImeCursorColor(androidx.compose.material3.MaterialTheme.colorScheme.error)))
                            androidx.compose.material3.Text("外部标题", Modifier.testTag("managed-outside"))
                            app.daiyu.android.ui.haptics.material.TextButton(onClick = { clicks++ },
                                modifier = Modifier.testTag("managed-button")) {
                                androidx.compose.material3.Text("动作次数 $clicks")
                            }
                        }
                    }
                    if (dialog) DaiyuDialog(onDismissRequest = {}) { fields() } else fields()
                }
            }
        }
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    private fun screenBounds(tag: String): androidx.compose.ui.geometry.Rect {
        awaitNodeWindowFocus(node(tag))
        val target = node(tag).fetchSemanticsNode()
        return ui.runOnIdle { target.boundsInRoot.translate(target.positionOnScreen - target.positionInRoot) }
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

    private fun tapField(tag: String) {
        realTap(tag)
        node(tag).assertIsFocused()
        awaitImeFrame(node(tag), visible = true)
    }

    private fun exerciseFields(dialog: Boolean) {
        showFields(dialog)
        val database = fixture.database.exportBackup()
        tapField("managed-first")
        node("managed-first").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetSelection) {
            assertTrue(it(5, 1, true))
        }
        realTap("managed-outside")
        node("managed-first").assertIsNotFocused()
        awaitImeFrame(node("managed-first"), visible = false)
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
        node("managed-second").assertIsNotFocused()
        awaitImeFrame(node("managed-second"), visible = false)
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
            instrumentation.targetContext.startActivity(android.content.Intent(
                instrumentation.targetContext, MainActivity::class.java).addFlags(
                android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
        awaitHostWindowFocus(original)
        node("managed-first").assertIsNotFocused()
        awaitImeFrame(node("managed-first"), visible = false)
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
            awaitImeFrame(node(tag), visible = false)
            node(tag).assertIsFocused()
            val hidden = pixels(tag)
            repeat(3) {
                ui.mainClock.advanceTimeBy(550)
                node(tag).assertIsFocused()
                assertTrue(hidden.contentEquals(pixels(tag)))
            }
            realTap("managed-outside")
            node(tag).assertIsNotFocused()
            assertTrue("Hidden IME must not draw a steady caret either", hidden.contentEquals(pixels(tag)))
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
        realLongPress("managed-outside")
        node("managed-first").assertIsFocused()
        realDrag("managed-outside")
        node("managed-first").assertIsFocused()
        awaitImeFrame(node("managed-first"), visible = true)
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
                androidx.compose.ui.viewinterop.AndroidView(factory = {
                    android.widget.LinearLayout(activity).apply {
                        orientation = android.widget.LinearLayout.VERTICAL
                        addView(first); addView(second); addView(outside)
                    }
                })
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
