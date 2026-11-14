package app.daiyu.android

import android.os.Build
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

    private data class NativeFrame(
        val geometry: Geometry, val window: Rect, val imeBottom: Int, val navigationBottom: Int,
        val imeVisible: Boolean, val statusVisible: Boolean, val statusTop: Int, val statusStableTop: Int,
    )

    @Suppress("DEPRECATION")
    private fun statusVisible(insets: WindowInsetsCompat): Boolean =
        if (Build.VERSION.SDK_INT < 30) {
            activity.window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0
        } else insets.isVisible(WindowInsetsCompat.Type.statusBars())

    private fun statusReady(view: View, visible: Boolean, physicalTop: Int): Boolean {
        val insets = ViewCompat.getRootWindowInsets(view) ?: return false
        val status = WindowInsetsCompat.Type.statusBars()
        return statusVisible(insets) == visible &&
            insets.getInsetsIgnoringVisibility(status).top == physicalTop &&
            (Build.VERSION.SDK_INT < 30 || insets.getInsets(status).top == (if (visible) physicalTop else 0))
    }

    private fun nativeFrame(view: View, geometry: () -> Geometry): NativeFrame {
        val insets = checkNotNull(ViewCompat.getRootWindowInsets(view))
        val position = IntArray(2)
        view.getLocationInWindow(position)
        return NativeFrame(geometry(),
            Rect(position[0].toFloat(), position[1].toFloat(),
                (position[0] + view.width).toFloat(), (position[1] + view.height).toFloat()),
            insets.getInsets(WindowInsetsCompat.Type.ime()).bottom,
            insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom,
            insets.isVisible(WindowInsetsCompat.Type.ime()), statusVisible(insets),
            insets.getInsets(WindowInsetsCompat.Type.statusBars()).top,
            insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars()).top)
    }

    private fun atBottom(baseline: Geometry, bottom: Float): Geometry {
        val gap = baseline.list.bottom - baseline.composer.bottom
        return baseline.copy(
            list = Rect(baseline.list.left, baseline.list.top, baseline.list.right, bottom),
            composer = Rect(baseline.composer.left, bottom - gap - baseline.composer.height,
                baseline.composer.right, bottom - gap),
        )
    }

    private fun nativeExpected(baseline: Geometry, frame: NativeFrame): Geometry =
        atBottom(baseline, frame.window.bottom - maxOf(frame.imeBottom, frame.navigationBottom))

    private fun assertNativeDraws(view: View, geometry: () -> Geometry, expected: Geometry,
        withIme: Boolean, label: String, change: () -> Unit, ready: () -> Boolean) {
        var draws = 0
        var failure: Throwable? = null
        val observer = view.viewTreeObserver
        val listener = ViewTreeObserver.OnDrawListener {
            draws++
            try {
                val frame = nativeFrame(view, geometry)
                // IME may animate its bottom; the chat top and composer shape must never jump.
                val expectedForDraw = if (withIme) atBottom(expected, frame.geometry.list.bottom) else expected
                assertEquals("$label draw=$draws $frame", expectedForDraw, frame.geometry)
            } catch (caught: Throwable) { if (failure == null) failure = caught }
        }
        try {
            mainThread {
                val baseline = nativeFrame(view, geometry)
                assertEquals("$label baseline $baseline", nativeExpected(expected, baseline), baseline.geometry)
                observer.addOnDrawListener(listener)
                change()
            }
            val settled = awaitStableWindowFrame(view) {
                failure?.let { throw it }
                nativeFrame(view, geometry).takeIf { ready() && nativeExpected(expected, it) == it.geometry }
            }
            assertEquals("$label settled $settled", nativeExpected(expected, settled), settled.geometry)
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
            statusVisible(checkNotNull(ViewCompat.getRootWindowInsets(view)))
        }
        val drawingWasEnabled = HardwareRendererCompat.isDrawingEnabled()
        try {
            if (!drawingWasEnabled) HardwareRendererCompat.setDrawingEnabled(true)
            mainThread { controller.show(status) }
            val physicalTop = awaitStableWindowFrame(view) {
                ViewCompat.getRootWindowInsets(view)?.takeIf {
                    statusVisible(it) && it.getInsetsIgnoringVisibility(status).top > 0
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
                    assertEquals("IME must not pan chat header", withoutIme.header, expected.header)
                    assertEquals("IME must not pan chat list top", withoutIme.list.top, expected.list.top)
                }
                repeat(2) { round ->
                    for (visible in listOf(false, true)) {
                        assertNativeDraws(view, geometry, expected, withIme,
                            label = "IME=$withIme round=$round statusVisible=$visible",
                            change = { if (visible) controller.show(status) else controller.hide(status) },
                            ready = {
                                statusReady(view, visible, physicalTop)
                            })
                    }
                }
                if (withIme) {
                    dismissIme()
                    val restored = awaitStableWindowFrame(view) { geometry() }
                    assertEquals("Chat returns after IME hides", withoutIme, restored)
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
