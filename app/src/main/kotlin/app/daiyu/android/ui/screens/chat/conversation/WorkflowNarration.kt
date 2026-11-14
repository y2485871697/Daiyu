package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.collectLatest
import app.daiyu.android.domain.ReportedReasoning

internal class WorkflowNarration(val projection: MarkdownProjection?, val reveal: MarkdownReveal,
    val entryVisible: (String) -> Boolean = { true })

/** Kept outside lazy items: an already admitted tool never disappears after remount. */
internal class WorkflowVisualAdmissions(initialOwners: Set<String>) {
    private val scopes = initialOwners.associateWith { WorkflowVisualScope(true) }.toMutableMap()
    fun scope(owner: String, baseline: Boolean) = scopes.getOrPut(owner) { WorkflowVisualScope(baseline) }
    fun retain(owners: Set<String>) { scopes.keys.retainAll(owners) }
}

internal class WorkflowVisualScope(private val baseline: Boolean) {
    private val owners = mutableMapOf<String, WorkflowVisualAdmission>()
    fun owner(key: String, steps: List<MessageContentUi>, running: Boolean) = owners.getOrPut(key) {
        WorkflowVisualAdmission(if (baseline || !running) steps.mapIndexed { index, item -> item.workflowKey(index) }
            else emptyList(), animateFirstNarration = !baseline && running)
    }
}

internal val LocalWorkflowVisualScope = staticCompositionLocalOf<WorkflowVisualScope?> { null }

internal class WorkflowVisualAdmission(initial: List<String>, private var animateFirstNarration: Boolean) {
    private val admitted = mutableStateMapOf<String, Unit>().apply { initial.forEach { put(it, Unit) } }
    fun contains(key: String) = admitted.containsKey(key)
    fun admit(keys: List<String>) { keys.forEach { admitted[it] = Unit } }
    fun settled(steps: List<MessageContentUi>) = steps.withIndex().all { (index, item) -> contains(item.workflowKey(index)) }
    fun initialBaseline(sources: List<MarkdownTypingSource>, baselineOnly: Boolean): List<MarkdownTypingSource> {
        val animate = animateFirstNarration && !baselineOnly
        animateFirstNarration = false
        return if (animate) emptyList() else sources.toList()
    }
    fun blockedText(steps: List<MessageContentUi>): Set<String> = buildSet {
        var blocked = false
        steps.forEachIndexed { index, item ->
            if (item is MessageContentUi.Tool && !this@WorkflowVisualAdmission.contains(item.workflowKey(index))) blocked = true
            if (blocked && item is MessageContentUi.Text && !this@WorkflowVisualAdmission.contains(item.workflowKey(index))) add(item.itemId)
        }
    }
}

private data class WorkflowAdmissionEntry(val key: String, val tool: Boolean,
    val precedingText: List<MarkdownTypingSource>)

private fun workflowAdmissionPlan(steps: List<MessageContentUi>): List<WorkflowAdmissionEntry> {
    val preceding = mutableListOf<MarkdownTypingSource>()
    return steps.mapIndexed { index, item ->
        if (item is MessageContentUi.Text && item.text.isNotBlank()) preceding += MarkdownTypingSource(item.itemId, item.text, item.markdownWindow)
        WorkflowAdmissionEntry(item.workflowKey(index), item is MessageContentUi.Tool, preceding.toList())
    }
}

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
internal fun WorkflowNarrationContent(
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
    admission: WorkflowVisualAdmission? = null,
    content: @Composable (WorkflowNarration) -> Unit,
) {
    run {
        val texts = remember(sources) { sources.map { it.text } }
        val projection = rememberMarkdownProjection(texts, streaming = running, windows = sources.map { it.window })
        val baselineOnly = !expanded || stopped
        val blocked = workflowBlockedTextSources(steps, reasoningReady) +
            if (baselineOnly) emptySet() else admission?.blockedText(steps).orEmpty()
        // Reopening is a silent baseline; reasoning handoff never resets this owner.
        val reveal = key(expanded, stopped) {
            val baseline = remember(admission, revealResetEpoch) { admission?.initialBaseline(sources, baselineOnly) }
            rememberMarkdownReveal(sources, projection, streaming = running && !paused,
                terminal = !running && projection?.sources == texts, resetEpoch = revealResetEpoch, onVisibleAdvance = onVisibleAdvance,
                enabled = expanded && !stopped && !paused, allowBaselinePrefix = false, blockedSources = blocked, live = running,
                initialBaseline = baseline)
        }
        val plan = remember(steps) { workflowAdmissionPlan(steps) }
        SideEffect { if (baselineOnly) admission?.admit(plan.map { it.key }) }
        val currentPlan by rememberUpdatedState(plan)
        val currentReveal by rememberUpdatedState(reveal)
        LaunchedEffect(admission, baselineOnly, paused, revealResetEpoch) {
            val gate = admission ?: return@LaunchedEffect
            if (baselineOnly || paused) return@LaunchedEffect
            snapshotFlow {
                currentPlan.firstOrNull { !gate.contains(it.key) }?.takeIf { entry ->
                    !entry.tool || entry.precedingText.all { currentReveal.ready(it.id, it.text, it.window) }
                }?.key
            }.collectLatest { candidate ->
                if (candidate != null) {
                    val entry = currentPlan.firstOrNull { it.key == candidate } ?: return@collectLatest
                    if (entry.tool && entry.precedingText.isNotEmpty()) {
                        withFrameNanos { }
                        withFrameNanos { }
                    }
                    val current = currentPlan.firstOrNull { !gate.contains(it.key) }
                    if (current?.key == candidate &&
                        (!current.tool || current.precedingText.all { currentReveal.ready(it.id, it.text, it.window) })) {
                        gate.admit(listOf(candidate))
                    }
                }
            }
        }
        val entryVisible = remember(admission, baselineOnly) {
            { key: String -> baselineOnly || admission?.contains(key) != false }
        }
        content(remember(projection, reveal, entryVisible) { WorkflowNarration(projection, reveal, entryVisible) })
    }
}
