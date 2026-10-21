package app.daiyu.android.domain.runtime

import app.daiyu.android.domain.*

/** Local input is frozen before the same admitted request connects; it is not billed usage. */
data class RequestInputCheckpoint(val request: RequestCheckpoint, val localTokens: Long,
    val billedTokens: Long? = null, val historyTokens: Long = localTokens) {
    init { require(localTokens >= 0 && historyTokens >= 0 && (billedTokens == null || billedTokens >= 0)) }
}
data class RunContextCheckpoint(
    val input: RequestInputCheckpoint? = null,
    val calibration: RequestInputCheckpoint? = null,
    val pressure: RequestCheckpoint? = null,
    val summaries: List<RequestCheckpoint> = emptyList(),
    val compactions: Int = 0,
)

/** Runs only at a completed protocol/tool boundary, on the original checkpoint writer. */
interface RunContextRuntime {
    val windowTokens: Long
    fun localTokens(history: List<Message>, tools: List<ToolDefinition>): Long
    fun compact(history: List<Message>, billedTokens: Long?, localTokens: Long,
        request: RequestCheckpoint, observe: (ModelEvent.Usage) -> Unit,
        admit: () -> Unit, cancelled: () -> Boolean): List<Message>?
    fun cancel() {}
}
