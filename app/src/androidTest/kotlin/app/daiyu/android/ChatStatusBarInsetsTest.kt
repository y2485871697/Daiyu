package app.daiyu.android

import android.accessibilityservice.AccessibilityService
import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.test.platform.graphics.HardwareRendererCompat

class ChatStatusBarInsetsTest : WiredActivityTestCase() {
    private data class Geometry(val list: Rect, val header: Rect, val composer: Rect)

    private fun bounds(node: SemanticsNode): Rect {
        assertTrue("Chat node must stay placed", node.layoutInfo.isPlaced)
        val coordinates = node.layoutInfo.coordinates
        assertTrue("Chat node must stay attached", coordinates.isAttached)
        return Rect(coordinates.localToWindow(Offset.Zero),
            Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat()))
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    private fun chatGeometry(): Pair<View, () -> Geometry> {
        val list = node("chat-messages").assertIsDisplayed().fetchSemanticsNode()
        val header = node("chat-top-bar").fetchSemanticsNode()
        val composer = node("chat-composer-surface").fetchSemanticsNode()
        val view = (list.root as ViewRootForTest).view
        return view to { Geometry(bounds(list), bounds(header), bounds(composer)) }
    }

    private fun assertNativeGeometry(view: View, geometry: () -> Geometry, baseline: Geometry, label: String) {
        val insets = checkNotNull(ViewCompat.getRootWindowInsets(view))
        val position = IntArray(2)
        view.getLocationInWindow(position)
        val windowBottom = position[1] + view.height
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
        val navigation = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
        val bottom = (windowBottom - maxOf(ime.bottom, navigation.bottom)).toFloat()
        val gap = baseline.list.bottom - baseline.composer.bottom
        val expected = baseline.copy(
            list = Rect(baseline.list.left, baseline.list.top, baseline.list.right, bottom),
            composer = Rect(baseline.composer.left, bottom - gap - baseline.composer.height,
                baseline.composer.right, bottom - gap),
        )
        assertEquals("$label window=(${position[0]},${position[1]},${view.width},${view.height}) " +
            "status=${insets.getInsets(WindowInsetsCompat.Type.statusBars())} " +
            "statusStable=${insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars())} " +
            "ime=$ime imeVisible=${insets.isVisible(WindowInsetsCompat.Type.ime())} nav=$navigation",
            expected, geometry())
    }

    private fun assertNativeDraws(view: View, geometry: () -> Geometry, expected: Geometry,
        label: String, change: () -> Unit, ready: () -> Boolean) {
        var draws = 0
        var failure: Throwable? = null
        val observer = view.viewTreeObserver
        val listener = ViewTreeObserver.OnDrawListener {
            draws++
            try { assertNativeGeometry(view, geometry, expected, "$label draw=$draws") }
            catch (caught: Throwable) { if (failure == null) failure = caught }
        }
        try {
            mainThread {
                assertNativeGeometry(view, geometry, expected, "$label baseline")
                observer.addOnDrawListener(listener)
                change()
            }
            awaitStableWindowFrame(view) {
                failure?.let { throw it }
                geometry().takeIf { ready() }
            }
            mainThread {
                failure?.let { throw it }
                assertTrue("Transition must produce native draws", draws > 0)
                assertTrue("Requested status-bar state must be reached", ready())
            }
        } finally {
            instrumentation.runOnMainSync {
                if (observer.isAlive) observer.removeOnDrawListener(listener)
                else view.viewTreeObserver.removeOnDrawListener(listener)
            }
        }
    }

