package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.domain.RunStatus
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** One ordered host from the first thought onward, including later image/question projections. */
internal data class AssistantWorkflowContent(
    val work: WorkProcessUi?,
    val steps: List<MessageContentUi>,
    val body: List<MessageContentUi>,
    val plainNarration: Set<String> = emptySet(),
    val pending: List<MessageContentUi> = emptyList(),
    val leading: List<MessageContentUi> = emptyList(),
)

/** Keep source partitions intact: reconnect offsets are classified before display filtering. */
internal fun AssistantWorkflowContent.withResolvedTerminalCards(): AssistantWorkflowContent {
    val visibleSteps = steps.filter { it !is MessageContentUi.Tool || it.step.isVisibleInWorkflow }
    val visibleWork = work?.withResolvedTerminalCards()
    return copy(work = visibleWork ?: work?.takeIf { visibleSteps.isNotEmpty() }?.copy(segments = emptyList()),
        steps = visibleSteps)
}

internal fun WorkProcessUi.withResolvedTerminalCards(): WorkProcessUi? {
    val visibleSegments = segments.mapNotNull { segment ->
        val steps = segment.steps.filter { it.isVisibleInWorkflow }
        when {
            steps.size == segment.steps.size -> segment
            steps.isEmpty() && segment.narration.isNullOrBlank() && segment.reasoning == null -> null
            else -> segment.copy(steps = steps)
        }
    }
    if (visibleSegments == segments) return this
    return if (visibleSegments.isEmpty()) null else copy(segments = visibleSegments)
}

internal fun ChatTurnUi.Assistant.workflowContent(): AssistantWorkflowContent {
    if (content.isNotEmpty()) {
        val lastStep = content.indexOfLast { it is MessageContentUi.Reasoning || it is MessageContentUi.Tool }
        if (lastStep < 0) return AssistantWorkflowContent(null, emptyList(), content)
        val firstStep = content.indexOfFirst { it is MessageContentUi.Reasoning || it is MessageContentUi.Tool }
        val leading = content.take(firstStep)
        val split = lastStep + 1
        val steps = content.subList(firstStep, split)
        val work = workProcess ?: WorkProcessUi(steps.mapNotNull {
            when (it) {
                is MessageContentUi.Reasoning -> WorkSegmentUi(reasoning = it.reported)
                is MessageContentUi.Tool -> WorkSegmentUi(steps = listOf(it.step))
                else -> null
            }
        }, summary = if (status == RunStatus.RUNNING) "进行中" else "已完成", running = status == RunStatus.RUNNING)
        val tail = content.drop(split)
        return if (work.running) AssistantWorkflowContent(work, steps, emptyList(), pending = tail, leading = leading)
        else AssistantWorkflowContent(work, steps, tail, leading = leading)
    }
    val work = workProcess ?: reportedReasoning.filter {
        hasReportedReasoningText(it.text) || it.activeSinceNanos != null
    }.takeIf { it.isNotEmpty() }?.let { reasoning ->
        WorkProcessUi(reasoning.map { WorkSegmentUi(reasoning = it) },
            summary = if (status == RunStatus.RUNNING) "思考中" else "已完成", running = status == RunStatus.RUNNING)
    }
    val narration = mutableSetOf<String>()
    val steps = work?.segments.orEmpty().flatMapIndexed { index, segment -> buildList {
        if (!segment.narration.isNullOrBlank()) {
            val id = segment.narrationId ?: "workflow-narration:$index"
            // Narration uses the same Markdown projection as mixed-content replies.
            add(MessageContentUi.Text(id, segment.narration, segment.narrationWindow))
        }
        segment.reasoning?.let { add(MessageContentUi.Reasoning(it, segment.reasoningWindow)) }
        segment.steps.forEach { add(MessageContentUi.Tool(it)) }
    } }
    val body = finalContent.ifEmpty {
        if (finalText.isBlank()) emptyList() else listOf(MessageContentUi.Text("final", finalText))
    }
    return if (work?.running == true) AssistantWorkflowContent(work, steps, emptyList(), narration, pending = body, leading = leadingContent)
    else AssistantWorkflowContent(work, steps, body, narration, leading = leadingContent)
}

/** Mixed content is authoritative; an empty legacy list is not evidence of body text. */
internal fun ChatTurnUi.Assistant.hasBodyTextActions(): Boolean =
    if (content.isNotEmpty()) content.any { it is MessageContentUi.Text && it.text.isNotBlank() }
    else leadingContent.any { it.text.isNotBlank() } ||
        if (finalContent.isNotEmpty()) finalContent.any { it.text.isNotBlank() } else finalText.isNotBlank()

internal fun ChatTurnUi.Assistant.isPlainStreamingBody(): Boolean {
    if (status != RunStatus.RUNNING || !isStreaming || stopRequested || !error.isNullOrBlank() ||
        workProcess != null || reportedReasoning.isNotEmpty() || leadingContent.isNotEmpty() || reconnectSource != null) return false
    val source = workflowContent()
    return source.work == null && source.steps.isEmpty() && source.leading.isEmpty() && source.pending.isEmpty() &&
        source.body.isNotEmpty() && source.body.all { it is MessageContentUi.Text } &&
        source.body.any { it is MessageContentUi.Text && it.text.isNotBlank() }
}

internal fun MessageContentUi.workflowKey(index: Int): String = when (this) {
    is MessageContentUi.Reasoning -> "reasoning:${reported.itemId}"
    is MessageContentUi.Tool -> "tool:${step.renderKey}"
    is MessageContentUi.Text -> "text:$itemId"
    is MessageContentUi.Image -> messageImagePreviewKey(image)
    is MessageContentUi.Video -> messageVideoPreviewKey(video)
}

internal fun visibleWorkflowKeys(content: List<MessageContentUi>, handoff: ReasoningHandoff): List<String> {
    val visible = mutableListOf<String>()
    for ((index, item) in content.withIndex()) {
        visible += item.workflowKey(index)
        if (item is MessageContentUi.Reasoning && !handoff.ready(item.reported)) break
    }
    return visible
}

/** Retain the unchanged workflow independently of an appended final answer. */
@Composable
internal fun rememberAssistantWorkflowContent(turn: ChatTurnUi.Assistant): AssistantWorkflowContent {
    val ordered = remember(turn) { turn.workflowContent() }
    val work = remember(ordered.work) { ordered.work }
    val steps = remember(ordered.steps) { ordered.steps }
    val narration = remember(ordered.plainNarration) { ordered.plainNarration }
    return remember(work, steps, ordered.body, narration, ordered.pending, ordered.leading) {
        AssistantWorkflowContent(work, steps, ordered.body, narration, ordered.pending, ordered.leading)
    }
}
