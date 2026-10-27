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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import app.daiyu.android.ui.components.chatInputDismissTapModifier
import app.daiyu.android.ui.components.hideChatInputIme
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
    val onReferenceShortcut: (ComposerShortcut, String) -> Unit = { _, _ -> },
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
    val onCycleGptSpeed: () -> Unit = {},
    val onOpenAssistant: () -> Unit = onAssistantClick,
    val onCopy: (String) -> Unit = {},
    val onRegenerate: (String) -> Unit = {},
    val onSpeak: (String) -> Unit = {},
    val onBranch: (String) -> Unit = {},
    val onEditUser: (String) -> Unit = {},
    val editingMessageKey: String? = null,
    val onCancelMessageEdit: () -> Unit = {},
    val onDeleteTurn: (String) -> Unit = {},
    val onRetryStep: (String) -> Unit = {},
    val onMenuAction: (ChatMenuAction) -> Unit = {},
    val onInteractiveModeChange: (Boolean) -> Unit = {},
    /** Measured visible completed turn keys after a frame, not merely selected/loaded history. */
    val onVisibleCompleted: (Set<String>) -> Unit = {},
    val onOpenWorkspace: () -> Unit = {},
    /** Actual typewriter growth in a visible row, never a network chunk or recomposition. */
    val onVisibleTextAdvance: (String) -> Unit = {},
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
    speechMessageId: String? = null,
) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    var draftFocused by remember { mutableStateOf(false) }
    var inputDismissInProgress by remember { mutableStateOf(false) }
    val dismissInputOnTap = chatInputDismissTapModifier {
        if (draftFocused && !app.daiyu.android.ui.components.InputDismissal.insideInput) {
            inputDismissInProgress = true
            try {
                hideChatInputIme(focus, keyboard, view)
            } finally {
                inputDismissInProgress = false
            }
        }
    }
    ChatMenuLayer(modifier.fillMaxSize()) {
    Column(
        Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        ChatTopBar(state.title, actions, modifier = dismissInputOnTap, assistantLabel = state.composer.assistantLabel, modelLabel = state.composer.modelLabel,
            interactiveModeEnabled = state.interactiveModeEnabled,
            interactiveModeChangeEnabled = state.interactiveModeChangeEnabled,
            automaticCompression = state.automaticCompression, automaticCompressionPending = state.automaticCompressionPending)
        state.statusText?.let { status ->
            // Operational errors/recovery/preparation are not money and never enter the amount slot.
            top.yukonga.miuix.kmp.basic.Text(status,
                modifier = dismissInputOnTap.testTag("chat-status").fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.footnote1)
        }
        ChatConversationLayers(Modifier.weight(1f)
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)), messages = { bottomInset ->
            androidx.compose.runtime.key(state.conversationKey) {
                ChatMessageList(state.turns, actions, Modifier.fillMaxSize().then(dismissInputOnTap),
                    state.scrollRequest, turnExtras, imageLoader, dedicatedTool, toolContent, bottomInset, state.timeline, state.compressionActivity,
                    speechMessageId = speechMessageId, compressionActivities = state.compressionActivities)
            }
        }, composer = { backdrop ->
            ChatComposer(state.composer, actions, assistant = assistant, balanceState = state.balanceState, backdrop = backdrop,
                onInputFocusChanged = { draftFocused = it }, isInputDismissInProgress = { inputDismissInProgress },
                attachmentContent = composerAttachments)
        })
    }
}
}
