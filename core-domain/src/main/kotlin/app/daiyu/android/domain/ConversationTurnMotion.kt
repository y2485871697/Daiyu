package app.daiyu.android.domain

import kotlin.math.min
import kotlin.math.sqrt

/** Retains velocity between messages. A null remaining distance means the target is not measured. */
class ConversationTurnMotion(viewportPx: Float) {
    private val viewport = viewportPx.coerceAtLeast(1f)
    private val acceleration = viewport * 32f
    private val cruiseSpeed = viewport * 10f
    private var speed = 0f

    fun advance(elapsedSeconds: Float, remainingPx: Float?): Float {
        val dt = elapsedSeconds.coerceIn(0f, 0.05f)
        if (dt == 0f) return 0f
        val remaining = remainingPx?.coerceAtLeast(0f)
        val brakingSpeed = remaining?.let { sqrt(2f * acceleration * it) } ?: cruiseSpeed
        speed = min(speed + acceleration * dt, min(cruiseSpeed, brakingSpeed))
        val step = min(speed * dt, viewport * 0.5f)
        return if (remaining == null) step else min(step, remaining)
    }
}
