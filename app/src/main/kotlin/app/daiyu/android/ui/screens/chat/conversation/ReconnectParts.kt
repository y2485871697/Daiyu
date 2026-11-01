package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.timeline.ChatTimelineEntry
import app.daiyu.android.timeline.TimelineAnchor

/** Ordered display evidence, never a replacement for a persisted message. */
data class ReconnectSourceUi(val owner: TimelineAnchor, val start: Long, val content: List<MessageContentUi>)

internal data class ReconnectPart(val turn: ChatTurnUi.Assistant, val after: List<ChatTimelineEntry.Reconnect>,
    val workflow: AssistantWorkflowContent)

private enum class ReconnectContentRole { WORKFLOW, BODY, PENDING }
private data class ReconnectContent(val item: MessageContentUi, val role: ReconnectContentRole)
internal data class ReconnectParts(val parts: List<ReconnectPart>, val placedIds: Set<String>)

private fun MessageContentUi.units(): Long = when (this) {
    is MessageContentUi.Text -> text.length.toLong()
    is MessageContentUi.Reasoning -> reported.text.length.toLong()
    else -> 1L
}

/** Out-of-range coordinates are deliberately not consumed: the list renders their fallback. */
internal fun reconnectParts(turn: ChatTurnUi.Assistant, events: List<ChatTimelineEntry.Reconnect>): ReconnectParts {
    val source = turn.reconnectSource ?: return ReconnectParts(emptyList(), emptySet())
    val end = source.start + source.content.sumOf { it.units() }
    val cuts = events.filter {
        it.anchor.conversationId == source.owner.conversationId && it.anchor.runId == source.owner.runId &&
            it.anchor.messageId == source.owner.messageId && it.position?.offset?.let { offset ->
                offset <= end && (offset > source.start || source.start == 0L && offset == 0L)
            } == true
    }.sortedWith(compareBy({ it.position!!.offset }, { it.created }, { it.id }))
    if (cuts.isEmpty()) return ReconnectParts(emptyList(), emptySet())
    // Classify before cutting: freezing a slice cannot turn an answer into process narration.
    val sourceWorkflow = turn.copy(content = source.content).workflowContent()
    val pending = source.content.mapIndexed { index, item ->
        val role = when {
            index < sourceWorkflow.steps.size -> ReconnectContentRole.WORKFLOW
            sourceWorkflow.pending.isNotEmpty() -> ReconnectContentRole.PENDING
            else -> ReconnectContentRole.BODY
        }
        ReconnectContent(item, role)
    }.toMutableList()
    var consumed = source.start
    val parts = mutableListOf<Pair<List<ReconnectContent>, List<ChatTimelineEntry.Reconnect>>>()
    for ((offset, lines) in cuts.groupBy { it.position!!.offset }) {
        val head = mutableListOf<ReconnectContent>()
        while (pending.isNotEmpty()) {
            val tagged = pending.first()
            val item = tagged.item
            val length = item.units()
            if (consumed + length <= offset) {
                head += pending.removeAt(0)
                consumed += length
            } else {
                val count = (offset - consumed).coerceAtLeast(0L).toInt()
                val text = when (item) {
                    is MessageContentUi.Text -> item.text
                    is MessageContentUi.Reasoning -> item.reported.text
                    else -> ""
                }
                // A malformed persisted coordinate must never split a surrogate pair.
                val safe = if (count in 1 until text.length && text[count - 1].isHighSurrogate() &&
                    text[count].isLowSurrogate()) count - 1 else count
                if (safe > 0) {
                    when (item) {
                        is MessageContentUi.Text -> {
                            head += tagged.copy(item = item.copy(text = text.take(safe)))
                            pending[0] = tagged.copy(item = item.copy(text = text.drop(safe)))
                        }
                        is MessageContentUi.Reasoning -> {
                            head += tagged.copy(item = item.copy(reported = item.reported.copy(text = text.take(safe), activeSinceNanos = null)))
                            pending[0] = tagged.copy(item = item.copy(reported = item.reported.copy(text = text.drop(safe))))
                        }
                        else -> Unit
                    }
                    consumed += safe
                }
                break
            }
        }
        parts += head to lines
    }
    // Even an empty live tail owns control state and the original reply's bottom actions.
    parts += pending.toList() to emptyList()
    return ReconnectParts(parts.mapIndexed { index, (content, lines) ->
        val live = index == parts.lastIndex
        val displayed = content.map { tagged ->
            val item = tagged.item
            if (!live && item is MessageContentUi.Reasoning)
                tagged.copy(item = item.copy(reported = item.reported.copy(activeSinceNanos = null))) else tagged
        }
        fun items(role: ReconnectContentRole) = displayed.filter { it.role == role }.map { it.item }
        val steps = items(ReconnectContentRole.WORKFLOW)
        val work = sourceWorkflow.work?.takeIf { steps.isNotEmpty() }?.let { original -> original.copy(
            segments = steps.mapNotNull { item -> when (item) {
                is MessageContentUi.Reasoning -> WorkSegmentUi(reasoning = item.reported)
                is MessageContentUi.Tool -> WorkSegmentUi(steps = listOf(item.step))
                is MessageContentUi.Text -> WorkSegmentUi(narration = item.text)
                else -> null
            } }, summary = if (live) original.summary else "已完成", running = live && original.running,
            controlActivity = original.controlActivity.takeIf { live }) }
        val workflow = AssistantWorkflowContent(work, steps, items(ReconnectContentRole.BODY),
            sourceWorkflow.plainNarration, items(ReconnectContentRole.PENDING))
        val reasoning = displayed.map { it.item }.filterIsInstance<MessageContentUi.Reasoning>().map { it.reported }
        ReconnectPart(turn.copy(
            listKey = "${turn.listKey}:reconnect-part:$index", finalText = "", finalContent = emptyList(), content = displayed.map { it.item },
            status = if (live) turn.status else app.daiyu.android.domain.RunStatus.COMPLETED,
            sourceListKeys = turn.sourceListKeys.ifEmpty { listOf(turn.listKey) },
            workProcess = work, reportedReasoning = reasoning,
            error = turn.error.takeIf { live }, isStreaming = live && turn.isStreaming,
            reconnectSource = null,
        ), lines, workflow)
    }, cuts.mapTo(hashSetOf()) { it.id })
}

