package app.daiyu.android.domain.usage

import app.daiyu.android.domain.ModelEvent
import app.daiyu.android.domain.runtime.RequestCheckpoint

sealed interface UsageOrigin {
    data object Main : UsageOrigin
    data class Child(val taskId: String, val profileId: String, val taskGroupId: String) : UsageOrigin {
        init { require(listOf(taskId, profileId, taskGroupId).all { it.isNotBlank() && it.length <= 200 }) }
    }
}

/** A provider observation tied to host identities; neither an estimate nor a billing receipt. */
data class AttributedRequestUsage(
    val conversationId: String,
    val parentRunId: String,
    val parentMessageId: String,
    val runId: String,
    val messageId: String,
    val origin: UsageOrigin,
    val request: RequestCheckpoint,
    val snapshot: ModelEvent.Usage,
) {
    init {
        require(listOf(conversationId, parentRunId, parentMessageId, runId, messageId)
            .all { it.isNotBlank() && it.length <= 200 })
        when (origin) {
            UsageOrigin.Main -> require(parentRunId == runId && parentMessageId == messageId)
            is UsageOrigin.Child -> require(origin.taskId == runId)
        }
    }
}

data class AttributedUsageSummary(
    val main: RecordedUsageSummary,
    val child: RecordedUsageSummary,
    val combined: RecordedUsageSummary,
)

/** Input contains the latest snapshot of each distinct request, not a stream of deltas. */
fun aggregateAttributedUsage(records: List<AttributedRequestUsage>): AttributedUsageSummary {
    val identities = records.map { listOf(it.conversationId, it.runId, it.request.owner, it.request.requestId) }
    require(identities.distinct().size == identities.size) { "duplicate_attributed_request" }
    return AttributedUsageSummary(
        aggregateRecordedUsage(records.asSequence().filter { it.origin == UsageOrigin.Main }.map { it.snapshot }),
        aggregateRecordedUsage(records.asSequence().filter { it.origin is UsageOrigin.Child }.map { it.snapshot }),
        aggregateRecordedUsage(records.asSequence().map { it.snapshot }),
    )
}
