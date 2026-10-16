package app.daiyu.android.haptics

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import app.daiyu.android.domain.haptics.HapticClock
import app.daiyu.android.domain.haptics.SelectionGesture

/**
 * Forward touch events here BEFORE native TextView handling. Always returns false; never consumes
 * selection down/move/up or performs click/navigation. The host wraps link activation in canOpenLink.
 * Native handle/long-press haptics are suppressed locally; feedback uses the Session's host View.
 */
class HapticSelectionObserver(
    private val textView: View,
    session: HapticsRuntime.Session,
) : AutoCloseable {
    private val handler = Handler(Looper.getMainLooper())
    private val originalHaptics = textView.isHapticFeedbackEnabled
    private val gesture = SelectionGesture(HapticClock(SystemClock::uptimeMillis),
        ViewConfiguration.getLongPressTimeout().toLong(),
        ViewConfiguration.get(textView.context).scaledTouchSlop.toFloat(), session::selectionStart)
    private var pointer = -1
    private var closed = false
    private val timeout = Runnable { if (!closed) gesture.timeout() }
    init { textView.isHapticFeedbackEnabled = false }

    fun onTouch(event: MotionEvent): Boolean {
        if (closed) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointer = event.getPointerId(0)
                gesture.down(event.x, event.y)
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(pointer)
                if (index < 0) gesture.cancel()
                else gesture.move(event.getX(index), event.getY(index))
            }
            MotionEvent.ACTION_UP -> { gesture.up(); pointer = -1 }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                gesture.cancel(); pointer = -1
            }
        }
        handler.removeCallbacks(timeout)
        gesture.deadlineMillis()?.let { handler.postAtTime(timeout, it) }
        return false
    }
    fun canOpenLink(): Boolean = !closed && gesture.canOpenLink()
    override fun close() {
        if (closed) return
        closed = true
        handler.removeCallbacks(timeout)
        gesture.cancel()
        textView.isHapticFeedbackEnabled = originalHaptics
    }
}
