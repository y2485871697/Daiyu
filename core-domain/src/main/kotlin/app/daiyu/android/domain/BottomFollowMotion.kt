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

    /** Retain momentum while waiting for a target, without counting idle time. */
    fun pauseFrameClock() {
        lastFrameNanos = null
    }

    fun step(distancePx: Float, frameTimeNanos: Long, density: Float, continuous: Boolean = false): Float {
        if (!distancePx.isFinite() || distancePx < 0f || !density.isFinite() || density <= 0f) {
            reset()
            return 0f
        }
        if (distancePx == 0f) {
            // Active reveal clocks its idle frames without inventing scroll or losing momentum.
            if (continuous) lastFrameNanos = frameTimeNanos else reset()
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
        val frameLimit = min(distancePx, 14f * density)
        while (timeLeft > 0.000001f && remaining > 0f && movement < frameLimit) {
            val dt = min(timeLeft, 1f / 240f)
            val targetVelocity = if (continuous) 720f * density
                else min(720f * density, sqrt(2f * acceleration * remaining))
            val nextVelocity = targetVelocity.coerceIn(
                (velocityPxPerSecond - acceleration * dt).coerceAtLeast(0f),
                velocityPxPerSecond + acceleration * dt,
            )
            val delta = min(min(remaining, frameLimit - movement), (velocityPxPerSecond + nextVelocity) * 0.5f * dt)
            velocityPxPerSecond = nextVelocity
            movement += delta
            remaining -= delta
            timeLeft -= dt
        }
        // Eliminate the asymptotic subpixel tail without a visible snap.
        if (remaining <= 0.5f && movement > 0f && distancePx <= frameLimit) {
            movement = distancePx
            if (!continuous) reset()
        }
        return movement.coerceIn(0f, frameLimit)
    }
}

/** Consumable safe-viewport loss for one following owner. Units are px. */
class BottomFollowViewport {
    var owner: String? = null
        private set
    var observedSafeHeight: Int? = null
        private set
    var pendingPx = 0f
        private set

    fun reset() {
        owner = null
        observedSafeHeight = null
        pendingPx = 0f
    }

    fun observe(owner: String?, height: Int, following: Boolean) {
        if (!following || owner == null) {
            reset()
            return
        }
        val next = height.coerceAtLeast(0)
        val previous = observedSafeHeight
        pendingPx = if (this.owner != owner || previous == null) 0f
            else (pendingPx + previous.toFloat() - next.toFloat()).coerceAtLeast(0f)
        this.owner = owner
        observedSafeHeight = next
    }

    fun correction(obscuredPx: Float): Float {
        // Layout may already have absorbed some loss; never carry it into later text growth.
        pendingPx = min(pendingPx, obscuredPx.coerceAtLeast(0f))
        return pendingPx
    }

    fun consume(actualPx: Float) {
        if (actualPx.isFinite() && actualPx > 0f) pendingPx = (pendingPx - actualPx).coerceAtLeast(0f)
    }
}
