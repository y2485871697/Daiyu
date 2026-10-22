package app.daiyu.android.ui.components

import android.app.Activity
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

internal val LocalChatInputFocusRequester = staticCompositionLocalOf<FocusRequester?> { null }

// Dynamic: only IME readers recompose when the drawer opens or closes, not the whole page.
internal val LocalConversationDrawerBlocksIme = compositionLocalOf { false }

internal fun hideChatInputIme(
    focusManager: FocusManager,
    keyboard: SoftwareKeyboardController?,
    view: View,
) {
    focusManager.clearFocus(force = true)
    keyboard?.hide()
    (view.context as? Activity)?.window?.let { window ->
        WindowCompat.getInsetsController(window, view).hide(WindowInsetsCompat.Type.ime())
    }
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

/** Returning to the app must not restore an old editing session's keyboard. */
@Composable
internal fun DismissImeOnStop() {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    DisposableEffect(lifecycle, focus, keyboard, view) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) hideChatInputIme(focus, keyboard, view)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}
