package app.daiyu.android.ui.navigation

import android.os.Build
import androidx.activity.BackEventCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import top.yukonga.miuix.kmp.theme.MiuixTheme

private class PageBackGestureState(val predictiveEnabled: Boolean, val resumed: Boolean) {
    var alive = true
    private var owner: Any? = null
    var progress by mutableFloatStateOf(0f)
    var active by mutableStateOf(false)
    var predictive by mutableStateOf(false)
    var direction by mutableFloatStateOf(1f)

    fun update(owner: Any, value: Float, predictive: Boolean, direction: Float = 1f) {
        this.owner = owner
        this.predictive = predictive
        this.direction = direction
        active = true
        progress = value.coerceIn(0f, 1f)
    }

    fun reset(owner: Any? = null) {
        if (owner != null && this.owner !== owner) return
        this.owner = null
        active = false
        progress = 0f
    }
}

private val LocalPageBackGesture = staticCompositionLocalOf<PageBackGestureState?> { null }

/** Keeps nested page handlers in the dispatcher's existing last-enabled-first order. */
@Composable
internal fun PageBackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    val state = LocalPageBackGesture.current
    if (state == null) {
        BackHandler(enabled, onBack)
        return
    }
    val predictive = state.predictiveEnabled
    val currentBack by rememberUpdatedState(onBack)
    val currentEnabled by rememberUpdatedState(enabled && predictive && state.resumed)
    BackHandler(enabled = enabled && state.resumed && !predictive) { currentBack() }
    PredictiveBackHandler(enabled = enabled && state.resumed && predictive) { events ->
        val owner = Any()
        try {
            events.collect { event ->
                if (!state.alive || !currentEnabled) throw CancellationException()
                state.update(owner, event.progress, predictive = true,
                    direction = if (event.swipeEdge == BackEventCompat.EDGE_RIGHT) -1f else 1f)
            }
            if (state.alive && currentEnabled) currentBack()
        } finally {
            state.reset(owner)
        }
    }
}

@Composable
internal fun PageBackGestureHost(
    routeKey: Any,
    canNavigateBack: Boolean,
    swipeDismissEnabled: Boolean,
    predictiveBackEnabled: Boolean,
    dispatchBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val resumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val state = remember(routeKey, swipeDismissEnabled, predictiveBackEnabled, resumed) {
        PageBackGestureState(predictiveBackEnabled && Build.VERSION.SDK_INT >= 34, resumed)
    }
    DisposableEffect(state) {
        onDispose { state.alive = false; state.reset() }
    }
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val layoutDirection = LocalLayoutDirection.current
    val currentBack by rememberUpdatedState(dispatchBack)
    val progress by key(state) {
        animateFloatAsState(state.progress,
            animationSpec = if (state.active) snap() else spring(), label = "page-back-reset")
    }
    val background = MiuixTheme.colorScheme.background
    CompositionLocalProvider(LocalPageBackGesture provides state) {
        Box(modifier.fillMaxSize().background(background).then(
            if (canNavigateBack && swipeDismissEnabled && resumed && !imeVisible) Modifier.pointerInput(state, layoutDirection) {
                val edgeWidth = 32.dp.toPx()
                val direction = if (layoutDirection == LayoutDirection.Ltr) 1f else -1f
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val gestureWidth = size.width
                    val threshold = maxOf(72.dp.toPx(), gestureWidth * 0.30f)
                    val fromEdge = if (direction > 0f) down.position.x else gestureWidth - down.position.x
                    if (fromEdge > edgeWidth || down.isConsumed || state.active) return@awaitEachGesture
                    val owner = Any()
                    var claimed = false
                    var distance = 0f
                    try {
                        while (true) {
                            // Main travels from children to parents: consumed control gestures stay with the child.
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            if (event.changes.size != 1) break
                            val change = event.changes.single()
                            if (change.id != down.id || change.isConsumed) break
                            val dx = (change.position.x - down.position.x) * direction
                            val dy = abs(change.position.y - down.position.y)
                            if (!change.pressed) {
                                if (claimed && distance >= threshold && state.alive) currentBack()
                                break
                            }
                            if (!claimed) {
                                if (dy > viewConfiguration.touchSlop || dx < -viewConfiguration.touchSlop) break
                                if (dx <= viewConfiguration.touchSlop || dx <= dy * 1.5f) continue
                                claimed = true
                            }
                            distance = dx.coerceAtLeast(0f)
                            state.update(owner, distance / gestureWidth, predictive = false, direction = direction)
                            change.consume()
                        }
                    } finally {
                        state.reset(owner)
                    }
                }
            } else Modifier,
        )) {
            Box(Modifier.fillMaxSize().testTag("page-back-surface").graphicsLayer {
                translationX = state.direction * size.width * progress * if (state.predictive) 0.12f else 1f
                val scale = if (state.predictive) 1f - 0.06f * progress else 1f
                scaleX = scale
                scaleY = scale
                shape = RoundedCornerShape(20.dp)
                clip = state.predictive && progress > 0f
                shadowElevation = 8.dp.toPx() * progress
            }.background(background)) { content() }
        }
    }
}
