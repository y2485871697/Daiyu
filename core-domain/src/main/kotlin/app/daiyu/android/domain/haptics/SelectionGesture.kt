package app.daiyu.android.domain.haptics

/** Observes only: the host must still deliver every event to native selection/scrolling. */
class SelectionGesture(
    private val clock: HapticClock,
    private val longPressMillis: Long,
    private val touchSlop: Float,
    private val onSelectionStart: () -> Unit,
) {
    init { require(longPressMillis > 0); require(touchSlop >= 0) }
    private var downAt: Long? = null
    private var x = 0f
    private var y = 0f
    private var eligible = false
    private var fired = false
    private var blockedUntil = 0L

    fun down(x: Float, y: Float) {
        this.x = x
        this.y = y
        downAt = clock.nowMillis()
        eligible = true
        fired = false
        blockedUntil = 0L
    }
    fun move(x: Float, y: Float) {
        if ((x - this.x) * (x - this.x) + (y - this.y) * (y - this.y) > touchSlop * touchSlop) {
            eligible = false
        }
    }
    fun deadlineMillis(): Long? = downAt?.takeIf { eligible && !fired }?.plus(longPressMillis)
    fun timeout() {
        val start = downAt ?: return
        if (!eligible || fired || clock.nowMillis() - start < longPressMillis) return
        fired = true
        onSelectionStart()
    }
    fun up() {
        val elapsed = downAt?.let { clock.nowMillis() - it } ?: 0L
        // Release after the timeout must block navigation even if the UI timeout was delayed.
        if (fired || elapsed >= longPressMillis) blockedUntil = clock.nowMillis() + 300L
        finish()
    }
    fun cancel() {
        if (fired) blockedUntil = clock.nowMillis() + 300L
        finish()
    }
    private fun finish() { downAt = null; eligible = false; fired = false }
    fun canOpenLink(): Boolean = !fired && clock.nowMillis() >= blockedUntil
}
