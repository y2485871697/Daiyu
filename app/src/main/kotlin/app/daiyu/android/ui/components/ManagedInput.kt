package app.daiyu.android.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.view.Window
import android.view.WindowManager.LayoutParams
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import kotlin.math.hypot

/** Only suppress selection/composition changes caused synchronously by our own dismissal. */
internal object InputDismissal {
    var inProgress = false
        private set

    var insideInput = false

    fun run(action: () -> Unit) {
        val previous = inProgress
        inProgress = true
        try { action() } finally { inProgress = previous }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun imeCursorColor(visibleColor: Color): Color =
    if (WindowInsets.isImeVisible) visibleColor else Color.Unspecified

// Material colors treat Unspecified as "use default", so use transparent for this API only.
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun materialImeCursorColor(visibleColor: Color): Color =
    if (WindowInsets.isImeVisible) visibleColor else Color.Transparent

@Composable
internal fun imeCursorBrush(visibleColor: Color): Brush = SolidColor(imeCursorColor(visibleColor))

private fun Context.inputActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.takeUnless { it === this }?.inputActivity()
    else -> null
}

private fun View.inputWindow(): Window? {
    var ancestor: Any? = this
    while (ancestor is View) {
        if (ancestor is DialogWindowProvider) return ancestor.window
        ancestor = ancestor.parent
    }
    val activity = (findViewTreeLifecycleOwner() as? Activity) ?: context.inputActivity()
    return activity?.window?.takeIf { isAttachedToWindow && it.decorView.windowToken == windowToken }
}

private fun View.visibleInputBounds(): RectF? {
    val bounds = android.graphics.Rect()
    if (!getGlobalVisibleRect(bounds)) return null
    val origin = IntArray(2)
    rootView.getLocationOnScreen(origin)
    return RectF(bounds).apply { offset(origin[0].toFloat(), origin[1].toFloat()) }
}

private fun inputTrace(message: String) {
    if (app.daiyu.android.BuildConfig.DEBUG) android.util.Log.d("ManagedInput", message)
}

private class InputEntry(
    val view: View,
    val focused: () -> Boolean,
    val bounds: () -> RectF?,
    val clear: () -> Unit,
)

/** 将 Compose 输入框的焦点和屏幕边界登记到所在窗口，供点击外部时收起输入使用。 */
internal fun Modifier.managedInput(): Modifier = composed {
    val view = LocalView.current
    val focus = LocalFocusManager.current
    val state = remember(view, focus) { ComposeInputRegion() }
    val entry = remember(view, focus) {
        InputEntry(view, { state.focused }, { state.bounds(view) }) {
            if (state.focused) focus.clearFocus(force = true)
        }
    }
    DisposableEffect(entry) {
        val detach = bindInput(entry)
        onDispose { detach() }
    }
    this.onGloballyPositioned { state.coordinates = it }.onFocusChanged {
        if (state.focused != it.hasFocus) inputTrace("compose focus=${it.hasFocus} attached=${view.isAttachedToWindow}")
        state.focused = it.hasFocus
    }
}

/** 清除焦点并请求隐藏键盘；失焦回调若将焦点转给另一个输入框，则跳过隐藏并返回 false。返回 true 表示已发出隐藏请求。 */
internal fun clearManagedInputFocus(
    view: View,
    hideIme: () -> Unit = { hideManagedInputIme(view) },
    clear: () -> Unit,
): Boolean {
    val action: () -> Unit = { clearWithoutNativeFocusFallback(view, clear) }
    val host = view.inputWindow()?.let { InputWindows.windows[it] }
    if (host != null) return host.dismiss(view, action, hideIme)
    val before = view.rootView.findFocus()
    InputDismissal.run {
        if (before !is EditText) {
            view.rootView.isFocusableInTouchMode = true
            view.rootView.requestFocus()
        }
        action()
    }
    val next = view.rootView.findFocus()
    if (next !== before && next?.onCheckIsTextEditor() == true) return false
    InputDismissal.run {
        view.rootView.isFocusableInTouchMode = true
        view.rootView.requestFocus()
    }
    hideIme()
    return true
}

internal fun hideManagedInputIme(view: View) {
    val controller = ViewCompat.getWindowInsetsController(view)
    if (controller != null) controller.hide(WindowInsetsCompat.Type.ime())
    else (view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
        ?.hideSoftInputFromWindow(view.windowToken, 0)
}

/** Let blur listeners transfer focus before suppressing Android's automatic root fallback. */
private fun clearWithoutNativeFocusFallback(view: View, clear: () -> Unit) {
    val root = view.rootView as? ViewGroup
    val field = root?.findFocus() as? EditText
    if (root == null || field == null) {
        clear()
        return
    }
    val start = field.selectionStart
    val end = field.selectionEnd
    val listener = field.onFocusChangeListener
    val focusable = root.focusable
    val touchFocusable = root.isFocusableInTouchMode
    val order = root.descendantFocusability
    var suppressed = false
    val wrapper = View.OnFocusChangeListener { target, focused ->
        listener?.onFocusChange(target, focused)
        if (!focused) {
            suppressed = true
            root.isFocusable = false
            root.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
    }
    field.onFocusChangeListener = wrapper
    try {
        clear()
    } finally {
        if (field.onFocusChangeListener === wrapper) field.onFocusChangeListener = listener
        if (suppressed) {
            root.focusable = focusable
            root.isFocusableInTouchMode = touchFocusable
            root.descendantFocusability = order
        }
        if (start >= 0 && end >= 0)
            field.setSelection(start.coerceAtMost(field.length()), end.coerceAtMost(field.length()))
    }
}

private class ComposeInputRegion {
    var focused = false
    var coordinates: LayoutCoordinates? = null

    fun bounds(view: View): RectF? {
        if (!view.isAttachedToWindow) return null
        val current = coordinates?.takeIf { it.isAttached } ?: return null
        val bounds = current.boundsInWindow()
        val screen = IntArray(2); val window = IntArray(2)
        view.getLocationOnScreen(screen); view.getLocationInWindow(window)
        return RectF(bounds.left + screen[0] - window[0], bounds.top + screen[1] - window[1],
            bounds.right + screen[0] - window[0], bounds.bottom + screen[1] - window[1])
    }
}

/** Native question/pet fields keep their existing text, selection and visual transformation. */
internal fun EditText.manageInput(window: Window? = null) {
    val field = this
    val entry = InputEntry(field, { field.hasFocus() }, {
        field.visibleInputBounds()
    }) {
        clearWithoutNativeFocusFallback(field) { field.clearFocus() }
    }
    bindInput(entry, window)
    val cursor = android.view.ViewTreeObserver.OnPreDrawListener {
        val visible = ViewCompat.getRootWindowInsets(field)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        if (field.isCursorVisible != visible) field.isCursorVisible = visible
        true
    }
    field.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) { field.viewTreeObserver.addOnPreDrawListener(cursor) }
        override fun onViewDetachedFromWindow(v: View) {
            if (field.viewTreeObserver.isAlive) field.viewTreeObserver.removeOnPreDrawListener(cursor)
        }
    })
    if (field.isAttachedToWindow) field.viewTreeObserver.addOnPreDrawListener(cursor)
}

