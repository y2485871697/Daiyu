package app.daiyu.android.ui.components

import android.app.Activity
import android.view.View
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

internal val LocalChatInputFocusRequester = staticCompositionLocalOf<FocusRequester?> { null }

// Dynamic: only IME readers recompose when the drawer opens or closes, not the whole page.
internal val LocalConversationDrawerBlocksIme = compositionLocalOf { false }

internal fun hideChatInputIme(
    focusManager: FocusManager,
    keyboard: SoftwareKeyboardController?,
    view: View,
) {
    clearManagedInputFocus(view, hideIme = {
        if (keyboard != null) keyboard.hide() else hideManagedInputIme(view)
    }) { focusManager.clearFocus(force = true) }
}

internal suspend fun showChatInputIme(
    requester: FocusRequester?,
    keyboard: SoftwareKeyboardController?,
    view: View,
) {
    withFrameNanos { }
    if (!view.hasWindowFocus()) return
    requester?.requestFocus()
    withFrameNanos { }
    if (!view.hasWindowFocus()) return
    if (keyboard != null) keyboard.show()
    else (view.context as? Activity)?.window?.let { window ->
        WindowCompat.getInsetsController(window, view).show(WindowInsetsCompat.Type.ime())
    }
}

/** Observe a short tap without taking the child's click, drag or long-press gesture. */
@Composable
internal fun chatInputDismissTapModifier(onTap: () -> Unit): Modifier {
    val currentTap by rememberUpdatedState(onTap)
    return Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var tap = true
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id }
                if (event.changes.size != 1 || change == null) tap = false
                if (change != null) {
                    if (change.position.x !in 0f..size.width.toFloat() ||
                        change.position.y !in 0f..size.height.toFloat() ||
                        (change.position - down.position).getDistance() > viewConfiguration.touchSlop ||
                        change.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis) tap = false
                    if (!change.pressed) {
                        if (tap && change.previousPressed &&
                            change.position.x in 0f..size.width.toFloat() &&
                            change.position.y in 0f..size.height.toFloat()) currentTap()
                        break
                    }
                }
                if (event.changes.none { it.pressed }) break
            }
        }
    }
}
