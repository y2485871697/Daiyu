package app.daiyu.android.ui.navigation

import kotlin.math.sqrt

internal fun pageBackInitialVelocity(progress: Float, velocity: Float): Float =
    velocity.coerceAtMost(sqrt(146f) * (1f - progress).coerceAtLeast(0f))