private fun bindInput(entry: InputEntry, explicitWindow: Window? = null): () -> Unit {
    val view = entry.view
    var host: InputWindow? = null
    var disposed = false
    fun tryAttach(): Boolean {
        if (disposed || !view.isAttachedToWindow) return false
        if (host != null) return true
        val window = explicitWindow ?: view.inputWindow() ?: return false
        if (view.windowToken == null || window.decorView.windowToken != view.windowToken) return false
        host = InputWindows.acquire(window, entry)
        inputTrace("bound entries=${host?.entries?.size}")
        return true
    }
    // A child can attach before its window token is published. Observe natural
    // traversals until ready; never manufacture frames or rebind an acquired host.
    var pendingTree: ViewTreeObserver? = null
    lateinit var ready: ViewTreeObserver.OnPreDrawListener
    fun stopWaiting() {
        pendingTree?.takeIf { it.isAlive }?.removeOnPreDrawListener(ready)
        pendingTree = null
    }
    ready = ViewTreeObserver.OnPreDrawListener {
        if (tryAttach()) {
            stopWaiting()
            inputTrace("bound on traversal")
        }
        true
    }
    fun attach() {
        stopWaiting()
        if (!disposed && view.isAttachedToWindow && !tryAttach()) {
            pendingTree = view.viewTreeObserver.also { it.addOnPreDrawListener(ready) }
            inputTrace("binding pending attached=${view.isAttachedToWindow}")
        }
    }
    fun detach() {
        stopWaiting()
        host?.remove(entry); host = null
    }
    val listener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) { attach() }
        override fun onViewDetachedFromWindow(v: View) { detach() }
    }
    view.addOnAttachStateChangeListener(listener)
    if (view.isAttachedToWindow) attach()
    return {
        disposed = true
        view.removeOnAttachStateChangeListener(listener)
        detach()
    }
}

