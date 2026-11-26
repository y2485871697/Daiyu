package app.daiyu.android.domain.runtime

import app.daiyu.android.domain.*

/** Local input is frozen before the same admitted request connects; it is not billed usage. */
data class RequestInputCheckpoint(val request: RequestCheckpoint, val localTokens: Long,
    val billedTokens: Long? = null, val historyTokens: Long = localTokens) {
    init { require(localTokens >= 0 && historyTokens >= 0 && (billedTokens == null || billedTokens >= 0)) }
}
data class ContextCapacityCheckpoint(val id: String, val created: Long, val estimatedTokens: Long,
    val windowTokens: Long, val budgetTokens: Long, val segment: Int) {
    init {
        require(id.isNotBlank() && id.length <= 200 && created >= 0 && segment >= 0)
        require(budgetTokens > 0 && windowTokens >= budgetTokens && estimatedTokens > budgetTokens)
    }
}

/** Accepted meter facts; provider counts and local surface prices never enter billing totals. */
data class ContextMeterCheckpoint(
    val anchorHeader: String? = null,
    val anchorEstimate: Long? = null,
    val anchorSurface: Long? = null,
    val anchorUsageTotal: Long? = null,
    val pressureTokens: Long? = null,
    val sampledSurfaceTokens: Long? = null,
    val surfaceTokens: Long = 0,
    val inputFixedSurfaceTokens: Long? = null,
    val overflowRetries: Int = 0,
    val cloudInputTokens: Long? = null,
    val cloudCompactionAttempted: Boolean = false,
) {
    init {
        require(surfaceTokens >= 0 && overflowRetries >= 0)
        require(listOf(anchorEstimate, anchorSurface, anchorUsageTotal, pressureTokens,
            sampledSurfaceTokens, inputFixedSurfaceTokens, cloudInputTokens).all { it == null || it >= 0 })
    }
}

data class RunContextCheckpoint(
    val input: RequestInputCheckpoint? = null,
    val calibration: RequestInputCheckpoint? = null,
    val pressure: RequestCheckpoint? = null,
    val summaries: List<RequestCheckpoint> = emptyList(),
    val compactions: Int = 0,
    val capacity: ContextCapacityCheckpoint? = null,
    val meter: ContextMeterCheckpoint? = null,
)

interface RunSummaryRequests {
    fun create(): RequestCheckpoint
    fun admit(request: RequestCheckpoint)
    fun observe(request: RequestCheckpoint, usage: ModelEvent.Usage)
}

/** Runs only at a completed protocol/tool boundary, on the original checkpoint writer. */
interface RunContextRuntime {
    val systemPrompt: String get() = ""
    val automatic: Boolean get() = true
    /** Read saved compression options once on the runner worker; keep this boundary snapshot fixed. */
    fun compressionSnapshot(): RunContextRuntime = this
    val canCompact: Boolean get() = true
    val windowTokens: Long
    val sendBudgetTokens: Long get() = windowTokens
    val initialMeter: ContextMeterCheckpoint? get() = null
    val pressureThresholdTokens: Long? get() =
        app.daiyu.android.domain.context.CodexContextPolicy.autoCompactLimit(windowTokens)
    fun headerIdentity(tools: List<ToolDefinition>): String? = null
    fun fixedSurfaceTokens(history: List<Message>): Long = localTokens(history, emptyList())
    fun localTokens(history: List<Message>, tools: List<ToolDefinition>): Long
    fun compact(history: List<Message>, billedTokens: Long?, localTokens: Long,
        request: RequestCheckpoint, observe: (ModelEvent.Usage) -> Unit,
        admit: () -> Unit, cancelled: () -> Boolean): List<Message>?
    fun compactRequests(history: List<Message>, billedTokens: Long?, localTokens: Long,
        requests: RunSummaryRequests, cancelled: () -> Boolean): List<Message>? {
        val request = requests.create()
        return compact(history, billedTokens, localTokens, request, { requests.observe(request, it) },
            { requests.admit(request) }, cancelled)
    }
    fun compactOverflowRequests(history: List<Message>, billedTokens: Long?, localTokens: Long,
        requests: RunSummaryRequests, cancelled: () -> Boolean): List<Message>? =
        compactRequests(history, billedTokens, localTokens, requests, cancelled)
    fun automaticCompactionDecision(input: Long, threshold: Long, attempted: Boolean) {}
    fun beginCompaction(boundary: Message) {}
    fun compactionApplied() {}
    fun finishedCompaction() {}
    fun failed(reason: String) {}
    fun cancel() {}
}
