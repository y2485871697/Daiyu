package app.daiyu.android.ui.navigation

import app.daiyu.android.diagnostics.BackPerformance
import app.daiyu.android.diagnostics.BackPerformanceRecorder
import app.daiyu.android.diagnostics.captureBackPerformanceToken
import kotlinx.coroutines.CancellationException
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.math.sqrt

internal fun pageBackInitialVelocity(progress: Float, velocity: Float): Float =
    velocity.coerceAtMost(sqrt(146f) * (1f - progress).coerceAtLeast(0f))

/**
 * A prepared gesture return owns its clock, not the destination's composition. Start waiting for
 * the first Compose frame during the commit handoff; the destination adopts this same animation.
 * This is speculative until the route accepts the request: abandon must cancel it on a veto.
 */
internal class PageBackCompletionMotion(scope: CoroutineScope, progress: Float, velocity: Float,
    val diagnosticToken: BackPerformanceRecorder.Token? = captureBackPerformanceToken()) {
    val animation = Animatable(progress)
    private val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        BackPerformance.mark(BackPerformanceRecorder.Event.MOTION_START, diagnosticToken)
        var first = true
        try {
            animation.animateTo(1f, spring(dampingRatio = 1f, stiffness = 146f, visibilityThreshold = 0.0025f),
                initialVelocity = pageBackInitialVelocity(progress, velocity)) {
                if (first) {
                    first = false
                    BackPerformance.mark(BackPerformanceRecorder.Event.MOTION_FIRST_TICK, diagnosticToken)
                }
            }
            BackPerformance.mark(BackPerformanceRecorder.Event.MOTION_FINISHED, diagnosticToken)
        } catch (cancelled: CancellationException) {
            BackPerformance.mark(BackPerformanceRecorder.Event.MOTION_CANCELLED, diagnosticToken)
            throw cancelled
        } catch (failure: Throwable) {
            BackPerformance.mark(BackPerformanceRecorder.Event.MOTION_FAILED, diagnosticToken)
            throw failure
        }
    }

    suspend fun awaitCompletion() = job.join()
    fun cancel() {
        if (job.isActive) BackPerformance.mark(BackPerformanceRecorder.Event.MOTION_CANCEL_REQUESTED, diagnosticToken)
        job.cancel()
    }
}
