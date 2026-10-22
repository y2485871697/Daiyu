package app.daiyu.android.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.Window
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
    return context.inputActivity()?.window?.takeIf { it.decorView.windowToken == windowToken }
}

private fun View.visibleInputBounds(): RectF? {
    val bounds = android.graphics.Rect()
    if (!getGlobalVisibleRect(bounds)) return null
    val origin = IntArray(2)
    rootView.getLocationOnScreen(origin)
    return RectF(bounds).apply { offset(origin[0].toFloat(), origin[1].toFloat()) }
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
        InputEntry(view, { state.focused }, { state.bounds }) { focus.clearFocus(force = true) }
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
    }.onFocusChanged { state.focused = it.hasFocus }
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
    var host: InputWindow? = null
    fun attach() {
        if (host != null) return
        val window = explicitWindow ?: entry.view.inputWindow() ?: return
        host = InputWindows.acquire(window, entry)
    }
    val listener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) { attach() }
        override fun onViewDetachedFromWindow(v: View) { host?.remove(entry); host = null }
    }
    entry.view.addOnAttachStateChangeListener(listener)
    if (entry.view.isAttachedToWindow) attach()
    return {
        entry.view.removeOnAttachStateChangeListener(listener)
        host?.remove(entry); host = null
    }
}

private object InputWindows {
    val windows = mutableMapOf<Window, InputWindow>()
    fun acquire(window: Window, entry: InputEntry): InputWindow {
        val host = windows.getOrPut(window) { InputWindow(window) }
        host.entries.add(entry)
        return host
    }
}

private class InputWindow(private val window: Window) {
    val entries = mutableSetOf<InputEntry>()
    private val original = window.callback
    private val lifecycle = (window.context.inputActivity() as? LifecycleOwner)?.lifecycle
    private val observer = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_STOP) entries.firstOrNull { it.focused() }?.let { dismiss(it) }
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
        val nativeBefore = window.currentFocus
        InputDismissal.run { entry.clear() }
        // A focus listener may have explicitly moved editing to a different field/view.
        if (entries.any { it.focused() } ||
            (window.currentFocus !== nativeBefore && window.currentFocus?.onCheckIsTextEditor() == true)) return
        ViewCompat.getWindowInsetsController(entry.view)?.hide(WindowInsetsCompat.Type.ime())
        (entry.view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(entry.view.windowToken, 0)
    }
    fun remove(entry: InputEntry) {
        entries.remove(entry)
        if (candidate === entry) candidate = null
        if (entries.isEmpty()) {
            lifecycle?.removeObserver(observer)
            if (window.callback === callback) window.callback = original
            InputWindows.windows.remove(window, this)
            nativeFocus = null; tap = false
        }
    }
}
