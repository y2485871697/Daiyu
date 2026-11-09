package app.daiyu.android.ui.navigation

import android.os.Build
import app.daiyu.android.diagnostics.synchronousTrace
import app.daiyu.android.diagnostics.BackPerformance
import app.daiyu.android.diagnostics.BackPerformanceRecorder
import android.os.SystemClock
import androidx.activity.BackEventCompat
import androidx.activity.compose.BackHandler
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import top.yukonga.miuix.kmp.nav.runtime.NavProgrammaticEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.systemBars
import top.yukonga.miuix.kmp.squircle.absoluteSquircleClip
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
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.abs
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal class PageBackGestureState(val predictiveEnabled: Boolean, val resumed: Boolean, val returnDirection: Float,
    private val scope: kotlinx.coroutines.CoroutineScope,
    val diagnosticOwner: Any,
    private val beginPreview: () -> Boolean,
    val commit: (Float, Boolean, Float, Float, Boolean, () -> Unit) -> Unit) {
    var predictiveGeneration = 0L
    var alive = true
    var transitionBlocked = false
    class PreviewOwner(enabled: Boolean, previewEnabled: Boolean) {
        var enabled by mutableStateOf(enabled)
        var previewEnabled by mutableStateOf(previewEnabled)
    }
    val previewOwners = mutableStateListOf<PreviewOwner>()
    var gesturePreviewReady by mutableStateOf(true)
        private set
    private var previewStarted = false
    private var predictiveMotion = VelocityTracker()
    val previewAllowed get() = gesturePreviewReady && previewOwners.none { it.enabled && !it.previewEnabled }
    fun restorePreview() { gesturePreviewReady = true }
    fun refreshPreview() {
        if (!previewAllowed) {
            settleJob?.cancel()
            settleJob = null
            progress = 0f
            visualProgress = 0f
            gestureVisualActive = false
            swipeCommit = null
        }
    }
    private var diagnosticToken: BackPerformanceRecorder.Token? = null
    fun startDiagnostic(kind: BackPerformanceRecorder.Kind) {
        diagnosticToken = BackPerformance.start(diagnosticOwner, kind)
    }
    var committing = false
        private set

    fun commitBack(back: () -> Unit) {
        if (!alive || (transitionBlocked && swipeCommit == null) || committing) return
        BackPerformance.safely {
            if (diagnosticToken == null || diagnosticToken != BackPerformance.recorder.token()) {
                startDiagnostic(BackPerformanceRecorder.Kind.BACK_PRESS)
            }
            BackPerformance.commit(diagnosticOwner)
        }
        val visual = swipeCommit
        swipeCommit = null
        committing = true
        val velocity = visual?.velocity ?: if (predictive) predictiveMotion.calculateVelocity().x / 1000f else 0f
        try { synchronousTrace("daiyu.back.callback-commit") {
            commit(if (previewAllowed) visual?.progress ?: progress else 0f,
                visual?.predictive ?: predictive, visual?.direction ?: direction, velocity, previewAllowed, back)
        } }
        finally { scope.launch { try { withFrameNanos { } } finally { committing = false } } }
    }
    private class SwipeCommit(val progress: Float, val predictive: Boolean, val direction: Float, val velocity: Float)
    private var swipeCommit: SwipeCommit? = null
    var dispatching = false
        private set

    fun dispatchBack(velocity: Float = 0f, back: () -> Unit) {
        if (!alive || transitionBlocked || committing || dispatching) return
        swipeCommit = SwipeCommit(progress, predictive, direction, velocity)
        dispatching = true
        try { synchronousTrace("daiyu.back.custom-up-dispatch") { back() } }
        finally { swipeCommit = null; dispatching = false }
    }
    private var owner: Any? = null
    private var settleJob: Job? = null
    var progress by mutableFloatStateOf(0f)
    /**
     * The visual value is read only from graphicsLayer/draw lambdas below. Keeping it separate
     * from the logical progress prevents every pointer move from re-entering the page content.
     */
    var visualProgress by mutableFloatStateOf(0f)
    var gestureVisualActive by mutableStateOf(false)
        private set
    var active by mutableStateOf(false)
    var predictive by mutableStateOf(false)
    var direction by mutableFloatStateOf(1f)

    fun update(owner: Any, value: Float, predictive: Boolean, direction: Float = 1f) {
        if (this.owner !== owner) {
            previewStarted = false
            predictiveMotion = VelocityTracker()
        }
        if (!previewStarted && value > 0f) {
            gesturePreviewReady = beginPreview()
            previewStarted = true
        }
        if (predictive) predictiveMotion.addPosition(SystemClock.uptimeMillis(), Offset(value * 1000f, 0f))
        this.owner = owner
        this.predictive = predictive
        this.direction = direction
        settleJob?.cancel()
        settleJob = null
        active = true
        val nextProgress = if (previewAllowed) value.coerceIn(0f, 0.999f) else 0f
        progress = nextProgress
        visualProgress = nextProgress
        gestureVisualActive = previewAllowed && nextProgress > 0f
    }

    fun reset(owner: Any? = null) {
        synchronousTrace("daiyu.back.reset") {
            if (owner != null && this.owner !== owner) return
            BackPerformance.safely {
                if (diagnosticToken != null && diagnosticToken == BackPerformance.recorder.token()) {
                    BackPerformance.reset(diagnosticOwner)
                }
            }
            diagnosticToken = null
            this.owner = null
            active = false
            progress = 0f
            val start = visualProgress
            if (!alive || start <= 0f || !previewAllowed) {
                settleJob?.cancel()
                settleJob = null
                visualProgress = 0f
                gestureVisualActive = false
                return
            }
            // Preserve the old cancel spring without making the page host observe every animation tick.
            gestureVisualActive = true
            settleJob?.cancel()
            val newSettleJob = scope.launch(start = CoroutineStart.LAZY) {
                val settle = Animatable(start)
                try {
                    settle.animateTo(0f, spring(dampingRatio = 1f, stiffness = 146f, visibilityThreshold = 0.0025f)) {
                        visualProgress = value
                    }
                } finally {
                    if (settleJob === kotlinx.coroutines.currentCoroutineContext()[Job]) {
                        visualProgress = 0f
                        gestureVisualActive = false
                        settleJob = null
                    }
                }
            }
            settleJob = newSettleJob
            newSettleJob.start()
        }
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

internal val LocalPageBackPreviewFrame = compositionLocalOf<PageBackPreviewLayers.Frame?> { null }

/** Records only the page body when a surrounding overlay is not part of its back target. */
@Composable
internal fun Modifier.pageBackPreviewSource(): Modifier {
    val frame = LocalPageBackPreviewFrame.current ?: return this
    return drawWithContent {
        frame.layer.record { this@drawWithContent.drawContent() }
        drawLayer(frame.layer)
        frame.recorded(IntSize(size.width.toInt(), size.height.toInt()))
    }.background(MiuixTheme.colorScheme.background)
}

internal val LocalPageBackGesture = compositionLocalOf<PageBackGestureState?> { null }

/** Keeps nested page handlers in the dispatcher's existing last-enabled-first order. */
@Composable
internal fun PageBackHandler(enabled: Boolean = true, previewEnabled: Boolean = true, onBack: () -> Unit) {
    if (!LocalPageContentActive.current) return
    val state = LocalPageBackGesture.current
    if (state == null) {
        BackHandler(enabled, onBack)
        return
    }
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
                activeState.predictiveGeneration++
                if (!activeState.alive || !currentEnabled || activeState.transitionBlocked || activeState.committing) {
                    session.rejected = true
                    return
                }
                val owner = Any()
                session.gesture = activeState to owner
                activeState.startDiagnostic(BackPerformanceRecorder.Kind.PREDICTIVE)
                activeState.update(owner, 0f, predictive = true,
                    direction = activeState.returnDirection)
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
                    direction = activeState.returnDirection)
            }

            override fun handleOnBackCancelled() {
                session.clear()
            }

            override fun handleOnBackPressed() {
                val activeState = currentState
                try {
                    if (!session.rejected && activeState.alive && currentEnabled) {
                        activeState.commitBack { currentBack() }
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
    contentKey: Any = routeKey,
    captureFromContent: Boolean = false,
    animateBackEnabled: Boolean = true,
    retainedRootKey: Any? = null,
    retainedRootVisible: Boolean = contentKey == retainedRootKey,
    retainedRootActive: Boolean = retainedRootVisible,
    retainedRootScopeKey: Any? = null,
    retainedRootContent: (@Composable (Boolean) -> Unit)? = null,
    previewCapture: suspend (PageBackPreviewLayers.Frame) -> ImageBitmap = { it.layer.toImageBitmap() },
    content: @Composable () -> Unit,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    val resumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val background = MiuixTheme.colorScheme.background
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val view = LocalView.current
    val context = LocalContext.current
    val windowInsets = view.rootWindowInsets
    val cornerRadius = remember(context, windowInsets, density) {
        val radius = if (Build.VERSION.SDK_INT >= 31) windowInsets?.getRoundedCorner(android.view.RoundedCorner.POSITION_BOTTOM_LEFT)?.radius?.takeIf { it > 0 } else null
        val resource = context.resources.getIdentifier("rounded_corner_radius_bottom", "dimen", "android")
        with(density) { (radius ?: if (resource > 0) context.resources.getDimensionPixelSize(resource) else 0).toDp() }
    }
    val graphics = LocalGraphicsContext.current
    val configuration = LocalConfiguration.current
    val colors = MiuixTheme.colorScheme
    val themeKey = listOf(background, colors.primary, colors.onBackground, colors.surface, colors.onSurface)
    val bars = WindowInsets.systemBars
    val insetsKey = IntSize(bars.getTop(density), bars.getBottom(density))
    val scope = rememberCoroutineScope()
    val layers = remember(graphics, themeKey, configuration, insetsKey, density, layoutDirection, previewScopeKey) {
        PageBackPreviewLayers(graphics, scope, previewCapture)
    }
    DisposableEffect(layers) { onDispose { layers.close() } }
    DisposableEffect(layers, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) layers.clearPreviews()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val stack = remember(routeKey) { PageBackStackRegistry() }
    val stackPath = remember(stack) { PageBackStackPath(stack) }
    stack.freezeCurrent = { layers.freezeCurrent() }
    val visualRouteKey = stack.routeKey(routeKey)
    // Stable across route-keyed state replacements; no route value enters the incident.
    val diagnosticFrameHost = app.daiyu.android.diagnostics.WindowFramePerformance.forContext(context)
    val diagnosticOwner = remember(diagnosticFrameHost) { diagnosticFrameHost?.backOwner() ?: Any() }
    val diagnosticRoute = remember { arrayOf<Any>(visualRouteKey) }
    SideEffect {
        if (diagnosticRoute[0] != visualRouteKey) {
            BackPerformance.safely { BackPerformance.recorder.route(diagnosticOwner) }
            diagnosticRoute[0] = visualRouteKey
        }
    }
    DisposableEffect(diagnosticOwner) {
        onDispose { BackPerformance.safely {
            BackPerformance.recorder.end(diagnosticOwner, BackPerformanceRecorder.End.OWNER_REMOVED)
        } }
    }
    app.daiyu.android.diagnostics.ObservePageFrames(visualRouteKey)
    val visualBackTargetKey = stack.targetKey(routeKey, backTargetKey)
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val departure = remember(layers, visualRouteKey) {
        layers.transition(visualRouteKey, visualBackTargetKey, animateEnter = animateBackEnabled && resumed, size = viewport, contentKey = contentKey)
    }
    val frame = remember(layers, visualRouteKey) {
        layers.frame(visualRouteKey, sourceKey = if (visualRouteKey == routeKey) contentKey else visualRouteKey, contentKey = contentKey)
    }
    val target = layers.find(visualBackTargetKey, viewport)
    var departing by remember(departure) { mutableStateOf(departure != null) }
    val exit = remember(departure) { Animatable(if (departure?.forward == false) departure.progress else 0f) }
    LaunchedEffect(departure, departure?.forwardSkipped) {
        if (departure != null) {
            if (!departure.forwardSkipped) {
                if (departure.frame.canDrawPreview && !departure.forwardSkipped) {
                    if (departure.progress == 0f) exit.animateTo(1f, tween(durationMillis = 500, easing = NavProgrammaticEasing))
                    else exit.animateTo(1f, spring(dampingRatio = 1f, stiffness = 146f, visibilityThreshold = 0.0025f),
                        initialVelocity = pageBackInitialVelocity(departure.progress, departure.velocity))
                }
            }
            departing = false
        }
    }
    SideEffect { layers.trim(frame, target, departure.takeIf { departing }, stack.ancestorKeys(routeKey, backTargetKey)) }
    val currentTarget by rememberUpdatedState(target)
    val state = remember(visualRouteKey, swipeDismissEnabled, predictiveBackEnabled, resumed, layers, visualBackTargetKey) {
        PageBackGestureState(predictiveBackEnabled && Build.VERSION.SDK_INT >= 34, resumed, if (layoutDirection == LayoutDirection.Ltr) 1f else -1f, scope, diagnosticOwner,
            beginPreview = { currentTarget != null && layers.beginGesture(frame) }) { value, predictive, direction, velocity, previewAllowed, back ->
            if (!previewAllowed) frame.animateReturn = false
            val request = if (value > 0f && currentTarget != null && visualBackTargetKey != null) {
                layers.prepare(frame, visualBackTargetKey, value, predictive, direction, velocity)
            } else null
            try { synchronousTrace("daiyu.back.business-handler") { back() } } finally {
                if (request != null) scope.launch {
                    withFrameNanos { }
                    // A veto or an asynchronous save must not be treated as a completed pop.
                    if (stack.routeKey(routeKey) == visualRouteKey) layers.abandon(request)
                }
            }
        }
    }
    DisposableEffect(state) {
        onDispose { state.alive = false; state.reset() }
    }
    SideEffect {
        layers.setActive(resumed)
        frame.contentKey = contentKey
        frame.sourceKey = if (visualRouteKey == routeKey) contentKey else visualRouteKey
        frame.backTargetKey = visualBackTargetKey
        frame.returnDirection = state.returnDirection
        frame.animateReturn = animateBackEnabled && resumed && state.previewAllowed
        if (departure?.forward == true && !state.previewAllowed) departure.forwardSkipped = true
    }
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val currentBack by rememberUpdatedState(dispatchBack)
    val activeDeparture = departing && !(departure?.forward == true &&
        (!state.previewAllowed || departure.forwardSkipped))
    // Do not read visualProgress here. It is intentionally consumed only by layer/draw lambdas so
    // pointer updates invalidate the motion layer rather than the whole page composition.
    state.transitionBlocked = activeDeparture || (state.previewAllowed && !state.active && state.gestureVisualActive)
    SideEffect {
        if (!state.active && !activeDeparture && !state.gestureVisualActive) {
            layers.resume(frame)
            state.restorePreview()
        }
    }
    val diagnosticSettling = state.transitionBlocked
    app.daiyu.android.diagnostics.ObserveDiagnosticInteraction("navigation") {
        when {
            state.active && state.predictive -> "predictive"
            state.active -> "drag"
            activeDeparture && departure?.forward == true -> "forward"
            activeDeparture -> "return"
            diagnosticSettling -> "settle"
            else -> "idle"
        }
    }

    val showDeparture = departure != null && departing && departure.frame.canDrawPreview && !departure.forwardSkipped && state.previewAllowed
    val entering by rememberUpdatedState(activeDeparture && departure?.forward == true)
    val returning by rememberUpdatedState(showDeparture && departure?.forward == false)
    val claimSwipe by rememberUpdatedState {
        departing = false
        state.transitionBlocked = false
    }
    CompositionLocalProvider(LocalPageBackGesture provides state, LocalPageBackStackPath provides stackPath,
        LocalPageBackPreviewFrame provides frame.takeIf { captureFromContent }) {
        Box(modifier.fillMaxSize().testTag("page-back-host").clipToBounds().background(background).onSizeChanged { viewport = it }.then(
            if (canNavigateBack && swipeDismissEnabled && resumed && !imeVisible) Modifier.pointerInput(state, layoutDirection) {
                val direction = if (layoutDirection == LayoutDirection.Ltr) 1f else -1f
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val gestureWidth = size.width
                    val threshold = gestureWidth * 0.5f + viewConfiguration.touchSlop
                    if (state.active || entering || state.committing || state.dispatching) return@awaitEachGesture
                    val interruptingReturn = returning
                    val owner = Any()
                    val predictiveGeneration = state.predictiveGeneration
                    val velocity = VelocityTracker().also { it.addPointerInputChange(down) }
                    var claimed = false
                    var consumedMovement = false
                    try {
                        while (true) {
                            // The return overlay blocks children; observe ahead of it until the next swipe claims.
                            val event = awaitPointerEvent(if (claimed || interruptingReturn) PointerEventPass.Initial else PointerEventPass.Final)
                            if (event.changes.size != 1 || !state.alive || state.predictiveGeneration != predictiveGeneration || (state.active && state.predictive)) break
                            val change = event.changes.single()
                            if (change.id != down.id) break
                            velocity.addPointerInputChange(change)
                            val dx = (change.position.x - down.position.x) * direction
                            val dy = abs(change.position.y - down.position.y)
                            if (!change.pressed) {
                                if (claimed && change.changedToUp()) {
                                    change.consume()
                                    val screensPerSecond = velocity.calculateVelocity().x * direction / gestureWidth
                                    val commit = when {
                                        screensPerSecond >= 1f -> true
                                        screensPerSecond <= -1f -> false
                                        else -> dx >= threshold
                                    }
                                    if (commit && state.alive) state.dispatchBack(screensPerSecond) { currentBack() }
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
                                if (dx < -viewConfiguration.touchSlop ||
                                    (dy > viewConfiguration.touchSlop && dx <= dy * 1.5f)) break
                                if (dx <= viewConfiguration.touchSlop || dx <= dy * 1.5f) continue
                                claimed = true
                                state.startDiagnostic(BackPerformanceRecorder.Kind.CUSTOM_SWIPE)
                                claimSwipe()
                            }
                            val distance = (dx - viewConfiguration.touchSlop).coerceAtLeast(0f)
                            state.update(owner, distance / gestureWidth, predictive = false, direction = direction)
                            change.consume()
                        }
                    } finally {
                        state.reset(owner)
                    }
                }
            } else Modifier,
        )) {
            if (target != null && state.previewAllowed && state.gestureVisualActive) {
                Box(Modifier.fillMaxSize().graphicsLayer {
                    val value = state.visualProgress
                    translationX = -state.direction * size.width * 0.25f * (1f - value)
                    alpha = 1f - 0.1f * (1f - value)
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                }.drawBehind {
                    drawPreview(target)
                }.testTag("page-back-target"))
                Box(Modifier.fillMaxSize().drawBehind {
                    drawRect(Color.Black, alpha = 0.5f * (1f - state.visualProgress))
                }.testTag("page-back-scrim"))
            }
            if (departure != null && showDeparture && departure.forward) {
                Box(Modifier.fillMaxSize().graphicsLayer {
                    translationX = -departure.direction * size.width * 0.25f * exit.value
                    alpha = 1f - 0.1f * exit.value
                }.drawBehind {
                    drawPreview(departure.frame)
                }.testTag("page-enter-previous"))
                Box(Modifier.fillMaxSize().drawBehind {
                    drawRect(Color.Black, alpha = 0.5f * exit.value)
                }.testTag("page-enter-scrim"))
            }
            key(if (retainedRootContent == null) contentKey else RetainedRootSurface) {
                Box(Modifier.fillMaxSize().graphicsLayer {
                    val visibleProgress = if (target != null && state.previewAllowed) state.visualProgress else 0f
                    if (departure != null && showDeparture && departure.forward) {
                        translationX = kotlin.math.round(departure.direction * size.width * (1f - exit.value))
                    } else if (departure != null && showDeparture) {
                        val value = exit.value
                        translationX = -departure.direction * size.width * 0.25f * (1f - value)
                        alpha = 1f - 0.1f * (1f - value)
                    } else translationX = kotlin.math.round(state.direction * size.width * visibleProgress)
                }.then(if ((target != null && state.previewAllowed && state.gestureVisualActive) ||
                    (showDeparture && departure?.forward == true)) {
                    if ((if (departure?.forward == true) departure.direction else state.direction) > 0f) Modifier.absoluteSquircleClip(topLeft = cornerRadius, topRight = 0.dp, bottomRight = 0.dp, bottomLeft = cornerRadius)
                    else Modifier.absoluteSquircleClip(topLeft = 0.dp, topRight = cornerRadius, bottomRight = cornerRadius, bottomLeft = 0.dp)
                } else Modifier).testTag("page-back-surface").drawWithContent {
                    if (departure?.forward == true && departing && !showDeparture) {
                        departure.forwardSkipped = true
                    }
                    val held = when {
                        departure != null && showDeparture && !departure.forward ->
                            if (retainedRootContent != null && retainedRootVisible) null else departure.targetImage
                        state.previewAllowed && (state.active || state.gestureVisualActive) -> frame.image
                        frame.holdingForChild && frame.childChangeCaptured == true && state.previewAllowed -> frame.image
                        else -> null
                    }
                    if (held != null) drawImage(held)
                    else {
                        if (frame.holdingForChild) frame.childChangeCaptured = false
                        drawContent()
                    }
                }.drawWithContent {
                    if (captureFromContent) drawContent()
                    else {
                        frame.layer.record { this@drawWithContent.drawContent() }
                        drawLayer(frame.layer)
                        frame.recorded(IntSize(size.width.toInt(), size.height.toInt()))
                    }
                }.background(background)) {
                    if (retainedRootContent != null) key(previewScopeKey, retainedRootScopeKey) {
                        val rootActive = retainedRootActive && resumed
                        CompositionLocalProvider(
                            LocalPageBackGesture provides state.takeIf { rootActive },
                            LocalPageBackStackPath provides stackPath.takeIf { rootActive },
                            LocalPageBackPreviewFrame provides frame.takeIf { retainedRootVisible && captureFromContent },
                        ) {
                            PageRetainedContent(retainedRootVisible, rootActive, resumed) { active ->
                                // Entry marker only; Compose tracing owns the composition duration.
                                synchronousTrace("daiyu.chat.retained-compose-entry") {}
                                retainedRootContent(active)
                            }
                        }
                    }
                    key(contentKey) { content() }
                }
            }
            if (departure != null && showDeparture && !departure.forward) Box(Modifier.fillMaxSize().drawBehind {
                val value = exit.value
                drawRect(Color.Black, alpha = 0.5f * (1f - value))
            }.testTag("page-back-departure-scrim"))
            if (departure != null && showDeparture && !departure.forward) Box(Modifier.fillMaxSize().graphicsLayer {
                val value = exit.value
                translationX = kotlin.math.round(departure.direction * size.width * value)
            }.then(if (departure.direction > 0f) Modifier.absoluteSquircleClip(topLeft = cornerRadius, topRight = 0.dp, bottomRight = 0.dp, bottomLeft = cornerRadius)
                else Modifier.absoluteSquircleClip(topLeft = 0.dp, topRight = cornerRadius, bottomRight = cornerRadius, bottomLeft = 0.dp)).drawBehind {
                drawPreview(departure.frame)
            }.testTag("page-back-departure").pointerInput(departure) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false).consume()
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                }
            })
            if (activeDeparture && departure?.forward == true) Box(Modifier.fillMaxSize().testTag("page-enter-blocker").pointerInput(departure) {
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

private fun DrawScope.drawPreview(frame: PageBackPreviewLayers.Frame) {
    val image = frame.image
    if (image != null) drawImage(image)
}

private object RetainedRootSurface
