package app.daiyu.android.storage

import app.daiyu.android.context.CheckpointPreservation
import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.*
import org.junit.Assert.*
import org.junit.Test

class RuntimeCheckpointReuseTest {
    private val request = RequestCheckpoint("main", "attempt", "owner", RequestOperation.NEXT)
    private val summary = RequestCheckpoint("summary", "summary-attempt", "preparation", RequestOperation.NEXT)
    private fun checkpoint(revision: Long = 1) = RunCheckpoint(
        Message(MessageId("m"), ConversationId("c"), RunId("r"), "assistant", emptyList(), RunStatus.RUNNING),
        emptyList(), ExecutionPhase.STREAMING, owner = "owner", revision = revision,
        usageRequests = listOf(request),
        usage = listOf(RequestUsageCheckpoint(summary, ModelEvent.Usage(20, 5, 0), UsageRequestSource.SUMMARY)),
        context = RunContextCheckpoint(summaries = listOf(summary), compactions = 1),
    )

    @Test fun aThousandCommittedAppendsReuseOnlyTheProvenPreviousFacts() {
        val cache = RuntimeCheckpointReuse()
        var previous = checkpoint()
        var encoded = "数🙂".repeat(120_000)
        cache.committed(previous, "process", encoded)
        var misses = 0
        repeat(1_000) { index ->
            assertSame(encoded, cache.expectedPayload("m"))
            val facts = cache.preservation(RuntimeCheckpointStamp.of(previous, "process"), true)
            if (facts == null) misses++
            val next = previous.copy(revision = previous.revision + 1,
                message = previous.message.copy(blocks = listOf(MessageBlock.Text("text", "append-$index"))))
            facts?.preserve(next)
            encoded = encoded + index.toString()
            cache.committed(next, "process", encoded)
            previous = next
        }
        assertEquals("Every byte-proven commit must reuse the previous facts", 0, misses)
        assertNull(cache.preservation(RuntimeCheckpointStamp.of(previous, "process"), false))
    }

    @Test fun everyIdentityAndMetadataFieldMustMatchInsideTheTransaction() {
        val cache = RuntimeCheckpointReuse(); val value = checkpoint()
        cache.committed(value, "process", "encoded")
        val stamp = RuntimeCheckpointStamp.of(value, "process")
        for (different in listOf(stamp.copy(message = "other"), stamp.copy(conversation = "other"),
            stamp.copy(run = "other"), stamp.copy(owner = "other"), stamp.copy(process = "other"),
            stamp.copy(revision = 2), stamp.copy(phase = ExecutionPhase.BOUNDARY.name))) {
            assertNull(cache.preservation(different, true))
        }
        assertEquals("", cache.expectedPayload("other"))
        assertNotNull(cache.preservation(stamp, true))
        assertNull(cache.preservation(stamp, false))
    }

    @Test fun clearTerminalOversizeAndNewLeaseCannotReuseOldFacts() {
        val value = checkpoint(); val stamp = RuntimeCheckpointStamp.of(value, "process")
        val cache = RuntimeCheckpointReuse(maxChars = 3)
        cache.committed(value, "process", "123"); assertNotNull(cache.preservation(stamp, true))
        cache.committed(value, "process", "1234"); assertNull(cache.preservation(stamp, true))
        assertEquals("", cache.expectedPayload("m"))
        cache.committed(value, "process", "123"); cache.clear(); assertNull(cache.preservation(stamp, true))
        assertNull(RuntimeCheckpointReuse().preservation(stamp, true))
        cache.committed(value.copy(phase = ExecutionPhase.TERMINAL), "process", "123")
        assertEquals("", cache.expectedPayload("m"))
    }

    @Test fun preservationKeepsAllFiveOriginalFailureConditions() {
        val prior = checkpoint(); val facts = CheckpointPreservation.capture(prior)
        fun rejected(code: String, next: RunCheckpoint) {
            val failure = runCatching { facts.preserve(next) }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertEquals(code, failure!!.message)
        }
        facts.preserve(prior.copy(revision = 2))
        rejected("main_request_coverage_dropped", prior.copy(usageRequests = emptyList()))
        rejected("summary_usage_changed_after_handoff", prior.copy(usage = emptyList()))
        rejected("runtime_compaction_dropped", prior.copy(context = prior.context!!.copy(compactions = 0)))
        rejected("runtime_summary_admission_dropped", prior.copy(context = prior.context!!.copy(summaries = emptyList())))
        val unadmitted = RequestCheckpoint("unadmitted", "a", "owner", RequestOperation.NEXT)
        rejected("runtime_summary_without_admission", prior.copy(usage = prior.usage +
            RequestUsageCheckpoint(unadmitted, ModelEvent.Usage(3, 2, 0), UsageRequestSource.SUMMARY)))
    }

    @Test fun capturedFactsDoNotAliasCallerCollections() {
        val base = checkpoint()
        val requests = base.usageRequests.toMutableList(); val usage = base.usage.toMutableList()
        val summaries = base.context!!.summaries.toMutableList()
        val mutable = base.copy(usageRequests = requests, usage = usage,
            context = base.context.copy(summaries = summaries))
        val facts = CheckpointPreservation.capture(mutable)
        requests.clear(); usage.clear(); summaries.clear()
        val rejected = runCatching { facts.preserve(mutable) }.exceptionOrNull()
        assertEquals("main_request_coverage_dropped", rejected!!.message)
        assertEquals("summary_usage_changed_after_handoff",
            runCatching { facts.preserve(base.copy(usage = usage)) }.exceptionOrNull()!!.message)
        assertEquals("runtime_summary_admission_dropped",
            runCatching { facts.preserve(base.copy(context = base.context.copy(summaries = summaries))) }
                .exceptionOrNull()!!.message)
        facts.preserve(base)
    }

    @Test fun mutationBetweenEncodingAndCachePublicationCannotChangeTheFrozenFacts() {
        val base = checkpoint()
        val requests = base.usageRequests.toMutableList()
        val usage = base.usage.toMutableList()
        val summaries = base.context!!.summaries.toMutableList()
        val input = base.copy(usageRequests = requests, usage = usage,
            context = base.context.copy(summaries = summaries))
        val frozen = CheckpointPreservation.freeze(input)
        requests.clear(); usage.clear(); summaries.clear()
        assertEquals(base, frozen)
        val cache = RuntimeCheckpointReuse()
        cache.committed(frozen, "process", "encoded-from-frozen")
        val facts = cache.preservation(RuntimeCheckpointStamp.of(frozen, "process"), true)!!
        facts.preserve(base)
        assertEquals("summary_usage_changed_after_handoff",
            runCatching { facts.preserve(base.copy(usage = emptyList())) }.exceptionOrNull()!!.message)
        assertEquals("runtime_summary_admission_dropped",
            runCatching { facts.preserve(base.copy(context = base.context.copy(summaries = emptyList()))) }
                .exceptionOrNull()!!.message)
    }

    @Test fun currentOwnerSummaryUsageMayGrowOnlyAfterAdmission() {
        val base = checkpoint(); val own = RequestCheckpoint("own-summary", "s", "owner", RequestOperation.NEXT)
        val next = base.copy(context = base.context!!.copy(summaries = base.context.summaries + own),
            usage = base.usage + RequestUsageCheckpoint(own, ModelEvent.Usage(8, 4, 0), UsageRequestSource.SUMMARY))
        CheckpointPreservation.capture(base).preserve(next)
        CheckpointPreservation.capture(next).preserve(next.copy(usage = base.usage))
    }
}
