package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.domain.RunStatus

/** Presentation-only state: timestamps are already formatted by the caller. */
data class ChatScreenState(
    val title: String,
    val balanceText: String? = null,
    val turns: List<ChatTurnUi> = emptyList(),
    val composer: ComposerUi = ComposerUi(),
    val canJumpToPreviousUser: Boolean = false,
)

sealed interface ChatTurnUi {
    val id: String
    val timestamp: String

    data class User(
        override val id: String,
        val text: String,
        override val timestamp: String = "",
    ) : ChatTurnUi

    data class Assistant(
        override val id: String,
        override val timestamp: String = "",
        val workProcess: WorkProcessUi? = null,
        val finalText: String = "",
        val status: RunStatus = RunStatus.COMPLETED,
        val error: String? = null,
    ) : ChatTurnUi
}

data class WorkProcessUi(
    val segments: List<WorkSegmentUi>,
    val summary: String,
    val running: Boolean = false,
)

data class WorkSegmentUi(val narration: String? = null, val steps: List<WorkStepUi> = emptyList())

data class WorkStepUi(
    val id: String,
    val name: String,
    val detail: String,
    val state: WorkStepState = WorkStepState.PENDING,
)

enum class WorkStepState { PENDING, OK, FAILED }
enum class ComposerPhase { IDLE, RUNNING, PAUSED }

data class ComposerUi(
    val text: String = "",
    val phase: ComposerPhase = ComposerPhase.IDLE,
    val modelLabel: String = "模型",
    val reasoningLabel: String = "推理强度",
    val assistantLabel: String = "助手",
    val contextFraction: Float? = null,
    val contextLabel: String = "上下文",
    val listening: Boolean = false,
    val enabled: Boolean = true,
)
