package app.daiyu.android.ui.components

import androidx.compose.animation.core.animate
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
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

internal val DialogMotionProgress = SemanticsPropertyKey<Float>("DialogMotionProgress")
internal val DialogDismissing = SemanticsPropertyKey<Boolean>("DialogDismissing")

internal enum class BottomSheetDismissSource { System, Outside, Action }

internal class BottomSheetMotion {
    val progress = Animatable(0f, visibilityThreshold = 0.0001f)
    val scrimProgress = Animatable(0f)
    var dismissSource by mutableStateOf<BottomSheetDismissSource?>(null)

    private var dismissAction: (() -> Unit)? = null

    fun requestDismiss(source: BottomSheetDismissSource) {
        if (dismissSource == null) dismissSource = source
    }

    fun requestAction(action: () -> Unit) {
        if (dismissSource != null) return
        dismissAction = action
        dismissSource = BottomSheetDismissSource.Action
    }

    fun takeAction(): (() -> Unit)? = dismissAction.also { dismissAction = null }

    fun cancelDismiss() {
        dismissAction = null
        dismissSource = null
    }
}

private val LocalBottomSheetMotion = staticCompositionLocalOf<BottomSheetMotion?> { null }

/** Bind inside the owning sheet so only its window exits before a closing action runs. */
@Composable
internal fun rememberBottomSheetAction(): (() -> Unit) -> Unit {
    val motion = LocalBottomSheetMotion.current
    return remember(motion) { { action ->
        if (motion == null) action() else motion.requestAction(action)
    } }
}

@Composable
internal fun DialogMotionProvider(
    motion: BottomSheetMotion?,
    onDismiss: () -> Unit,
    onOutsideDismiss: () -> Unit = onDismiss,
    content: @Composable () -> Unit,
) {
    if (motion != null) {
        val size = LocalWindowInfo.current.containerSize
        val density = LocalDensity.current.density
        val largeScreen = size.width / density >= 840f && size.height / density >= 480f
        val latestDismiss by rememberUpdatedState(onDismiss)
        val latestOutsideDismiss by rememberUpdatedState(onOutsideDismiss)
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        DisposableEffect(lifecycle, motion) {
            val observer = LifecycleEventObserver { _, event ->
                // A dismiss animation must not acquire a replacement foreground callback.
                if (event == Lifecycle.Event.ON_PAUSE) motion.cancelDismiss()
            }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer) }
        }
        LaunchedEffect(motion.dismissSource) {
            val source = motion.dismissSource
            val target = if (source == null) 1f else 0f
            coroutineScope {
                launch { motion.scrimProgress.animateTo(target, tween(300, easing = DecelerateEasing(1.5f))) }
                motion.progress.animateTo(target, dialogEnterSpec(largeScreen))
            }
            if (source != null) {
                when (source) {
                    BottomSheetDismissSource.System -> latestDismiss()
                    BottomSheetDismissSource.Outside -> latestOutsideDismiss()
                    BottomSheetDismissSource.Action -> motion.takeAction()?.invoke()
                }
                // A vetoing owner keeps the window: restore it instead of leaving an invisible modal.
                withFrameNanos { }
                motion.cancelDismiss()
            }
        }
    }
    CompositionLocalProvider(LocalBottomSheetMotion provides motion, content = content)
}

@Composable
internal fun Modifier.dialogDismissInputGuard(): Modifier {
    val motion = LocalBottomSheetMotion.current ?: return this
    val guarded = if (motion.dismissSource == null) this else clearAndSetSemantics {
        this[DialogDismissing] = true
        this[DialogMotionProgress] = motion.progress.value
    }
    return guarded.pointerInput(motion) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (motion.dismissSource != null) event.changes.forEach { it.consume() }
            }
        }
    }
}

// Same motion as Miuix 0.9.4-rc01 WindowDialog; callers retain their own layout.
internal fun dialogEnterSpec(largeScreen: Boolean): FiniteAnimationSpec<Float> =
    if (largeScreen) folmeSpring(damping = 0.9f, response = 0.3f)
    else spring(dampingRatio = 0.88f, stiffness = 450f, visibilityThreshold = 0.0001f)

@Composable
internal fun rememberDialogEnterProgress(largeScreen: Boolean): Animatable<Float, androidx.compose.animation.core.AnimationVector1D> {
    LocalBottomSheetMotion.current?.let { return it.progress }
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
    val shared = LocalBottomSheetMotion.current
    if (shared != null) return drawWithContent {
        drawRect(color.copy(alpha = color.alpha * shared.scrimProgress.value))
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
    val motion = LocalBottomSheetMotion.current
    SideEffect { window?.setWindowAnimations(0) }
    DisposableEffect(window) {
        window?.setDimAmount(0f)
        onDispose { window?.setDimAmount(targetDim) }
    }
    LaunchedEffect(window, motion) {
        if (motion != null) {
            snapshotFlow { motion.scrimProgress.value }.collect { value ->
                window?.setDimAmount(targetDim * value)
            }
        } else {
            animate(0f, 1f, animationSpec = tween(300, easing = DecelerateEasing(1.5f))) { value, _ ->
                window?.setDimAmount(targetDim * value)
            }
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
    val motion = remember(bottomSheet) { if (bottomSheet) BottomSheetMotion() else null }
    ComposeDialog(onDismissRequest = {
        motion?.requestDismiss(BottomSheetDismissSource.System) ?: onDismissRequest()
    }, properties = properties) {
        app.daiyu.android.diagnostics.ObserveDialogFrames(if (bottomSheet) "sheet" else "dialog")
        DialogMotionProvider(motion, onDismissRequest) {
            if (bottomSheet) DisablePlatformDialogAnimation() else PlatformDialogFade()
            val contentMotion = if (bottomSheet && animateContent) Modifier.dialogEnterMotion() else Modifier
            Box(contentMotion.dialogDismissInputGuard()) { content() }
        }
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
        confirmButton = { app.daiyu.android.diagnostics.ObserveDialogFrames("alert"); PlatformDialogFade(); confirmButton() },
        modifier = modifier,
        dismissButton = dismissButton, icon = icon, title = title, text = text,
        shape = shape, containerColor = containerColor, iconContentColor = iconContentColor,
        titleContentColor = titleContentColor, textContentColor = textContentColor,
        tonalElevation = tonalElevation, properties = properties,
    )
}
