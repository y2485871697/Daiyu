package app.daiyu.android.ui.components

import android.app.Activity
import android.view.View
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import app.daiyu.android.ui.haptics.TouchHaptics
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.delay

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
    repeat(3) {
        withFrameNanos { }
        runCatching { requester?.requestFocus() }
        keyboard?.show()
        (view.context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).show(WindowInsetsCompat.Type.ime())
        }
        delay(48)
    }
}
