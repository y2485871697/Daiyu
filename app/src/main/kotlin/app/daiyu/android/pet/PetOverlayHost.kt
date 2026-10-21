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
import app.daiyu.android.domain.pet.*
import kotlin.math.abs
import kotlin.math.roundToInt

internal fun constrainPetOverlayPosition(x: Int, y: Int, screenWidth: Int, screenHeight: Int,
    childWidth: Int, childHeight: Int): Pair<Int, Int> =
    x.coerceIn(0, (screenWidth - childWidth).coerceAtLeast(0)) to
        y.coerceIn(0, (screenHeight - childHeight).coerceAtLeast(0))

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
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
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
        (current as FrameLayout).removeAllViews()
    }
    private val view: View? get() = owner.window

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
        if (view == null) {
            val frame = createWindow()
            params.x = s.settings.x.coerceAtLeast(0); params.y = s.settings.y.coerceAtLeast(0)
            if (!owner.add(frame)) {
                if (owner.cleanupPending) reportCleanupError()
                else onState(PetOverlayState.ERROR, "桌宠悬浮窗添加失败；仅在应用内显示")
                return
            }
        }
        if (s.settings.x >= 0) params.x = s.settings.x
        if (s.settings.y >= 0) params.y = s.settings.y
        if (update()) onState(PetOverlayState.VISIBLE, null)
    }
    internal fun createWindow(): FrameLayout = DragWindow(context).apply {
        addView(PetViewFactory.createPet(context, controller, this@PetOverlayHost.resources, true),
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                ui.post { if (!closed && !owner.cleanupPending && view === this) update() }
            }
        }
    }
    private fun update(): Boolean {
        if (closed || owner.cleanupPending) return false
        val current = view ?: return false
        val metrics = context.resources.displayMetrics
        val (x, y) = constrainPetOverlayPosition(params.x, params.y, metrics.widthPixels, metrics.heightPixels,
            current.width, current.height)
        params.x = x; params.y = y
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
    private inner class DragWindow(context: Context) : FrameLayout(context) {
        private var downX = 0f; private var downY = 0f; private var originX = 0; private var originY = 0
        private var dragging = false
        override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
            if (closed || owner.cleanupPending || view !== this) return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; originX = params.x; originY = params.y; dragging = false }
                MotionEvent.ACTION_MOVE -> if (abs(event.rawX - downX) + abs(event.rawY - downY) >= 12 * resources.displayMetrics.density) dragging = true
            }
            return dragging
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (closed || owner.cleanupPending || view !== this) return false
            if (!dragging) return super.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_MOVE || event.actionMasked == MotionEvent.ACTION_UP) {
                params.x = originX + (event.rawX - downX).roundToInt(); params.y = originY + (event.rawY - downY).roundToInt(); update()
            }
            if (!closed && !owner.cleanupPending && view === this &&
                (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL)) {
                dragging = false; controller.setPosition(params.x, params.y)
            }
            return true
        }
    }
}
