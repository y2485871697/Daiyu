package app.daiyu.android

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.test.platform.app.InstrumentationRegistry
import app.daiyu.android.ui.screens.chat.conversation.DoubaoCallActions
import app.daiyu.android.ui.screens.chat.conversation.DoubaoCallPresentation
import app.daiyu.android.ui.screens.chat.conversation.DoubaoCallUi
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class VoiceCallFloatingWindowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var windows: RecordingWindows
    private lateinit var host: VoiceCallFloatingWindow
    private var animationScale = "1"
    private var overlayMode = "default"
    private val completed = mutableListOf<Long>()
    private var hangups = 0
    private val actions get() = DoubaoCallActions(onHangUp = { hangups++ }, onCloseAnimationFinished = { completed.add(it) })

    private open class RecordingWindows(context: Context,
        protected val actual: WindowManager = context.getSystemService(WindowManager::class.java)) : WindowManager by actual {
        val added = mutableListOf<View>()
        val removals = mutableListOf<View>()
        var updates = 0
        var failAdd = false
        var failUpdate = false
        var onDraw: ((View) -> Unit)? = null
        override fun updateViewLayout(view: View, params: ViewGroup.LayoutParams) {
            if (failUpdate) throw SecurityException("synthetic overlay update rejection")
            assertInputPolicy(params)
            updates++
            actual.updateViewLayout(view, params)
        }
        override fun addView(view: View, params: ViewGroup.LayoutParams) {
            if (failAdd) throw SecurityException("synthetic overlay rejection")
            assertInputPolicy(params)
            assertFalse("a new call window must not overlap an existing one", added.any { it.isAttachedToWindow })
            view.viewTreeObserver.addOnDrawListener { onDraw?.invoke(view) }
            actual.addView(view, params); added.add(view)
        }
        private fun assertInputPolicy(layout: ViewGroup.LayoutParams) {
            val params = layout as WindowManager.LayoutParams
            assertEquals("WM must not clamp an untouchable overlay to 80% opacity", 0,
                params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
            assertEquals(1f, params.alpha, 0f)
            assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
            assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL != 0)
        }
        override fun removeViewImmediate(view: View) { removals.add(view); actual.removeViewImmediate(view) }
        fun detachExternally(view: View) { actual.removeViewImmediate(view) }
    }
    @android.annotation.TargetApi(30)
    private class RecordingWindowsApi30(context: Context) : RecordingWindows(context) {
        override fun getMaximumWindowMetrics(): android.view.WindowMetrics =
            actual.maximumWindowMetrics
    }
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText().trim() }
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 2_000
        var satisfied = false
        while (!satisfied && SystemClock.uptimeMillis() < deadline) {
            main { satisfied = condition() }
            if (!satisfied) SystemClock.sleep(8)
        }
        assertTrue(message, satisfied)
    }
    private fun awaitNativeFade(root: View, message: String, close: () -> Unit) {
        var drewBaseline = false
        var drewIntermediateFrame = false
        var closing = false
        val frames = mutableListOf<String>()
        val redrawWhileClosing = object : Runnable {
            override fun run() {
                if (!closing || drewIntermediateFrame || !root.isAttachedToWindow) return
                root.invalidate()
                root.postOnAnimation(this)
            }
        }
        try {
            main {
                check(windows.onDraw == null)
                windows.onDraw = { view ->
                    if (view === root) {
                        frames += "${SystemClock.uptimeMillis()} close=$closing attached=${root.isAttachedToWindow} " +
                            "shown=${root.isShown} alpha=${root.alpha} scale=${root.scaleX},${root.scaleY}"
                        if (!closing && root.isAttachedToWindow && root.isShown && root.alpha == 1f &&
                            root.scaleX == 1f && root.scaleY == 1f) drewBaseline = true
                        if (closing && root.isAttachedToWindow && root.isShown && root.alpha > 0f && root.alpha < 1f &&
                            root.scaleX < 1f && root.scaleY < 1f) drewIntermediateFrame = true
                    }
                }
                root.invalidate()
            }
            await("complete visible baseline before external close") { drewBaseline }
            main { closing = true; root.postOnAnimation(redrawWhileClosing); close() }
            await(message) { drewIntermediateFrame }
        } catch (failure: AssertionError) {
            var snapshot = ""
            main {
                snapshot = "baseline=$drewBaseline closing=$closing frames=$frames " +
                    "finalAttached=${root.isAttachedToWindow} finalAlpha=${root.alpha} " +
                    "finalScale=${root.scaleX},${root.scaleY} removals=${windows.removals.size} completed=$completed"
            }
            throw AssertionError("$message; $snapshot", failure)
        } finally {
            main { root.removeCallbacks(redrawWhileClosing); windows.onDraw = null }
        }
    }
    private fun assertTouchDispatch(root: View, expected: List<Int>, transition: () -> Unit = {}) {
        val container = root as ViewGroup
        val received = mutableListOf<Int>()
        val probe = View(context).apply {
            setOnTouchListener { _, event -> received += event.actionMasked; true }
        }
        container.addView(probe, ViewGroup.LayoutParams(root.width, root.height))
        probe.layout(0, 0, root.width, root.height)
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, root.width / 2f, root.height / 2f, 0)
        val up = MotionEvent.obtain(now, now + 16, MotionEvent.ACTION_UP, root.width / 2f, root.height / 2f, 0)
        try {
            assertTrue(root.dispatchTouchEvent(down))
            transition()
            root.dispatchTouchEvent(up)
            assertEquals(expected, received)
        } finally {
            container.removeView(probe)
            down.recycle(); up.recycle()
        }
    }
    private fun picture(key: Long = -2L, mini: Boolean = false) = DoubaoCallUi(key, "original-conversation",
        presentation = if (mini) DoubaoCallPresentation.MINI else DoubaoCallPresentation.EXPANDED,
        startedAtMillis = SystemClock.elapsedRealtime(), title = "GPT", responseLabel = "助手", modelSelectable = true)

    @Before fun setUp() {
        animationScale = shell("settings get global animator_duration_scale")
        shell("settings put global animator_duration_scale 1")
        overlayMode = Regex("SYSTEM_ALERT_WINDOW: (allow|ignore|deny|default|foreground)")
            .find(shell("appops get ${context.packageName} SYSTEM_ALERT_WINDOW"))?.groupValues?.get(1) ?: "default"
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
        main {
            windows = if (android.os.Build.VERSION.SDK_INT >= 30) RecordingWindowsApi30(context)
                else RecordingWindows(context)
            host = VoiceCallFloatingWindow(context, windows)
        }
    }
    @After fun tearDown() {
        try {
            main { if (::host.isInitialized) host.close() }
        } finally {
            if (animationScale == "null") shell("settings delete global animator_duration_scale")
            else shell("settings put global animator_duration_scale $animationScale")
            shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW $overlayMode")
        }
    }

    @Test fun boundedOverlayResizesWithoutTakingFocusOrHangingUpOnReturn() {
        val state = picture()
        main { host.update(state, actions) }
        await("floating call attached") { host.isShowing(state.sessionKey) && windows.added.single().isLaidOut }
        main {
            val root = windows.added.single()
            val expanded = root.width
            val params = root.layoutParams as WindowManager.LayoutParams
            assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, params.type)
            assertTrue(params.flags and WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED != 0)
            assertTrue(root.isHardwareAccelerated)
            assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
            assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL != 0)
            assertTrue(root.height < context.resources.displayMetrics.heightPixels)
            host.update(state.copy(presentation = DoubaoCallPresentation.MINI), actions)
            assertTrue((root.layoutParams as WindowManager.LayoutParams).width < expanded)
            host.hide()
            assertFalse(root.isAttachedToWindow)
            assertFalse(host.isShowing(state.sessionKey))
            assertEquals(0, hangups)
            assertTrue(completed.isEmpty())
        }
    }

    @Test fun externalCloseHasOwnFadeAndScaleBeforeRemovingTheOldWindowOnce() {
        val state = picture()
        main { host.update(state, actions) }
        await("laid out before close") { windows.added.single().isLaidOut }
        val root = windows.added.single()
        awaitNativeFade(root, "distinct external fade and scale progressed") {
            // Production stops audio synchronously before publishing this visual close.
            assertTouchDispatch(root, listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL)) {
                host.update(state.copy(presentation = DoubaoCallPresentation.CLOSING), actions)
            }
            assertFalse(host.isShowing(state.sessionKey))
            assertTouchDispatch(root, emptyList())
            assertTrue(root.isAttachedToWindow)
        }
        await("animation removes the window") { !root.isAttachedToWindow }
        main {
            assertEquals(listOf(state.sessionKey), completed)
            assertEquals(0, (root as ViewGroup).childCount)
            assertEquals(Lifecycle.State.DESTROYED, (root as LifecycleOwner).lifecycle.currentState)
            host.dismiss()
            host.close()
            assertEquals(1, completed.size)
            assertEquals(listOf(root), windows.removals)
        }
    }

    @Test fun newCallReleasesTheOldWindowBeforeAttachingAndDestroyDoesNotWaitForAnimation() {
        val old = picture()
        main { host.update(old, actions) }
        await("first surface laid out") { windows.added.single().isLaidOut }
        val next = picture(-3L)
        main {
            val departing = windows.added.single()
            host.dismiss()
            host.update(next, actions)
            assertFalse("old window must leave before the new one attaches", departing.isAttachedToWindow)
            assertEquals(listOf(old.sessionKey), completed)
            assertEquals(listOf(departing), windows.removals)
            host.update(old.copy(presentation = DoubaoCallPresentation.CLOSING), actions)
        }
        await("new session attached") { host.isShowing(next.sessionKey) }
        SystemClock.sleep(VoiceCallFloatingWindow.CLOSE_MILLIS + 40)
        main {
            assertTrue(host.isShowing(next.sessionKey))
            assertEquals(2, windows.added.size)
            assertEquals(listOf(windows.added.first()), windows.removals)
            assertEquals(listOf(old.sessionKey), completed)
            host.close()
            assertFalse(windows.added.last().isAttachedToWindow)
            host.close()
            assertEquals(1, completed.size)
        }
    }

    @Test fun staleCloseDoesNotDismissTheNewSessionAndUsesLatestCompletionCallback() {
        val active = picture(-4L)
        main { host.update(active, actions) }
        await("active surface laid out") { host.isShowing(active.sessionKey) && windows.added.single().isLaidOut }
        val updated = mutableListOf<Long>()
        main {
            host.update(picture(-99L).copy(presentation = DoubaoCallPresentation.CLOSING), actions)
            assertTrue(host.isShowing(active.sessionKey))
            host.update(active.copy(presentation = DoubaoCallPresentation.CLOSING),
                actions.copy(onCloseAnimationFinished = { updated.add(-100L) }))
            host.update(active.copy(presentation = DoubaoCallPresentation.CLOSING),
                actions.copy(onCloseAnimationFinished = { updated.add(it) }))
        }
        await("latest completion callback") { updated == listOf(active.sessionKey) }
        main { assertTrue(completed.isEmpty()) }
    }

    @Test fun revokedOverlayPermissionStillCompletesTheExactClosingSession() {
        val active = picture(-5L)
        main { host.update(active, actions) }
        await("permission fixture laid out") { windows.added.single().isLaidOut }
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW ignore")
        main { host.update(active.copy(presentation = DoubaoCallPresentation.CLOSING), actions) }
        await("revocation removes closing surface") { !windows.added.single().isAttachedToWindow }
        main { assertEquals(listOf(active.sessionKey), completed) }
    }

    @Test fun systemDetachmentReleasesAfterChildDispatchAndRemainsIdempotent() {
        val active = picture(-6L)
        main { host.update(active, actions) }
        await("external removal fixture laid out") { windows.added.single().isLaidOut }
        val root = windows.added.single()
        main {
            windows.detachExternally(root)
            assertFalse(root.isAttachedToWindow)
        }
        await("detached lifecycle released") {
            (root as LifecycleOwner).lifecycle.currentState == Lifecycle.State.DESTROYED &&
                (root as ViewGroup).childCount == 0
        }
        main {
            host.hide()
            host.close()
            assertTrue(completed.isEmpty())
            assertEquals(0, hangups)
            assertEquals(listOf(root), windows.removals)
        }
    }

    @Test fun hideBeforeFirstTraversalStillRemovesTheAddedWindow() {
        main {
            host.update(picture(), actions)
            val root = windows.added.single()
            host.hide()
            assertNull(root.parent)
            assertFalse(root.isAttachedToWindow)
            assertEquals(0, (root as ViewGroup).childCount)
        }
    }

    @Test fun globalWindowRemainsVisibleOnEveryStateUpdate() {
        val state = picture(-7L)
        main { assertTrue(host.update(state, actions)) }
        await("global surface drawn from first presentation") { windows.added.single().isLaidOut }
        val root = windows.added.single()
        main {
            val params = root.layoutParams as WindowManager.LayoutParams
            val position = params.x to params.y
            repeat(3) {
                assertTrue(host.isShowing(state.sessionKey))
                assertTrue(host.update(state.copy(reply = "same call"), actions))
                assertSame(root, windows.added.single())
                assertEquals(1f, params.alpha, 0f)
                assertEquals(0, params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                assertEquals(position, params.x to params.y)
                assertTrue(root.isAttachedToWindow)
            }
            assertTrue(windows.removals.isEmpty())
            assertTrue(completed.isEmpty())
            assertEquals(0, hangups)
        }
    }

    @Test fun closeBeforeFirstTraversalCompletesOnceWithoutWaitingForLayout() {
        val state = picture(-8L)
        main {
            host.update(state, actions)
            val root = windows.added.single()
            host.update(state.copy(presentation = DoubaoCallPresentation.CLOSING), actions)
            assertFalse(root.isAttachedToWindow)
            host.update(state.copy(presentation = DoubaoCallPresentation.CLOSING), actions)
            assertEquals(listOf(state.sessionKey), completed)
            assertEquals(listOf(root), windows.removals)
            assertEquals(0, hangups)
        }
    }

    @Test fun detachedGlobalSurfaceIsRebuiltInsteadOfReusingADestroyedComposition() {
        val state = picture(-9L)
        main { host.update(state, actions) }
        await("global detach surface laid out") { windows.added.single().isLaidOut }
        val root = windows.added.single()
        main {
            windows.detachExternally(root)
            host.update(state, actions)
            assertEquals(2, windows.added.size)
            assertNotSame(root, windows.added.last())
        }
        await("replacement surface visible") { host.isShowing(state.sessionKey) && windows.added.last().isLaidOut }
        main {
            assertEquals(Lifecycle.State.DESTROYED, (root as LifecycleOwner).lifecycle.currentState)
            assertTrue(completed.isEmpty())
            assertEquals(0, hangups)
        }
    }
    @Test fun rejectedGlobalWindowReportsFailureInsteadOfRunningWithoutControls() {
        var unavailable = 0
        main {
            host.close()
            host = VoiceCallFloatingWindow(context, windows, onUnavailable = { unavailable++ })
            windows.failAdd = true
            assertFalse(host.update(picture(-10L), actions))
            assertFalse(host.isShowing(-10L))
            assertTrue(windows.added.isEmpty())
            assertTrue(completed.isEmpty())
            windows.failAdd = false
            assertTrue(host.update(picture(-10L), actions))
        }
        await("drag failure fixture attached") { host.isShowing(-10L) }
        main {
            windows.failUpdate = true
            val move = host.javaClass.declaredMethods.single { it.name.startsWith("move-") }
                .apply { isAccessible = true }
            move.invoke(host, windows.added.single(), androidx.compose.ui.geometry.Offset(8f, 8f).packedValue)
        }
        await("coalesced drag reports rejected window update") { unavailable == 1 }
        main {
            assertEquals(1, unavailable)
            assertFalse(host.isShowing(-10L))
            assertFalse(windows.added.single().isAttachedToWindow)
            assertTrue(completed.isEmpty())
            assertEquals(0, hangups)
        }
    }

    @Test fun permissionRevocationNotifiesTheOwnerWithoutAnotherStateUpdate() {
        var revoked = 0
        main {
            host.close()
            host = VoiceCallFloatingWindow(context, windows, onUnavailable = { revoked++ })
            assertTrue(host.update(picture(-11L), actions))
        }
        await("global permission fixture attached") { host.isShowing(-11L) }
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW ignore")
        await("permission loss reaches the call owner") { revoked > 0 }
        main { assertTrue(completed.isEmpty()) }
    }
    @Test fun callConfigurationIsAnotherOverlayAboveTheSamePrimaryWindow() {
        val state = picture(-12L)
        main { host.update(state, actions) }
        await("call already visible before configuration") { windows.added.single().isLaidOut }
        val root = windows.added.single()
        main {
            val dialog = app.daiyu.android.ui.components.PreviewOverlayWindow(context,
                androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
                inheritedSecure = false, parent = null, onDismiss = {},
                content = { androidx.compose.material3.Text("configuration fixture") })
            try {
                dialog.show()
                val params = checkNotNull(dialog.window).attributes
                assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, params.type)
                assertEquals(0, params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                assertTrue(host.isShowing(state.sessionKey))
                assertSame(root, windows.added.single())
            } finally { dialog.dismiss() }
            assertTrue(host.isShowing(state.sessionKey))
            assertTrue(windows.removals.isEmpty())
            assertEquals(0, hangups)
        }
    }

    private fun callAnchor(): app.daiyu.android.ui.screens.chat.conversation.DoubaoCallAnchor {
        val metrics = context.resources.displayMetrics
        val d = metrics.density
        val w = metrics.widthPixels.toFloat()
        val h = metrics.heightPixels.toFloat()
        return app.daiyu.android.ui.screens.chat.conversation.DoubaoCallAnchor(
            androidx.compose.ui.geometry.Rect(w - 56 * d, h - 76 * d, w - 36 * d, h - 56 * d),
            h - 96 * d, androidx.compose.ui.geometry.Rect(0f, 0f, w, h))
    }

    private fun tapMinimize(root: View) {
        var x = 0f
        var y = 0f
        main {
            val panel = root.javaClass.getDeclaredMethod("getPanelBounds").apply { isAccessible = true }
                .invoke(root) as androidx.compose.ui.geometry.Rect
            val inset = 38f * context.resources.displayMetrics.density
            x = panel.right - inset
            y = panel.top + inset
        }
        val now = SystemClock.uptimeMillis()
        val down = android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_DOWN, x, y, 0)
        val up = android.view.MotionEvent.obtain(now, now + 16, android.view.MotionEvent.ACTION_UP, x, y, 0)
        try {
            instrumentation.sendPointerSync(down)
            instrumentation.sendPointerSync(up)
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    @Test fun inAppEntrySettlesTouchableAndCloseUsesGenieWithoutNativeFade() {
        val state = picture(-13L)
        val anchor = callAnchor()
        val motion = app.daiyu.android.ui.screens.chat.conversation.DoubaoCallMotionState()
        var ready = false
        var minimizes = 0
        val callbacks = actions.copy(onPresentationReady = { if (it == state.sessionKey) ready = true },
            onMinimize = { minimizes++ })
        val drawnOrigins = mutableListOf<Pair<Int, Int>>()
        var drewIntermediateGenie = false
        var settledFrame: android.graphics.Rect? = null
        var invalidDraw: String? = null
        main {
            windows.onDraw = { root ->
                val screen = IntArray(2)
                val local = IntArray(2)
                root.getLocationOnScreen(screen)
                root.getLocationInWindow(local)
                val origin = intArrayOf(screen[0] - local[0], screen[1] - local[1])
                drawnOrigins += origin[0] to origin[1]
                if (motion.transforming && motion.progress.value > 0f && motion.progress.value < 1f)
                    drewIntermediateGenie = true
                val composition = (root as ViewGroup).getChildAt(0)
                val params = root.layoutParams as WindowManager.LayoutParams
                if (origin[0] != params.x || origin[1] != params.y)
                    invalidDraw = "a native frame was drawn before placement was committed"
                if (!motion.transforming && motion.progress.value == 1f &&
                    params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE == 0 &&
                    root.width == params.width && root.height == params.height)
                    settledFrame = android.graphics.Rect(params.x, params.y, params.x + params.width, params.y + params.height)
                if (root.alpha != 1f || params.alpha != 1f || root.scaleX != 1f || root.scaleY != 1f)
                    invalidDraw = "native alpha or scale changed during Genie"
                if (kotlin.math.abs(composition.translationX + origin[0]) > .5f ||
                    kotlin.math.abs(composition.translationY + origin[1]) > .5f)
                    invalidDraw = "composition origin did not match the drawn native origin"
            }
            assertTrue(host.update(state, callbacks, anchor, motion, inApp = true))
        }
        await("in-app entry returns the same window to a touchable bounded frame") {
            windows.added.singleOrNull()?.let { root ->
                val params = root.layoutParams as WindowManager.LayoutParams
                ready && settledFrame != null && root.isLaidOut && root.width == params.width && root.height == params.height &&
                    motion.progress.value == 1f && !motion.transforming &&
                    params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE == 0
            } == true
        }
        val root = windows.added.single()
        main {
            assertTrue("the original Genie must draw intermediate frames", drewIntermediateGenie)
            assertNull(invalidDraw)
            assertTrue("entry and live frames were observed", drawnOrigins.size > 1)
            assertEquals("settling must not move the native surface", 1, drawnOrigins.distinct().size)
            val panel = root.javaClass.getDeclaredMethod("getPanelBounds").apply { isAccessible = true }
                .invoke(root) as androidx.compose.ui.geometry.Rect
            val shadow = (28 * context.resources.displayMetrics.density).roundToInt().toFloat()
            val expected = panel.inflate(shadow)
            val actual = checkNotNull(settledFrame)
            assertEquals(expected.left.roundToInt().coerceAtLeast(0), actual.left)
            assertEquals(expected.top.roundToInt().coerceAtLeast(0), actual.top)
            assertEquals("settled viewport must retain exactly the original shadow, not the large animation envelope",
                expected.right.roundToInt(), actual.right)
            assertEquals(expected.bottom.roundToInt(), actual.bottom)
            windows.onDraw = null
        }
        tapMinimize(root)
        await("real minimize tap reaches the settled Compose control") { minimizes == 1 }
        main {
            ready = false
            val move = host.javaClass.declaredMethods.single { it.name.startsWith("move-") }
                .apply { isAccessible = true }
            move.invoke(host, root, androidx.compose.ui.geometry.Offset(8f, 8f).packedValue)
        }
        await("moved window redraws in real screen coordinates") { ready }
        tapMinimize(root)
        await("real control remains clickable after window translation") { minimizes == 2 }
        main {
            assertTouchDispatch(root, listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL)) {
                host.update(state.copy(presentation = DoubaoCallPresentation.CLOSING), callbacks, anchor, motion, inApp = true)
                host.update(state.copy(presentation = DoubaoCallPresentation.CLOSING), callbacks, anchor, motion, inApp = true)
            }
            assertFalse(host.isShowing(state.sessionKey))
            assertTouchDispatch(root, emptyList())
        }
        await("close reverses the original Genie clock") {
            root.isAttachedToWindow && motion.transforming && motion.progress.value > 0f && motion.progress.value < 1f
        }
        main {
            assertEquals(1f, root.alpha, 0f)
            assertEquals(1f, root.scaleX, 0f)
            assertEquals(1f, root.scaleY, 0f)
        }
        await("Genie completion removes the system window") { !root.isAttachedToWindow }
        main {
            assertEquals(listOf(state.sessionKey), completed)
            assertEquals(listOf(root), windows.removals)
            assertEquals(0, hangups)
        }
    }

    @Test fun leavingAppKeepsTheSameWindowAndOnlyExternalCloseFades() {
        val state = picture(-14L).copy(skipEntryAnimation = true)
        val anchor = callAnchor()
        main { host.update(state, actions, anchor, inApp = true) }
        await("in-app global window laid out") { windows.added.single().isLaidOut }
        val root = windows.added.single()
        awaitNativeFade(root, "only the external close uses native fade") {
            assertTrue(host.update(state, actions, anchor, inApp = false))
            assertSame(root, windows.added.single())
            assertTrue(windows.removals.isEmpty())
            assertTrue(host.isShowing(state.sessionKey))
            host.update(state.copy(presentation = DoubaoCallPresentation.CLOSING), actions, anchor, inApp = false)
        }
        await("external close detaches") { !root.isAttachedToWindow }
        main {
            assertSame(root, windows.added.single())
            assertEquals(listOf(state.sessionKey), completed)
            assertEquals(listOf(root), windows.removals)
            assertEquals(0, hangups)
        }
    }


    private fun awaitCommittedDraw(root: View) {
        val frame = java.util.concurrent.CountDownLatch(1)
        val listener = android.view.ViewTreeObserver.OnDrawListener {
            if (android.os.Build.VERSION.SDK_INT >= 29 && root.isHardwareAccelerated) {
                root.viewTreeObserver.registerFrameCommitCallback { frame.countDown() }
            } else root.postOnAnimation { frame.countDown() }
        }
        main { root.viewTreeObserver.addOnDrawListener(listener); root.invalidate() }
        try {
            assertTrue("native frame committed", frame.await(2, java.util.concurrent.TimeUnit.SECONDS))
        } finally { main { if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnDrawListener(listener) } }
    }

    private fun panel(root: View) = root.javaClass.getDeclaredMethod("getPanelBounds").apply { isAccessible = true }
        .invoke(root) as androidx.compose.ui.geometry.Rect

    private fun move(root: View, x: Float, y: Float) {
        host.javaClass.declaredMethods.single { it.name.startsWith("move-") }.apply { isAccessible = true }
            .invoke(host, root, androidx.compose.ui.geometry.Offset(x, y).packedValue)
    }

    @Test fun untouchedWindowTracksEachComposerFrameAndRestoresItsFullDefaultBounds() {
        val state = picture(-30L).copy(skipEntryAnimation = true)
        val full = callAnchor()
        val density = context.resources.displayMetrics.density
        fun shifted(dy: Float) = full.copy(bounds = full.bounds.translate(androidx.compose.ui.geometry.Offset(0f, dy)),
            composerTop = full.composerTop + dy)
        main { assertTrue(host.update(state, actions, shifted(-220f * density), inApp = true)) }
        await("keyboard anchor fixture") { windows.added.single().isLaidOut }
        val root = windows.added.single()
        var followed = androidx.compose.ui.geometry.Rect.Zero
        for (offset in listOf(-220f, -183f, -129f, -72f, -9f, 0f)) {
            val anchor = shifted(offset * density)
            main {
                val before = panel(root).bottom
                val previousComposerTop = root.javaClass.getDeclaredMethod("getAnchor").apply { isAccessible = true }
                    .invoke(root) as app.daiyu.android.ui.screens.chat.conversation.DoubaoCallAnchor
                assertTrue(host.update(state, actions, anchor, inApp = true))
                assertEquals("no deferred catch-up or final-inset teleport", anchor.composerTop - 10f * density,
                    panel(root).bottom, 1f)
                assertEquals("floating and composer must use the same per-frame displacement",
                    anchor.composerTop - previousComposerTop.composerTop, panel(root).bottom - before, 1f)
            }
            awaitCommittedDraw(root)
            main {
                assertEquals("same-frame composer bottom alignment", anchor.composerTop - 10f * density,
                    panel(root).bottom, 1f)
                val screen = IntArray(2)
                val local = IntArray(2)
                root.getLocationOnScreen(screen)
                root.getLocationInWindow(local)
                val params = root.layoutParams as WindowManager.LayoutParams
                assertEquals(params.x, screen[0] - local[0])
                assertEquals(params.y, screen[1] - local[1])
                val composition = (root as ViewGroup).getChildAt(0)
                assertEquals(-(screen[1] - local[1]).toFloat(), composition.translationY, .5f)
            }
        }
        main {
            followed = panel(root)
            val direct = state.copy(sessionKey = -31L)
            assertTrue(host.update(direct, actions, full, inApp = true))
            assertEquals("IME dismissal must match a direct no-IME entry", followed, panel(windows.added.last()))
        }
        val next = windows.added.last()
        await("fresh default window attached") { next.isLaidOut }
        main { move(next, 0f, -12f) }
        await("manual drag consumed") { panel(next).top < followed.top }
        main {
            val manual = panel(next)
            assertTrue(host.update(state.copy(sessionKey = -31L), actions, shifted(-80f * density), inApp = true))
            assertEquals("manually moved calls do not re-anchor", manual, panel(next))
        }
        val coordinatedState = state.copy(sessionKey = -35L)
        val inset = (full.composerTop - 210f * density).roundToInt()
        var currentInset = inset
        fun imeAnchor(bottom: Int, source: Int, target: Int) = full.copy(
            bounds = full.bounds.translate(androidx.compose.ui.geometry.Offset(0f, -bottom.toFloat())),
            composerTop = full.composerTop - bottom,
            imeInsets = app.daiyu.android.ui.screens.chat.conversation.DoubaoCallImeInsets(bottom, source, target))
        main { assertTrue(host.update(coordinatedState, actions, imeAnchor(inset, inset, inset), inApp = true)) }
        val coordinated = windows.added.last()
        await("coordinated IME fixture attached") { coordinated.isLaidOut }
        awaitCommittedDraw(coordinated)
        fun imeStage(target: Int, progress: List<Float>, resetTailMetadata: Boolean = false) {
            val source = currentInset
            var initial = androidx.compose.ui.geometry.Rect.Zero
            var endHeight = 0f
            val startComposer = full.composerTop - source
            val endComposer = full.composerTop - target
            main {
                initial = panel(coordinated)
                val safe = coordinated.javaClass.getDeclaredMethod("getSafeTop").apply { isAccessible = true }
                    .invoke(coordinated) as Int
                endHeight = minOf(followed.height, endComposer - 10f * density - maxOf(8f * density, safe.toFloat()))
                assertTrue(host.update(coordinatedState, actions, imeAnchor(source, source, target), inApp = true))
                assertEquals("IME reversal starts from the displayed bounds", initial, panel(coordinated))
            }
            for ((index, fraction) in progress.withIndex()) {
                currentInset = (source + (target - source) * fraction).roundToInt()
                val anchor = imeAnchor(currentInset, source, target).let {
                    if (resetTailMetadata && index >= progress.lastIndex - 1)
                        it.copy(imeInsets = app.daiyu.android.ui.screens.chat.conversation.DoubaoCallImeInsets(target)) else it
                }
                val actualProgress = (anchor.composerTop - startComposer) / (endComposer - startComposer)
                val expectedHeight = initial.height + (endHeight - initial.height) * actualProgress
                main {
                    val before = panel(coordinated)
                    val oldComposer = full.composerTop - (if (index == 0) source else
                        (source + (target - source) * progress[index - 1]).roundToInt())
                    assertTrue(host.update(coordinatedState, actions, anchor, inApp = true))
                    val bounds = panel(coordinated)
                    assertEquals("IME height and displacement share the composer's progress", expectedHeight, bounds.height, 1.5f)
                    assertEquals("coordinated IME bottom follows the composer in the same frame",
                        anchor.composerTop - 10f * density, bounds.bottom, 1f)
                    assertEquals(anchor.composerTop - oldComposer, bounds.bottom - before.bottom, 1f)
                    if (fraction < 1f && target < source) {
                        assertTrue("height restoration must already have intermediate displacement", bounds.top > initial.top + 1f)
                        assertTrue("height restoration must not finish before displacement", bounds.height < endHeight - 1f)
                    }
                }
                awaitCommittedDraw(coordinated)
                main { assertEquals(expectedHeight, panel(coordinated).height, 1.5f) }
            }
        }
        imeStage(0, listOf(.22f, .55f))
        main {
            val skewed = imeAnchor(currentInset, inset, 0).copy(imeInsets =
                app.daiyu.android.ui.screens.chat.conversation.DoubaoCallImeInsets((currentInset * .7f).roundToInt(), inset, 0))
            val before = panel(coordinated)
            assertTrue(host.update(coordinatedState, actions, skewed, inApp = true))
            assertEquals("inset metadata ahead of drawing must not move the displayed panel", before, panel(coordinated))
        }
        imeStage(inset, listOf(.31f, .68f))
        imeStage(0, listOf(.15f, .56f, .91f, 1f), resetTailMetadata = true)
        main { assertEquals("coordinated IME motion reaches the direct no-IME bounds", followed, panel(coordinated)) }
        val squeezedState = state.copy(sessionKey = -34L)
        val squeezed = full.copy(composerTop = 210f * density,
            bounds = full.bounds.translate(androidx.compose.ui.geometry.Offset(0f, 210f * density - full.composerTop)))
        main { assertTrue(host.update(squeezedState, actions, squeezed, inApp = true)) }
        val compressed = windows.added.last()
        await("genuinely squeezed fixture attached") { compressed.isLaidOut }
        awaitCommittedDraw(compressed)
        var compressedBounds = androidx.compose.ui.geometry.Rect.Zero
        main {
            compressedBounds = panel(compressed)
            assertTrue("fixture must actually squeeze the expanded panel", compressedBounds.height < followed.height - 1f)
            compressed.dispatchConfigurationChanged(android.content.res.Configuration(context.resources.configuration))
            assertEquals("configuration must not anticipate the next composer frame", compressedBounds, panel(compressed))
            move(compressed, 0f, 0f)
            assertEquals("zero displacement is not a drag", compressedBounds, panel(compressed))
        }
        val now = SystemClock.uptimeMillis()
        val touchX = compressedBounds.center.x
        val touchY = compressedBounds.top + 16f * density
        for ((action, time, x) in listOf(Triple(MotionEvent.ACTION_DOWN, now, touchX),
            Triple(MotionEvent.ACTION_MOVE, now + 16, touchX + 1f),
            Triple(MotionEvent.ACTION_UP, now + 32, touchX + 1f))) {
            val event = MotionEvent.obtain(now, time, action, x, touchY, 0)
            try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
        }
        main {
            assertEquals("light touch must retain the squeezed height", compressedBounds, panel(compressed))
            val shifted = squeezed.copy(composerTop = squeezed.composerTop + 4f * density)
            assertTrue(host.update(squeezedState, actions, shifted, inApp = true))
            assertEquals("light touch must retain composer following", compressedBounds.bottom + 4f * density,
                panel(compressed).bottom, 1f)
            compressedBounds = panel(compressed)
        }
        var drewIntermediateRestore = false
        val restoreFrames = mutableListOf<androidx.compose.ui.geometry.Rect>()
        try {
            main {
                windows.onDraw = { view ->
                    if (view === compressed && view.isAttachedToWindow) {
                        val bounds = panel(view)
                        restoreFrames += bounds
                        if (bounds.height > compressedBounds.height + 1f && bounds.height < followed.height - 1f)
                            drewIntermediateRestore = true
                    }
                }
                move(compressed, density, 0f)
                assertEquals("valid drag immediately starts, rather than jumps through, IME release",
                    compressedBounds.height, panel(compressed).height, 1f)
                assertNotNull(compressed.javaClass.getDeclaredMethod("getHeightRestore").apply { isAccessible = true }.invoke(compressed))
                assertTrue(host.update(squeezedState, actions, full, inApp = true))
                compressed.dispatchConfigurationChanged(android.content.res.Configuration(context.resources.configuration))
                assertEquals("IME updates and unchanged configuration cannot finish the drag animation",
                    compressedBounds.height, panel(compressed).height, 1f)
            }
            await("valid drag consumes its coalesced displacement") { panel(compressed).left > compressedBounds.left }
            await("drag release must draw a genuinely intermediate height: $restoreFrames") { drewIntermediateRestore }
            await("drag release reaches full height") { kotlin.math.abs(panel(compressed).height - followed.height) < 1f }
            awaitCommittedDraw(compressed)
        } finally { main { windows.onDraw = null } }
        main {
            assertEquals("restoration preserves the coalesced horizontal drag", compressedBounds.left + density,
                panel(compressed).left, 1f)
            val manual = panel(compressed)
            assertTrue(host.update(squeezedState, actions, full, inApp = true))
            assertEquals("IME retract preserves manual full-height placement", manual, panel(compressed))
            compressed.dispatchConfigurationChanged(android.content.res.Configuration(context.resources.configuration))
            assertEquals("configuration preserves manual full-height placement", manual, panel(compressed))
            assertTrue(host.update(squeezedState.copy(presentation = DoubaoCallPresentation.MINI), actions,
                squeezed, inApp = true))
            assertTrue(host.update(squeezedState, actions, full, inApp = true))
            assertEquals("mini round-trip must not revive the squeezed height", manual, panel(compressed))
        }
    }

    @Test fun stableDragIsCoalescedAndKeepsTheHandleOutsideTheStatusBar() {
        val state = picture(-32L).copy(skipEntryAnimation = true)
        main { assertTrue(host.update(state, actions, callAnchor(), inApp = true)) }
        await("coalesced drag fixture") { windows.added.single().isLaidOut }
        val root = windows.added.single()
        var updates = 0
        var before = androidx.compose.ui.geometry.Rect.Zero
        main {
            updates = windows.updates
            before = panel(root)
            repeat(12) { move(root, 0f, -1f) }
            assertEquals("pointer events must not submit separate WM layouts", updates, windows.updates)
        }
        await("one coalesced move") { panel(root).top < before.top }
        main {
            assertEquals(updates + 1, windows.updates)
            assertEquals(before.top - 12f, panel(root).top, .01f)
            move(root, 0f, -100_000f)
        }
        await("upward drag reaches safe top") {
            val safe = root.javaClass.getDeclaredMethod("getSafeTop").apply { isAccessible = true }.invoke(root) as Int
            panel(root).top == safe.toFloat()
        }
        main {
            val safe = root.javaClass.getDeclaredMethod("getSafeTop").apply { isAccessible = true }.invoke(root) as Int
            assertTrue("fixture must have a nonzero status-bar inset", safe > 0)
            assertEquals(safe.toFloat(), panel(root).top, 0f)
            assertTrue(host.update(state.copy(presentation = DoubaoCallPresentation.MINI), actions, callAnchor(), inApp = true))
            move(root, 0f, 20f)
        }
        await("mini moves away from upper boundary first") {
            val safe = root.javaClass.getDeclaredMethod("getSafeTop").apply { isAccessible = true }.invoke(root) as Int
            panel(root).top > safe
        }
        main { move(root, 0f, -100_000f) }
        await("mini handle remains below status bar") {
            val safe = root.javaClass.getDeclaredMethod("getSafeTop").apply { isAccessible = true }.invoke(root) as Int
            panel(root).top == safe.toFloat()
        }
        main {
            val count = windows.updates
            move(root, 0f, 12f)
            host.hide()
            assertEquals(count, windows.updates)
        }
        SystemClock.sleep(50)
        main { assertFalse(root.isAttachedToWindow); assertEquals(0, hangups) }
    }

    @Test fun frozenSystemWindowPanelHasNoOpacityJumpWhenItBecomesLive() {
        val state = picture(-33L)
        val motion = app.daiyu.android.ui.screens.chat.conversation.DoubaoCallMotionState()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main.immediate)
        var liveReady = false
        val callbacks = actions.copy(onPresentationReady = { liveReady = true })
        val drawing = androidx.test.platform.graphics.HardwareRendererCompat.isDrawingEnabled()
        val diagnostics = java.io.File(context.getExternalFilesDir(null), "visual-diagnostics/native-call-alpha")
            .apply { mkdirs() }
        fun capture(root: View, name: String, expectedBackground: Int,
            deadline: Long = SystemClock.uptimeMillis() + 2_000): android.graphics.Bitmap {
            var bounds = androidx.compose.ui.geometry.Rect.Zero
            main { bounds = panel(root); root.setBackgroundColor(expectedBackground) }
            val density = context.resources.displayMetrics.density
            val controlX = bounds.center.x.roundToInt()
            val controlY = (bounds.top - 27f * density).roundToInt()
            val channels = listOf<(Int) -> Int>(android.graphics.Color::red,
                android.graphics.Color::green, android.graphics.Color::blue)
            while (true) {
                val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                val control = image.getPixel(controlX, controlY)
                val composed = channels.all { channel ->
                    kotlin.math.abs(channel(control) - channel(expectedBackground)) < 8
                }
                val timely = SystemClock.uptimeMillis() <= deadline
                if (composed || !timely) {
                    java.io.File(diagnostics, "$name.png").outputStream().use {
                        check(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                    }
                    assertTrue("$name backdrop reached composition at $controlX,$controlY: " +
                        Integer.toHexString(control), composed && timely)
                    return image
                }
                image.recycle()
                SystemClock.sleep(8)
            }
        }
        try {
            if (!drawing) androidx.test.platform.graphics.HardwareRendererCompat.setDrawingEnabled(true)
            main { assertTrue(host.update(state, callbacks, callAnchor(), motion, inApp = true)) }
            await("native capture reached actual Genie raster") {
                windows.added.singleOrNull()?.isLaidOut == true && motion.transforming && motion.progress.value > 0f
            }
            val root = windows.added.single()
            main {
                assertTrue(root.isHardwareAccelerated)
                scope.launch { motion.progress.stop(); motion.progress.snapTo(.999f) }
            }
            await("near-end frozen native panel") { motion.transforming && motion.progress.value == .999f }
            main { assertTouchDispatch(root, emptyList()) }
            val frozenDark = capture(root, "frozen-dark", android.graphics.Color.BLACK)
            val frozenLight = capture(root, "frozen-light", android.graphics.Color.WHITE)
            main {
                assertTouchDispatch(root, emptyList()) { motion.transforming = false }
                scope.launch { motion.progress.snapTo(1f) }
            }
            await("actual live branch after frozen raster") { liveReady }
            main { assertTouchDispatch(root, listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) }
            val liveDeadline = SystemClock.uptimeMillis() + 2_000
            capture(root, "live-dark", android.graphics.Color.BLACK, liveDeadline).recycle()
            val live = capture(root, "live-light", android.graphics.Color.WHITE, liveDeadline)
            var bounds = androidx.compose.ui.geometry.Rect.Zero
            main { bounds = panel(root) }
            val density = context.resources.displayMetrics.density
            val controlX = bounds.center.x.roundToInt()
            val controlY = (bounds.top - 27f * density).roundToInt()
            val control = listOf(frozenDark.getPixel(controlX, controlY), frozenLight.getPixel(controlX, controlY), live.getPixel(controlX, controlY))
            java.io.File(diagnostics, "control.txt").writeText("panel=$bounds control=$controlX,$controlY rgba=" + control.joinToString { Integer.toHexString(it) })
            for (channel in listOf<(Int) -> Int>(android.graphics.Color::red, android.graphics.Color::green, android.graphics.Color::blue)) {
                assertTrue("dark control proves the new background reached composition: $control", channel(control[0]) < 8)
                assertTrue("light control proves the new background reached composition: $control", channel(control[1]) > 247)
                assertTrue("live control stays on the light background: $control", channel(control[2]) > 247)
            }
            val samples = mutableListOf<String>()
            for (i in 1..9) {
                val x = (bounds.left + 8f * density).roundToInt()
                val y = (bounds.top + bounds.height * (i / 10f)).roundToInt()
                val colors = listOf(frozenDark.getPixel(x, y), frozenLight.getPixel(x, y), live.getPixel(x, y))
                samples += "$x,$y: " + colors.joinToString { Integer.toHexString(it) }
                assertTrue("target panel exists in screenshot", kotlin.math.abs(android.graphics.Color.red(colors[2]) - 253) <= 4 &&
                    kotlin.math.abs(android.graphics.Color.green(colors[2]) - 251) <= 4 && android.graphics.Color.blue(colors[2]) >= 251)
                for (channel in listOf<(Int) -> Int>(android.graphics.Color::red, android.graphics.Color::green, android.graphics.Color::blue)) {
                    assertTrue("frozen panel leaks its black backdrop at $x,$y: $colors", kotlin.math.abs(channel(colors[0]) - channel(colors[1])) <= 4)
                    assertTrue("frozen-to-live opacity jump at $x,$y: $colors", kotlin.math.abs(channel(colors[1]) - channel(colors[2])) <= 4)
                }
            }
            java.io.File(diagnostics, "samples.txt").writeText("panel=$bounds hardware=${root.isHardwareAccelerated}\n" + samples.joinToString("\n"))
        } finally {
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            if (!drawing) androidx.test.platform.graphics.HardwareRendererCompat.setDrawingEnabled(false)
        }
    }
}
