package app.daiyu.android

import android.app.AppOpsManager
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
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
        current.actions = actions
        current.anchor = anchor ?: current.anchor
        current.inApp = inApp
        current.picture = state
        resize(current)
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
        current.params.flags = current.params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        try { windows.updateViewLayout(current, current.params) }
        catch (_: RuntimeException) { remove(current, completed = true); return }
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

    private fun placeInitial(current: CallWindow) {
        val metrics = app.resources.displayMetrics
        val host = current.anchor?.hostBounds
        val mini = current.picture.presentation == DoubaoCallPresentation.MINI
        current.expandedWidth = minOf(host?.width?.minus(dp(20)) ?: dp(380).toFloat(),
            (metrics.widthPixels - dp(20)).toFloat()).coerceAtLeast(1f)
        val width = if (mini) minOf(dp(250).toFloat(), current.expandedWidth) else current.expandedWidth
        val bottom = current.anchor?.let { it.composerTop - dp(10) }
        val topLimit = (host?.top ?: 0f) + dp(8)
        current.expandedHeight = minOf(dp(374).toFloat(),
            bottom?.let { (it - topLimit).coerceAtLeast(1f) } ?: (metrics.heightPixels - dp(100)).toFloat())
        val height = if (mini) minOf(dp(67).toFloat(), current.expandedHeight) else current.expandedHeight
        val left = (host?.center?.x ?: (metrics.widthPixels / 2f)) - width / 2f
        val top = bottom?.minus(height) ?: (metrics.heightPixels * .18f)
        current.panelBounds = Rect(left, top, left + width, top + height)
        clamp(current)
    }

    private fun resize(current: CallWindow) {
        val mini = current.picture.presentation == DoubaoCallPresentation.MINI
        val width = if (mini) minOf(dp(250).toFloat(), current.expandedWidth) else current.expandedWidth
        val height = if (mini) minOf(dp(67).toFloat(), current.expandedHeight) else current.expandedHeight
        val bounds = current.panelBounds
        current.panelBounds = Rect(bounds.left, bounds.top, bounds.left + width, bounds.top + height)
        clamp(current)
        syncFrame(current)
    }

    private fun move(current: CallWindow, delta: Offset) {
        if (window !== current || current.departing || current.inApp && current.entryGenie && current.motion.transforming) return
        current.panelBounds = current.panelBounds.translate(delta)
        clamp(current)
        syncFrame(current)
    }
    private fun clamp(current: CallWindow) {
        val metrics = app.resources.displayMetrics
        val bounds = current.panelBounds
        val left = bounds.left.coerceIn(0f, (metrics.widthPixels - bounds.width).coerceAtLeast(0f))
        val top = bounds.top.coerceIn(0f, (metrics.heightPixels - bounds.height - dp(48)).coerceAtLeast(0f))
        current.panelBounds = Rect(left, top, left + bounds.width, top + bounds.height)
    }
    private fun syncFrame(current: CallWindow) {
        if (current.removed || current.fading) return
        val morphing = current.genieClosing || current.inApp && current.entryGenie && current.motion.transforming
        val panel = current.panelBounds
        val anchor = current.anchor?.bounds
        val frame = if (morphing && anchor != null) Rect(
            minOf(panel.left, anchor.left) - dp(28), minOf(panel.top, anchor.top) - dp(28),
            maxOf(panel.right, anchor.right) + dp(28), maxOf(panel.bottom, anchor.bottom) + dp(28)) else panel
        val left = frame.left.roundToInt().coerceAtLeast(0)
        val top = frame.top.roundToInt().coerceAtLeast(0)
        val width = (frame.right.roundToInt() - left).coerceAtLeast(1)
        val height = (frame.bottom.roundToInt() - top).coerceAtLeast(1)
        val flags = if (morphing || current.departing) current.params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            else current.params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        if (current.params.x == left && current.params.y == top && current.params.width == width &&
            current.params.height == height && current.params.flags == flags) return
        current.params.x = left; current.params.y = top
        current.params.width = width; current.params.height = height; current.params.flags = flags
        if (!current.isAttachedToWindow) return
        try { windows.updateViewLayout(current, current.params) }
        catch (_: RuntimeException) { remove(current, completed = current.departing); onUnavailable() }
    }
    private fun remove(current: CallWindow, completed: Boolean) {
        if (current.removed) return
        current.removed = true
        current.animate().withEndAction(null).cancel()
        mainHandler.removeCallbacks(current.settleFrame)
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
        var panelBounds by mutableStateOf(Rect.Zero)
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
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
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
        private val syncOrigin = ViewTreeObserver.OnPreDrawListener {
            syncCompositionOrigin()
            true
        }
        private var released = false
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
                            completion, panelBounds, anchor?.bounds ?: panelBounds, motion,
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
        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            super.onLayout(changed, left, top, right, bottom)
            syncCompositionOrigin()
        }
        private fun syncCompositionOrigin() {
            val origin = IntArray(2)
            getLocationOnScreen(origin)
            composition.translationX = -origin[0].toFloat()
            composition.translationY = -origin[1].toFloat()
        }
        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            ownerLifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
            ownerLifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        fun release() {
            if (released) return
            released = true
            mainHandler.removeCallbacks(releaseAfterDetach)
            if (viewTreeObserver.isAlive) viewTreeObserver.removeOnPreDrawListener(syncOrigin)
            composition.disposeComposition()
            if (lifecycle.currentState != Lifecycle.State.DESTROYED)
                ownerLifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            removeAllViews()
        }
        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            detached = true
            // ViewGroup is still dispatching child detachment during this callback.
            mainHandler.post(releaseAfterDetach)
        }
    }
    companion object { internal const val CLOSE_MILLIS = 180L }
}
