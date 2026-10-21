package app.daiyu.android.ui.haptics

import android.view.View
import app.daiyu.android.domain.haptics.HapticKind
import app.daiyu.android.haptics.HapticsRuntime
import java.lang.ref.WeakReference

/** Existing click sites delegate to P31; no second system-click fallback or shadow preferences. */
internal object TouchHaptics {
    private var actionDepth = 0
    private var runtime = WeakReference<HapticsRuntime>(null)

    /** Install the application-owned runtime once; stale disposal cannot unbind its replacement. */
    fun install(value: HapticsRuntime): AutoCloseable {
        val registration = WeakReference(value)
        runtime = registration
        return AutoCloseable { if (runtime === registration) runtime = WeakReference(null) }
    }

    fun click(view: View?) = perform(view, HapticKind.CLICK)
    fun longPress(view: View?) = perform(view, HapticKind.LONG_PRESS)
    fun tick(view: View?) = perform(view, HapticKind.TICK)
    fun gestureThreshold(view: View?) = perform(view, HapticKind.THRESHOLD)

    fun <T> action(view: View?, kind: HapticKind = HapticKind.CLICK, block: () -> T): T {
        perform(view, kind)
        actionDepth++
        return try { block() } finally { actionDepth-- }
    }

    private fun perform(view: View?, kind: HapticKind) {
        if (actionDepth == 0 && view != null) runtime.get()?.uiTouch(view, kind)
    }
}
