package app.daiyu.android.domain.delegation

import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.*

/** One child owns its meter; changing the routed model replaces only its context policy. */
internal class ChildModelContextRuntime(private val initial: Message) : RunContextRuntime {
    private var selection: ChildModelDefinition? = null
    @Volatile private var delegate: RunContextRuntime? = null
    @Volatile private var compacting: RunContextRuntime? = null

    fun prepare(selected: ChildModelDefinition) {
        if (selection?.sameRequest(selected) == true) return
        val next = selected.contextFor(initial)
        selection = selected
        delegate = next
    }
    override val automatic = true
    override val canCompact: Boolean get() = delegate?.canCompact == true
    override val systemPrompt: String get() = delegate?.systemPrompt.orEmpty()
    override val windowTokens: Long get() = delegate?.windowTokens ?: 0L
    override val sendBudgetTokens: Long get() = delegate?.sendBudgetTokens ?: 0L
    override val pressureThresholdTokens: Long? get() = delegate?.pressureThresholdTokens
    override fun headerIdentity(tools: List<ToolDefinition>): String? = delegate?.headerIdentity(tools)
    override fun fixedSurfaceTokens(history: List<Message>): Long = delegate?.fixedSurfaceTokens(history) ?: 0L
    override fun localTokens(history: List<Message>, tools: List<ToolDefinition>): Long = delegate?.localTokens(history, tools) ?: 0L
    override fun beginCompaction(boundary: Message) {
        compacting = delegate
        compacting?.beginCompaction(boundary)
    }
    override fun compact(history: List<Message>, billedTokens: Long?, localTokens: Long,
        request: RequestCheckpoint, observe: (ModelEvent.Usage) -> Unit,
        admit: () -> Unit, cancelled: () -> Boolean): List<Message>? =
        compacting?.compact(history, billedTokens, localTokens, request, observe, admit, cancelled)
    override fun compactRequests(history: List<Message>, billedTokens: Long?, localTokens: Long,
        requests: RunSummaryRequests, cancelled: () -> Boolean): List<Message>? =
        compacting?.compactRequests(history, billedTokens, localTokens, requests, cancelled)
    override fun compactOverflowRequests(history: List<Message>, billedTokens: Long?, localTokens: Long,
        requests: RunSummaryRequests, cancelled: () -> Boolean): List<Message>? =
        compacting?.compactOverflowRequests(history, billedTokens, localTokens, requests, cancelled)
    override fun compactionApplied() { compacting?.compactionApplied() }
    override fun failed(reason: String) { (compacting ?: delegate)?.failed(reason) }
    override fun finishedCompaction() {
        try { compacting?.finishedCompaction() } finally { compacting = null }
    }
    override fun cancel() { (compacting ?: delegate)?.cancel() }
}
