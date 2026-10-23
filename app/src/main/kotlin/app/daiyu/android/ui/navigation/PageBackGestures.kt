package app.daiyu.android.ui.navigation

import android.os.Build
import androidx.activity.BackEventCompat
import androidx.activity.compose.BackHandler
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.abs
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.theme.MiuixTheme

private class PageBackGestureState(val predictiveEnabled: Boolean, val resumed: Boolean,
    val commit: (Float, Boolean, Float, () -> Unit) -> Unit) {
    var alive = true
    var transitionBlocked = false
    class PreviewOwner(enabled: Boolean, previewEnabled: Boolean) {
        var enabled by mutableStateOf(enabled)
        var previewEnabled by mutableStateOf(previewEnabled)
    }
    val previewOwners = mutableStateListOf<PreviewOwner>()
    val previewAllowed get() = previewOwners.none { it.enabled && !it.previewEnabled }
    fun refreshPreview() {
        if (!previewAllowed) { progress = 0f; swipeCommit = null }
    }
    var committing = false
        private set

    fun commitBack(scope: kotlinx.coroutines.CoroutineScope, back: () -> Unit) {
        if (!alive || (transitionBlocked && swipeCommit == null) || committing) return
        val visual = swipeCommit
        swipeCommit = null
        committing = true
        try { commit(if (previewAllowed) visual?.progress ?: progress else 0f, visual?.predictive ?: predictive, visual?.direction ?: direction, back) }
        finally { scope.launch { withFrameNanos { }; committing = false } }
    }
    private class SwipeCommit(val progress: Float, val predictive: Boolean, val direction: Float)
    private var swipeCommit: SwipeCommit? = null
    var dispatching = false
        private set

    fun dispatchBack(back: () -> Unit) {
        if (!alive || transitionBlocked || committing || dispatching) return
        swipeCommit = SwipeCommit(progress, predictive, direction)
        dispatching = true
        try { back() }
        finally { swipeCommit = null; dispatching = false }
    }
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
        progress = if (previewAllowed) value.coerceIn(0f, 1f) else 0f
    }

    fun reset(owner: Any? = null) {
        if (owner != null && this.owner !== owner) return
        this.owner = null
        active = false
        progress = 0f
    }
}

private class PageBackCallbackSession {
    var gesture: Pair<PageBackGestureState, Any>? = null
    var started = false
    var rejected = false

    fun clear() {
        gesture?.let { (state, owner) -> state.reset(owner) }
        gesture = null
        started = false
        rejected = false
    }

    fun invalidate() {
        val rejectCompletion = started || rejected
        clear()
        rejected = rejectCompletion
    }
}

private val LocalPageBackGesture = staticCompositionLocalOf<PageBackGestureState?> { null }

