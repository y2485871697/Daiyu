package app.daiyu.android.domain.usage

import app.daiyu.android.domain.ModelEvent

/** A subtotal of reported fields, not an estimate for unknown or unrecorded requests. */
data class ReportedTokenSubtotal(
    val reportedTokens: Long?,
    val reportedRequestCount: Long,
    val unknownRequestCount: Long,
)

/** Only requests with a persisted Usage snapshot are counted. Cache reads are not extra input. */
data class RecordedUsageSummary(
    val requestSnapshotCount: Long,
    val input: ReportedTokenSubtotal,
    val output: ReportedTokenSubtotal,
    val cached: ReportedTokenSubtotal,
    val creation: ReportedTokenSubtotal = ReportedTokenSubtotal(null, 0, requestSnapshotCount),
)

/** Each item must already be the latest snapshot of a distinct host request.
 * No time, provider, model, billing or unreported-request identity is inferred here. */
fun aggregateRecordedUsage(snapshots: Sequence<ModelEvent.Usage>): RecordedUsageSummary {
    val input = TokenSubtotal(); val output = TokenSubtotal(); val cached = TokenSubtotal(); val creation = TokenSubtotal()
    var requests = 0L
    snapshots.forEach {
        requests = Math.addExact(requests, 1)
        input.add(it.inputTokens); output.add(it.outputTokens); cached.add(it.cachedTokens); creation.add(it.cacheCreationTokens)
    }
    return RecordedUsageSummary(requests, input.result(), output.result(), cached.result(), creation.result())
}

private class TokenSubtotal {
    private var tokens = 0L
    private var reported = 0L
    private var unknown = 0L
    fun add(value: Long?) {
        if (value == null) unknown = Math.addExact(unknown, 1)
        else { tokens = Math.addExact(tokens, value); reported = Math.addExact(reported, 1) }
    }
    fun result() = ReportedTokenSubtotal(tokens.takeIf { reported != 0L }, reported, unknown)
}
