package app.daiyu.android.ui.navigation

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
internal class PageBackCompletionMotion(scope: CoroutineScope, progress: Float, velocity: Float) {
    val animation = Animatable(progress)
    private val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        animation.animateTo(1f, spring(dampingRatio = 1f, stiffness = 146f, visibilityThreshold = 0.0025f),
            initialVelocity = pageBackInitialVelocity(progress, velocity))
    }

    suspend fun awaitCompletion() = job.join()
    fun cancel() = job.cancel()
}
