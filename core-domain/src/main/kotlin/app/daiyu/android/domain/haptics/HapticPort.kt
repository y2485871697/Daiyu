package app.daiyu.android.domain.haptics

/** Monotonic time only: wall-clock changes must not bypass throttling. */
fun interface HapticClock { fun nowMillis(): Long }

enum class HapticKind { CLICK, KEYBOARD, LONG_PRESS, TICK, THRESHOLD, CONFIRM }
/** Foreground agent actions use their own primitive/fallback semantics, not a View click. */
enum class HapticAgentAction(val baseScale: Float) {
    TAP(0.45f), LONG_PRESS(0.7f), SWIPE(0.32f), RUN_STARTED(0.62f)
}
data class HapticRequest(
    val kind: HapticKind,
    val intensity: HapticIntensity,
    val preview: Boolean = false,
    /** A stopped View may reject ticks; only generation may fall back to the motor. */
    val generation: Boolean = false,
    val agentAction: HapticAgentAction? = null,
)
enum class HapticDelivery { PLAYED, SYSTEM_DISABLED, UNAVAILABLE, PERMISSION_DENIED, FAILED }

/** No Android types, scheduling, preferences, or owner policy in the device boundary. */
interface HapticPort {
    fun play(request: HapticRequest): HapticDelivery
    fun cancel()
}
