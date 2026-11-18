package app.daiyu.android.ui.screens.chat.conversation

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.unit.Density
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.imeAnimationSource
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import app.daiyu.android.ui.components.chatInputDismissTapModifier
import app.daiyu.android.ui.components.hideChatInputIme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.Modifier
import top.yukonga.miuix.kmp.theme.MiuixTheme

private class NativeChatStatusBarInsets(
    private val view: View,
    private val source: WindowInsets,
) : WindowInsets by source {
    override fun getTop(density: Density): Int {
        // Keep layout invalidation, but bypass Compat's animated synthetic inset objects.
        val composeTop = source.getTop(density)
        return ViewCompat.getRootWindowInsets(view)
            ?.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars())?.top ?: composeTop
    }
}

private class ChatCallAnchorCoordinates {
    var host: LayoutCoordinates? = null
    var composer: LayoutCoordinates? = null
    var voice: LayoutCoordinates? = null

    fun read(): DoubaoCallAnchor? {
        val host = host?.takeIf { it.isAttached && it.size.width > 0 } ?: return null
        val composer = composer?.takeIf { it.isAttached && it.size.height > 0 } ?: return null
        val voice = voice?.takeIf { it.isAttached && it.size.width > 0 } ?: return null
        fun bounds(coordinates: LayoutCoordinates): Rect {
            val topLeft = coordinates.positionOnScreen()
            return Rect(topLeft.x, topLeft.y, topLeft.x + coordinates.size.width,
                topLeft.y + coordinates.size.height)
        }
        return DoubaoCallAnchor(bounds(voice), composer.positionOnScreen().y, bounds(host), composerBounds = bounds(composer))
    }
}

