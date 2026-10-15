package app.daiyu.android.ui.haptics

import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View

/** UI feedback uses the platform's motor theme and respects its touch-feedback switch. */
internal object TouchHaptics {
    fun click(view: View?) = perform(view, HapticFeedbackConstants.CONTEXT_CLICK)
    fun longPress(view: View?) = perform(view, HapticFeedbackConstants.LONG_PRESS)
    fun gestureThreshold(view: View?) = perform(view,
        if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
        else HapticFeedbackConstants.CLOCK_TICK)

    private fun perform(view: View?, constant: Int) {
        if (view == null || Settings.System.getInt(view.context.contentResolver,
                Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 0) return
        view.performHapticFeedback(constant)
    }
}
