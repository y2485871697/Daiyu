package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.domain.Message
import app.daiyu.android.domain.MessageBlock
import app.daiyu.android.domain.MessageHistory
import app.daiyu.android.domain.MessageImageOwner
import app.daiyu.android.domain.RunStatus

/** No clock, Android APIs or mutable state. Timestamp formatting belongs to the caller. */
object ChatTurnMapper {
    fun map(
        messages: List<Message>,
        confirmedToolError: (Message, MessageBlock.Tool) -> Boolean? = { _, _ -> null },
        timestamp: (Message) -> String = { "" },
    ): List<ChatTurnUi> {
        // The host can pass either persisted owners or already-expanded history. Expansion
        // retains the segment's message id (especially supplemental user image ownership).
        val history = if (messages.any { it.historySegments.isNotEmpty() }) MessageHistory.expand(messages) else messages
        val databaseOwners = messages.flatMap { owner -> owner.historySegments.map { segment ->
            TurnOwner(owner.conversationId.value, owner.runId.value, segment.messageId.value) to owner.id.value
        } }.toMap()
        val nextSegment = mutableMapOf<TurnOwner, Int>()
        return history.mapNotNull { message ->
            if (message.role != Message.USER_ROLE && message.role != Message.ASSISTANT_ROLE) return@mapNotNull null
            val owner = TurnOwner(message.conversationId.value, message.runId.value, message.id.value)
            val segment = nextSegment[owner] ?: 0
            nextSegment[owner] = segment + 1
            val listKey = owner.listKey(segment)
            when (message.role) {
                Message.USER_ROLE -> ChatTurnUi.User(
                    id = message.id.value,
                    text = message.blocks.filterIsInstance<MessageBlock.Text>().joinToString("\n\n") { it.text },
                    timestamp = timestamp(message),
                    content = mixedContent(message, confirmedToolError),
                    listKey = listKey,
                    ownerMessageId = databaseOwners[owner] ?: message.id.value,
                    segmentIndex = segment,
                )
                Message.ASSISTANT_ROLE -> mapAssistant(message, timestamp(message), confirmedToolError).copy(
                    listKey = listKey,
                    ownerMessageId = databaseOwners[owner] ?: message.id.value,
                    segmentIndex = segment,
                )
                else -> null
            }
        }
    }

    /**
     * Count segments within their conversation/run/message identity, never within the loaded
     * window. Prepending other owners therefore leaves existing keys unchanged. All turns,
     * including single-segment owners, use the same length-prefixed encoding: raw ids and
     * delimiter-containing ids cannot collide with synthetic segment keys.
     */
    private data class TurnOwner(val conversationId: String, val runId: String, val messageId: String) {
        fun listKey(segment: Int): String =
            listOf(conversationId, runId, messageId, segment.toString()).joinToString("", prefix = "turn:") {
                "${it.length}:$it"
            }
    }

    /** Legacy text-only layout remains unchanged; mixed content is authoritative when set. */
    private fun mixedContent(message: Message,
        confirmedToolError: (Message, MessageBlock.Tool) -> Boolean?): List<MessageContentUi> {
        if (message.blocks.none { it is MessageBlock.Image }) return emptyList()
        val owner = MessageImageOwner(message.conversationId.value, message.id.value)
        return message.blocks.map { block -> when (block) {
            is MessageBlock.Text -> MessageContentUi.Text(block.itemId, block.text)
            is MessageBlock.Image -> MessageContentUi.Image(MessageImageUi(owner, block.reference))
            is MessageBlock.Tool -> MessageContentUi.Tool(
                workStep(block, message.status, confirmedToolError(message, block)), block.invocation.arguments, block.result,
            )
        } }
    }

    private fun workStep(block: MessageBlock.Tool, status: RunStatus, confirmedError: Boolean?) = WorkStepUi(
        id = block.invocation.id.value,
        name = block.invocation.name,
        detail = block.result?.takeIf { it.isNotBlank() } ?: block.invocation.arguments,
        state = when (confirmedError) {
            true -> WorkStepState.FAILED
            false -> WorkStepState.OK
            null -> stepState(block.result, status)
        },
    )

    private fun mapAssistant(message: Message, timestamp: String,
        confirmedToolError: (Message, MessageBlock.Tool) -> Boolean?): ChatTurnUi.Assistant {
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
                    is MessageBlock.Image -> Unit // Kept, in order, by mixedContent; never converted to narration.
                    is MessageBlock.Text -> if (index != lastText) {
                        segments += WorkSegmentUi(narration = block.text)
                    }
                    is MessageBlock.Tool -> {
                        val step = workStep(block, message.status, confirmedToolError(message, block))
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
            reportedReasoning = message.reportedReasoning,
            content = mixedContent(message, confirmedToolError),
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
