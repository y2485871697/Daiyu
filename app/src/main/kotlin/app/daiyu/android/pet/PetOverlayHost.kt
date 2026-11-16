package app.daiyu.android.pet

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.daiyu.android.domain.pet.*
import kotlin.math.abs
import kotlin.math.roundToInt


enum class PetOverlayState { DISABLED, IN_APP, PERMISSION_REQUIRED, VISIBLE, ERROR, CLOSED }

/** UNKNOWN includes a failed add which may have registered a root before attaching its View. */
internal enum class PetOverlayRemoval { DETACHED, UNKNOWN }

/** Small synchronous port: DETACHED promises that the window no longer needs cleanup. */
internal interface PetOverlayWindowPort<W> {
    fun add(window: W)
    fun update(window: W)
    fun remove(window: W): PetOverlayRemoval
}

/** Keep the cleanup handle even when add/remove throws. Independent of Android for fault tests. */
internal class PetOverlayWindowOwner<W : Any>(
    private val port: PetOverlayWindowPort<W>, private val releaseContent: (W) -> Unit
) {
    var window: W? = null
        private set
    var cleanupPending = false
        private set

    fun add(candidate: W): Boolean {
        check(window == null) { "Previous overlay still owns a window" }
        window = candidate // Ownership precedes add: add is not an atomic operation.
        return try { port.add(candidate); true }
        catch (_: Exception) { remove(); false }
    }

    fun remove(): Boolean {
        val current = window ?: return true
        cleanupPending = true // Fence layout/drag work before releasing child Views.
        // Real child detach ends PetPanel observations, speech animation and sprite ticks,
        // even if the empty WindowManager root cannot yet be removed. Retry on later cleanup.
        try { releaseContent(current) } catch (_: Exception) { /* still try removing the root */ }
        val result = try { port.remove(current) } catch (_: Exception) { PetOverlayRemoval.UNKNOWN }
        if (result != PetOverlayRemoval.DETACHED) return false
        window = null
        cleanupPending = false
        return true
    }
}

