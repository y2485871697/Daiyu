package app.daiyu.android.haptics

import android.app.Application
import android.content.Context
import android.test.InstrumentationTestCase
import android.view.View
import android.view.WindowManager
import app.daiyu.android.domain.haptics.HapticDelivery
import app.daiyu.android.domain.haptics.HapticKind
import app.daiyu.android.domain.haptics.HapticPort
import app.daiyu.android.domain.haptics.HapticRequest
import app.daiyu.android.ui.haptics.TouchHaptics

@Suppress("DEPRECATION")
class TouchHapticsTest : InstrumentationTestCase() {
    private class Port : HapticPort {
        val requests = mutableListOf<HapticRequest>()
        override fun play(request: HapticRequest): HapticDelivery {
            requests += request
            return HapticDelivery.PLAYED
        }
        override fun cancel() = Unit
    }

    private fun withFeedback(block: (View, HapticsRuntime, Port) -> Unit) {
        val context = instrumentation.targetContext
        val name = "touch-actions-${System.nanoTime()}"
        var failure: Throwable? = null
        try {
            instrumentation.runOnMainSync {
                val store = LocalHapticSettingsStore(context.getSharedPreferences(name, Context.MODE_PRIVATE))
                val port = Port()
                val runtime = HapticsRuntime(store) { port }
                val registration = TouchHaptics.install(runtime)
                val view = object : View(context) {
                    override fun isAttachedToWindow() = true
                    override fun getWindowVisibility() = VISIBLE
                }
                try { block(view, runtime, port) }
                catch (error: Throwable) { failure = error }
                finally {
                    registration.close(); runtime.close()
                    (context.applicationContext as? app.daiyu.android.ChatApplication)?.let {
                        TouchHaptics.install(it.hapticsRuntime)
                    }
                }
            }
            failure?.let { throw it }
        } finally { context.deleteSharedPreferences(name) }
    }

    fun testNestedAndLegacyCallbacksHaveOneFeedbackOwner() = withFeedback { view, _, port ->
        var actions = 0
        TouchHaptics.action(view) {
            TouchHaptics.click(view)
            TouchHaptics.action(view) { actions++ }
        }
        assertEquals(1, actions)
        assertEquals(listOf(HapticKind.CLICK), port.requests.map { it.kind })
    }

    fun testTwoImmediateIndependentClicksAreNotTimeDebounced() = withFeedback { view, _, port ->
        repeat(2) { TouchHaptics.action(view) {} }
        assertEquals(2, port.requests.size)
    }

    fun testLongPressHasNoNestedClickFeedback() = withFeedback { view, _, port ->
        TouchHaptics.action(view, HapticKind.LONG_PRESS) { TouchHaptics.click(view) }
        assertEquals(listOf(HapticKind.LONG_PRESS), port.requests.map { it.kind })
    }

    fun testCallbackExceptionRestoresNextActionOwner() = withFeedback { view, _, port ->
        try { TouchHaptics.action(view) { throw IllegalStateException("expected") } }
        catch (_: IllegalStateException) { }
        TouchHaptics.action(view) {}
        assertEquals(2, port.requests.size)
    }

    fun testDisabledAppTouchStillRunsBusinessActionWithoutFeedback() = withFeedback { view, runtime, port ->
        assertTrue(runtime.store.write(runtime.store.read().copy(touch = false)))
        var actions = 0
        TouchHaptics.action(view) { actions++ }
        assertEquals(1, actions)
        assertTrue(port.requests.isEmpty())
    }

    fun testOnlyVisibleOwnedOverlayWorksWithoutForegroundActivity() = withFeedback { view, runtime, port ->
        runtime.installForeground(instrumentation.targetContext.applicationContext as Application)
        TouchHaptics.action(view) {}
        assertTrue(port.requests.isEmpty())
        val types = listOf(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        types.forEachIndexed { index, type ->
            view.layoutParams = WindowManager.LayoutParams().apply { this.type = type }
            TouchHaptics.action(view) {}
            assertEquals(index + 1, port.requests.size)
        }
        assertTrue(runtime.store.write(runtime.store.read().copy(touch = false)))
        TouchHaptics.action(view) {}
        assertEquals(types.size, port.requests.size)
    }
}
