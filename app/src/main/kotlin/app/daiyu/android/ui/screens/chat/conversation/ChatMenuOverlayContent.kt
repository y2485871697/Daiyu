package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal class ChatMenuLayerState<T : Any> {
    var active by mutableStateOf<T?>(null)
}

/** Keep menu ownership reads below the captured page's restart scope. */
@Composable
internal fun <T : Any> ChatMenuOverlayContent(
    state: ChatMenuLayerState<T>,
    content: @Composable (T) -> Unit,
) {
    state.active?.let { menu ->
        key(menu) { content(menu) }
    }
}
