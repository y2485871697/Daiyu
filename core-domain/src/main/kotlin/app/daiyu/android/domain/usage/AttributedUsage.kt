package app.daiyu.android.domain.usage

import app.daiyu.android.domain.ModelEvent
import app.daiyu.android.domain.runtime.RequestCheckpoint

sealed interface UsageOrigin {
    data object Main : UsageOrigin
    /** A host operation, not a run, assistant message or synthetic summary message. */
    data class ConversationPreparation(val conversationId: String, val operationId: String) : UsageOrigin {
        init { require(listOf(conversationId, operationId).all { it.isNotBlank() && it.length <= 200 }) }
    }
    data class Child(val taskId: String, val profileId: String, val taskGroupId: String) : UsageOrigin {
        init { require(listOf(taskId, profileId, taskGroupId).all { it.isNotBlank() && it.length <= 200 }) }
    }
}

/** A provider observation tied to host identities; neither an estimate nor a billing receipt. */
data class AttributedRequestUsage(
    val conversationId: String,
    val parentRunId: String?,
    val parentMessageId: String?,
    val runId: String?,
    val messageId: String?,
    val origin: UsageOrigin,
    val request: RequestCheckpoint,
    val snapshot: ModelEvent.Usage,
    /** Explicit durable purpose, independent of parent/child attribution and request ID spelling. */
    val source: app.daiyu.android.domain.runtime.UsageRequestSource = app.daiyu.android.domain.runtime.UsageRequestSource.MAIN,
) {
    init {
        require(conversationId.isNotBlank() && conversationId.length <= 200)
        if (origin is UsageOrigin.ConversationPreparation) {
            require(origin.conversationId == conversationId &&
                listOf(parentRunId, parentMessageId, runId, messageId).all { it == null })
            require(source == app.daiyu.android.domain.runtime.UsageRequestSource.MANUAL_SUMMARY)
        } else {
            require(listOf(parentRunId, parentMessageId, runId, messageId)
                .all { it != null && it.isNotBlank() && it.length <= 200 })
            require(source != app.daiyu.android.domain.runtime.UsageRequestSource.MANUAL_SUMMARY)
            when (origin) {
                UsageOrigin.Main -> require(parentRunId == runId && parentMessageId == messageId)
                is UsageOrigin.Child -> require(origin.taskId == runId)
                is UsageOrigin.ConversationPreparation -> error("unreachable")
            }
        }
    }
}

data class AttributedUsageSummary(
    val main: RecordedUsageSummary,
    val child: RecordedUsageSummary,
    val combined: RecordedUsageSummary,
    val conversationPreparation: RecordedUsageSummary = aggregateRecordedUsage(emptySequence()),
)

/** Input contains the latest snapshot of each distinct request, not a stream of deltas. */
fun aggregateAttributedUsage(records: List<AttributedRequestUsage>): AttributedUsageSummary {
    val identities = records.map { listOf(it.conversationId,
        if (it.origin is UsageOrigin.ConversationPreparation) "operation" else "run",
        (it.origin as? UsageOrigin.ConversationPreparation)?.operationId ?: it.runId,
        it.request.owner, it.request.requestId) }
    require(identities.distinct().size == identities.size) { "duplicate_attributed_request" }
    return AttributedUsageSummary(
        aggregateRecordedUsage(records.asSequence().filter { it.origin == UsageOrigin.Main }.map { it.snapshot }),
        aggregateRecordedUsage(records.asSequence().filter { it.origin is UsageOrigin.Child }.map { it.snapshot }),
        aggregateRecordedUsage(records.asSequence().map { it.snapshot }),
        aggregateRecordedUsage(records.asSequence().filter { it.origin is UsageOrigin.ConversationPreparation }.map { it.snapshot }),
    )
}