    fun testStatusBarVisibilityKeepsEveryNativeChatDrawStableAndImeLive() {
        dismissIme()
        val (view, geometry) = chatGeometry()
        val status = WindowInsetsCompat.Type.statusBars()
        val controller = WindowCompat.getInsetsController(activity.window, view)
        val originallyVisible = ui.runOnIdle {
            checkNotNull(ViewCompat.getRootWindowInsets(view)).isVisible(status)
        }
        val drawingWasEnabled = HardwareRendererCompat.isDrawingEnabled()
        try {
            if (!drawingWasEnabled) HardwareRendererCompat.setDrawingEnabled(true)
            mainThread { controller.show(status) }
            val physicalTop = awaitStableWindowFrame(view) {
                ViewCompat.getRootWindowInsets(view)?.takeIf {
                    it.isVisible(status) && it.getInsets(status).top > 0
                }?.getInsetsIgnoringVisibility(status)?.top
            }
            val withoutIme = awaitStableWindowFrame(view) { geometry() }
            for (withIme in listOf(false, true)) {
                if (withIme) {
                    node("chat-draft").performTouchInput {
                        advanceEventTime(android.view.ViewConfiguration.getDoubleTapTimeout().toLong() + 1)
                        click()
                    }
                    awaitImeFrame(node("chat-draft"), visible = true)
                }
                val expected = awaitStableWindowFrame(view) { geometry() }
                if (withIme) {
                    assertTrue("IME still raises composer", expected.composer.bottom < withoutIme.composer.bottom - 48f)
                    assertEquals("IME must not pan chat header", withoutIme.header.bottom, expected.header.bottom, 1f)
                }
                repeat(2) { round ->
                    for (visible in listOf(false, true)) {
                        assertNativeDraws(view, geometry, expected,
                            label = "IME=$withIme round=$round statusVisible=$visible",
                            change = { if (visible) controller.show(status) else controller.hide(status) },
                            ready = {
                                val insets = ViewCompat.getRootWindowInsets(view)
                                insets != null && insets.isVisible(status) == visible &&
                                    insets.getInsets(status).top == (if (visible) physicalTop else 0) &&
                                    insets.getInsetsIgnoringVisibility(status).top == physicalTop
                            })
                    }
                }
                if (withIme) {
                    dismissIme()
                    val restored = awaitStableWindowFrame(view) { geometry() }
                    assertEquals("Composer returns after IME hides", withoutIme.composer.bottom, restored.composer.bottom, 1f)
                }
            }
        } finally {
            mainThread { if (originallyVisible) controller.show(status) else controller.hide(status) }
            dismissIme()
            if (!drawingWasEnabled) HardwareRendererCompat.setDrawingEnabled(false)
        }
    }

    fun testHomeReturnKeepsChatGeometryFromFirstNativeDraw() {
        dismissIme()
        val original = activity
        val (view, geometry) = chatGeometry()
        val expected = awaitStableWindowFrame(view) { geometry() }
        var awaitingResume = false
        var recording = false
        var draws = 0
        var resumes = 0
        var failure: Throwable? = null
        val observer = view.viewTreeObserver
        val listener = ViewTreeObserver.OnDrawListener {
            if (recording) {
                draws++
                try { assertEquals("HOME return changed chat geometry from the first native draw", expected, geometry()) }
                catch (caught: Throwable) { if (failure == null) failure = caught }
            }
        }
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && awaitingResume) {
                awaitingResume = false
                recording = true
                resumes++
            }
        }
        val drawingWasEnabled = HardwareRendererCompat.isDrawingEnabled()
        try {
            if (!drawingWasEnabled) HardwareRendererCompat.setDrawingEnabled(true)
            mainThread {
                observer.addOnDrawListener(listener)
                original.lifecycle.addObserver(lifecycleObserver)
            }
            repeat(2) { round ->
                mainThread { recording = false; awaitingResume = true; draws = 0; failure = null }
                assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME))
                awaitControl("chat host stopped after HOME") {
                    !original.isDestroyed && original.lifecycle.currentState == Lifecycle.State.CREATED
                }
                restoreOriginalHost(original)
                awaitHostWindowFocus(original)
                mainThread {
                    recording = false
                    failure?.let { throw it }
                    assertEquals(round + 1, resumes)
                    assertTrue("HOME return must produce native draws", draws > 0)
                    assertSame(original, activity)
                }
            }
        } finally {
            mainThread {
                recording = false
                original.lifecycle.removeObserver(lifecycleObserver)
                if (observer.isAlive) observer.removeOnDrawListener(listener)
                else view.viewTreeObserver.removeOnDrawListener(listener)
            }
            if (!drawingWasEnabled) HardwareRendererCompat.setDrawingEnabled(false)
        }
    }
}