/** Retained WindowManager adapter, exclusively owned by PetHost. No permission request/service launch. */
internal class PetOverlayHost(
    private val context: Context, private val controller: PetController,
    private val resources: PetResourcePort,
    private val onOpenSettings: () -> Unit,
    windowPort: PetOverlayWindowPort<View>? = null,
    private val canDrawOverlays: () -> Boolean = { Settings.canDrawOverlays(context) },
    private val onState: (PetOverlayState, String?) -> Unit
) : AutoCloseable {
    private val ui = Handler(Looper.getMainLooper())
    private var closed = false
    private val params = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; title = "DaiyuPet" }
    private val windows = windowPort ?: object : PetOverlayWindowPort<View> {
        private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        override fun add(window: View) { wm.addView(window, params) }
        override fun update(window: View) { wm.updateViewLayout(window, params) }
        override fun remove(window: View): PetOverlayRemoval = try {
            wm.removeViewImmediate(window)
            if (!window.isAttachedToWindow && window.parent == null) PetOverlayRemoval.DETACHED
            else PetOverlayRemoval.UNKNOWN
        } catch (_: Exception) {
            // !isAttachedToWindow alone cannot prove removal: add may fail after root
            // registration but before attachment. An exception never relinquishes ownership.
            PetOverlayRemoval.UNKNOWN
        }
    }
    private val owner = PetOverlayWindowOwner(windows) { current ->
        ui.removeCallbacksAndMessages(null)
        (current as DragWindow).releaseContent()
    }
    private val view: View? get() = owner.window
    private var anchorX = -1
    private var anchorY = -1
    private var dragging = false

    fun sync(snapshot: PetSnapshot) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (closed) { removeAndReport(PetOverlayState.CLOSED); return }
        val s = snapshot.state
        if (snapshot.closed || !s.settings.enabled || snapshot.error?.contains("存储") == true) {
            removeAndReport(PetOverlayState.DISABLED); return
        }
        if (!s.settings.globalVisible) { removeAndReport(PetOverlayState.IN_APP); return }
        if (!canDrawOverlays()) {
            removeAndReport(PetOverlayState.PERMISSION_REQUIRED, "系统悬浮权限未授权；仅在应用内显示"); return
        }
        if (owner.cleanupPending) { reportCleanupError(); return }
        if (!dragging) { anchorX = s.settings.x; anchorY = s.settings.y }
        if (view == null) {
            val frame = createWindow()
            val initial = placement(frame)
            params.x = initial.x; params.y = initial.y
            if (!owner.add(frame)) {
                if (owner.cleanupPending) reportCleanupError()
                else onState(PetOverlayState.ERROR, "桌宠悬浮窗添加失败；仅在应用内显示")
                return
            }
        }
        if (update()) onState(PetOverlayState.VISIBLE, null)
    }
    internal fun createWindow(): FrameLayout = DragWindow(context).apply {
        alpha = 0f
        clipChildren = false; clipToPadding = false
        addView(PetViewFactory.createPet(context, controller, this@PetOverlayHost.resources, true),
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        viewTreeObserver.addOnPreDrawListener {
            if (closed || owner.cleanupPending || view !== this) true
            else {
                val next = placement(this)
                val changed = params.x != next.x || params.y != next.y
                if (changed) { alpha = 0f; schedulePlacementUpdate(this); false }
                else if (!windowOriginMatches(this, next)) {
                    alpha = 0f
                    postInvalidateOnAnimation()
                    false
                } else { alpha = 1f; true }
            }
        }
    }
    private fun schedulePlacementUpdate(current: DragWindow) {
        if (current.placementUpdatePending) return
        current.placementUpdatePending = true
        // Submit after pre-draw returns, outside ViewRoot's active traversal.
        ui.post {
            current.placementUpdatePending = false
            if (!closed && !owner.cleanupPending && view === current) update()
        }
    }
    private fun windowOriginMatches(current: View, target: PetPlacement): Boolean {
        val screen = IntArray(2)
        val local = IntArray(2)
        current.getLocationOnScreen(screen)
        current.getLocationInWindow(local)
        // LayoutParams are requested coordinates; WindowManager applies them on a later traversal.
        // Compare the applied window origin, independent of the content's in-window offset.
        return screen[0] - local[0] == target.x && screen[1] - local[1] == target.y
    }
    private fun placement(current: View): PetPlacement {
        val metrics = context.resources.displayMetrics
        val side = (128f * controller.snapshot().state.settings.scale * metrics.density).roundToInt()
        return petPlacement(anchorX, anchorY, metrics.widthPixels, metrics.heightPixels, side,
            current.width.takeIf { it > 0 } ?: side, current.height.takeIf { it > 0 } ?: side,
            (16 * metrics.density).roundToInt())
    }
    private fun update(): Boolean {
        if (closed || owner.cleanupPending) return false
        val current = view ?: return false
        val next = placement(current)
        params.x = next.x; params.y = next.y
        return try { windows.update(current); true }
        catch (_: Exception) {
            if (owner.remove()) onState(PetOverlayState.ERROR, "桌宠悬浮窗更新失败")
            else reportCleanupError()
            false
        }
    }
    private fun reportCleanupError() {
        onState(PetOverlayState.ERROR, "桌宠悬浮窗清理结果未知；保留清理句柄并禁止创建新窗口")
    }
    private fun removeAndReport(state: PetOverlayState, message: String? = null) {
        if (owner.remove()) onState(state, message) else reportCleanupError()
    }
    override fun close() {
        check(Looper.myLooper() == Looper.getMainLooper())
        closed = true
        ui.removeCallbacksAndMessages(null)
        // A prior close can leave an unknown root. Closing again must retry that same owner.
        removeAndReport(PetOverlayState.CLOSED)
    }
    private inner class DragWindow(context: Context) : FrameLayout(context), LifecycleOwner, SavedStateRegistryOwner {
        var placementUpdatePending = false
        private val ownerLifecycle = LifecycleRegistry(this)
        private val savedState = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = ownerLifecycle
        override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
        init {
            savedState.performRestore(null)
            ownerLifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            setViewTreeLifecycleOwner(this)
            setViewTreeSavedStateRegistryOwner(this)
        }
        override fun onAttachedToWindow() {
            if (lifecycle.currentState != Lifecycle.State.DESTROYED) {
                ownerLifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
                ownerLifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            }
            super.onAttachedToWindow()
        }
        fun releaseContent() {
            dragging = false; spriteGesture = false
            removeAllViews()
            if (lifecycle.currentState != Lifecycle.State.DESTROYED)
                ownerLifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            if (lifecycle.currentState != Lifecycle.State.DESTROYED)
                ownerLifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
        private var spriteGesture = false
        private var downX = 0f; private var downY = 0f; private var originX = 0; private var originY = 0
        override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
            if (closed || owner.cleanupPending || view !== this) return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val bounds = android.graphics.Rect()
                    val sprite = findViewById<View>(app.daiyu.android.R.id.pet_sprite)
                    spriteGesture = sprite != null && sprite.getLocalVisibleRect(bounds) && run {
                        val screen = IntArray(2)
                        sprite.getLocationOnScreen(screen)
                        bounds.offset(screen[0], screen[1])
                        bounds.contains(event.rawX.roundToInt(), event.rawY.roundToInt())
                    }
                    downX = event.rawX; downY = event.rawY
                    originX = placement(this).spriteX; originY = placement(this).spriteY; dragging = false
                }
                MotionEvent.ACTION_MOVE -> if (spriteGesture && abs(event.rawX - downX) + abs(event.rawY - downY) >= 12f) dragging = true
            }
            return dragging
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (closed || owner.cleanupPending || view !== this) return false
            if (!dragging) return super.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                    val metrics = context.resources.displayMetrics
                    val side = (128f * controller.snapshot().state.settings.scale * metrics.density).roundToInt()
                    anchorX = (originX + (event.rawX - downX).roundToInt()).coerceIn(0, (metrics.widthPixels - side).coerceAtLeast(0))
                    anchorY = (originY + (event.rawY - downY).roundToInt()).coerceIn(0, (metrics.heightPixels - side).coerceAtLeast(0))
                    val released = event.actionMasked == MotionEvent.ACTION_UP
                    if (released) dragging = false
                    // Move the actual overlay surface; translating its wrap-content root clips the sprite.
                    if (update() && released) controller.setPosition(anchorX, anchorY)
                }
                MotionEvent.ACTION_CANCEL -> {
                    anchorX = originX; anchorY = originY
                    dragging = false
                    update()
                }
            }
            return true
        }
    }
}
