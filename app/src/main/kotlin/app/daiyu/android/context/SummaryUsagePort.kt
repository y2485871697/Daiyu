package app.daiyu.android.context

import app.daiyu.android.domain.ModelEvent
import app.daiyu.android.domain.runtime.RequestCheckpoint
import app.daiyu.android.domain.runtime.RequestOperation
import app.daiyu.android.domain.runtime.RequestUsageCheckpoint

/** Host identities, never reconstructed from the summarizer's synthetic evidence message.
 * The Controller must admit this request/attempt durably under its live parent ownership BEFORE
 * opening a transport. No database or additional ledger is introduced by this port. */
data class ParentSummaryUsage(
    val conversationId: String,
    val parentRunId: String,
    val parentMessageId: String,
    val request: RequestCheckpoint,
) {
    init {
        require(listOf(conversationId, parentRunId, parentMessageId).all { it.isNotBlank() && it.length <= 200 })
        require(request.operation != RequestOperation.OPEN) { "summary_usage_requires_admitted_request" }
    }
}

/** Implement with the existing authoritative parent's checkpoint CAS, not context_usage.
 * Replace the whole snapshot for (request.owner, requestId), retaining the accepted attemptId.
 * The sink must return only after durable commit; failure must propagate, never retry a POST. */
fun interface SummaryUsagePort {
    fun record(parent: ParentSummaryUsage, usage: RequestUsageCheckpoint)
}

/** One admitted transport/attempt only. Repeated identical observations are idempotent; lower
 * counters and null corrections replace, never add to, the previously accepted snapshot.
 * Every observation, including an identical replay, reaches the durable sink so cancelled or
 * handed-off owners cannot acknowledge a late duplicate. The sink enforces durable CAS and
 * replay/conflict rules. A new attempt requires fresh host admission, never automatic POST. */
class BoundSummaryUsagePort(val parent: ParentSummaryUsage,
    private val beforeClaim: () -> Unit = {}, private val sink: SummaryUsagePort) {
    private var claimed = false

    @Synchronized internal fun claim() {
        check(!claimed) { "summary_usage_request_already_opened" }
        beforeClaim() // Durable admission must succeed before provider.open/next can connect.
        claimed = true
    }

    @Synchronized internal fun observe(snapshot: ModelEvent.Usage) {
        check(claimed) { "summary_usage_request_not_opened" }
        // The durable sink validates even identical late observations after revoke/handoff.
        sink.record(parent, RequestUsageCheckpoint(parent.request, snapshot,
            app.daiyu.android.domain.runtime.UsageRequestSource.SUMMARY))
    }
}
