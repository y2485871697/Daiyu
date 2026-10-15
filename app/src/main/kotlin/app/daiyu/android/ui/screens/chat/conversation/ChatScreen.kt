package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Host owns every business operation; this screen never accesses a controller or repository. */
data class ChatScreenActions(
    val onOpenSidebar: () -> Unit = {},
    val onMore: () -> Unit = {},
    val onSend: (String) -> Unit = {},
    val onTextChange: (String) -> Unit = {},
    val onPrimaryTap: () -> Unit = {},
    val onPrimaryLongPress: () -> Unit = {},
    val onAttach: () -> Unit = {},
    val onVoice: () -> Unit = {},
    val onVoiceLongPress: () -> Unit = {},
    val onModelClick: () -> Unit = {},
    val onReasoningClick: () -> Unit = {},
    val onAssistantClick: () -> Unit = {},
    val onOpenReasoning: () -> Unit = onReasoningClick,
    val onOpenAssistant: () -> Unit = onAssistantClick,
    val onContextClick: () -> Unit = {},
    val onCopy: (String) -> Unit = {},
    val onRegenerate: (String) -> Unit = {},
    val onSpeak: (String) -> Unit = {},
    val onBranch: (String) -> Unit = {},
    val onEditUser: (String) -> Unit = {},
    val onDeleteTurn: (String) -> Unit = {},
    val onJumpPreviousUser: () -> Unit = {},
    val onRetryStep: (String) -> Unit = {},
    val onMenuAction: (ChatMenuAction) -> Unit = {},
)

@Composable
internal fun DaiyuChatScreen(
    state: ChatScreenState,
    actions: ChatScreenActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)
            .statusBarsPadding().navigationBarsPadding().imePadding(),
    ) {
        ChatTopBar(state.title, state.balanceText, actions)
        ChatMessageList(state.turns, state.canJumpToPreviousUser, actions, Modifier.weight(1f))
        ChatComposer(state.composer, actions)
    }
}
