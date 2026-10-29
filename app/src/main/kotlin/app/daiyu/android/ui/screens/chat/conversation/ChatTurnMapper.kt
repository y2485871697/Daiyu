package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.domain.Message
import app.daiyu.android.domain.MessageBlock
import app.daiyu.android.domain.MessageHistory
import app.daiyu.android.domain.MessageImageOwner
import app.daiyu.android.domain.RunStatus
import app.daiyu.android.domain.runtime.ExecutionPhase

/** No clock, Android APIs or mutable state. Timestamp formatting belongs to the caller. */
object ChatTurnMapper {
    fun map(
        messages: List<Message>,
        confirmedToolError: (Message, MessageBlock.Tool) -> Boolean? = { _, _ -> null },
        toolState: (Message, MessageBlock.Tool) -> WorkStepState? = { _, _ -> null },
        dedicatedTool: (Message, MessageBlock.Tool) -> String? = { _, _ -> null },
        toolReceipt: (Message, MessageBlock.Tool) -> ToolReceiptUi? = { _, _ -> null },
        timestamp: (Message) -> String = { "" },
        boundariesAfter: Set<String> = emptySet(),
        assistantIdentity: (Message, String) -> app.daiyu.android.assistants.AssistantReplyIdentity? = { _, _ -> null },
    ): List<ChatTurnUi> {
        // The host can pass either persisted owners or already-expanded history. Expansion
        // retains the segment's message id (especially supplemental user image ownership).
        val history = if (messages.any { it.historySegments.isNotEmpty() }) MessageHistory.expand(messages) else messages
        val toolSources = history.flatMap { source -> source.blocks.filterIsInstance<MessageBlock.Tool>().map {
            (TurnOwner(source.conversationId.value, source.runId.value, source.id.value) to it.invocation.id.value) to source
        } }.toMap()
        fun source(message: Message, block: MessageBlock.Tool): Message = toolSources[
            TurnOwner(message.conversationId.value, message.runId.value, message.id.value) to block.invocation.id.value] ?: message
        val errors: (Message, MessageBlock.Tool) -> Boolean? = { message, block -> confirmedToolError(source(message, block), block) }
        val dedicated: (Message, MessageBlock.Tool) -> String? = { message, block -> dedicatedTool(source(message, block), block) }
        val states: (Message, MessageBlock.Tool) -> WorkStepState? = { message, block ->
            val original = source(message, block)
            toolReceipt(original, block)?.state ?: toolState(original, block)
        }
        val activity: (Message, MessageBlock.Tool) -> ToolActivityUi? = { message, block -> toolReceipt(source(message, block), block)?.activity }
        val databaseOwners = messages.flatMap { owner -> owner.historySegments.map { segment ->
            TurnOwner(owner.conversationId.value, owner.runId.value, segment.messageId.value) to owner.id.value
        } }.toMap()
        val nextSegment = mutableMapOf<TurnOwner, Int>()
        val display = mutableListOf<DisplayMessage>()
        history.forEach { message ->
            val owner = TurnOwner(message.conversationId.value, message.runId.value, message.id.value)
            val segment = nextSegment[owner] ?: 0
            nextSegment[owner] = segment + 1
            val previous = display.lastOrNull()
            // Protocol snapshots and their raw-free live tail are one visual workflow, not
            // separate model replies. Keep durable history/action targets unchanged.
            val joins = previous != null && message.role == Message.ASSISTANT_ROLE &&
                previous.message.status == message.status &&
                previous.message.role == message.role && previous.owner == owner &&
                previous.keys.last() !in boundariesAfter && owner.listKey(segment) !in boundariesAfter
            if (joins) {
                val prior = checkNotNull(previous)
                val offset = prior.message.blocks.size
                display[display.lastIndex] = prior.copy(
                    message = prior.message.copy(blocks = prior.message.blocks + message.blocks,
                        reportedReasoning = prior.message.reportedReasoning + message.reportedReasoning.map {
                            it.copy(blockOffset = (it.blockOffset ?: 0) + offset)
                        }),
                    segments = prior.segments + segment, keys = prior.keys + owner.listKey(segment))
            } else display += DisplayMessage(message, owner, listOf(segment), listOf(owner.listKey(segment)))
        }
        return display.mapNotNull { source ->
            val message = source.message
            if (message.role != Message.USER_ROLE && message.role != Message.ASSISTANT_ROLE) return@mapNotNull null
            val owner = source.owner
            val segment = source.segments.first()
            val listKey = source.keys.first()
            when (message.role) {
                Message.USER_ROLE -> ChatTurnUi.User(
                    id = message.id.value,
                    text = message.blocks.filterIsInstance<MessageBlock.Text>().joinToString("\n\n") { it.text },
                    timestamp = timestamp(message),
                    content = mixedContent(message, errors, states, dedicated, activity),
                    listKey = listKey,
                    ownerMessageId = databaseOwners[owner] ?: message.id.value,
                    segmentIndex = segment,
                )
                Message.ASSISTANT_ROLE -> mapAssistant(message, timestamp(message), errors, states, dedicated, activity).copy(
                    listKey = listKey,
                    ownerMessageId = databaseOwners[owner] ?: message.id.value,
                    segmentIndex = segment,
                    assistantIdentity = assistantIdentity(message, databaseOwners[owner] ?: message.id.value),
                    sourceListKeys = source.keys, sourceSegmentIndices = source.segments,
                )
                else -> null
            }
        }
    }

