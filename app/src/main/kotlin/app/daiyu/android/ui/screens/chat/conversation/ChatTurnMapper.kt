package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.domain.Message
import app.daiyu.android.domain.delegation.ChildRunOwner
import app.daiyu.android.domain.MessageBlock
import app.daiyu.android.domain.MessageHistory
import app.daiyu.android.domain.MessageImageOwner
import app.daiyu.android.domain.RunStatus
import app.daiyu.android.domain.runtime.ExecutionPhase
import java.util.IdentityHashMap

/** No clock or Android APIs. Timestamp formatting and optional presentation reuse belong to the caller. */
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
        presentations: ToolPresentationCache? = null,
        messageVideos: (Message) -> List<MessageVideoUi> = { emptyList() },
        reconnects: List<app.daiyu.android.timeline.ChatTimelineEntry.Reconnect> = emptyList(),
        childTaskNames: Map<ChildRunOwner, String> = emptyMap(),
    ): List<ChatTurnUi> {
        // The host can pass either persisted owners or already-expanded history. Expansion
        // retains the segment's message id (especially supplemental user image ownership).
        val history = if (messages.any { it.historySegments.isNotEmpty() }) MessageHistory.expand(messages) else messages
        presentations?.retain(history)
        // Invocation ids are only unique within one provider response in practice. A restored
        // reply can contain several history segments with the same id, so an id-only map lets
        // the last segment steal every receipt. Keep a complete, owner-scoped occurrence index.
        val sourcePositions = IdentityHashMap<MessageBlock, Int>()
        val sourceMessages = IdentityHashMap<MessageBlock, Message>()
        val occurrenceByCall = mutableMapOf<ToolSourceKey, Int>()
        val toolSources = mutableMapOf<ToolSourceKey, Message>()
        history.forEach { source ->
            val owner = TurnOwner(source.conversationId.value, source.runId.value, source.id.value)
            source.blocks.filterIsInstance<MessageBlock.Tool>().forEach { block ->
                val prefix = ToolSourceKey(owner, block.invocation.id.value, 0)
                val occurrence = occurrenceByCall[prefix] ?: 0
                occurrenceByCall[prefix] = occurrence + 1
                val key = prefix.copy(occurrence = occurrence)
                sourcePositions[block] = occurrence
                sourceMessages[block] = source
                toolSources[key] = source
            }
        }
        fun source(message: RenderMessage, block: MessageBlock.Tool): Message {
            val owner = TurnOwner(message.conversationId.value, message.runId.value, message.id.value)
            val occurrence = message.sourcePositions[block]
                ?: message.blocks.asSequence().takeWhile { it !== block }
                    .filterIsInstance<MessageBlock.Tool>()
                    .count { it.invocation.id == block.invocation.id }
            return message.sourceMessages[block]
                ?: toolSources[ToolSourceKey(owner, block.invocation.id.value, occurrence)]
                ?: checkNotNull(message.authoritative)
        }
        val errors: (RenderMessage, MessageBlock.Tool) -> Boolean? = { message, block -> confirmedToolError(source(message, block), block) }
        val dedicated: (RenderMessage, MessageBlock.Tool) -> String? = { message, block -> dedicatedTool(source(message, block), block) }
        val states: (RenderMessage, MessageBlock.Tool) -> WorkStepState? = { message, block ->
            val original = source(message, block)
            toolReceipt(original, block)?.state ?: toolState(original, block)
        }
        val activity: (RenderMessage, MessageBlock.Tool) -> ToolActivityUi? = { message, block -> toolReceipt(source(message, block), block)?.activity }
        val databaseOwners = messages.flatMap { owner -> owner.historySegments.map { segment ->
            TurnOwner(owner.conversationId.value, owner.runId.value, segment.messageId.value) to owner.id.value
        } }.toMap()
        return mapRows(history.map { renderMessage(it, sourcePositions, sourceMessages) }, databaseOwners,
            errors,
            states,
            dedicated,
            activity,
            { timestamp(requireNotNull(it.authoritative)) }, boundariesAfter,
            { message, owner -> assistantIdentity(requireNotNull(message.authoritative), owner) }, presentations,
            { messageVideos(requireNotNull(it.authoritative)) }, reconnects, childTaskNames)
    }

    internal fun mapDisplay(window: app.daiyu.android.storage.MessageDisplayWindow,
        presentations: ToolPresentationCache? = null,
        timestamp: (Long?) -> String = { "" },
        boundariesAfter: Set<String> = emptySet(),
        reconnects: List<app.daiyu.android.timeline.ChatTimelineEntry.Reconnect> = emptyList(),
        childTaskNames: Map<ChildRunOwner, String> = emptyMap(),
    ): List<ChatTurnUi> {
        val rows = window.rows.map { it.second }
        val supplements = rows.flatMap { owner -> owner.segments.filter { it.role == Message.USER_ROLE }.map {
            Triple(owner.conversationId, owner.runId, it.messageId)
        } }.toSet()
        val originals = rows.associateBy { Triple(it.conversationId, it.runId, it.id) }
        val owners = mutableMapOf<TurnOwner, String>()
        val history = rows.flatMap { row ->
            if (row.segments.isNotEmpty()) row.segments.map { segment ->
                owners[TurnOwner(row.conversationId.value, row.runId.value, segment.messageId.value)] = row.id.value
                RenderMessage(segment.messageId, row.conversationId, row.runId, segment.role, segment.blocks,
                    if (segment.role == Message.USER_ROLE) RunStatus.COMPLETED else row.status,
                    if (segment.role == Message.USER_ROLE) null else row.error,
                    segment.createdAt ?: originals[Triple(row.conversationId, row.runId, segment.messageId)]?.createdAt,
                    segment.reportedReasoning, coordinateBlocks = segment.coordinateBlocks)
            } else if (Triple(row.conversationId, row.runId, row.id) in supplements) emptyList()
            else listOf(RenderMessage(row.id, row.conversationId, row.runId, row.role, row.blocks, row.status,
                row.error, row.createdAt, emptyList(), coordinateBlocks = row.coordinateBlocks))
        }
        presentations?.retainBlocks(history.flatMap { it.blocks })
        return mapRows(history, owners, { _, _ -> null }, { _, _ -> null }, { _, _ -> null },
            { _, _ -> null }, { timestamp(it.createdAt) }, boundariesAfter, { _, _ -> null }, presentations,
            { emptyList() }, reconnects, childTaskNames)
    }

    private data class RenderMessage(val id: app.daiyu.android.domain.MessageId,
        val conversationId: app.daiyu.android.domain.ConversationId, val runId: app.daiyu.android.domain.RunId,
        val role: String, val blocks: List<MessageBlock>, val status: RunStatus, val error: String?,
        val createdAt: Long?, val reportedReasoning: List<app.daiyu.android.domain.ReportedReasoning>,
        val authoritative: Message? = null,
        /** Absolute tool occurrence within this owner, retained when adjacent rows are merged. */
        val sourcePositions: Map<MessageBlock, Int> = emptyMap(),
        val sourceMessages: Map<MessageBlock, Message> = emptyMap(),
        val coordinateBlocks: List<MessageBlock> = blocks)

    private fun renderMessage(message: Message, positions: Map<MessageBlock, Int> = emptyMap(),
        sources: Map<MessageBlock, Message> = emptyMap()): RenderMessage {
        val local = IdentityHashMap<MessageBlock, Int>()
        val localSources = IdentityHashMap<MessageBlock, Message>()
        message.blocks.forEach { block ->
            positions[block]?.let { local[block] = it }
            sources[block]?.let { localSources[block] = it }
        }
        return RenderMessage(message.id, message.conversationId, message.runId, message.role,
            message.blocks.map(app.daiyu.android.model.LegacySearchDisplay::display),
            message.status, message.error, message.createdAt, message.reportedReasoning, message, local, localSources, message.blocks)
    }

    private fun mapRows(history: List<RenderMessage>, databaseOwners: Map<TurnOwner, String>,
        errors: (RenderMessage, MessageBlock.Tool) -> Boolean?,
        states: (RenderMessage, MessageBlock.Tool) -> WorkStepState?,
        dedicated: (RenderMessage, MessageBlock.Tool) -> String?,
        activity: (RenderMessage, MessageBlock.Tool) -> ToolActivityUi?,
        timestamp: (RenderMessage) -> String, boundariesAfter: Set<String>,
        assistantIdentity: (RenderMessage, String) -> app.daiyu.android.assistants.AssistantReplyIdentity?,
        presentations: ToolPresentationCache?, messageVideos: (RenderMessage) -> List<MessageVideoUi>,
        reconnects: List<app.daiyu.android.timeline.ChatTimelineEntry.Reconnect>,
        childTaskNames: Map<ChildRunOwner, String> = emptyMap(),
    ): List<ChatTurnUi> {
        val nextSegment = mutableMapOf<TurnOwner, Int>()
        val nextOffset = mutableMapOf<TurnOwner, Long>()
        val display = mutableListOf<DisplayMessage>()
        history.forEach { original ->
            val positionedReconnect = reconnects.any { it.position != null &&
                it.anchor.conversationId == original.conversationId.value && it.anchor.runId == original.runId.value &&
                it.anchor.messageId == original.id.value }
            val message = if (positionedReconnect) original else original.authoritative?.let(::normalizeCompletedReasoning)?.let { normalized ->
                renderMessage(normalized, original.sourcePositions, original.sourceMessages)
            } ?: original
            val owner = TurnOwner(message.conversationId.value, message.runId.value, message.id.value)
            val segment = nextSegment[owner] ?: 0
            nextSegment[owner] = segment + 1
            val start = nextOffset[owner] ?: 0L
            nextOffset[owner] = start + original.coordinateBlocks.sumOf { block -> when (block) {
                is MessageBlock.Text -> block.text.length.toLong()
                is MessageBlock.Tool -> 1L
                is MessageBlock.Image -> 1L
            } } + original.reportedReasoning.sumOf { it.text.length.toLong() }
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
                        coordinateBlocks = prior.message.coordinateBlocks + message.coordinateBlocks,
                        sourcePositions = IdentityHashMap<MessageBlock, Int>().apply {
                            putAll(prior.message.sourcePositions)
                            putAll(message.sourcePositions)
                        },
                        sourceMessages = IdentityHashMap<MessageBlock, Message>().apply {
                            putAll(prior.message.sourceMessages)
                            putAll(message.sourceMessages)
                        },
                        reportedReasoning = prior.message.reportedReasoning + message.reportedReasoning.map {
                            it.copy(blockOffset = (it.blockOffset ?: 0) + offset)
                        }, authoritative = prior.message.authoritative?.let { original -> original.copy(
                            blocks = original.blocks + message.authoritative?.blocks.orEmpty(),
                            reportedReasoning = original.reportedReasoning + message.authoritative?.reportedReasoning.orEmpty().map {
                                it.copy(blockOffset = (it.blockOffset ?: 0) + original.blocks.size)
                            }) }),
                    segments = prior.segments + segment, keys = prior.keys + owner.listKey(segment))
            } else display += DisplayMessage(message, owner, listOf(segment), listOf(owner.listKey(segment)), start)
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
                    content = mixedContent(message, errors, states, dedicated, activity, presentations = presentations, childTaskNames = childTaskNames,
                        videos = messageVideos(message).filter { video ->
                            video.expectedOwner.conversationId == message.conversationId.value &&
                                video.expectedOwner.messageId == message.id.value &&
                                video.reference.owner == video.expectedOwner
                        }),
                    listKey = listKey,
                    ownerMessageId = databaseOwners[owner] ?: message.id.value,
                    segmentIndex = segment,
                )
                Message.ASSISTANT_ROLE -> mapAssistant(message, timestamp(message), errors, states, dedicated, activity, presentations, childTaskNames).copy(
                    listKey = listKey,
                    ownerMessageId = databaseOwners[owner] ?: message.id.value,
                    segmentIndex = segment,
                    assistantIdentity = assistantIdentity(message, databaseOwners[owner] ?: message.id.value),
                    sourceListKeys = source.keys, sourceSegmentIndices = source.segments,
                    reconnectSource = if (reconnects.any { it.position != null &&
                        it.anchor.conversationId == owner.conversationId && it.anchor.runId == owner.runId &&
                        it.anchor.messageId == owner.messageId }) ReconnectSourceUi(
                        app.daiyu.android.timeline.TimelineAnchor(owner.conversationId, owner.runId, owner.messageId, segment),
                        source.start, mixedContent(message.copy(blocks = message.coordinateBlocks), errors, states, dedicated, activity,
                            integrateReasoning = true, presentations = presentations, childTaskNames = childTaskNames, forceOrdered = true)) else null,
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
        val streaming = if (!stopRequested && (phase == null || phase == ExecutionPhase.STREAMING)) last else -1
        fun stopping(turn: ChatTurnUi.Assistant): Boolean =
            stopRequested && live != null && turn.ownerMessageId == live.id.value &&
                turn.listKey == TurnOwner(live.conversationId.value, live.runId.value, turn.id).listKey(turn.segmentIndex) ||
                turn.stopRequested && turn.status == RunStatus.RUNNING && live == null
        if (turns.withIndex().none { (index, turn) ->
                turn is ChatTurnUi.Assistant && (turn.isStreaming != (index == streaming) || turn.stopRequested != stopping(turn) ||
                    turn.workProcess?.controlActivity != controlActivity.takeIf { index == last && turn.workProcess != null })
            }) return turns
        return turns.mapIndexed { index, turn ->
            if (turn is ChatTurnUi.Assistant) {
                val activity = controlActivity.takeIf { index == last }
                val work = turn.workProcess?.let { if (it.controlActivity == activity) it else it.copy(controlActivity = activity) }
                val stopped = stopping(turn)
                if (turn.isStreaming == (index == streaming) && work === turn.workProcess && turn.stopRequested == stopped) turn
                else turn.copy(isStreaming = index == streaming, workProcess = work, stopRequested = stopped)
            } else turn
        }
    }

    private data class DisplayMessage(val message: RenderMessage, val owner: TurnOwner,
        val segments: List<Int>, val keys: List<String>, val start: Long)

    /**
     * Count segments within their conversation/run/message identity, never within the loaded
     * window. Prepending other owners therefore leaves existing keys unchanged. All turns,
     * including single-segment owners, use the same length-prefixed encoding: raw ids and
     * delimiter-containing ids cannot collide with synthetic segment keys.
     */
    private data class ToolSourceKey(val owner: TurnOwner, val invocationId: String, val occurrence: Int)

    private data class TurnOwner(val conversationId: String, val runId: String, val messageId: String) {
        fun listKey(segment: Int): String =
            listOf(conversationId, runId, messageId, segment.toString()).joinToString("", prefix = "turn:") {
                "${it.length}:$it"
            }
    }

    /** Legacy text-only layout remains unchanged; mixed content is authoritative when set. */
    private fun mixedContent(message: RenderMessage,
        confirmedToolError: (RenderMessage, MessageBlock.Tool) -> Boolean?,
        toolState: (RenderMessage, MessageBlock.Tool) -> WorkStepState?,
        dedicatedTool: (RenderMessage, MessageBlock.Tool) -> String?,
        activity: (RenderMessage, MessageBlock.Tool) -> ToolActivityUi?,
        integrateReasoning: Boolean = message.blocks.any { it is MessageBlock.Tool },
        presentations: ToolPresentationCache? = null,
        videos: List<MessageVideoUi> = emptyList(),
        forceOrdered: Boolean = false,
        childTaskNames: Map<ChildRunOwner, String> = emptyMap()): List<MessageContentUi> {
        if (!forceOrdered && videos.isEmpty() && message.blocks.none { it is MessageBlock.Image ||
                it is MessageBlock.Tool && dedicatedTool(message, it) != null }) return emptyList()
        val owner = MessageImageOwner(message.conversationId.value, message.id.value)
        val reasoning = if (forceOrdered) message.reportedReasoning.groupBy { it.blockOffset ?: 0 }
            else if (integrateReasoning) reasoningAtBoundaries(message) else emptyMap()
        return buildList {
            for (index in 0..message.blocks.size) {
                reasoning[index].orEmpty().forEach { add(MessageContentUi.Reasoning(it)) }
                val block = message.blocks.getOrNull(index) ?: continue
                add(when (block) {
                    is MessageBlock.Text -> MessageContentUi.Text(block.itemId, block.text, app.daiyu.android.model.LegacySearchDisplay.window(block))
                    is MessageBlock.Image -> MessageContentUi.Image(MessageImageUi(owner, block.reference))
                    is MessageBlock.Tool -> MessageContentUi.Tool(
                        workStep(message, block, confirmedToolError(message, block), toolState(message, block), dedicatedTool(message, block), activity(message, block), presentations,
                            toolOccurrence(message, block), childTaskNames),
                    )
                })
            }
            // Sidecar order is defined after the source row's text/image blocks.
            videos.forEach { add(MessageContentUi.Video(it)) }
        }
    }

    private fun workflowFinalTextIndex(message: RenderMessage): Int {
        val lastTool = message.blocks.indexOfLast { it is MessageBlock.Tool }
        val lastReasoning = message.reportedReasoning.filter {
            hasReportedReasoningText(it.text) || it.activeSinceNanos != null
        }.maxOfOrNull { it.blockOffset ?: 0 } ?: 0
        val start = maxOf(lastTool + 1, lastReasoning)
        return (start until message.blocks.size).firstOrNull { message.blocks[it] is MessageBlock.Text } ?: -1
    }

    private fun reasoningAtBoundaries(message: RenderMessage) = message.reportedReasoning
        .filter { hasReportedReasoningText(it.text) || it.activeSinceNanos != null }
        .groupBy { it.blockOffset ?: 0 }

    private fun toolOccurrence(message: RenderMessage, block: MessageBlock.Tool): Int =
        message.sourcePositions[block] ?: message.blocks.asSequence().takeWhile { it !== block }
            .filterIsInstance<MessageBlock.Tool>().count { it.invocation.id == block.invocation.id }

    private fun toolRenderKey(message: RenderMessage, block: MessageBlock.Tool, occurrence: Int): String =
        listOf(message.conversationId.value, message.runId.value, message.id.value,
            block.invocation.id.value, occurrence.toString()).joinToString("", prefix = "tool:") { "${it.length}:$it" }

    private fun workStep(message: RenderMessage, block: MessageBlock.Tool, confirmedError: Boolean?, receipt: WorkStepState?, dedicatedKey: String?, activity: ToolActivityUi?, presentations: ToolPresentationCache?, occurrence: Int = toolOccurrence(message, block),
        childTaskNames: Map<ChildRunOwner, String> = emptyMap()): WorkStepUi {
        val cached = presentations?.present(block) ?: ToolCardPresentations.present(block.invocation.name, block.invocation.arguments, block.result)
        val display = childTaskNamedPresentation(cached, message.conversationId.value, block.invocation.name,
            block.invocation.arguments, block.result, childTaskNames)
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
            renderKey = toolRenderKey(message, block, occurrence),
            dedicatedKey = dedicatedKey, categoryDetails = display.details,
            hasBrowserScreenshot = block.invocation.name in setOf("browser_read", "browser_write") &&
                runCatching { org.json.JSONObject(block.invocation.arguments).optString("action") == "screenshot" }.getOrDefault(false),
            hasScreenScreenshot = display.hasScreenScreenshot,
            operationGlyph = toolOperationGlyph(block.invocation.name, block.invocation.arguments),
            activity = activity ?: ToolActivityUi.from(receipt ?: when (confirmedError) {
                true -> WorkStepState.FAILED
                false -> WorkStepState.OK
                null -> if (message.status == RunStatus.RUNNING && block.result == null) WorkStepState.PENDING else WorkStepState.UNKNOWN
            }),
        )
    }

    private fun mapAssistant(message: RenderMessage, timestamp: String,
        confirmedToolError: (RenderMessage, MessageBlock.Tool) -> Boolean?,
        toolState: (RenderMessage, MessageBlock.Tool) -> WorkStepState?,
        dedicatedTool: (RenderMessage, MessageBlock.Tool) -> String?,
        activity: (RenderMessage, MessageBlock.Tool) -> ToolActivityUi?,
        presentations: ToolPresentationCache?,
        childTaskNames: Map<ChildRunOwner, String>): ChatTurnUi.Assistant {
        val hasTools = message.blocks.any { it is MessageBlock.Tool }
        val hasWorkflow = hasTools || message.reportedReasoning.any { hasReportedReasoningText(it.text) || it.activeSinceNanos != null }
        // Keep the unclassified tail out of process narration. The UI admits it only
        // after a real successor step or a terminal reply resolves its ownership.
        val lastText = workflowFinalTextIndex(message)
        val finalContent = message.blocks.mapIndexedNotNull { index, block ->
            (block as? MessageBlock.Text)?.takeIf { !hasWorkflow || lastText >= 0 && index >= lastText }
                ?.let { MessageContentUi.Text(it.itemId, it.text) }
        }
        val finalText = finalContent.joinToString("\n\n") { it.text }
        val segments = mutableListOf<WorkSegmentUi>()
        if (hasWorkflow) {
            val reasoning = reasoningAtBoundaries(message)
            for (index in 0..message.blocks.size) {
                reasoning[index].orEmpty().forEach { segments += WorkSegmentUi(reasoning = it) }
                val block = message.blocks.getOrNull(index) ?: continue
                when (block) {
                    is MessageBlock.Image -> Unit // Kept, in order, by mixedContent; never converted to narration.
                    is MessageBlock.Text -> if (lastText < 0 || index < lastText) {
                        segments += WorkSegmentUi(narration = block.text, narrationId = block.itemId)
                    }
                    is MessageBlock.Tool -> {
                        val step = workStep(message, block, confirmedToolError(message, block), toolState(message, block), dedicatedTool(message, block), activity(message, block), presentations,
                            toolOccurrence(message, block), childTaskNames)
                        // Keep even trailing tools while reserving the last text for the final answer.
                        if (segments.isEmpty()) segments += WorkSegmentUi()
                        val previous = segments.last()
                        segments[segments.lastIndex] = previous.copy(steps = previous.steps + step)
                    }
                }
            }
        }
        val running = message.status == RunStatus.RUNNING
        val activities = segments.flatMap { it.steps }.filter { it.state == WorkStepState.PENDING || running && it.activity.inFlight }.map { it.activity }
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
            finalContent = finalContent,
            status = message.status,
            error = message.error,
            reportedReasoning = message.reportedReasoning,
            content = mixedContent(message, confirmedToolError, toolState, dedicatedTool, activity, integrateReasoning = hasWorkflow, presentations = presentations, childTaskNames = childTaskNames),
        )
    }

}
