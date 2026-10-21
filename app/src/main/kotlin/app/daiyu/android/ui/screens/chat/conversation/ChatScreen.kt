package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
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
    val onSupplement: (String) -> Unit = {},
    val onTextChange: (String) -> Unit = {},
    val onSelectionChange: (Int, Int) -> Unit = { _, _ -> },
    val onProjectedDraftEdit: ((String, String, Int, Int) -> Boolean)? = null,
    val onControlPress: () -> Unit = {},
    val onControlRelease: () -> Unit = {},
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
    val onCopy: (String) -> Unit = {},
    val onRegenerate: (String) -> Unit = {},
    val onSpeak: (String) -> Unit = {},
    val onBranch: (String) -> Unit = {},
    val onEditUser: (String) -> Unit = {},
    val onDeleteTurn: (String) -> Unit = {},
    val onJumpPreviousUser: () -> Unit = {},
    val onRetryStep: (String) -> Unit = {},
    val onMenuAction: (ChatMenuAction) -> Unit = {},
    val onInteractiveModeChange: (Boolean) -> Unit = {},
    /** Measured visible completed turn keys after a frame, not merely selected/loaded history. */
    val onVisibleCompleted: (Set<String>) -> Unit = {},
)

@Composable
internal fun DaiyuChatScreen(
    state: ChatScreenState,
    actions: ChatScreenActions,
    modifier: Modifier = Modifier,
    turnExtras: @Composable (ChatTurnUi.Assistant) -> Unit = {},
    imageLoader: MessageImageLoader? = null,
    assistant: app.daiyu.android.ui.components.AssistantDisplay? = null,
    dedicatedTool: @Composable (WorkStepUi) -> Boolean = { false },
    toolContent: @Composable (WorkStepUi) -> Unit = {},
    composerAttachments: @Composable () -> Unit = {},
) {
    ChatMenuLayer(modifier.fillMaxSize()) {
    Column(
        Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)
            .statusBarsPadding().navigationBarsPadding().imePadding(),
    ) {
        ChatTopBar(state.title, state.balanceState, actions, assistantLabel = state.composer.assistantLabel, modelLabel = state.composer.modelLabel,
            interactiveModeEnabled = state.interactiveModeEnabled,
            interactiveModeChangeEnabled = state.interactiveModeChangeEnabled,
            automaticCompression = state.automaticCompression, automaticCompressionPending = state.automaticCompressionPending)
        state.statusText?.let { status ->
            // Operational errors/recovery/preparation are not money and never enter the amount slot.
            top.yukonga.miuix.kmp.basic.Text(status,
                modifier = Modifier.testTag("chat-status").fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.footnote1)
        }
        ChatConversationLayers(Modifier.weight(1f), messages = { bottomInset ->
            androidx.compose.runtime.key(state.conversationKey) {
                ChatMessageList(state.turns, state.canJumpToPreviousUser, actions, Modifier.fillMaxSize(),
                    state.scrollRequest, turnExtras, imageLoader, dedicatedTool, toolContent, bottomInset)
            }
        }, composer = { backdrop ->
            ChatComposer(state.composer, actions, assistant = assistant, balanceState = state.balanceState, backdrop = backdrop, attachmentContent = composerAttachments)
        })
    }
}
}
