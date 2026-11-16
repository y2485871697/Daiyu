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
        override fun addView(view: View, params: ViewGroup.LayoutParams) { actual.addView(view, params); added.add(view) }
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
        main {
            // Production stops audio synchronously before publishing this visual close.
            host.update(state.copy(presentation = DoubaoCallPresentation.CLOSING), actions)
            assertFalse(host.isShowing(state.sessionKey))
            assertTrue((root.layoutParams as WindowManager.LayoutParams).flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
            assertTrue(root.isAttachedToWindow)
        }
        await("distinct external fade and scale progressed") {
            root.isAttachedToWindow && root.alpha > 0f && root.alpha < 1f && root.scaleX < 1f && root.scaleY < 1f
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

    @Test fun foregroundPreparationKeepsTheSameComposedWindowAcrossBackgroundUpdates() {
        val state = picture(-7L)
        main { host.update(state, actions, visible = false) }
        await("transparent prepared surface laid out") { windows.added.single().isLaidOut }
        val root = windows.added.single()
        main {
            assertFalse(host.isShowing(state.sessionKey))
            assertEquals(0f, (root.layoutParams as WindowManager.LayoutParams).alpha, 0f)
            assertTrue((root.layoutParams as WindowManager.LayoutParams).flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
            repeat(3) {
                host.update(state, actions)
                assertTrue(host.isShowing(state.sessionKey))
                assertEquals(1f, (root.layoutParams as WindowManager.LayoutParams).alpha, 0f)
                assertEquals(0, (root.layoutParams as WindowManager.LayoutParams).flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                host.update(state.copy(reply = "same call"), actions)
                host.update(state, actions, visible = false)
                assertFalse(host.isShowing(state.sessionKey))
                assertTrue(root.isAttachedToWindow)
            }
            assertEquals(listOf(root), windows.added)
            assertTrue(windows.removals.isEmpty())
            assertTrue(completed.isEmpty())
            assertEquals(0, hangups)
        }
    }

    @Test fun hiddenCloseCompletesOnceWithoutAnimatingAnInvisibleWindow() {
        val state = picture(-8L)
        main { host.update(state, actions, visible = false) }
        await("hidden close surface laid out") { windows.added.single().isLaidOut }
        main {
            val root = windows.added.single()
            host.update(state.copy(presentation = DoubaoCallPresentation.CLOSING), actions)
            assertFalse(root.isAttachedToWindow)
            host.update(state.copy(presentation = DoubaoCallPresentation.CLOSING), actions)
            assertEquals(listOf(state.sessionKey), completed)
            assertEquals(listOf(root), windows.removals)
            assertEquals(0, hangups)
        }
    }

    @Test fun detachedPreparedSurfaceIsRebuiltInsteadOfReusingADestroyedComposition() {
        val state = picture(-9L)
        main { host.update(state, actions, visible = false) }
        await("prepared detach surface laid out") { windows.added.single().isLaidOut }
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
}
