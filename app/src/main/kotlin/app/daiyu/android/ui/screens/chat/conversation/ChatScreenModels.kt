package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.domain.RunStatus

/** Presentation-only state: timestamps are already formatted by the caller. */
data class ChatScreenState(
    val title: String,
    val balanceState: app.daiyu.android.balance.BalanceState? = null,
    val statusText: String? = null,
    val turns: List<ChatTurnUi> = emptyList(),
    val composer: ComposerUi = ComposerUi(),
    val conversationKey: String? = null,
    val scrollRequest: ChatScrollRequest? = null,
    val contentRevision: Long = 0L,
    val interactiveModeEnabled: Boolean = false,
    val interactiveModeChangeEnabled: Boolean = true,
    val automaticCompression: Boolean? = null,
    val automaticCompressionPending: Boolean = false,
)

/** A new serial consumes a host navigation request, never a model execution state. */
data class ChatScrollRequest(
    val serial: Long,
    val messageId: String? = null,
    val followBottom: Boolean = true,
    /** Exact occurrence of messageId. Null retains legacy first-message navigation. */
    val segmentIndex: Int? = null,
    /** Optional exact projected key; never substitute a database owner id for messageId. */
    val listKey: String? = null,
)

/** A missing exact target stays pending; it must never fall back to the owner's first segment. */
internal fun ChatScrollRequest.targetIndex(turns: List<ChatTurnUi>): Int = when {
    listKey != null -> turns.indexOfFirst { it.listKey == listKey }
    messageId != null -> turns.indexOfFirst {
        it.id == messageId && (segmentIndex == null || it.segmentIndex == segmentIndex)
    }
    else -> -1
}

sealed interface ChatTurnUi {
    val id: String
    val timestamp: String
    /** Separate from the action/message id: an owner may have several history segments. */
    val listKey: String get() = id
    /** Database window identity only; actions and navigation still use id. */
    val ownerMessageId: String get() = id
    val segmentIndex: Int get() = 0

    data class User(
        override val id: String,
        val text: String,
        override val timestamp: String = "",
        val content: List<MessageContentUi> = emptyList(),
        override val listKey: String = id,
        override val ownerMessageId: String = id,
        override val segmentIndex: Int = 0,
    ) : ChatTurnUi

    data class Assistant(
        override val id: String,
        override val timestamp: String = "",
        val workProcess: WorkProcessUi? = null,
        val finalText: String = "",
        val status: RunStatus = RunStatus.COMPLETED,
        val error: String? = null,
        val content: List<MessageContentUi> = emptyList(),
        override val listKey: String = id,
        override val ownerMessageId: String = id,
        override val segmentIndex: Int = 0,
        val reportedReasoning: List<app.daiyu.android.domain.ReportedReasoning> = emptyList(),
        val assistantIdentity: app.daiyu.android.assistants.AssistantReplyIdentity? = null,
        val isStreaming: Boolean = false,
    ) : ChatTurnUi
}

data class WorkProcessUi(
    val segments: List<WorkSegmentUi>,
    val summary: String,
    val running: Boolean = false,
)

data class WorkSegmentUi(val narration: String? = null, val steps: List<WorkStepUi> = emptyList())

data class WorkStepOwnerUi(val conversationId: String, val runId: String, val messageId: String)
data class ToolDetailSectionUi(val title: String, val text: String)

data class WorkStepUi(
    val id: String,
    val name: String,
    val detail: String,
    val state: WorkStepState = WorkStepState.PENDING,
    val owner: WorkStepOwnerUi? = null,
    val toolName: String = name,
    val category: String = "generic",
    val sections: List<ToolDetailSectionUi> = emptyList(),
    /** Host-validated dedicated projection; never grants execution permission. */
    val dedicatedKey: String? = null,
    val hasBrowserScreenshot: Boolean = false,
    val categoryDetails: ToolCategoryDetailsUi? = null,
    val activity: ToolActivityUi = ToolActivityUi.from(state),
    val operationGlyph: String = "generic",
) {
    val toolCallId: String get() = id
}

enum class WorkStepState { PENDING, OK, FAILED, UNKNOWN, NOT_EXECUTED }
enum class ComposerPhase { IDLE, RUNNING, PAUSED }

/** Null means no controllable owner. PAUSED is a UI projection of pauseRequested, never RunStatus. */
internal fun composerPhaseForRun(pauseRequested: Boolean?): ComposerPhase = when (pauseRequested) {
    null -> ComposerPhase.IDLE
    true -> ComposerPhase.PAUSED
    false -> ComposerPhase.RUNNING
}

data class ComposerUi(
    val text: String = "",
    val selectionStart: Int = text.length,
    val selectionEnd: Int = text.length,
    val phase: ComposerPhase = ComposerPhase.IDLE,
    val modelLabel: String = "模型",
    val reasoningLabel: String = "默认",
    val assistantLabel: String = "助手",
    val contextFraction: Float? = null,
    val contextLabel: String = "上下文",
    val contextUsageText: String? = null,
    val contextTokensText: String? = null,
    val listening: Boolean = false,
    val enabled: Boolean = true,
    val controlOwnerKey: String? = null,
    val supplementAvailable: Boolean = false,
    val supplementPending: Boolean = false,
    /** Ready owned images only; never file/folder/path strings or video approval. Idle only. */
    val imageSendAvailable: Boolean = false,
    /** A fenced/importing image draft must not silently fall through to text send. Editing is separate. */
    val imageSendBlocked: Boolean = false,
    val videoSendAvailable: Boolean = false,
    val videoSendBlocked: Boolean = false,
    /** Actual runtime evidence, independent of pause-request/control-button projection. */
    val executionPhase: app.daiyu.android.domain.runtime.ExecutionPhase? = null,
    /** Null means the current owner's output is not available; never guess from RUNNING. */
    val hasAssistantOutput: Boolean? = null,
    val morphLoadingIndicator: Boolean = true,
    val morphLoadingBeforeResponseOnly: Boolean = false,
    val editorGeneration: Long = 0,
    val voiceMode: ChatMenuAction = ChatMenuAction.VOICE_INPUT,
    val voiceModes: Set<ChatMenuAction> = setOf(ChatMenuAction.VOICE_INPUT, ChatMenuAction.VOICE_CHAT, ChatMenuAction.VOICE_DOUBAO),
)

internal fun ComposerUi.textActionEnabled(): Boolean = enabled && !imageSendBlocked && !videoSendBlocked && !supplementPending && when (phase) {
    ComposerPhase.IDLE -> text.isNotBlank() || imageSendAvailable || videoSendAvailable
    ComposerPhase.PAUSED -> text.isNotBlank() && supplementAvailable
    ComposerPhase.RUNNING -> false
}