private object InputWindows {
    val windows = mutableMapOf<Window, InputWindow>()
    fun acquire(window: Window, entry: InputEntry): InputWindow {
        val host = windows.getOrPut(window) {
            InputWindow(window)
        }
        host.entries.add(entry)
        return host
    }
}

private class InputWindow(private val window: Window) {
    val entries = mutableSetOf<InputEntry>()
    private val decor = window.decorView as? ViewGroup
    private var clearing = 0
    private var restingFocusOrder: Int? = null
    private var restingFocusTree: ViewTreeObserver? = null
    private val focusTransfer = ViewTreeObserver.OnGlobalFocusChangeListener { _, next ->
        if (clearing == 0 && next != null && next !== decor && next.rootView === decor && decor?.findFocus() === next)
            releaseRestingFocus()
    }
    private fun holdRestingFocus() {
        val root = decor ?: return
        if (restingFocusOrder != null) return
        restingFocusOrder = root.descendantFocusability
        restingFocusTree = root.viewTreeObserver.also { it.addOnGlobalFocusChangeListener(focusTransfer) }
        root.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
    }
    private fun releaseRestingFocus() {
        val previous = restingFocusOrder ?: return
        restingFocusOrder = null
        restingFocusTree?.takeIf { it.isAlive }?.removeOnGlobalFocusChangeListener(focusTransfer)
        restingFocusTree = null
        decor?.let { root ->
            if (root.descendantFocusability == ViewGroup.FOCUS_BEFORE_DESCENDANTS)
                root.descendantFocusability = previous
        }
    }
    private val originalImeState = window.attributes.softInputMode and LayoutParams.SOFT_INPUT_MASK_STATE
    private var managedImeState = LayoutParams.SOFT_INPUT_STATE_UNCHANGED
    private var ownsImeState = true
    private var awaitingVisibleIme = false
    private var visibleImeFrame: Rect? = null
    private var visibleImeRotation = -1
    private val legacyImeTree = if (Build.VERSION.SDK_INT < 30) decor?.viewTreeObserver else null
    private val legacyImeLayout = ViewTreeObserver.OnGlobalLayoutListener { rememberLegacyImeState() }
    private val resumeImeObservation = Runnable {
        awaitingVisibleIme = false
        rememberLegacyImeState()
    }

