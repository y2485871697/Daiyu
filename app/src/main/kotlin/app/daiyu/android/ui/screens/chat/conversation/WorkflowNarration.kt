package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import app.daiyu.android.MarkdownBlock
import app.daiyu.android.MarkdownInline
import app.daiyu.android.domain.ReportedReasoning

private val plainWorkflowNarrationParser: (String) -> List<MarkdownBlock> = { text ->
    if (text.isEmpty()) emptyList() else listOf(MarkdownBlock.Paragraph(listOf(MarkdownInline.Text(text))))
}

internal class WorkflowNarration(val projection: MarkdownProjection?, val reveal: MarkdownReveal)

internal fun workflowBlockedTextSources(steps: List<MessageContentUi>, reasoningReady: (ReportedReasoning) -> Boolean): Set<String> {
    var ready = true
    return buildSet {
        steps.forEach { item ->
            if (item is MessageContentUi.Reasoning && !reasoningReady(item.reported)) ready = false
            if (!ready && item is MessageContentUi.Text) add(item.itemId)
        }
    }
}

/** Confirmed narration owns its budget separately from the final answer. */
@Composable
internal fun rememberWorkflowNarration(
    steps: List<MessageContentUi>,
    plainText: Boolean,
    sources: List<MarkdownTypingSource>,
    running: Boolean,
    paused: Boolean,
    expanded: Boolean,
    stopped: Boolean,
    revealResetEpoch: Long,
    reasoningReady: (ReportedReasoning) -> Boolean,
    onVisibleAdvance: () -> Unit,
): WorkflowNarration {
    val parser = if (plainText) plainWorkflowNarrationParser else LocalMarkdownParser.current
    var result: WorkflowNarration? = null
    CompositionLocalProvider(LocalMarkdownParser provides parser) {
        val texts = remember(sources) { sources.map { it.text } }
        val projection = rememberMarkdownProjection(texts, streaming = running)
        val blocked = workflowBlockedTextSources(steps, reasoningReady)
        // Reopening is a silent baseline; reasoning handoff never resets this owner.
        val reveal = key(expanded) {
            rememberMarkdownReveal(sources, projection, streaming = running && !paused,
                terminal = !running && projection?.sources == texts, resetEpoch = revealResetEpoch, onVisibleAdvance = onVisibleAdvance,
                enabled = expanded && !stopped && !paused, allowBaselinePrefix = false, blockedSources = blocked)
        }
        result = remember(projection, reveal) { WorkflowNarration(projection, reveal) }
    }
    return checkNotNull(result)
}