    /** Resolve all exact source occurrences for non-destructive whole-reply copy. */
    fun sourceMessages(turn: ChatTurnUi, messages: List<Message>): List<Message> {
        val keys = (turn as? ChatTurnUi.Assistant)?.sourceListKeys?.takeIf { it.isNotEmpty() }
            ?: listOf(turn.listKey)
        val wanted = keys.toSet()
        val nextSegment = mutableMapOf<TurnOwner, Int>()
        val sources = MessageHistory.expand(messages).filter { message ->
            val owner = TurnOwner(message.conversationId.value, message.runId.value, message.id.value)
            val segment = nextSegment[owner] ?: 0
            nextSegment[owner] = segment + 1
            owner.listKey(segment) in wanted
        }
        return sources.takeIf { it.size == keys.size }.orEmpty()
    }

    fun withStreamingOwner(turns: List<ChatTurnUi>, owner: Message?, phase: ExecutionPhase? = null,
        pauseRequested: Boolean = false, stopRequested: Boolean = false): List<ChatTurnUi> {
        val live = owner?.takeIf { it.role == Message.ASSISTANT_ROLE && it.status == RunStatus.RUNNING }
        val last = if (live == null) -1 else turns.indexOfLast { turn ->
            turn is ChatTurnUi.Assistant && turn.ownerMessageId == live.id.value &&
                turn.listKey == TurnOwner(live.conversationId.value, live.runId.value, turn.id).listKey(turn.segmentIndex)
        }
        val controlActivity = when {
            stopRequested -> ToolActivityUi.STOPPING
            phase == ExecutionPhase.PAUSED -> ToolActivityUi.PAUSED
            pauseRequested -> ToolActivityUi.PAUSING
            phase == ExecutionPhase.WAITING_USER -> ToolActivityUi.WAITING
            else -> null
        }
        val streaming = if (phase == null || phase == ExecutionPhase.STREAMING) last else -1
        if (turns.withIndex().none { (index, turn) ->
                turn is ChatTurnUi.Assistant && (turn.isStreaming != (index == streaming) ||
                    turn.workProcess?.controlActivity != controlActivity.takeIf { index == last && turn.workProcess != null })
            }) return turns
        return turns.mapIndexed { index, turn ->
            if (turn is ChatTurnUi.Assistant) {
                val activity = controlActivity.takeIf { index == last }
                val work = turn.workProcess?.let { if (it.controlActivity == activity) it else it.copy(controlActivity = activity) }
                if (turn.isStreaming == (index == streaming) && work === turn.workProcess) turn
                else turn.copy(isStreaming = index == streaming, workProcess = work)
            } else turn
        }
    }

