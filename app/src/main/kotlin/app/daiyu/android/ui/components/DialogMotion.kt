package app.daiyu.android.ui.components

import androidx.compose.animation.core.animate
import androidx.compose.runtime.DisposableEffect
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.drawWithContent
import top.yukonga.miuix.kmp.anim.DecelerateEasing
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog as MaterialAlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.Dialog as ComposeDialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import top.yukonga.miuix.kmp.anim.folmeSpring

internal val DialogEnterSettled = SemanticsPropertyKey<Boolean>("DialogEnterSettled")

// Same motion as Miuix 0.9.4-rc01 WindowDialog; callers retain their own layout.
internal fun dialogEnterSpec(largeScreen: Boolean): FiniteAnimationSpec<Float> =
    if (largeScreen) folmeSpring(damping = 0.9f, response = 0.3f)
    else spring(dampingRatio = 0.88f, stiffness = 450f, visibilityThreshold = 0.0001f)

@Composable
internal fun rememberDialogEnterProgress(largeScreen: Boolean): Animatable<Float, androidx.compose.animation.core.AnimationVector1D> {
    val progress = remember { Animatable(0f, visibilityThreshold = 0.0001f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, dialogEnterSpec(largeScreen)) }
    return progress
}

@Composable
internal fun Modifier.dialogEnterMotion(): Modifier {
    val size = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current.density
    val largeScreen = size.width / density >= 840f && size.height / density >= 480f
    val progress = rememberDialogEnterProgress(largeScreen)
    return semantics {
        this[DialogEnterSettled] = progress.value == 1f && !progress.isRunning
    }.graphicsLayer {
        val value = progress.value
        if (largeScreen) {
            scaleX = 0.8f + 0.2f * value
            scaleY = scaleX
            alpha = value
        } else {
            translationY = (1f - value) * size.height
        }
    }
}

@Composable
internal fun Modifier.dialogDimBackground(color: Color, animate: Boolean = true): Modifier {
    if (!animate) return drawWithContent {
        drawRect(color)
        drawContent()
    }
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(300, easing = DecelerateEasing(1.5f))) }
    return drawWithContent {
        drawRect(color.copy(alpha = color.alpha * progress.value))
        drawContent()
    }
}

@Composable
internal fun PlatformDialogFade() {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    SideEffect { window?.usePlatformDialogFade() }
}

@Composable
internal fun DisablePlatformDialogAnimation() {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    val targetDim = remember(window) { window?.attributes?.dimAmount ?: 0f }
    SideEffect { window?.setWindowAnimations(0) }
    DisposableEffect(window) {
        window?.setDimAmount(0f)
        onDispose { window?.setDimAmount(targetDim) }
    }
    LaunchedEffect(window) {
        animate(0f, 1f, animationSpec = tween(300, easing = DecelerateEasing(1.5f))) { value, _ ->
            window?.setDimAmount(targetDim * value)
        }
    }
}

@Composable
internal fun DaiyuDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    animateContent: Boolean = true,
    bottomSheet: Boolean = false,
    content: @Composable () -> Unit,
) {
    ComposeDialog(onDismissRequest = onDismissRequest, properties = properties) {
        if (bottomSheet) DisablePlatformDialogAnimation() else PlatformDialogFade()
        Box(if (bottomSheet && animateContent) Modifier.dialogEnterMotion() else Modifier) { content() }
    }
}

@Composable
internal fun DaiyuAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    shape: Shape = AlertDialogDefaults.shape,
    containerColor: Color = AlertDialogDefaults.containerColor,
    iconContentColor: Color = AlertDialogDefaults.iconContentColor,
    titleContentColor: Color = AlertDialogDefaults.titleContentColor,
    textContentColor: Color = AlertDialogDefaults.textContentColor,
    tonalElevation: Dp = AlertDialogDefaults.TonalElevation,
    properties: DialogProperties = DialogProperties(),
) {
    MaterialAlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = { PlatformDialogFade(); confirmButton() },
        modifier = modifier,
        dismissButton = dismissButton, icon = icon, title = title, text = text,
        shape = shape, containerColor = containerColor, iconContentColor = iconContentColor,
        titleContentColor = titleContentColor, textContentColor = textContentColor,
        tonalElevation = tonalElevation, properties = properties,
    )
}
