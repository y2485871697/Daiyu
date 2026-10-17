package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.domain.Message
import app.daiyu.android.domain.MessageBlock
import app.daiyu.android.domain.runtime.ExecutionPhase

/** Inspect only the active assistant owner supplied by the host, never the whole chat history. */
internal fun assistantOutputExists(message: Message): Boolean {
    fun List<MessageBlock>.hasOutput() = any {
        when (it) {
            is MessageBlock.Text -> it.text.isNotBlank()
            is MessageBlock.Tool, is MessageBlock.Image -> true
        }
    }
    if (message.role != Message.ASSISTANT_ROLE) return false
    return message.blocks.hasOutput() || message.historySegments.any {
        it.role == Message.ASSISTANT_ROLE && it.blocks.hasOutput()
    }
}

/**
 * RUNNING is an action-button state, not proof that a response has not started. Unknown
 * runtime/output evidence is deliberately not rendered as pre-response loading. Listening
 * animation is independent and remains owned by SpeechMorphButton.
 */
internal fun ComposerUi.showGenerationLoading(): Boolean {
    if (!morphLoadingIndicator || phase != ComposerPhase.RUNNING) return false
    val active = when (executionPhase) {
        ExecutionPhase.IDLE, ExecutionPhase.BOUNDARY, ExecutionPhase.STREAMING,
        ExecutionPhase.RECONNECTING, ExecutionPhase.TOOL_EXECUTING -> true
        ExecutionPhase.PAUSED, ExecutionPhase.WAITING_USER, ExecutionPhase.TERMINAL, null -> false
    }
    return active && (!morphLoadingBeforeResponseOnly || hasAssistantOutput == false)
}
