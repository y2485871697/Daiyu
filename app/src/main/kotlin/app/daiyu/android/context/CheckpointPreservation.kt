package app.daiyu.android.context

import app.daiyu.android.domain.runtime.*

/** Only the facts which a later main checkpoint must preserve, without retaining its message graph. */
internal class CheckpointPreservation private constructor(
    private val requests: List<Pair<String, String>>,
    private val summaryUsage: List<RequestUsageCheckpoint>,
    private val compactions: Int,
    private val summaries: List<RequestCheckpoint>,
) {
    fun preserve(next: RunCheckpoint) {
        check(requests.all { old -> next.usageRequests.any {
            it.owner == old.first && it.requestId == old.second
        } }) { "main_request_coverage_dropped" }
        check(summaryUsage == next.usage.filter {
            it.source == UsageRequestSource.SUMMARY && it.request.owner != next.owner
        }) { "summary_usage_changed_after_handoff" }
        check((next.context?.compactions ?: 0) >= compactions) { "runtime_compaction_dropped" }
        check(summaries.all { it in next.context?.summaries.orEmpty() }) { "runtime_summary_admission_dropped" }
        next.usage.filter { it.source == UsageRequestSource.SUMMARY && it.request.owner == next.owner }
            .forEach { check(it.request in next.context?.summaries.orEmpty()) { "runtime_summary_without_admission" } }
    }

    companion object {
        /** Encoding and the post-commit cache must use the same preservation snapshot. */
        fun freeze(value: RunCheckpoint) = value.copy(
            usageRequests = value.usageRequests.toList(),
            usage = value.usage.toList(),
            context = value.context?.let { it.copy(summaries = it.summaries.toList()) },
        )

        fun capture(prior: RunCheckpoint) = CheckpointPreservation(
            prior.usageRequests.map { it.owner to it.requestId },
            prior.usage.filter { it.source == UsageRequestSource.SUMMARY && it.request.owner != prior.owner },
            prior.context?.compactions ?: 0,
            prior.context?.summaries.orEmpty().toList(),
        )
    }
}
