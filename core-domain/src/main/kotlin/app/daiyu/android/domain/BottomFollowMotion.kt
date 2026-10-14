package app.daiyu.android.domain

import kotlin.math.min
import kotlin.math.sqrt

/** Stateful follow motion: target jumps do not become velocity jumps. Units are px/s. */
class BottomFollowMotion {
    var velocityPxPerSecond = 0f
        private set
    private var lastFrameNanos: Long? = null

    fun reset() {
        velocityPxPerSecond = 0f
        lastFrameNanos = null
    }

    fun step(distancePx: Float, frameTimeNanos: Long, density: Float): Float {
        if (!distancePx.isFinite() || distancePx <= 0f || !density.isFinite() || density <= 0f) {
            reset()
            return 0f
        }
        val previous = lastFrameNanos
        lastFrameNanos = frameTimeNanos
        if (previous == null || frameTimeNanos <= previous) return 0f
        // A stalled UI never repays the entire missed time in one large scroll.
        var timeLeft = ((frameTimeNanos - previous) / 1_000_000_000f).coerceAtMost(0.032f)
        val acceleration = 2400f * density
        var remaining = distancePx
        var movement = 0f
        while (timeLeft > 0.000001f && remaining > 0f) {
            val dt = min(timeLeft, 1f / 240f)
            val targetVelocity = min(720f * density, min(remaining / 0.16f, sqrt(2f * acceleration * remaining)))
            val nextVelocity = targetVelocity.coerceIn(
                (velocityPxPerSecond - acceleration * dt).coerceAtLeast(0f),
                velocityPxPerSecond + acceleration * dt,
            )
            val delta = min(remaining, (velocityPxPerSecond + nextVelocity) * 0.5f * dt)
            velocityPxPerSecond = nextVelocity
            movement += delta
            remaining -= delta
            timeLeft -= dt
        }
        // Eliminate the asymptotic subpixel tail without a visible snap.
        if (remaining <= 0.5f && movement > 0f) {
            movement = distancePx
            reset()
        }
        return movement.coerceIn(0f, distancePx)
    }
}
