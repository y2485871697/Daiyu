package app.daiyu.android

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
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

    private class RecordingWindows(context: Context,
        private val actual: WindowManager = context.getSystemService(WindowManager::class.java)) : WindowManager by actual {
        val added = mutableListOf<View>()
        val removals = mutableListOf<View>()
        var failAdd = false
        var failUpdate = false
        override fun updateViewLayout(view: View, params: ViewGroup.LayoutParams) {
            if (failUpdate) throw SecurityException("synthetic overlay update rejection")
            actual.updateViewLayout(view, params)
        }
        override fun addView(view: View, params: ViewGroup.LayoutParams) {
            if (failAdd) throw SecurityException("synthetic overlay rejection")
            actual.addView(view, params); added.add(view)
        }
        override fun removeViewImmediate(view: View) { removals.add(view); actual.removeViewImmediate(view) }
        fun detachExternally(view: View) { actual.removeViewImmediate(view) }
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
        var drewIntermediateFrame = false
        var observer: android.view.ViewTreeObserver? = null
        val probe = android.view.ViewTreeObserver.OnDrawListener {
            if (root.isAttachedToWindow && root.alpha > 0f && root.alpha < 1f &&
                root.scaleX < 1f && root.scaleY < 1f) drewIntermediateFrame = true
        }
        try {
            main {
                observer = root.viewTreeObserver
                observer!!.addOnDrawListener(probe)
                close()
            }
            await(message) { drewIntermediateFrame }
        } finally {
            main { observer?.takeIf { it.isAlive }?.removeOnDrawListener(probe) }
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
        main { windows = RecordingWindows(context); host = VoiceCallFloatingWindow(context, windows) }
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
            host.update(state.copy(presentation = DoubaoCallPresentation.CLOSING), actions)
            assertFalse(host.isShowing(state.sessionKey))
            assertTrue((root.layoutParams as WindowManager.LayoutParams).flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
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

    @Test fun lateOldExitCannotRemoveANewCallAndDestroyDoesNotWaitForAnimation() {
        val old = picture()
        main { host.update(old, actions) }
        await("first surface laid out") { windows.added.single().isLaidOut }
        val next = picture(-3L)
        main {
            val departing = windows.added.single()
            host.dismiss()
            host.update(next, actions)
            assertTrue("new call must not truncate the old exit", departing.isAttachedToWindow)
            assertTrue(completed.isEmpty())
            host.update(old.copy(presentation = DoubaoCallPresentation.CLOSING), actions)
        }
        await("new session attached") { host.isShowing(next.sessionKey) }
        SystemClock.sleep(VoiceCallFloatingWindow.CLOSE_MILLIS + 40)
        main {
            assertTrue(host.isShowing(next.sessionKey))
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
            val origin = IntArray(2)
            root.getLocationOnScreen(origin)
            val inset = 38f * context.resources.displayMetrics.density
            x = origin[0] + root.width - inset
            y = origin[1] + inset
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
        main { assertTrue(host.update(state, callbacks, anchor, motion, inApp = true)) }
        await("in-app entry returns the same window to a touchable bounded frame") {
            windows.added.singleOrNull()?.let { root ->
                val params = root.layoutParams as WindowManager.LayoutParams
                ready && root.isLaidOut && root.width == params.width && root.height == params.height &&
                    motion.progress.value == 1f && !motion.transforming &&
                    params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE == 0
            } == true
        }
        val root = windows.added.single()
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
            host.update(state.copy(presentation = DoubaoCallPresentation.CLOSING), callbacks, anchor, motion, inApp = true)
            host.update(state.copy(presentation = DoubaoCallPresentation.CLOSING), callbacks, anchor, motion, inApp = true)
            assertFalse(host.isShowing(state.sessionKey))
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

}