internal fun reconnectPartsForTurns(turns: List<ChatTurnUi>, events: List<ChatTimelineEntry.Reconnect>): Map<String, ReconnectParts> {
    val placed = hashSetOf<String>()
    return turns.filterIsInstance<ChatTurnUi.Assistant>().associate { turn ->
        val parts = reconnectParts(turn, events.filterNot { it.id in placed })
        placed += parts.placedIds
        turn.listKey to parts
    }
}

internal fun reconnectFallbackEntries(turn: ChatTurnUi, timeline: List<ChatTimelineEntry>, placedIds: Set<String>,
    turns: List<ChatTurnUi> = listOf(turn)): List<ChatTimelineEntry> {
    fun keys(row: ChatTurnUi) = (row as? ChatTurnUi.Assistant)?.sourceListKeys?.takeIf { it.isNotEmpty() } ?: listOf(row.listKey)
    fun sameOwner(row: ChatTurnUi, entry: ChatTimelineEntry): Boolean {
        val owner = (row as? ChatTurnUi.Assistant)?.reconnectSource?.owner ?: return false
        return owner.conversationId == entry.anchor.conversationId && owner.runId == entry.anchor.runId &&
            owner.messageId == entry.anchor.messageId
    }
    return timeline.filter { entry ->
        entry.id !in placedIds && (entry.anchor.listKey in keys(turn) ||
            entry is ChatTimelineEntry.Reconnect && entry.position != null &&
                turns.none { entry.anchor.listKey in keys(it) } &&
                turns.lastOrNull { sameOwner(it, entry) }?.listKey == turn.listKey)
    }
}
