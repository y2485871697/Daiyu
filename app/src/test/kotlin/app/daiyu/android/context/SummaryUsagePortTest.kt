package app.daiyu.android.context

import app.daiyu.android.domain.ModelEvent
import app.daiyu.android.domain.runtime.RequestCheckpoint
import app.daiyu.android.domain.runtime.RequestOperation
import app.daiyu.android.domain.runtime.RequestUsageCheckpoint
import org.junit.Assert.*
import org.junit.Test

class SummaryUsagePortTest {
    private val parent = ParentSummaryUsage("conversation", "admitted-parent-run", "admitted-parent-message",
        RequestCheckpoint("host-summary-request", "host-summary-attempt", "host-request-owner", RequestOperation.NEXT))

    @Test fun usesHostRequestAndAttemptAndReplacesWholeSnapshotWithoutAdding() {
        val records = mutableListOf<Pair<ParentSummaryUsage, RequestUsageCheckpoint>>()
        val port = BoundSummaryUsagePort(parent) { owner, usage -> records.add(owner to usage) }
        port.claim()
        port.observe(ModelEvent.Usage(100, 20, 5)); port.observe(ModelEvent.Usage(100, 20, 5))
        port.observe(ModelEvent.Usage(null, 0, 0))
        assertEquals(3, records.size) // Durable sink validates even identical replays.
        assertEquals(parent, records.last().first)
        assertEquals(parent.request, records.last().second.request)
        assertEquals(app.daiyu.android.domain.runtime.UsageRequestSource.SUMMARY, records.last().second.source)
        assertNull(records.last().second.snapshot.inputTokens)
        assertEquals(0L, records.last().second.snapshot.outputTokens)
        assertEquals(0L, records.last().second.snapshot.cachedTokens)
        assertThrows(IllegalStateException::class.java) { port.claim() }
    }

    @Test fun failedCommitIsNotAcknowledgedAndNothingInventsUnknownUsage() {
        var calls = 0
        val port = BoundSummaryUsagePort(parent) { _, _ -> if (++calls == 1) error("durable-write-failed") }
        assertEquals(0, calls)
        assertThrows(IllegalStateException::class.java) { port.observe(ModelEvent.Usage(null, null)) }
        port.claim()
        assertThrows(IllegalStateException::class.java) { port.observe(ModelEvent.Usage(null, null)) }
        assertEquals(1, calls)
        // Replaying the observation is not reopening or retrying a provider POST.
        port.observe(ModelEvent.Usage(null, null)); port.observe(ModelEvent.Usage(null, null))
        assertEquals(3, calls)
    }

    @Test fun openOperationIsNotAProviderRequestUsageIdentity() {
        assertThrows(IllegalArgumentException::class.java) {
            parent.copy(request = parent.request.copy(operation = RequestOperation.OPEN))
        }
    }
}
