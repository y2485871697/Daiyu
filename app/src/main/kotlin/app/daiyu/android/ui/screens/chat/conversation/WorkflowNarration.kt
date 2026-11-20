package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.staticCompositionLocalOf
import app.daiyu.android.domain.ReportedReasoning

internal class WorkflowNarration(val projection: MarkdownProjection?, val reveal: MarkdownReveal,
    val entryVisible: (String) -> Boolean = { true })

/** Kept outside lazy items: an already admitted tool never disappears after remount. */
internal class WorkflowVisualAdmissions(initialOwners: Set<String>) {
    private val scopes = initialOwners.associateWith { WorkflowVisualScope(true) }.toMutableMap()
    fun scope(owner: String, baseline: Boolean, live: Boolean = true) =
        scopes.getOrPut(owner) { WorkflowVisualScope(baseline || !live) }
    fun retain(owners: Set<String>) { scopes.keys.retainAll(owners) }
}

internal val LocalReasoningPaused = staticCompositionLocalOf { false }

internal class WorkflowVisualScope(private val baseline: Boolean) {
    private var replyControl: ReplyPresentationControl? = null
    fun retainReplyControl(control: ReplyPresentationControl) { replyControl = control }
    fun replyPlayback(current: ReplyPlayback?): ReplyPlayback? =
        current ?: replyControl?.let { ReplyPlayback(it, runtimePaused = false) }
    private val reasoningClaims = mutableSetOf<Pair<String, String>>()
    private val handoffs = mutableMapOf<String, ReasoningHandoff>()
    fun claimReasoning(owner: String, itemId: String): Boolean = reasoningClaims.add(owner to itemId) && !baseline
    fun handoff(owner: String, initial: List<ReportedReasoning>) = handoffs.getOrPut(owner) {
        ReasoningHandoff(if (baseline) initial else emptyList())
    }
    private val bodyOwners = mutableSetOf<String>()
    fun bodyBaseline(key: String, sources: List<MarkdownTypingSource>): List<MarkdownTypingSource> =
        if (bodyOwners.add(key) && !baseline) emptyList() else sources.toList()
    private val owners = mutableMapOf<String, WorkflowVisualAdmission>()
    private val motions = mutableMapOf<String, WorkflowNodeMotions>()
    fun motions(key: String, steps: List<MessageContentUi>, running: Boolean) = motions.getOrPut(key) {
        WorkflowNodeMotions(if (baseline || !running) steps else emptyList())
    }
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
        val playback = LocalReplyPlayback.current
        val displayPaused = paused || playback?.paused == true
        val texts = remember(sources) { sources.map { it.text } }
        val projection = rememberMarkdownProjection(texts, streaming = running, windows = sources.map { it.window })
        val baselineOnly = !expanded || stopped || playback?.control?.finished == true
        SideEffect { if (baselineOnly && admission != null) playback?.control?.skipLane(admission) }
        val blocked = workflowBlockedTextSources(steps, reasoningReady) +
            if (baselineOnly) emptySet() else admission?.blockedText(steps).orEmpty()
        // Reopening is a silent baseline; reasoning handoff never resets this owner.
        val reveal = key(expanded, stopped) {
            val baseline = remember(admission, revealResetEpoch) { admission?.initialBaseline(sources, baselineOnly) }
            rememberMarkdownReveal(sources, projection, streaming = running && !displayPaused,
                terminal = !running && projection?.sources == texts, resetEpoch = revealResetEpoch, onVisibleAdvance = onVisibleAdvance,
                enabled = expanded && !stopped && !paused, allowBaselinePrefix = false, blockedSources = blocked, live = running,
                initialBaseline = baseline, presentationLane = admission.takeUnless { baselineOnly }, awaitPaintAdmission = true)
        }
        val keys = remember(steps) { steps.mapIndexed { index, item -> item.workflowKey(index) } }
        SideEffect {
            if (!paused && playback?.paused != true) admission?.admit(keys)
        }
        val entryVisible = remember(admission, baselineOnly) {
            { key: String -> baselineOnly || admission?.contains(key) != false }
        }
        CompositionLocalProvider(LocalReasoningPaused provides paused) {
            content(remember(projection, reveal, entryVisible) { WorkflowNarration(projection, reveal, entryVisible) })
        }
    }
}
