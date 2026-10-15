package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.domain.Message
import app.daiyu.android.domain.MessageBlock
import app.daiyu.android.domain.RunStatus

/** No clock, Android APIs or mutable state. Message has no timestamp; the caller supplies one. */
object ChatTurnMapper {
    fun map(
        messages: List<Message>,
        timestamp: (Message) -> String = { "" },
    ): List<ChatTurnUi> = messages.mapNotNull { message ->
        when (message.role) {
            Message.USER_ROLE -> ChatTurnUi.User(
                id = message.id.value,
                text = message.blocks.filterIsInstance<MessageBlock.Text>().joinToString("\n\n") { it.text },
                timestamp = timestamp(message),
            )
            Message.ASSISTANT_ROLE -> mapAssistant(message, timestamp(message))
            else -> null
        }
    }

    private fun mapAssistant(message: Message, timestamp: String): ChatTurnUi.Assistant {
        val hasTools = message.blocks.any { it is MessageBlock.Tool }
        val lastText = message.blocks.indexOfLast { it is MessageBlock.Text }
        val finalText = if (hasTools) {
            (message.blocks.getOrNull(lastText) as? MessageBlock.Text)?.text.orEmpty()
        } else {
            message.blocks.filterIsInstance<MessageBlock.Text>().joinToString("\n\n") { it.text }
        }
        val segments = mutableListOf<WorkSegmentUi>()
        if (hasTools) {
            message.blocks.forEachIndexed { index, block ->
                when (block) {
                    is MessageBlock.Text -> if (index != lastText) {
                        segments += WorkSegmentUi(narration = block.text)
                    }
                    is MessageBlock.Tool -> {
                        val step = WorkStepUi(
                            id = block.invocation.id.value,
                            name = block.invocation.name,
                            detail = block.result?.takeIf { it.isNotBlank() } ?: block.invocation.arguments,
                            state = stepState(block.result, message.status),
                        )
                        // Keep even trailing tools while reserving the last text for the final answer.
                        if (segments.isEmpty()) segments += WorkSegmentUi()
                        val previous = segments.last()
                        segments[segments.lastIndex] = previous.copy(steps = previous.steps + step)
                    }
                }
            }
        }
        val running = message.status == RunStatus.RUNNING
        val summary = when {
            running -> "进行中"
            message.status == RunStatus.FAILED || segments.any { segment ->
                segment.steps.any { it.state == WorkStepState.FAILED }
            } -> "失败"
            message.status == RunStatus.STOPPED -> "已停止"
            message.status == RunStatus.INTERRUPTED -> "已中断"
            else -> "已完成"
        }
        return ChatTurnUi.Assistant(
            id = message.id.value,
            timestamp = timestamp,
            workProcess = if (hasTools) WorkProcessUi(segments, summary, running) else null,
            finalText = finalText,
            status = message.status,
            error = message.error,
        )
    }

    private fun stepState(result: String?, status: RunStatus): WorkStepState {
        if (result.isNullOrBlank()) {
            return if (status == RunStatus.RUNNING) WorkStepState.PENDING else WorkStepState.FAILED
        }
        val normalized = result.trimStart()
        return if (normalized.startsWith("error", ignoreCase = true) || normalized.startsWith("失败")) {
            WorkStepState.FAILED
        } else WorkStepState.OK
    }
}