/** Keeps nested page handlers in the dispatcher's existing last-enabled-first order. */
@Composable
internal fun PageBackHandler(enabled: Boolean = true, previewEnabled: Boolean = true, onBack: () -> Unit) {
    val state = LocalPageBackGesture.current
    if (state == null) {
        BackHandler(enabled, onBack)
        return
    }
    val scope = rememberCoroutineScope()
    val previewOwner = remember(state) { PageBackGestureState.PreviewOwner(enabled, previewEnabled) }
    DisposableEffect(state, previewOwner) {
        state.previewOwners.add(previewOwner)
        state.refreshPreview()
        onDispose { state.previewOwners.remove(previewOwner); state.refreshPreview() }
    }
    SideEffect {
        previewOwner.enabled = enabled
        previewOwner.previewEnabled = previewEnabled
        state.refreshPreview()
    }
    val dispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentState by rememberUpdatedState(state)
    val currentBack by rememberUpdatedState(onBack)
    val currentEnabled by rememberUpdatedState(enabled && state.resumed)
    val currentPreview by rememberUpdatedState(previewEnabled)
    val session = remember(dispatcher) { PageBackCallbackSession() }
    val callback = remember(dispatcher) {
        object : OnBackPressedCallback(false) {

            override fun handleOnBackStarted(backEvent: BackEventCompat) {
                handleOnBackCancelled()
                session.started = true
                val activeState = currentState
                if (!activeState.predictiveEnabled) return
                if (!activeState.alive || !currentEnabled || activeState.transitionBlocked || activeState.committing) {
                    session.rejected = true
                    return
                }
                val owner = Any()
                session.gesture = activeState to owner
                activeState.update(owner, 0f, predictive = true,
                    direction = if (backEvent.swipeEdge == BackEventCompat.EDGE_RIGHT) -1f else 1f)
            }

            override fun handleOnBackProgressed(backEvent: BackEventCompat) {
                val (activeState, owner) = session.gesture ?: return
                if (activeState !== currentState || !activeState.alive || !currentEnabled ||
                    activeState.transitionBlocked || activeState.committing) {
                    activeState.reset(owner)
                    session.gesture = null
                    session.rejected = true
                    return
                }
                activeState.update(owner, if (currentPreview) backEvent.progress else 0f, predictive = true,
                    direction = if (backEvent.swipeEdge == BackEventCompat.EDGE_RIGHT) -1f else 1f)
            }

            override fun handleOnBackCancelled() {
                session.clear()
            }

            override fun handleOnBackPressed() {
                val activeState = currentState
                try {
                    if (!session.rejected && activeState.alive && currentEnabled) {
                        activeState.commitBack(scope) { currentBack() }
                    }
                } finally {
                    handleOnBackCancelled()
                }
            }
        }
    }
    SideEffect {
        callback.isEnabled = enabled && state.resumed
        if (!callback.isEnabled) session.invalidate()
    }
    DisposableEffect(state, callback) {
        onDispose { session.invalidate() }
    }
    DisposableEffect(dispatcher, lifecycleOwner, callback) {
        dispatcher.addCallback(lifecycleOwner, callback)
        onDispose { callback.handleOnBackCancelled(); callback.remove() }
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
    backTargetKey: Any? = null,
    previewScopeKey: Any? = null,
    content: @Composable () -> Unit,
) {
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val resumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val background = MiuixTheme.colorScheme.background
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val graphics = LocalGraphicsContext.current
    val configuration = LocalConfiguration.current
    val colors = MiuixTheme.colorScheme
    val themeKey = listOf(background, colors.primary, colors.onBackground, colors.surface, colors.onSurface)
    val bars = WindowInsets.systemBars
    val insetsKey = IntSize(bars.getTop(density), bars.getBottom(density))
    val scope = rememberCoroutineScope()
    val layers = remember(graphics, themeKey, configuration, insetsKey, density, layoutDirection, resumed, previewScopeKey) {
        PageBackPreviewLayers(graphics)
    }
    DisposableEffect(layers) { onDispose { layers.close() } }
    val departure = remember(layers, routeKey) { layers.enter(routeKey) }
    val frame = remember(layers, routeKey) { layers.frame(routeKey) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val target = layers.find(backTargetKey, viewport)
    var departing by remember(departure) { mutableStateOf(departure != null) }
    val exit = remember(departure) { Animatable(0f) }
    LaunchedEffect(departure) {
        if (departure != null) {
            exit.animateTo(1f, tween(180))
            departing = false
        }
    }
    SideEffect { layers.trim(frame, target, departure.takeIf { departing }) }
    val currentTarget by rememberUpdatedState(target)
    val state = remember(routeKey, swipeDismissEnabled, predictiveBackEnabled, resumed, layers, backTargetKey) {
        PageBackGestureState(predictiveBackEnabled && Build.VERSION.SDK_INT >= 34, resumed) { value, predictive, direction, back ->
            val request = if (value > 0f && currentTarget != null && backTargetKey != null) {
                layers.prepare(frame, backTargetKey, value, predictive, direction)
            } else null
            try { back() } finally {
                if (request != null) scope.launch {
                    withFrameNanos { }
                    // A veto or an asynchronous save must not be treated as a completed pop.
                    if (layers.currentKey == routeKey) layers.abandon(request)
                }
            }
        }
    }
    DisposableEffect(state) {
        onDispose { state.alive = false; state.reset() }
    }
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val currentBack by rememberUpdatedState(dispatchBack)
    val progress by key(state) {
        animateFloatAsState(state.progress,
            animationSpec = if (state.active) snap() else spring(), label = "page-back-reset")
    }
    state.transitionBlocked = departing || (state.previewAllowed && !state.active && progress > 0f)
    CompositionLocalProvider(LocalPageBackGesture provides state) {
        Box(modifier.fillMaxSize().clipToBounds().background(background).onSizeChanged { viewport = it }.then(
            if (canNavigateBack && swipeDismissEnabled && resumed && !imeVisible && !departing) Modifier.pointerInput(state, layoutDirection) {
                val direction = if (layoutDirection == LayoutDirection.Ltr) 1f else -1f
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val gestureWidth = size.width
                    val threshold = maxOf(72.dp.toPx(), gestureWidth * 0.30f)
                    if (state.active || state.transitionBlocked || state.committing || state.dispatching) return@awaitEachGesture
                    val owner = Any()
                    var claimed = false
                    var consumedMovement = false
                    try {
                        while (true) {
                            // Let children arbitrate first; after claiming, consume before their Main pass.
                            val event = awaitPointerEvent(if (claimed) PointerEventPass.Initial else PointerEventPass.Final)
                            if (event.changes.size != 1 || !state.alive || (state.active && state.predictive)) break
                            val change = event.changes.single()
                            if (change.id != down.id) break
                            val dx = (change.position.x - down.position.x) * direction
                            val dy = abs(change.position.y - down.position.y)
                            if (!change.pressed) {
                                if (claimed && change.changedToUp()) {
                                    change.consume()
                                    if (dx >= threshold && state.alive) {
                                        state.dispatchBack { currentBack() }
                                    }
                                }
                                break
                            }
                            if (!claimed) {
                                if (change.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis) break
                                if (change.position == change.previousPosition) continue
                                // A clickable may consume one move while cancelling its press, not a drag.
                                if (change.isConsumed) {
                                    if (consumedMovement) break
                                    consumedMovement = true
                                    continue
                                }
                                consumedMovement = false
                                if (dy > viewConfiguration.touchSlop || dx < -viewConfiguration.touchSlop) break
                                if (dx <= viewConfiguration.touchSlop || dx <= dy * 1.5f) continue
                                claimed = true
                            }
                            val distance = dx.coerceAtLeast(0f)
                            state.update(owner, distance / gestureWidth, predictive = false, direction = direction)
                            change.consume()
                        }
                    } finally {
                        state.reset(owner)
                    }
                }
            } else Modifier,
        )) {
            if (target != null && state.previewAllowed && progress > 0f) Box(Modifier.fillMaxSize().drawBehind {
                drawLayer(target.layer)
            }.testTag("page-back-target"))
            key(routeKey) {
                Box(Modifier.fillMaxSize().graphicsLayer {
                    val visibleProgress = if (target != null && state.previewAllowed) progress else 0f
                    translationX = state.direction * size.width * visibleProgress * if (state.predictive) 0.12f else 1f
                    val scale = if (state.predictive) 1f - 0.06f * visibleProgress else 1f
                    scaleX = scale
                    scaleY = scale
                    shape = RoundedCornerShape(20.dp)
                    clip = visibleProgress > 0f
                    shadowElevation = 8.dp.toPx() * visibleProgress
                }.testTag("page-back-surface").drawWithContent {
                    frame.layer.record { this@drawWithContent.drawContent() }
                    frame.size = viewport
                    drawLayer(frame.layer)
                }.background(background)) { content() }
            }
            if (departure != null && departing) Box(Modifier.fillMaxSize().graphicsLayer {
                val fromX = departure.direction * size.width * departure.progress * if (departure.predictive) 0.12f else 1f
                translationX = fromX + (departure.direction * size.width - fromX) * exit.value
                val initialScale = if (departure.predictive) 1f - 0.06f * departure.progress else 1f
                scaleX = initialScale + (1f - initialScale) * exit.value
                scaleY = scaleX
                shape = RoundedCornerShape(20.dp)
                clip = true
                shadowElevation = 8.dp.toPx() * departure.progress * (1f - exit.value)
            }.drawBehind {
                drawLayer(departure.frame.layer)
            }.testTag("page-back-departure").pointerInput(departure) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false).consume()
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                }
            })
        }
    }
}
