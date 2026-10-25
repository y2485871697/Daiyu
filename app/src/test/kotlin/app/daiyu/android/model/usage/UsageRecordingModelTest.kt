package app.daiyu.android.model.usage

import app.daiyu.android.domain.*
import app.daiyu.android.storage.UsageStatisticsStore
import org.junit.Assert.*
import org.junit.Test

class UsageRecordingModelTest {
    private data class Saved(val id: String, val owner: String?, val at: Long, val fields: UsageStatisticsStore.Fields)
    private val history = listOf(Message(MessageId("user"), ConversationId("conversation"), RunId("run"), "user",
        emptyList(), RunStatus.COMPLETED))
    private class Transport : CheckpointedResumableModelSession {
        override val canResume = true
        var nextEvents = emptyList<ModelEvent>()
        var resumed = emptyList<ModelEvent>()
        var cancelled = false
        var disconnected = false
        var ack: ModelDeliveryAck? = null
        override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) { nextEvents.forEach(sink) }
        override fun resume(sink: (ModelEvent) -> Unit) { resumed.forEach(sink) }
        override fun cancel() { cancelled = true }
        override fun disconnectTransport() { disconnected = true }
        override fun nextAcknowledged(outputs: List<ToolOutput>, sink: (ModelEventBatch) -> ModelDeliveryAck) {
            ack = sink(ModelEventBatch(nextEvents, TransportCheckpoint("resp_one", 1, "{}")))
        }
        override fun resumeAcknowledged(sink: (ModelEventBatch) -> ModelDeliveryAck) {
            ack = sink(ModelEventBatch(resumed, TransportCheckpoint("resp_one", 2, "{}")))
        }
    }
    @Test fun nextUsesNewIdentityResumeRetainsIdentityAndPartialNullDoesNotErase() {
        val wire = Transport(); val saved = mutableListOf<Saved>(); var ids = 0; var clock = 100L
        val model = UsageRecordingModel(ModelPort { wire }, { id, owner, at, fields -> saved += Saved(id, owner, at, fields) },
            clock = { clock++ }, requestId = { "request-${++ids}" })
        val session = model.open(history) as CheckpointedResumableModelSession
        wire.nextEvents = listOf(ModelEvent.Usage(100, 20, 40, 10), ModelEvent.Usage(null, 0, null, null))
        session.next(emptyList()) { }
        assertEquals(2, saved.size)
        assertEquals(UsageStatisticsStore.Fields(100, 0, 40, 10), saved.last().fields)
        assertTrue(saved.all { it.id == "request-1" && it.at == 100L && it.owner == "conversation" })
        wire.resumed = listOf(ModelEvent.Usage(90, 7, 0, 0))
        session.resume { }
        assertEquals("request-1", saved.last().id); assertEquals(100L, saved.last().at)
        wire.nextEvents = listOf(ModelEvent.Usage(3, 2, 0, 0))
        session.next(emptyList()) { }
        assertEquals("request-2", saved.last().id); assertEquals(101L, saved.last().at)
        session.disconnectTransport(); session.cancel()
        assertTrue(wire.cancelled); assertTrue(wire.disconnected)
    }
    @Test fun batchWritesBeforeAckAndRejectedAckIsNotPromoted() {
        val wire = Transport(); val saved = mutableListOf<Saved>()
        val session = UsageRecordingModel(ModelPort { wire }, { id, owner, at, fields -> saved += Saved(id, owner, at, fields) })
            .open(history) as CheckpointedResumableModelSession
        wire.nextEvents = listOf(ModelEvent.Usage(10, 2, 0, 0))
        session.nextAcknowledged(emptyList()) {
            assertEquals(1, saved.size)
            ModelDeliveryAck.REJECTED
        }
        assertEquals(ModelDeliveryAck.REJECTED, wire.ack)
        val first = saved.single().id
        wire.resumed = listOf(ModelEvent.Usage(null, 3, null, null))
        session.resumeAcknowledged { ModelDeliveryAck.ACCEPTED }
        assertEquals(first, saved.last().id); assertEquals(3L, saved.last().fields.output)
        assertEquals(ModelDeliveryAck.ACCEPTED, wire.ack)
    }
    @Test fun accountingFailureRetriesSameRequestAndNeverHidesSinkFailure() {
        val wire = Transport(); var writes = 0; var failures = 0; val ids = mutableListOf<String>()
        val session = UsageRecordingModel(ModelPort { wire }, { id, _, _, _ ->
            ids += id; if (++writes == 1) throw IllegalStateException("database failed")
        }, onFailure = { failures++ }).open(history)
        wire.nextEvents = listOf(ModelEvent.Usage(10, 2, 0, 0))
        val original = IllegalArgumentException("sink")
        try { session.next(emptyList()) { throw original }; fail("sink exception swallowed") }
        catch (failure: IllegalArgumentException) { assertSame(original, failure) }
        assertEquals(1, failures); assertEquals(2, writes); assertEquals(1, ids.toSet().size)
    }
    @Test fun normalizationPreservesUnknownCacheAndFiltersImplausibleInputWithoutDroppingOutput() {
        val zero = UsageStatisticsStore.normalized(UsageStatisticsStore.Fields(0, 7, null, null), 100)
        assertEquals(UsageStatisticsStore.Fields(0, 7, null, null), zero)
        val invalid = UsageStatisticsStore.normalized(UsageStatisticsStore.Fields(1000, 7, 20, null), 100)
        assertEquals(UsageStatisticsStore.Fields(0, 7, 0, null), invalid)
        val lowered = UsageStatisticsStore.Fields(100, 4, 20, 5).merge(UsageStatisticsStore.Fields(0, null, null, null))
        assertEquals(0L, lowered.input); assertEquals(4L, lowered.output); assertEquals(20L, lowered.cached)
    }
}
