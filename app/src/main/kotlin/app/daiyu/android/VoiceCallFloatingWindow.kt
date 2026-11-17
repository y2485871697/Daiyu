package app.daiyu.android

import android.app.AppOpsManager
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.daiyu.android.ui.screens.chat.conversation.DoubaoCallActions
import app.daiyu.android.ui.screens.chat.conversation.DoubaoCallPresentation
import app.daiyu.android.ui.screens.chat.conversation.DoubaoCallUi
import app.daiyu.android.ui.screens.chat.conversation.FloatingVoiceCallSurface
import app.daiyu.android.ui.screens.chat.conversation.DoubaoCallAnchor
import app.daiyu.android.ui.screens.chat.conversation.DoubaoCallMotionState
import kotlin.math.roundToInt

/** Presentation only. The existing microphone foreground service owns call eligibility. */
internal class VoiceCallFloatingWindow(context: Context,
    private val windows: WindowManager = context.getSystemService(WindowManager::class.java),
    private val onUnavailable: () -> Unit = {},
) : AutoCloseable {
    private val app = context.applicationContext
    private var window: CallWindow? = null
    private val exiting = mutableSetOf<CallWindow>()
    private var closed = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val appOps = app.getSystemService(AppOpsManager::class.java)
    private val overlayPermission = AppOpsManager.OnOpChangedListener { op, pkg ->
        if (op == AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW && pkg == app.packageName) mainHandler.post {
            if (!closed && !Settings.canDrawOverlays(app)) {
                onUnavailable()
            }
        }
    }
    init { appOps.startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, app.packageName, overlayPermission) }

    fun isShowing(key: Long): Boolean = window?.let {
        it.picture.sessionKey == key && !it.departing && it.isAttachedToWindow
    } == true

    fun update(state: DoubaoCallUi, actions: DoubaoCallActions,
        anchor: DoubaoCallAnchor? = null, motion: DoubaoCallMotionState? = null,
        inApp: Boolean = false): Boolean {
        checkMain()
        if (closed) return false
        val permitted = Settings.canDrawOverlays(app)
        if (state.presentation == DoubaoCallPresentation.CLOSING) {
            val target = window?.takeIf { it.picture.sessionKey == state.sessionKey }
                ?: exiting.firstOrNull { it.picture.sessionKey == state.sessionKey }
            if (target != null) {
                target.actions = actions
                if (permitted) {
                    if (inApp && anchor != null) {
                        target.anchor = anchor
                        if (target.genieClosing) syncFrame(target)
                    }
                    if (target.genieClosing && !inApp) fade(target)
                    else if (!target.departing) {
                        target.anchor = anchor ?: target.anchor
                        target.inApp = inApp
                        dismiss(target)
                    }
                } else remove(target, completed = true)
            }
            if (!permitted) hide()
            return permitted
        }
        if (!permitted) { hide(); return false }
        if (exiting.any { it.picture.sessionKey == state.sessionKey }) return true
        exiting.toList().forEach { remove(it, completed = true) }
        val previous = window
        val current = if (previous == null || previous.picture.sessionKey != state.sessionKey || previous.departing || previous.detached) {
            previous?.let { remove(it, completed = false) }
            val created = CallWindow(state, actions, anchor.takeIf { inApp }, motion, inApp)
            window = created
            placeInitial(created)
            syncFrame(created)
            try { windows.addView(created, created.params) }
            catch (_: RuntimeException) { remove(created, completed = false); return false }
            created
        } else previous
        val previousAnchor = current.anchor
        if (current.picture.presentation != state.presentation) {
            cancelHeightRestore(current)
            current.imePlacement = null
            current.manualImePlacement = null
        }
        current.actions = actions
        current.anchor = anchor ?: current.anchor
        current.inApp = inApp
        current.picture = state
        if (inApp && current.anchor != null && !current.manuallyDragged) {
            placeInitial(current, previousAnchor)
            syncFrame(current)
        } else if (!inApp || !current.manuallyDragged || !placeManualIme(current, previousAnchor)) resize(current)
        return !current.removed
    }

    /** Final removal or permission revocation, never an Activity foreground transition. */
    fun hide() {
        checkMain()
        window?.let { remove(it, completed = false) }
        exiting.toList().forEach { remove(it, completed = true) }
    }

    /** Audio is stopped before the in-app Genie or the external fade begins. */
    fun dismiss() {
        checkMain()
        window?.let(::dismiss)
    }

    private fun dismiss(current: CallWindow) {
        if (current.departing || current.removed) return
        current.departing = true
        cancelHeightRestore(current)
        current.imePlacement = null
        current.manualImePlacement = null
        current.removeCallbacks(current.moveFrame)
        current.movePending = false
        current.pendingMove = Offset.Zero
        exiting.add(current)
        if (window === current) window = null
        if (current.inApp) {
            if (current.anchor == null || !current.isLaidOut || !ValueAnimator.areAnimatorsEnabled()) {
                remove(current, completed = true)
                return
            }
            current.genieClosing = true
            current.picture = current.picture.copy(presentation = DoubaoCallPresentation.CLOSING)
            syncFrame(current)
        } else fade(current)
    }

    private fun fade(current: CallWindow) {
        if (current.removed || current.fading) return
        current.fading = true
        current.genieClosing = false
        current.inApp = false
        if (current.picture.presentation == DoubaoCallPresentation.CLOSING)
            current.picture = current.picture.copy(presentation = current.livePresentation)
        if (!current.isLaidOut || !ValueAnimator.areAnimatorsEnabled()) {
            remove(current, completed = true)
            return
        }
        current.pivotX = current.width / 2f
        current.pivotY = current.height / 2f
        current.animate().alpha(0f).scaleX(.88f).scaleY(.88f).translationY(dp(8).toFloat())
            .setDuration(CLOSE_MILLIS).setInterpolator(PathInterpolator(.2f, 0f, 0f, 1f))
            .withEndAction { remove(current, completed = true) }.start()
    }

    private fun placeInitial(current: CallWindow, previousAnchor: DoubaoCallAnchor? = null) {
        val metrics = app.resources.displayMetrics
        val host = current.anchor?.hostBounds
        val mini = current.picture.presentation == DoubaoCallPresentation.MINI
        current.expandedWidth = minOf(host?.width?.minus(dp(20)) ?: dp(380).toFloat(),
            (metrics.widthPixels - dp(20)).toFloat()).coerceAtLeast(1f)
        val width = if (mini) minOf(dp(250).toFloat(), current.expandedWidth) else current.expandedWidth
        val bottom = current.anchor?.let { it.composerTop - dp(10) }
        val topLimit = maxOf((host?.top ?: 0f) + dp(8), current.safeTop.toFloat())
        current.expandedHeight = fullHeight(current)
        val fittedHeight = bottom?.let { (it - topLimit).coerceAtLeast(1f) } ?: current.expandedHeight
        var height = minOf(if (mini) dp(67).toFloat() else current.expandedHeight, fittedHeight)
        if (mini) current.imePlacement = null
        else height = imeHeight(current, previousAnchor, height) { end ->
            minOf(current.expandedHeight, (end - dp(10) - topLimit).coerceAtLeast(1f))
        }
        val left = (host?.center?.x ?: (metrics.widthPixels / 2f)) - width / 2f
        val top = bottom?.minus(height) ?: (metrics.heightPixels * .18f)
        current.panelBounds = Rect(left, top, left + width, top + height)
        clamp(current)
    }

    private fun imeHeight(current: CallWindow, previousAnchor: DoubaoCallAnchor?, fittedHeight: Float,
        endHeight: (Float) -> Float): Float {
        val anchor = current.anchor ?: return fittedHeight
        val ime = anchor.imeInsets ?: return fittedHeight
        if (previousAnchor == null || !current.isLaidOut) return fittedHeight
        if (previousAnchor.hostBounds != anchor.hostBounds) {
            current.imePlacement = null
            current.imeComposerBaseline = null
        }
        val placement = current.imePlacement
        if (previousAnchor.hostBounds == anchor.hostBounds && ime.source != ime.target &&
            (placement == null || placement.targetInset != ime.target)) {
            val start = previousAnchor.composerTop
            val baseline = current.imeComposerBaseline ?: (start + (previousAnchor.imeInsets?.bottom ?: ime.source))
            current.imeComposerBaseline = baseline
            val end = baseline - ime.target
            if (kotlin.math.abs(end - start) > .5f)
                current.imePlacement = ImePlacement(ime.target, start, end, current.panelBounds.height, endHeight(end))
        }
        val transition = current.imePlacement ?: return fittedHeight
        // Read the displayed composer, including trailing frames after inset metadata settles.
        val progress = ((anchor.composerTop - transition.startComposerTop) /
            (transition.endComposerTop - transition.startComposerTop)).coerceIn(0f, 1f)
        val height = minOf(fittedHeight, transition.startHeight +
            (transition.endHeight - transition.startHeight) * progress)
        if (progress >= 1f) current.imePlacement = null
        return height
    }

    private data class ManualImePlacement(val bounds: Rect, val composerTop: Float)

    private fun placeManualIme(current: CallWindow, previousAnchor: DoubaoCallAnchor?): Boolean {
        val anchor = current.anchor ?: return false
        val ime = anchor.imeInsets ?: return false
        val previousIme = previousAnchor?.imeInsets
        val closed = ime.bottom == 0 && ime.target == 0
        if (closed && current.manualImePlacement == null) current.manualImeSuppressed = false
        if (current.picture.presentation != DoubaoCallPresentation.EXPANDED) return false
        if (previousAnchor != null && previousAnchor.hostBounds != anchor.hostBounds) {
            current.manualImePlacement = null
            current.imePlacement = null
            current.imeComposerBaseline = null
            return false
        }
        if (current.manualImePlacement == null) {
            if (previousAnchor == null) return false
            val opening = previousIme != null && previousIme.bottom == 0 && previousIme.target == 0 &&
                (ime.target > 0 || ime.bottom > 0)
            if (!opening || current.manualImeSuppressed) return false
            current.manualImeSuppressed = true
            val resting = current.panelBounds
            val composer = previousAnchor.composerBounds ?: Rect(previousAnchor.hostBounds.left,
                previousAnchor.composerTop, previousAnchor.hostBounds.right, previousAnchor.hostBounds.bottom)
            if (resting.overlaps(composer)) {
                current.actions.onMinimize()
                return true
            }
            cancelHeightRestore(current)
            current.imePlacement = null
            current.imeComposerBaseline = previousAnchor.composerTop
            current.manualImePlacement = ManualImePlacement(resting, previousAnchor.composerTop)
        }
        val saved = checkNotNull(current.manualImePlacement)
        val topLimit = maxOf(anchor.hostBounds.top + dp(8), current.safeTop.toFloat())
        val bottom = (saved.bounds.bottom + anchor.composerTop - saved.composerTop).coerceAtLeast(topLimit + 1f)
        val fittedHeight = minOf(saved.bounds.height, (bottom - topLimit).coerceAtLeast(1f))
        val height = imeHeight(current, previousAnchor, fittedHeight) { end ->
            minOf(saved.bounds.height, (saved.bounds.bottom + end - saved.composerTop - topLimit).coerceAtLeast(1f))
        }
        current.panelBounds = Rect(saved.bounds.left, bottom - height, saved.bounds.right, bottom)
        clamp(current)
        syncFrame(current)
        if (closed && kotlin.math.abs(current.panelBounds.top - saved.bounds.top) < .5f &&
            kotlin.math.abs(current.panelBounds.height - saved.bounds.height) < .5f) {
            current.panelBounds = saved.bounds
            current.manualImePlacement = null
            current.manualImeSuppressed = false
            current.imePlacement = null
            current.imeComposerBaseline = null
        }
        return true
    }

    private fun fullHeight(current: CallWindow): Float = minOf(dp(374).toFloat(),
        (app.resources.displayMetrics.heightPixels - current.safeTop - dp(48)).coerceAtLeast(1).toFloat())

    private fun resize(current: CallWindow) {
        current.expandedHeight = fullHeight(current)
        if (current.heightRestore != null) {
            if (current.heightRestoreTarget != current.expandedHeight) restoreFullHeight(current)
            clamp(current)
            syncFrame(current)
            return
        }
        val mini = current.picture.presentation == DoubaoCallPresentation.MINI
        val width = if (mini) minOf(dp(250).toFloat(), current.expandedWidth) else current.expandedWidth
        val height = if (mini) minOf(dp(67).toFloat(), current.expandedHeight) else current.expandedHeight
        val bounds = current.panelBounds
        current.panelBounds = Rect(bounds.left, bounds.top, bounds.left + width, bounds.top + height)
        clamp(current)
        syncFrame(current)
    }

    private data class ImePlacement(val targetInset: Int, val startComposerTop: Float,
        val endComposerTop: Float, val startHeight: Float, val endHeight: Float)

    private fun cancelHeightRestore(current: CallWindow) {
        val animator = current.heightRestore
        current.heightRestore = null
        current.heightRestoreFinished = false
        current.pendingHeight = null
        animator?.cancel()
    }

    private fun restoreFullHeight(current: CallWindow) {
        cancelHeightRestore(current)
        current.expandedHeight = fullHeight(current)
        if (current.picture.presentation == DoubaoCallPresentation.MINI ||
            kotlin.math.abs(current.panelBounds.height - current.expandedHeight) < .5f ||
            !ValueAnimator.areAnimatorsEnabled()) {
            resize(current)
            return
        }
        current.heightRestoreTarget = current.expandedHeight
        val animator = ValueAnimator.ofFloat(current.panelBounds.height, current.expandedHeight)
        current.heightRestore = animator
        animator.duration = RESTORE_MILLIS
        animator.interpolator = PathInterpolator(.2f, 0f, 0f, 1f)
        animator.addUpdateListener {
            if (current.heightRestore !== it || current.removed || current.departing) return@addUpdateListener
            current.pendingHeight = it.animatedValue as Float
            scheduleMove(current)
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (current.heightRestore === animation) {
                    current.heightRestoreFinished = true
                    scheduleMove(current)
                }
            }
        })
        animator.start()
    }

    private fun move(current: CallWindow, delta: Offset) {
        if (window !== current || current.departing || current.inApp && current.entryGenie && current.motion.transforming) return
        if (delta == Offset.Zero) return
        if (!current.manuallyDragged || current.manualImePlacement != null) {
            current.manuallyDragged = true
            current.manualImePlacement = null
            current.manualImeSuppressed = current.anchor?.imeInsets?.let { it.bottom > 0 || it.target > 0 } == true
            current.imeComposerBaseline = null
            current.imePlacement = null
            restoreFullHeight(current)
            if (current.removed) return
        }
        current.pendingMove += delta
        scheduleMove(current)
    }
    private fun scheduleMove(current: CallWindow) {
        if (!current.movePending) {
            current.movePending = true
            current.postOnAnimation(current.moveFrame)
        }
    }
    private fun applyMove(current: CallWindow) {
        current.movePending = false
        val delta = current.pendingMove
        current.pendingMove = Offset.Zero
        val height = current.pendingHeight ?: current.panelBounds.height
        current.pendingHeight = null
        if (window !== current || current.removed || current.departing || current.fading) return
        val bounds = current.panelBounds
        current.panelBounds = Rect(bounds.left, bounds.top, bounds.right, bounds.top + height).translate(delta)
        if (current.heightRestoreFinished) {
            current.heightRestore = null
            current.heightRestoreFinished = false
        }
        clamp(current)
        syncFrame(current)
    }
    private fun clamp(current: CallWindow) {
        val metrics = app.resources.displayMetrics
        val bounds = current.panelBounds
        val left = bounds.left.coerceIn(0f, (metrics.widthPixels - bounds.width).coerceAtLeast(0f))
        val top = bounds.top.coerceIn(current.safeTop.toFloat(),
            (metrics.heightPixels - bounds.height - dp(48)).coerceAtLeast(current.safeTop.toFloat()))
        current.panelBounds = Rect(left, top, left + bounds.width, top + bounds.height)
    }
    private fun syncFrame(current: CallWindow) {
        if (current.removed || current.fading) return
        val morphing = current.genieClosing || current.inApp && current.entryGenie && current.motion.transforming
        val panel = current.panelBounds
        current.panelSize = Size(panel.width.roundToInt().toFloat(), panel.height.roundToInt().toFloat())
        val anchor = current.anchor?.bounds
        val frame = if (morphing && anchor != null) Rect(
            minOf(panel.left, anchor.left) - dp(28), minOf(panel.top, anchor.top) - dp(28),
            maxOf(panel.right, anchor.right) + dp(28), maxOf(panel.bottom, anchor.bottom) + dp(28)) else panel.inflate(dp(28).toFloat())
        val left = frame.left.roundToInt().coerceAtLeast(0)
        val top = frame.top.roundToInt().coerceAtLeast(0)
        val width = (frame.right.roundToInt() - left).coerceAtLeast(1)
        val height = (frame.bottom.roundToInt() - top).coerceAtLeast(1)
        if (current.params.x == left && current.params.y == top && current.params.width == width &&
            current.params.height == height) return
        current.params.x = left; current.params.y = top
        current.params.width = width; current.params.height = height
        if (!current.isAttachedToWindow) return
        try { windows.updateViewLayout(current, current.params) }
        catch (_: RuntimeException) { remove(current, completed = current.departing); onUnavailable() }
    }
    private fun remove(current: CallWindow, completed: Boolean) {
        if (current.removed) return
        current.removed = true
        cancelHeightRestore(current)
        current.imePlacement = null
        current.manualImePlacement = null
        current.animate().withEndAction(null).cancel()
        mainHandler.removeCallbacks(current.settleFrame)
        current.removeCallbacks(current.moveFrame)
        current.movePending = false
        current.pendingMove = Offset.Zero
        // addView may have returned before the first attachment traversal.
        try { windows.removeViewImmediate(current) } catch (_: IllegalArgumentException) { }
        val finish = current.actions.onCloseAnimationFinished
        val key = current.picture.sessionKey
        current.release()
        exiting.remove(current)
        if (window === current) window = null
        if (completed) finish(key)
    }
    override fun close() {
        checkMain()
        closed = true
        appOps.stopWatchingMode(overlayPermission)
        window?.let { remove(it, completed = false) }
        exiting.toList().forEach { remove(it, completed = false) }
    }
    private fun safeTopInset(): Int {
        if (android.os.Build.VERSION.SDK_INT >= 30) return windows.maximumWindowMetrics.windowInsets
            .getInsetsIgnoringVisibility(android.view.WindowInsets.Type.statusBars() or
                android.view.WindowInsets.Type.displayCutout()).top
        @Suppress("DiscouragedApi")
        val id = app.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id != 0) app.resources.getDimensionPixelSize(id) else 0
    }
    private fun dp(value: Int) = (value * app.resources.displayMetrics.density).roundToInt()
    private fun checkMain() { check(Looper.myLooper() == Looper.getMainLooper()) }

    private inner class CallWindow(initial: DoubaoCallUi, initialActions: DoubaoCallActions,
        initialAnchor: DoubaoCallAnchor?, sharedMotion: DoubaoCallMotionState?, initialInApp: Boolean) :
        FrameLayout(app), LifecycleOwner, SavedStateRegistryOwner {
        var picture by mutableStateOf(initial)
        var actions by mutableStateOf(initialActions)
        var departing by mutableStateOf(false)
        var genieClosing by mutableStateOf(false)
        var inApp by mutableStateOf(initialInApp)
        var anchor by mutableStateOf(initialAnchor)
        var manuallyDragged = false
        var manualImePlacement: ManualImePlacement? = null
        var manualImeSuppressed = false
        var imePlacement: ImePlacement? = null
        var imeComposerBaseline: Float? = null
        var heightRestore: ValueAnimator? = null
        var heightRestoreFinished = false
        var heightRestoreTarget = 0f
        var pendingHeight: Float? = null
        var pendingMove = Offset.Zero
        var movePending = false
        val moveFrame = Runnable { applyMove(this) }
        var safeTop = safeTopInset()
        var configurationGeometry = app.resources.displayMetrics.let {
            listOf(it.widthPixels, it.heightPixels, it.densityDpi, safeTop)
        }
        var panelBounds by mutableStateOf(Rect.Zero)
        var panelSize by mutableStateOf(Size.Zero)
        var expandedWidth = dp(380).toFloat()
        var expandedHeight = dp(374).toFloat()
        val entryGenie = initialInApp && initialAnchor != null && !initial.skipEntryAnimation
        val motion = sharedMotion ?: DoubaoCallMotionState()
        var livePresentation = initial.presentation
        var fading = false
        val settleFrame = Runnable { syncFrame(this) }
        var detached = false
        var removed = false
        val params = WindowManager.LayoutParams(
            minOf(dp(if (initial.presentation == DoubaoCallPresentation.MINI) 250 else 380), (app.resources.displayMetrics.widthPixels - dp(20)).coerceAtLeast(1)),
            minOf(dp(if (initial.presentation == DoubaoCallPresentation.MINI) 67 else 374), (app.resources.displayMetrics.heightPixels - dp(100)).coerceAtLeast(1)),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.START
                title = "代鱼语音通话"
                windowAnimations = 0
                if (android.os.Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
            }
        private val ownerLifecycle = LifecycleRegistry(this)
        private val savedState = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = ownerLifecycle
        override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
        private val composition: ComposeView
        private val syncOrigin = ViewTreeObserver.OnPreDrawListener { syncCompositionOrigin() }
        private var released = false
        private var originCommitted = false
        private val releaseAfterDetach = Runnable {
            if (departing && !removed) remove(this, completed = true) else release()
        }
        init {
            savedState.performRestore(null)
            ownerLifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            setViewTreeLifecycleOwner(this)
            setViewTreeSavedStateRegistryOwner(this)
            composition = ComposeView(app).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent {
                    androidx.compose.material3.MaterialTheme {
                        if (picture.presentation != DoubaoCallPresentation.CLOSING)
                            livePresentation = picture.presentation
                        val completion = actions.copy(onCloseAnimationFinished = { key ->
                            if (key == picture.sessionKey && genieClosing && departing && !removed)
                                remove(this@CallWindow, completed = true)
                        })
                        FloatingVoiceCallSurface(picture.copy(skipEntryAnimation = !entryGenie || picture.skipEntryAnimation),
                            completion, Rect(Offset.Zero, panelSize), anchor?.bounds ?: Rect.Zero, motion,
                            panelPosition = { panelBounds.topLeft },
                            animationAllowed = inApp, enabled = !departing,
                            onDrag = { move(this@CallWindow, it) })
                        LaunchedEffect(inApp, genieClosing, motion.transforming) {
                            mainHandler.removeCallbacks(settleFrame)
                            mainHandler.post(settleFrame)
                        }
                    }
                }
            }
            val metrics = app.resources.displayMetrics
            addView(composition, LayoutParams(metrics.widthPixels, metrics.heightPixels))
            viewTreeObserver.addOnPreDrawListener(syncOrigin)
        }
        private val inputBlocked get() = departing || genieClosing || inApp && entryGenie && motion.transforming
        override fun onInterceptTouchEvent(event: MotionEvent): Boolean =
            inputBlocked || super.onInterceptTouchEvent(event)
        override fun onTouchEvent(event: MotionEvent): Boolean =
            inputBlocked || super.onTouchEvent(event)
        override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
            super.onConfigurationChanged(newConfig)
            val metrics = app.resources.displayMetrics
            val geometry = listOf(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi, safeTopInset())
            if (geometry != configurationGeometry) {
                configurationGeometry = geometry
                imePlacement = null
                imeComposerBaseline = null
                manualImePlacement = null
            }
            safeTop = safeTopInset()
            if (!departing && !removed) {
                if (manuallyDragged && manualImePlacement == null) resize(this) else clamp(this)
                mainHandler.removeCallbacks(settleFrame)
                mainHandler.post(settleFrame)
            }
        }
        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            super.onLayout(changed, left, top, right, bottom)
            syncCompositionOrigin()
        }
        private fun syncCompositionOrigin(): Boolean {
            val screen = IntArray(2)
            val local = IntArray(2)
            getLocationOnScreen(screen)
            getLocationInWindow(local)
            val x = screen[0] - local[0]
            val y = screen[1] - local[1]
            composition.translationX = -x.toFloat()
            composition.translationY = -y.toFloat()
            val matches = x == params.x && y == params.y
            if (matches) originCommitted = true
            val strictPlacement = !originCommitted || genieClosing || inApp && entryGenie && motion.transforming
            val placed = fading || !strictPlacement || matches
            if (!placed) postInvalidateOnAnimation()
            return placed
        }
        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            ownerLifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
            ownerLifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        fun release() {
            if (released) return
            released = true
            cancelHeightRestore(this)
            imePlacement = null
            manualImePlacement = null
            mainHandler.removeCallbacks(releaseAfterDetach)
            removeCallbacks(moveFrame)
            movePending = false
            pendingMove = Offset.Zero
            if (viewTreeObserver.isAlive) viewTreeObserver.removeOnPreDrawListener(syncOrigin)
            composition.disposeComposition()
            if (lifecycle.currentState != Lifecycle.State.DESTROYED)
                ownerLifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            removeAllViews()
        }
        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            detached = true
            cancelHeightRestore(this)
            // ViewGroup is still dispatching child detachment during this callback.
            mainHandler.post(releaseAfterDetach)
        }
    }
    companion object {
        internal const val CLOSE_MILLIS = 180L
        internal const val RESTORE_MILLIS = 220L
    }
}