/** 聊天界面的操作回调，由宿主执行对应业务。 */
data class ChatScreenActions(
    val onOpenSidebar: () -> Unit = {},
    val onMore: () -> Unit = {},
    val onCompressionSendBlocked: () -> Unit = {},
    val onSend: (String) -> Unit = {},
    val onSupplement: (String) -> Unit = {},
    val onTextChange: (String) -> Unit = {},
    val onSelectionChange: (Int, Int) -> Unit = { _, _ -> },
    val onReferenceShortcut: (ComposerShortcut, String) -> Unit = { _, _ -> },
    val onProjectedDraftEdit: ((String, String, Int, Int) -> Boolean)? = null,
    val onControlPress: () -> Unit = {},
    val onControlRelease: () -> Unit = {},
    val onPrimaryTap: () -> Unit = {},
    val onCancelSend: () -> Unit = {},
    val onPrimaryLongPress: () -> Unit = {},
    val onAttach: () -> Unit = {},
    val onVoice: () -> Unit = {},
    val onVoiceLongPress: () -> Boolean = { true },
    val onModelClick: () -> Unit = {},
    val onReasoningClick: () -> Unit = {},
    val onAssistantClick: () -> Unit = {},
    val onOpenReasoning: () -> Unit = onReasoningClick,
    val onCycleGptSpeed: () -> Unit = {},
    val onOpenAssistant: () -> Unit = onAssistantClick,
    val messageActionsEnabled: Boolean = true,
    val messageBranchEnabled: Boolean = true,
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

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
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
    callUi: DoubaoCallUi? = null,
    callActions: DoubaoCallActions = DoubaoCallActions(),
    callEntryMotion: DoubaoCallMotionState? = null,
    onCallAnchorChanged: (DoubaoCallAnchor) -> Unit = {},
    readAutomaticCompression: (() -> ChatAutomaticCompressionState)? = null,
    speechPreparingMessageId: String? = null,
    menuAvailable: Boolean = true,
    onPresentationReady: (Boolean) -> Unit = {},
    hasTurnExtras: (ChatTurnUi.Assistant) -> Boolean = { true },
    suppliedUserMessageEntrance: UserMessageEntranceMotion? = null,
) {
    val presentationActive = LocalChatPresentationActive.current &&
        app.daiyu.android.ui.navigation.LocalPageContentActive.current
    val suppliedActions = actions
    val actions = if (presentationActive) suppliedActions else ChatScreenActions(
        editingMessageKey = suppliedActions.editingMessageKey,
        messageActionsEnabled = suppliedActions.messageActionsEnabled,
        messageBranchEnabled = suppliedActions.messageBranchEnabled,
    )
    val suppliedCallActions = callActions
    val callActions = if (presentationActive) suppliedCallActions else DoubaoCallActions()
    val drawerBlocksIme = app.daiyu.android.ui.components.LocalConversationDrawerBlocksIme.current
    val conversationInsets = if (drawerBlocksIme) WindowInsets.navigationBars
        else WindowInsets.navigationBars.union(WindowInsets.ime)
    val motionState = remember(callUi?.sessionKey) { DoubaoCallMotionState() }
    var voiceAnchor by remember(state.conversationKey) { mutableStateOf(Rect.Zero) }
    var composerBounds by remember(state.conversationKey) { mutableStateOf(Rect.Zero) }
    var overlayOrigin by remember { mutableStateOf(Offset.Zero) }
    val callCoordinates = remember(state.conversationKey) { ChatCallAnchorCoordinates() }
    val latestCallAnchorChanged by rememberUpdatedState(onCallAnchorChanged)
    val displayedTurns = submissionTurns(state.turns, state.submissionPreview)
    val userMessageEntrance = suppliedUserMessageEntrance ?:
        rememberUserMessageEntrance(state.userMessageEntrance, presentationActive, awaitPlacement = true)
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    val density = LocalDensity.current
    val ime = WindowInsets.ime
    val imeSource = WindowInsets.imeAnimationSource
    val imeTarget = WindowInsets.imeAnimationTarget
    val navigationBars = WindowInsets.navigationBars
    val latestCallIme by rememberUpdatedState {
        fun effective(insets: WindowInsets): Int = if (drawerBlocksIme) 0 else
            (insets.getBottom(density) - navigationBars.getBottom(density)).coerceAtLeast(0)
        DoubaoCallImeInsets(effective(ime), effective(imeSource), effective(imeTarget))
    }
    DisposableEffect(view, presentationActive, callCoordinates) {
        var reported: DoubaoCallAnchor? = null
        val observer = view.viewTreeObserver
        val listener = android.view.ViewTreeObserver.OnPreDrawListener {
            if (presentationActive) callCoordinates.read()?.copy(imeInsets = latestCallIme())?.let { anchor ->
                if (anchor != reported) {
                    reported = anchor
                    latestCallAnchorChanged(anchor)
                }
            }
            true
        }
        observer.addOnPreDrawListener(listener)
        onDispose {
            if (observer.isAlive) observer.removeOnPreDrawListener(listener)
            else view.viewTreeObserver.removeOnPreDrawListener(listener)
        }
    }
    val statusBars = WindowInsets.statusBarsIgnoringVisibility
    val stableStatusBars = remember(view, statusBars) { NativeChatStatusBarInsets(view, statusBars) }
    var draftFocused by remember { mutableStateOf(false) }
    var inputDismissInProgress by remember { mutableStateOf(false) }
    val dismissInputOnTap = chatInputDismissTapModifier {
        if (presentationActive && draftFocused && !app.daiyu.android.ui.components.InputDismissal.insideInput) {
            inputDismissInProgress = true
            try {
                hideChatInputIme(focus, keyboard, view)
            } finally {
                inputDismissInProgress = false
            }
        }
    }
    ChatMenuLayer(modifier.fillMaxSize()) {
    Box(Modifier.fillMaxSize().onGloballyPositioned {
        overlayOrigin = it.positionInRoot()
        callCoordinates.host = it
    }) {
    Column(
        Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)
            .windowInsetsPadding(stableStatusBars),
    ) {
        ChatTopBar(state.title, actions, modifier = dismissInputOnTap, assistantLabel = state.composer.assistantLabel, modelLabel = state.composer.modelLabel,
            interactiveModeEnabled = state.interactiveModeEnabled,
            interactiveModeChangeEnabled = state.interactiveModeChangeEnabled,
            automaticCompression = state.automaticCompression, automaticCompressionPending = state.automaticCompressionPending,
            readAutomaticCompression = readAutomaticCompression, menuAvailable = menuAvailable)
        state.statusText?.let { status ->
            // 错误、恢复和准备进度使用独立状态行，与金额展示分开。
            top.yukonga.miuix.kmp.basic.Text(status,
                modifier = dismissInputOnTap.testTag("chat-status").fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.footnote1)
        }
        ChatConversationLayers(Modifier.weight(1f)
            .windowInsetsPadding(conversationInsets), messages = { bottomInset ->
            androidx.compose.runtime.key(state.conversationKey) {
                ChatMessageList(displayedTurns, actions, Modifier.fillMaxSize().then(dismissInputOnTap),
                    state.scrollRequest, turnExtras, imageLoader, dedicatedTool, toolContent, bottomInset, state.timeline, state.compressionActivity,
                    speechMessageId = speechMessageId, speechPreparingMessageId = speechPreparingMessageId, compressionActivities = state.compressionActivities,
                    onPresentationReady = onPresentationReady, historyReady = state.historyReady,
                    retainUnconfirmedRows = !state.historyReady, userMessageEntrance = userMessageEntrance,
                    hasTurnExtras = hasTurnExtras)
            }
        }, composer = { backdrop ->
            ChatComposer(state.composer, actions, assistant = assistant, balanceState = state.balanceState, backdrop = backdrop,
                modifier = Modifier.onGloballyPositioned {
                    composerBounds = it.boundsInRoot()
                    callCoordinates.composer = it
                },
                onVoiceAnchorBounds = { voiceAnchor = it },
                onVoiceAnchorCoordinates = { callCoordinates.voice = it }, callUi = callUi, callMotion = motionState.takeIf { callUi != null }, callEntryMotion = callEntryMotion,
                onInputFocusChanged = { focused ->
                    draftFocused = focused
                    if (presentationActive && focused && callUi?.presentation == DoubaoCallPresentation.EXPANDED) callActions.onMinimize()
                }, isInputDismissInProgress = { inputDismissInProgress },
                attachmentContent = composerAttachments)
        })
    }
    if (callUi != null && voiceAnchor.width > 0f) {
        DoubaoCallOverlay(callUi, callActions,
            anchorBounds = voiceAnchor.translate(-overlayOrigin),
            composerTop = composerBounds.takeIf { it.height > 0f }?.top?.minus(overlayOrigin.y),
            motion = motionState)
    }
    }
}
}