    private data class DisplayMessage(val message: Message, val owner: TurnOwner,
        val segments: List<Int>, val keys: List<String>)

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
        confirmedToolError: (Message, MessageBlock.Tool) -> Boolean?,
        toolState: (Message, MessageBlock.Tool) -> WorkStepState?,
        dedicatedTool: (Message, MessageBlock.Tool) -> String?,
        activity: (Message, MessageBlock.Tool) -> ToolActivityUi?,
        integrateReasoning: Boolean = message.blocks.any { it is MessageBlock.Tool }): List<MessageContentUi> {
        if (message.blocks.none { it is MessageBlock.Image ||
                it is MessageBlock.Tool && dedicatedTool(message, it) != null }) return emptyList()
        val owner = MessageImageOwner(message.conversationId.value, message.id.value)
        val reasoning = if (integrateReasoning) reasoningAtBoundaries(message) else emptyMap()
        return buildList {
            for (index in 0..message.blocks.size) {
                reasoning[index].orEmpty().forEach { add(MessageContentUi.Reasoning(it)) }
                val block = message.blocks.getOrNull(index) ?: continue
                add(when (block) {
                    is MessageBlock.Text -> MessageContentUi.Text(block.itemId, block.text)
                    is MessageBlock.Image -> MessageContentUi.Image(MessageImageUi(owner, block.reference))
                    is MessageBlock.Tool -> MessageContentUi.Tool(
                        workStep(message, block, confirmedToolError(message, block), toolState(message, block), dedicatedTool(message, block), activity(message, block)),
                    )
                })
            }
        }
    }

    private fun workflowFinalTextIndex(message: Message): Int {
        val index = message.blocks.indexOfLast { it is MessageBlock.Text }
        // A text followed by another workflow event is narration. It must not switch
        // the whole reply between a process card and unframed mixed-content rows.
        return if (message.blocks.drop(index + 1).any { it is MessageBlock.Tool } ||
            message.reportedReasoning.any { (it.blockOffset ?: 0) > index }) -1 else index
    }

    private fun reasoningAtBoundaries(message: Message) = message.reportedReasoning
        .filter { hasReportedReasoningText(it.text) || it.activeSinceNanos != null }
        .groupBy { it.blockOffset ?: 0 }

    private fun workStep(message: Message, block: MessageBlock.Tool, confirmedError: Boolean?, receipt: WorkStepState?, dedicatedKey: String?, activity: ToolActivityUi?): WorkStepUi {
        val display = ToolCardPresentations.present(block.invocation.name, block.invocation.arguments, block.result)
        return WorkStepUi(
            id = block.invocation.id.value, name = display.title, detail = display.summary,
            state = receipt ?: when (confirmedError) {
                true -> WorkStepState.FAILED
                false -> WorkStepState.OK
                null -> if (message.status == RunStatus.RUNNING && block.result == null) WorkStepState.PENDING else WorkStepState.UNKNOWN
            },
            owner = WorkStepOwnerUi(message.conversationId.value, message.runId.value, message.id.value),
            toolName = block.invocation.name, category = display.category, sections = display.sections,
            executionTargetLabel = display.targetLabel,
            dedicatedKey = dedicatedKey, categoryDetails = display.details,
            hasBrowserScreenshot = block.invocation.name in setOf("browser_read", "browser_write") &&
                runCatching { org.json.JSONObject(block.invocation.arguments).optString("action") == "screenshot" }.getOrDefault(false),
            operationGlyph = toolOperationGlyph(block.invocation.name, block.invocation.arguments),
            activity = activity ?: ToolActivityUi.from(receipt ?: when (confirmedError) {
                true -> WorkStepState.FAILED
                false -> WorkStepState.OK
                null -> if (message.status == RunStatus.RUNNING && block.result == null) WorkStepState.PENDING else WorkStepState.UNKNOWN
            }),
        )
    }

    private fun mapAssistant(message: Message, timestamp: String,
        confirmedToolError: (Message, MessageBlock.Tool) -> Boolean?,
        toolState: (Message, MessageBlock.Tool) -> WorkStepState?,
        dedicatedTool: (Message, MessageBlock.Tool) -> String?,
        activity: (Message, MessageBlock.Tool) -> ToolActivityUi?): ChatTurnUi.Assistant {
        val hasTools = message.blocks.any { it is MessageBlock.Tool }
        val hasWorkflow = hasTools
        val lastText = workflowFinalTextIndex(message)
        val finalText = if (hasWorkflow) {
            (message.blocks.getOrNull(lastText) as? MessageBlock.Text)?.text.orEmpty()
        } else {
            message.blocks.filterIsInstance<MessageBlock.Text>().joinToString("\n\n") { it.text }
        }
        val segments = mutableListOf<WorkSegmentUi>()
        if (hasWorkflow) {
            val reasoning = reasoningAtBoundaries(message)
            for (index in 0..message.blocks.size) {
                reasoning[index].orEmpty().forEach { segments += WorkSegmentUi(reasoning = it) }
                val block = message.blocks.getOrNull(index) ?: continue
                when (block) {
                    is MessageBlock.Image -> Unit // Kept, in order, by mixedContent; never converted to narration.
                    is MessageBlock.Text -> if (index != lastText) {
                        segments += WorkSegmentUi(narration = block.text)
                    }
                    is MessageBlock.Tool -> {
                        val step = workStep(message, block, confirmedToolError(message, block), toolState(message, block), dedicatedTool(message, block), activity(message, block))
                        // Keep even trailing tools while reserving the last text for the final answer.
                        if (segments.isEmpty()) segments += WorkSegmentUi()
                        val previous = segments.last()
                        segments[segments.lastIndex] = previous.copy(steps = previous.steps + step)
                    }
                }
            }
        }
        val running = message.status == RunStatus.RUNNING
        val activities = segments.flatMap { it.steps }.filter { it.state == WorkStepState.PENDING }.map { it.activity }
        val summary = when {
            running -> when {
                ToolActivityUi.STOPPING in activities -> ToolActivityUi.STOPPING.label
                ToolActivityUi.PAUSING in activities -> ToolActivityUi.PAUSING.label
                ToolActivityUi.PAUSED in activities -> ToolActivityUi.PAUSED.label
                ToolActivityUi.RUNNING in activities -> "执行中"
                ToolActivityUi.WAITING in activities -> ToolActivityUi.WAITING.label
                segments.any { it.reasoning?.activeSinceNanos != null } -> "思考中"
                else -> activities.lastOrNull()?.label ?: "进行中"
            }
            message.status == RunStatus.FAILED -> "流程失败"
            message.status == RunStatus.STOPPED -> "已停止"
            message.status == RunStatus.INTERRUPTED -> "已中断"
            segments.any { segment -> segment.steps.any { it.state == WorkStepState.FAILED } } -> "失败"
            segments.any { it.steps.any { step -> step.state == WorkStepState.UNKNOWN } } -> "结果未确认"
            segments.any { it.steps.any { step -> step.state == WorkStepState.NOT_EXECUTED } } -> "含未执行步骤"
            segments.any { it.reasoning?.activeSinceNanos != null || it.steps.any { step -> step.state == WorkStepState.PENDING } } -> "含未完成步骤"
            else -> "已完成"
        }
        return ChatTurnUi.Assistant(
            id = message.id.value,
            timestamp = timestamp,
            workProcess = if (hasWorkflow) WorkProcessUi(segments, summary, running) else null,
            finalText = finalText,
            status = message.status,
            error = message.error,
            reportedReasoning = message.reportedReasoning,
            content = mixedContent(message, confirmedToolError, toolState, dedicatedTool, activity, integrateReasoning = hasWorkflow),
        )
    }

}
