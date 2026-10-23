package app.daiyu.android.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.view.Window
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
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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

/** Registration changes no drawing, sizing or pointer consumption, including in Dialog windows. */
internal fun Modifier.managedInput(): Modifier = composed {
    val view = LocalView.current
    val focus = LocalFocusManager.current
    val state = remember(view, focus) { ComposeInputRegion() }
    val entry = remember(view, focus) {
        InputEntry(view, { state.focused }, { state.bounds }) {
            // Keep Android's clearFocus fallback out of the Compose editor tree.
            val root = view.rootView
            root.isFocusableInTouchMode = true
            root.requestFocus()
            inputTrace("dismiss root=${root.javaClass.simpleName} focused=${root.isFocused}")
            if (state.focused) focus.clearFocus(force = true)
        }
    }
    DisposableEffect(entry) {
        val detach = bindInput(entry)
        onDispose { detach() }
    }
    this.onGloballyPositioned { coordinates ->
        if (coordinates.isAttached) {
            val bounds = coordinates.boundsInWindow()
            val screen = IntArray(2); val window = IntArray(2)
            view.getLocationOnScreen(screen); view.getLocationInWindow(window)
            state.bounds = RectF(bounds.left + screen[0] - window[0], bounds.top + screen[1] - window[1],
                bounds.right + screen[0] - window[0], bounds.bottom + screen[1] - window[1])
        } else state.bounds = null
    }.onFocusChanged {
        if (state.focused != it.hasFocus) inputTrace("compose focus=${it.hasFocus} attached=${view.isAttachedToWindow}")
        state.focused = it.hasFocus
    }
}

internal fun clearManagedInputFocus(view: View, clear: () -> Unit) {
    val action = {
        val root = view.rootView
        root.isFocusableInTouchMode = true
        root.requestFocus()
        clear()
    }
    val host = view.inputWindow()?.let { InputWindows.windows[it] }
    if (host != null) host.dismiss(view, action) else InputDismissal.run(action)
}

private class ComposeInputRegion {
    var focused = false
    var bounds: RectF? = null
}

/** Native question/pet fields keep their existing text, selection and visual transformation. */
internal fun EditText.manageInput(window: Window? = null) {
    val field = this
    val entry = InputEntry(field, { field.hasFocus() }, {
        field.visibleInputBounds()
    }) {
        val start = field.selectionStart; val end = field.selectionEnd
        field.rootView.isFocusableInTouchMode = true
        field.rootView.requestFocus()
        field.clearFocus()
        if (start >= 0 && end >= 0) field.setSelection(start.coerceAtMost(field.length()), end.coerceAtMost(field.length()))
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
            val lifecycle = entry.view.findViewTreeLifecycleOwner()?.lifecycle
                ?: (window.context.inputActivity() as? LifecycleOwner)?.lifecycle
            inputTrace("new window lifecycle=${lifecycle?.currentState}")
            InputWindow(window, lifecycle)
        }
        host.entries.add(entry)
        return host
    }
}

private class InputWindow(private val window: Window, private val lifecycle: Lifecycle?) {
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
    private val original = window.callback
    private val observer = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_STOP) {
            inputTrace("stop entries=${entries.size} focused=${entries.count { it.focused() }}")
            entries.firstOrNull { it.focused() }?.let { dismiss(it) }
        }
    }
    private var candidate: InputEntry? = null
    private var nativeFocus: View? = null
    private var x = 0f
    private var y = 0f
    private var downTime = 0L
    private var tap = false
    private val slop = ViewConfiguration.get(window.context).scaledTouchSlop
    private val callback = object : Window.Callback by original {
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
        window.callback = callback
        lifecycle?.addObserver(observer)
    }
    private fun dismiss(entry: InputEntry) {
        if (!entry.focused() || entry !in entries) return
        dismiss(entry.view, entry.clear)
    }
    fun dismiss(view: View, clear: () -> Unit) {
        val nativeBefore = window.currentFocus
        holdRestingFocus()
        clearing++
        try {
            InputDismissal.run { clear() }
        } finally {
            clearing--
            if (clearing == 0 && (decor?.isFocused != true || entries.any { it.focused() })) releaseRestingFocus()
        }
        inputTrace("cleared focused=${entries.count { it.focused() }} nativeSame=${window.currentFocus === nativeBefore}")
        // A focus listener may have explicitly moved editing to a different field/view.
        if (entries.any { it.focused() } ||
            (window.currentFocus !== nativeBefore && window.currentFocus?.onCheckIsTextEditor() == true)) return
        ViewCompat.getWindowInsetsController(view)?.hide(WindowInsetsCompat.Type.ime())
        (view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(view.windowToken, 0)
    }
    fun remove(entry: InputEntry) {
        entries.remove(entry)
        if (candidate === entry) candidate = null
        if (entries.isEmpty()) {
            releaseRestingFocus()
            lifecycle?.removeObserver(observer)
            if (window.callback === callback) window.callback = original
            InputWindows.windows.remove(window, this)
            nativeFocus = null; tap = false
        }
    }
}
