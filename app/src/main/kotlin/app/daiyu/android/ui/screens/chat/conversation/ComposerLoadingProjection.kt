package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.domain.runtime.ExecutionPhase

/** Generation animation follows the active runtime, including after response output starts. */
internal fun ComposerUi.showGenerationLoading(): Boolean {
    if (phase != ComposerPhase.RUNNING) return false
    return when (executionPhase) {
        ExecutionPhase.IDLE, ExecutionPhase.BOUNDARY, ExecutionPhase.STREAMING,
        ExecutionPhase.RECONNECTING, ExecutionPhase.TOOL_EXECUTING -> true
        ExecutionPhase.PAUSED, ExecutionPhase.WAITING_USER, ExecutionPhase.TERMINAL, null -> false
    }
}
