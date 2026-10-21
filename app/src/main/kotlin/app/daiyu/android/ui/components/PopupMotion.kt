package app.daiyu.android.ui.components

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import top.yukonga.miuix.kmp.basic.ListPopupDefaults

internal const val PopupExitRetentionMillis = 160L

internal class PopupMotion(
    val visibleState: MutableTransitionState<Boolean>,
    val fraction: State<Float>,
    val alpha: State<Float>,
)

internal val LocalPopupMotion = staticCompositionLocalOf<PopupMotion?> { null }

@Composable
internal fun rememberPopupMotion(expanded: Boolean): PopupMotion {
    val visibleState = remember { MutableTransitionState(false) }
    visibleState.targetState = expanded
    val transition = rememberTransition(visibleState, label = "DaiyuPopup")
    val fraction = transition.animateFloat(transitionSpec = {
        if (false isTransitioningTo true) ListPopupDefaults.FractionAnimationSpec
        else tween(durationMillis = 150)
    }, label = "popupFraction") { if (it) 1f else 0f }
    val alpha = transition.animateFloat(transitionSpec = {
        if (false isTransitioningTo true) ListPopupDefaults.AlphaEnterAnimationSpec
        else ListPopupDefaults.AlphaExitAnimationSpec
    }, label = "popupAlpha") { if (it) 1f else 0f }
    return remember(visibleState, fraction, alpha) { PopupMotion(visibleState, fraction, alpha) }
}
