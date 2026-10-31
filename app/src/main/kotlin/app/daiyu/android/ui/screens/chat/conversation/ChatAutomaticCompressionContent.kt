package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable

@Immutable
internal data class ChatAutomaticCompressionState(val enabled: Boolean?, val pending: Boolean)

/**
 * Read menu-only configuration in a restartable leaf, not in the page that owns the
 * captured chat/list. Opening More refreshes this state while the list can still fling.
 */
@Composable
internal fun ChatAutomaticCompressionContent(
    readState: () -> ChatAutomaticCompressionState,
    content: @Composable (ChatAutomaticCompressionState) -> Unit,
) {
    content(readState())
}
