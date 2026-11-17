package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/** One UI-owned send, shared by its preparation preview and durable user row. */
class UserMessageEntrance(val key: String) {
    var messageId by mutableStateOf(key)
        internal set
    private var claimed = false

    internal fun claim(): Boolean {
        if (claimed) return false
        claimed = true
        return true
    }
}

internal class UserMessageEntranceMotion(
    val request: UserMessageEntrance,
    val progress: Animatable<Float, AnimationVector1D>,
)

@Composable
internal fun rememberUserMessageEntrance(
    request: UserMessageEntrance?,
    active: Boolean,
): UserMessageEntranceMotion? {
    if (request == null) return null
    val progress = remember(request) { Animatable(if (request.claim() && active) 0f else 1f) }
    LaunchedEffect(progress, active) {
        if (!active) progress.snapTo(1f)
        else progress.animateTo(1f, tween(300, easing = LinearEasing))
    }
    return remember(request, progress) { UserMessageEntranceMotion(request, progress) }
}

internal fun userMessageEntranceScale(progress: Float): Float {
    val remaining = 1f - progress.coerceIn(0f, 1f)
    return 1f - .18f * remaining * remaining * (2.6f * remaining - 1.6f)
}
