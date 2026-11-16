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
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
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
import app.daiyu.android.ui.screens.chat.conversation.FloatingVoiceCallContent
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

    fun update(state: DoubaoCallUi, actions: DoubaoCallActions): Boolean {
        checkMain()
        if (closed) return false
        val permitted = Settings.canDrawOverlays(app)
        if (state.presentation == DoubaoCallPresentation.CLOSING) {
            val target = window?.takeIf { it.picture.sessionKey == state.sessionKey }
                ?: exiting.firstOrNull { it.picture.sessionKey == state.sessionKey }
            if (target != null) {
                target.actions = actions
                if (permitted) dismiss(target) else remove(target, completed = true)
            }
            if (!permitted) hide()
            return permitted
        }
        if (!permitted) { hide(); return false }
        if (exiting.any { it.picture.sessionKey == state.sessionKey }) return true
        val previous = window
        val current = if (previous == null || previous.picture.sessionKey != state.sessionKey || previous.departing || previous.detached) {
            previous?.let { remove(it, completed = false) }
            val created = CallWindow(state, actions)
            window = created
            val metrics = app.resources.displayMetrics
            created.params.x = ((metrics.widthPixels - created.params.width) / 2).coerceAtLeast(0)
            created.params.y = (metrics.heightPixels * .18f).roundToInt()
            clamp(created)
            try { windows.addView(created, created.params) }
            catch (_: RuntimeException) { remove(created, completed = false); return false }
            created
        } else previous
        current.actions = actions
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

    /** Audio is stopped by the caller before this independent, anchor-free exit begins. */
    fun dismiss() {
        checkMain()
        window?.let(::dismiss)
    }

    private fun dismiss(current: CallWindow) {
        if (current.departing || current.removed) return
        current.departing = true
        exiting.add(current)
        if (window === current) window = null
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

    private fun resize(current: CallWindow) {
        val mini = current.picture.presentation == DoubaoCallPresentation.MINI
        val metrics = app.resources.displayMetrics
        val width = minOf(dp(if (mini) 250 else 380), (metrics.widthPixels - dp(20)).coerceAtLeast(1))
        val height = minOf(dp(if (mini) 67 else 374), (metrics.heightPixels - dp(100)).coerceAtLeast(1))
        if (current.params.width == width && current.params.height == height) return
        current.params.width = width
        current.params.height = height
        clamp(current)
        try { windows.updateViewLayout(current, current.params) }
        catch (_: RuntimeException) { remove(current, completed = false) }
    }

    private fun move(current: CallWindow, delta: Offset) {
        if (window !== current || current.departing) return
        current.params.x += delta.x.roundToInt()
        current.params.y += delta.y.roundToInt()
        clamp(current)
        try { windows.updateViewLayout(current, current.params) }
        catch (_: RuntimeException) {
            remove(current, completed = false)
            onUnavailable()
        }
    }
    private fun clamp(current: CallWindow) {
        val metrics = app.resources.displayMetrics
        current.params.x = current.params.x.coerceIn(0, (metrics.widthPixels - current.params.width).coerceAtLeast(0))
        current.params.y = current.params.y.coerceIn(0, (metrics.heightPixels - current.params.height - dp(48)).coerceAtLeast(0))
    }
    private fun remove(current: CallWindow, completed: Boolean) {
        if (current.removed) return
        current.removed = true
        current.animate().withEndAction(null).cancel()
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

    private inner class CallWindow(initial: DoubaoCallUi, initialActions: DoubaoCallActions) :
        FrameLayout(app), LifecycleOwner, SavedStateRegistryOwner {
        var picture by mutableStateOf(initial)
        var actions by mutableStateOf(initialActions)
        var departing by mutableStateOf(false)
        var detached = false
        var removed = false
        val params = WindowManager.LayoutParams(
            minOf(dp(if (initial.presentation == DoubaoCallPresentation.MINI) 250 else 380), (app.resources.displayMetrics.widthPixels - dp(20)).coerceAtLeast(1)),
            minOf(dp(if (initial.presentation == DoubaoCallPresentation.MINI) 67 else 374), (app.resources.displayMetrics.heightPixels - dp(100)).coerceAtLeast(1)),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.START
                title = "代鱼语音通话"
                windowAnimations = 0
            }
        private val ownerLifecycle = LifecycleRegistry(this)
        private val savedState = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = ownerLifecycle
        override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
        private val composition: ComposeView
        private var released = false
        private val releaseAfterDetach = Runnable { release() }
        init {
            savedState.performRestore(null)
            ownerLifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            setViewTreeLifecycleOwner(this)
            setViewTreeSavedStateRegistryOwner(this)
            composition = ComposeView(app).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent {
                    androidx.compose.material3.MaterialTheme {
                        FloatingVoiceCallContent(picture, actions, !departing) { move(this@CallWindow, it) }
                    }
                }
            }
            addView(composition, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
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
