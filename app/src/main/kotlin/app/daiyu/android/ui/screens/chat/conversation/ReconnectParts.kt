package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.timeline.ChatTimelineEntry
import app.daiyu.android.timeline.TimelineAnchor

/** Ordered display evidence, never a replacement for a persisted message. */
data class ReconnectSourceUi(val owner: TimelineAnchor, val start: Long, val content: List<MessageContentUi>)

internal data class ReconnectPart(val turn: ChatTurnUi.Assistant, val after: List<ChatTimelineEntry.Reconnect>)
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
    val pending = source.content.toMutableList()
    var consumed = source.start
    val parts = mutableListOf<Pair<List<MessageContentUi>, List<ChatTimelineEntry.Reconnect>>>()
    for ((offset, lines) in cuts.groupBy { it.position!!.offset }) {
        val head = mutableListOf<MessageContentUi>()
        while (pending.isNotEmpty()) {
            val item = pending.first()
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
                            head += item.copy(text = text.take(safe))
                            pending[0] = item.copy(text = text.drop(safe))
                        }
                        is MessageContentUi.Reasoning -> {
                            head += item.copy(reported = item.reported.copy(text = text.take(safe), activeSinceNanos = null))
                            pending[0] = item.copy(reported = item.reported.copy(text = text.drop(safe)))
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
        val displayed = if (live) content else content.map { item ->
            if (item is MessageContentUi.Reasoning) item.copy(reported = item.reported.copy(activeSinceNanos = null)) else item
        }
        val work = turn.workProcess?.let { original -> original.copy(segments = displayed.mapNotNull { item -> when (item) {
            is MessageContentUi.Reasoning -> WorkSegmentUi(reasoning = item.reported)
            is MessageContentUi.Tool -> WorkSegmentUi(steps = listOf(item.step))
            is MessageContentUi.Text -> WorkSegmentUi(narration = item.text)
            else -> null
        } }, summary = if (live) original.summary else "已完成", running = live && original.running,
            controlActivity = original.controlActivity.takeIf { live }) }
        val reasoning = displayed.filterIsInstance<MessageContentUi.Reasoning>().map { it.reported }
        ReconnectPart(turn.copy(
            listKey = "${turn.listKey}:reconnect-part:$index", finalText = "", finalContent = emptyList(), content = displayed,
            status = if (live) turn.status else app.daiyu.android.domain.RunStatus.COMPLETED,
            sourceListKeys = turn.sourceListKeys.ifEmpty { listOf(turn.listKey) },
            workProcess = work, reportedReasoning = reasoning,
            error = turn.error.takeIf { live }, isStreaming = live && turn.isStreaming,
            reconnectSource = null,
        ), lines)
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

/** Preserve unresolved live text as pending while frozen workflow prefixes stay in their host. */
internal fun reconnectWorkflowContent(turn: ChatTurnUi.Assistant, ordered: AssistantWorkflowContent,
    frozen: Boolean): AssistantWorkflowContent {
    val work = turn.workProcess ?: return ordered
    return when {
        frozen -> ordered.copy(work = work, steps = turn.content, body = emptyList(), pending = emptyList())
        work.running -> ordered.copy(work = work, body = emptyList(), pending = ordered.pending + ordered.body)
        else -> ordered
    }
}