    private fun rememberLegacyImeState() {
        val root = decor ?: return
        if (!root.hasWindowFocus() || root.windowVisibility != View.VISIBLE) return
        val owner = entries.firstOrNull()?.view?.findViewTreeLifecycleOwner()
            ?: (window.context.inputActivity() as? LifecycleOwner) ?: return
        if (owner.lifecycle.currentState != Lifecycle.State.RESUMED) return
        val insets = ViewCompat.getRootWindowInsets(root) ?: return
        val focused = entries.any { it.focused() }
        val visible = if (!focused) false else if (insets.isVisible(WindowInsetsCompat.Type.ime())) {
            if (window.isFloating) {
                visibleImeFrame = Rect().also { root.getWindowVisibleDisplayFrame(it) }
                visibleImeRotation = root.display?.rotation ?: -1
            }
            true
        } else if (!window.isFloating) false else {
            val shown = visibleImeFrame ?: return
            val frame = Rect().also { root.getWindowVisibleDisplayFrame(it) }
            if (root.display?.rotation != visibleImeRotation || frame.left != shown.left ||
                frame.right != shown.right || frame.top != shown.top || frame.bottom <= shown.bottom) return
            false
        }
        if (focused && awaitingVisibleIme && !visible) return
        awaitingVisibleIme = false
        val state = if (focused && visible) LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
            else LayoutParams.SOFT_INPUT_STATE_UNCHANGED
        val mode = window.attributes.softInputMode
        if (!ownsImeState) return
        if ((mode and LayoutParams.SOFT_INPUT_MASK_STATE) != managedImeState) {
            ownsImeState = false
            return
        }
        if (state == managedImeState) return
        managedImeState = state
        window.setSoftInputMode((mode and LayoutParams.SOFT_INPUT_MASK_STATE.inv()) or state)
    }
    private val original = window.callback
    private var candidate: InputEntry? = null
    private var nativeFocus: View? = null
    private var x = 0f
    private var y = 0f
    private var downTime = 0L
    private var tap = false
    private val slop = ViewConfiguration.get(window.context).scaledTouchSlop
    private val callback = object : Window.Callback by original {
        override fun onWindowFocusChanged(hasFocus: Boolean) {
            if (legacyImeTree != null) {
                decor?.removeCallbacks(resumeImeObservation)
                awaitingVisibleIme = hasFocus && managedImeState == LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                if (hasFocus) decor?.post(resumeImeObservation)
            }
            original.onWindowFocusChanged(hasFocus)
        }
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            val outside = entries.none { it.bounds()?.contains(event.rawX, event.rawY) == true }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    candidate = entries.firstOrNull { it.focused() }
                    nativeFocus = window.currentFocus
                    x = event.rawX; y = event.rawY; downTime = event.eventTime
                    tap = outside && candidate != null
                }
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> tap = false
            }
            if (event.pointerCount != 1 || hypot(event.rawX - x, event.rawY - y) > slop ||
                event.eventTime - downTime >= ViewConfiguration.getLongPressTimeout()) tap = false
            if (window.decorView.visibleInputBounds()?.contains(event.rawX, event.rawY) != true) tap = false
            if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_UP) {
                inputTrace("touch action=${event.actionMasked} outside=$outside focused=${entries.count { it.focused() }} tap=$tap elapsed=${event.eventTime - downTime} windowFocus=${window.decorView.hasWindowFocus()}")
            }
            val before = candidate
            val shouldDismiss = event.actionMasked == MotionEvent.ACTION_UP && tap && outside
            // Child clicks and focus changes complete first; return the original result unchanged.
            val previousInside = InputDismissal.insideInput
            InputDismissal.insideInput = !outside
            val handled = try { original.dispatchTouchEvent(event) }
                finally { InputDismissal.insideInput = previousInside }
            if (shouldDismiss && before != null && before in entries && before.focused() &&
                before.view.isAttachedToWindow && window.decorView.hasWindowFocus() &&
                window.currentFocus === nativeFocus) dismiss(before)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                candidate = null; nativeFocus = null; tap = false
            }
            return handled
        }
    }
    init {
        val mode = window.attributes.softInputMode
        if (originalImeState != LayoutParams.SOFT_INPUT_STATE_UNCHANGED) {
            window.setSoftInputMode((mode and LayoutParams.SOFT_INPUT_MASK_STATE.inv()) or
                LayoutParams.SOFT_INPUT_STATE_UNCHANGED)
        }
        window.callback = callback
        legacyImeTree?.addOnGlobalLayoutListener(legacyImeLayout)
    }
    private fun dismiss(entry: InputEntry) {
        if (!entry.focused() || entry !in entries) return
        dismiss(entry.view, entry.clear)
    }
    fun dismiss(view: View, clear: () -> Unit, hideIme: () -> Unit = { hideManagedInputIme(view) }): Boolean {
        val nativeBefore = window.currentFocus
        clearing++
        try {
            InputDismissal.run {
                if (nativeBefore !is EditText) {
                    holdRestingFocus()
                    decor?.let { root ->
                        root.isFocusableInTouchMode = true
                        root.requestFocus()
                    }
                }
                clear()
            }
            if (entries.none { it.focused() } &&
                !(window.currentFocus !== nativeBefore && window.currentFocus?.onCheckIsTextEditor() == true)) {
                holdRestingFocus()
                decor?.let { root ->
                    root.isFocusableInTouchMode = true
                    root.requestFocus()
                }
            }
        } finally {
            clearing--
            if (clearing == 0 && (decor?.isFocused != true || entries.any { it.focused() })) releaseRestingFocus()
        }
        inputTrace("cleared focused=${entries.count { it.focused() }} nativeSame=${window.currentFocus === nativeBefore}")
        // A focus listener may have explicitly moved editing to a different field/view.
        if (entries.any { it.focused() } ||
            (window.currentFocus !== nativeBefore && window.currentFocus?.onCheckIsTextEditor() == true)) return false
        hideIme()
        return true
    }
    fun remove(entry: InputEntry) {
        entries.remove(entry)
        if (candidate === entry) candidate = null
        if (entries.isEmpty()) {
            val mode = window.attributes.softInputMode
            decor?.removeCallbacks(resumeImeObservation)
            legacyImeTree?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(legacyImeLayout)
            if (ownsImeState && originalImeState != managedImeState &&
                (mode and LayoutParams.SOFT_INPUT_MASK_STATE) == managedImeState) {
                val restored = (mode and LayoutParams.SOFT_INPUT_MASK_STATE.inv()) or originalImeState
                window.setSoftInputMode(restored)
                // setSoftInputMode(0) restores defaults but leaves the previous attributes value.
                if (restored == 0) window.attributes = window.attributes.apply { softInputMode = restored }
            }
            releaseRestingFocus()
            if (window.callback === callback) window.callback = original
            InputWindows.windows.remove(window, this)
            nativeFocus = null; tap = false
        }
    }
}
