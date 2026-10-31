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
)

internal fun ChatTurnUi.Assistant.workflowContent(): AssistantWorkflowContent {
    if (content.isNotEmpty()) {
        val lastStep = content.indexOfLast { it is MessageContentUi.Reasoning || it is MessageContentUi.Tool }
        if (lastStep < 0) return AssistantWorkflowContent(null, emptyList(), content)
        val split = if (workProcess?.running ?: (status == RunStatus.RUNNING)) content.size else lastStep + 1
        val steps = content.take(split)
        val work = workProcess ?: WorkProcessUi(steps.mapNotNull {
            when (it) {
                is MessageContentUi.Reasoning -> WorkSegmentUi(reasoning = it.reported)
                is MessageContentUi.Tool -> WorkSegmentUi(steps = listOf(it.step))
                else -> null
            }
        }, summary = if (status == RunStatus.RUNNING) "进行中" else "已完成", running = status == RunStatus.RUNNING)
        return AssistantWorkflowContent(work, steps, content.drop(split))
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
            val id = "workflow-narration:$index"
            narration += id
            add(MessageContentUi.Text(id, segment.narration))
        }
        segment.reasoning?.let { add(MessageContentUi.Reasoning(it)) }
        segment.steps.forEach { add(MessageContentUi.Tool(it)) }
    } }
    val body = if (finalText.isBlank()) emptyList() else listOf(MessageContentUi.Text("final", finalText))
    return AssistantWorkflowContent(work, steps, body, narration)
}

internal fun MessageContentUi.workflowKey(index: Int): String = when (this) {
    is MessageContentUi.Reasoning -> "reasoning:${reported.itemId}"
    is MessageContentUi.Tool -> "tool:${step.toolCallId}"
    is MessageContentUi.Text -> "text:$itemId"
    is MessageContentUi.Image -> "image:$index:${image.reference.id}"
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
    return remember(work, steps, ordered.body, narration) {
        AssistantWorkflowContent(work, steps, ordered.body, narration)
    }
}
