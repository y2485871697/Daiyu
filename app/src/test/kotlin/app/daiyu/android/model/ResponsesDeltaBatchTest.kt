package app.daiyu.android.model

import app.daiyu.android.domain.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.StringReader

class ResponsesDeltaBatchTest {
    private fun wire(type: String, seq: Int, value: JSONObject = JSONObject()) =
        "data: ${value.put("type", type).put("sequence_number", seq)}\n\n"
    private fun created() = wire("response.created", 0, JSONObject().put("response", JSONObject().put("id", "resp_batch")))
    private fun delta(seq: Int, text: String = "x") = wire("response.output_text.delta", seq,
        JSONObject().put("item_id", "text").put("content_index", 0).put("delta", text))
    private fun read(state: ResponsesStream.State, source: String, sink: (ModelEventBatch) -> ModelDeliveryAck) =
        state.read(StringReader(source).buffered(), { false }, acknowledgedSink = sink)

    @Test fun fiveHundredTwelveSingleCharacterFramesHaveOneDeltaCommit() {
        val state = ResponsesStream.State(bufferDeltas = true, nanoTime = { 0 })
        val batches = mutableListOf<ModelEventBatch>()
        assertThrows(ModelRequestException::class.java) { read(state, created() + (1..512).joinToString("") { delta(it) }) {
            batches.add(it); ModelDeliveryAck.ACCEPTED
        } }
        assertEquals(listOf(0L, 512L), batches.map { it.checkpoint.sequence })
        assertEquals("x".repeat(512), (batches.last().events.single() as ModelEvent.TextFinal).text)
        assertEquals(512L, state.acknowledgedSequence)
    }
    @Test fun aRejectedWholeBatchRestoresTheLastCommittedDecoder() {
        val state = ResponsesStream.State(bufferDeltas = true, nanoTime = { 0 })
        assertThrows(ModelDeliveryRejectedException::class.java) {
            read(state, created() + delta(1, "a") + delta(2, "b")) {
                if (it.checkpoint.sequence == 0L) ModelDeliveryAck.ACCEPTED else ModelDeliveryAck.REJECTED
            }
        }
        assertEquals(0L, state.acknowledgedSequence); assertTrue(state.canResume)
        val batches = mutableListOf<ModelEventBatch>()
        assertThrows(ModelRequestException::class.java) { read(state, delta(1, "a") + delta(2, "b")) {
            batches.add(it); ModelDeliveryAck.ACCEPTED
        } }
        assertEquals("ab", (batches.single().events.single() as ModelEvent.TextFinal).text)
    }
    @Test fun anUnacknowledgedDuplicateSequenceIsNotAppendedTwice() {
        val state = ResponsesStream.State(bufferDeltas = true, nanoTime = { 0 }); val batches = mutableListOf<ModelEventBatch>()
        assertThrows(ModelRequestException::class.java) { read(state, created() + delta(1, "a") + delta(1, "a") + delta(2, "b")) {
            batches.add(it); ModelDeliveryAck.ACCEPTED
        } }
        assertEquals("ab", (batches.last().events.single() as ModelEvent.TextFinal).text)
    }
    @Test fun elapsedTimeAndDoneAreFlushBoundaries() {
        var now = 0L
        val state = ResponsesStream.State(bufferDeltas = true, nanoTime = { now.also { now += 250_000_000L } })
        val batches = mutableListOf<ModelEventBatch>()
        assertThrows(ModelRequestException::class.java) { read(state, created() + delta(1) + delta(2) + "data: [DONE]\n\n") {
            batches.add(it); ModelDeliveryAck.ACCEPTED
        } }
        assertEquals(listOf(0L, 1L, 2L), batches.map { it.checkpoint.sequence })
    }
    @Test fun uncertainCommitPoisonsAndIsNeverRetriedByTheErrorFlush() {
        val state = ResponsesStream.State(bufferDeltas = true, nanoTime = { 0 }); val failure = IllegalStateException("disk")
        var writes = 0
        val actual = assertThrows(IllegalStateException::class.java) { read(state, created() + delta(1)) {
            writes++; if (it.checkpoint.sequence > 0) throw failure else ModelDeliveryAck.ACCEPTED
        } }
        assertSame(failure, actual); assertEquals(2, writes); assertFalse(state.canResume)
    }
    @Test fun cancellationRollsBackBufferedDeltasWithoutLateDelivery() {
        val state = ResponsesStream.State(bufferDeltas = true, nanoTime = { 0 }); var calls = 0; var cancelled = false
        val first = created() + delta(1)
        val reader = object : java.io.BufferedReader(StringReader(first + delta(2))) {
            var characters = 0
            override fun read(): Int = super.read().also { if (++characters > first.length) cancelled = true }
        }
        assertThrows(InterruptedException::class.java) { state.read(reader, { cancelled }, acknowledgedSink = {
            calls++; ModelDeliveryAck.ACCEPTED
        }) }
        assertEquals(1, calls); assertEquals(0L, state.acknowledgedSequence)
    }
}
