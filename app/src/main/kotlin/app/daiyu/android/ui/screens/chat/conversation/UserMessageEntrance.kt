package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationSource
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** One UI-owned send, shared by its preparation preview and durable user row. */
class UserMessageEntrance(val key: String, internal val waitForIme: Boolean = false) {
    var messageId by mutableStateOf(key)
        internal set
    internal var claimed = false
        private set

    internal fun claim(): Boolean {
        if (claimed) return false
        claimed = true
        return true
    }
}

internal class UserMessageEntranceMotion(
    val request: UserMessageEntrance,
    val progress: Animatable<Float, AnimationVector1D>,
) {
    var positioned by mutableStateOf(false)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun rememberUserMessageEntrance(
    request: UserMessageEntrance?,
    active: Boolean,
    awaitPlacement: Boolean = false,
): UserMessageEntranceMotion? {
    if (request == null) return null
    val progress = remember(request) { Animatable(if (request.claimed) 1f else 0f) }
    val motion = remember(request, progress) { UserMessageEntranceMotion(request, progress) }
    var wasActive by remember(request) { mutableStateOf(false) }
    DisposableEffect(request) { onDispose { if (wasActive) request.claim() } }
    val density = LocalDensity.current
    val ime = WindowInsets.ime
    val imeSource = WindowInsets.imeAnimationSource
    val imeTarget = WindowInsets.imeAnimationTarget
    val navigationBars = WindowInsets.navigationBars
    LaunchedEffect(motion, active, awaitPlacement) {
        if (!active) {
            if (wasActive || request.claimed) {
                request.claim()
                progress.snapTo(1f)
            }
            return@LaunchedEffect
        }
        wasActive = true
        if (request.claimed) return@LaunchedEffect
        snapshotFlow { !awaitPlacement || motion.positioned }.first { it }
        if (request.waitForIme) {
            // Let the system finish most of its travel; a refused hide must not hide the message.
            withTimeoutOrNull(240) {
                snapshotFlow {
                    userMessageEntranceImeReady(ime.getBottom(density), imeSource.getBottom(density),
                        imeTarget.getBottom(density), navigationBars.getBottom(density))
                }.first { it }
            }
        }
        if (request.claim()) progress.animateTo(1f, tween(300, easing = LinearEasing))
    }
    return motion
}

internal fun userMessageEntranceScale(progress: Float): Float {
    val remaining = 1f - progress.coerceIn(0f, 1f)
    return 1f - .18f * remaining * remaining * (2.6f * remaining - 1.6f)
}

internal fun userMessageActionEntranceAlpha(progress: Float): Float =
    ((progress - .25f) / .75f).coerceIn(0f, 1f)

internal fun userMessageEntranceImeReady(current: Int, source: Int, target: Int, navigation: Int): Boolean =
    current <= navigation || source > target && current <= target + (source - target) * .35f
