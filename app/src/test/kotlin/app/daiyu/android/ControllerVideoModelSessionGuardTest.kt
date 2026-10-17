package app.daiyu.android

import app.daiyu.android.domain.*
import org.junit.Assert.*
import org.junit.Test

/** Pure JVM coverage of the exact runtime guard used by ControllerMessageVideos.wrap.
 * The production factory intentionally returns ResponsesModel, not injectable ModelSession. */
class ControllerVideoModelSessionGuardTest {
    private val event = ModelEvent.TextDelta("item", "retained")
    private val batch = ModelEventBatch(listOf(event), TransportCheckpoint("resp_video", 7, "decoder-state"))
    private val outputs = listOf(ToolOutput(ToolCallId("call"), "existing result"))

    private enum class Delivery { NEXT, RESUME, NEXT_ACK, RESUME_ACK }

    private class Session(private val event: ModelEvent, private val batch: ModelEventBatch) : CheckpointedResumableModelSession {
        override var canResume = false
        var admissions = 0
        var cancellations = 0
        var disconnects = 0
        var outputs: List<ToolOutput>? = null
        var beforeDelivery: () -> Unit = {}
        var eventSink: ((ModelEvent) -> Unit)? = null
        var batchSink: ((ModelEventBatch) -> ModelDeliveryAck)? = null
        var ack: ModelDeliveryAck? = null
        override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
            this.outputs = outputs; deliver(sink)
        }
        override fun resume(sink: (ModelEvent) -> Unit) = deliver(sink)
        private fun deliver(sink: (ModelEvent) -> Unit) {
            admissions++; eventSink = sink; beforeDelivery(); sink(event)
        }
        override fun nextAcknowledged(outputs: List<ToolOutput>, sink: (ModelEventBatch) -> ModelDeliveryAck) {
            this.outputs = outputs; deliverAcknowledged(sink)
        }
        override fun resumeAcknowledged(sink: (ModelEventBatch) -> ModelDeliveryAck) = deliverAcknowledged(sink)
        private fun deliverAcknowledged(sink: (ModelEventBatch) -> ModelDeliveryAck) {
            admissions++; batchSink = sink; beforeDelivery(); ack = sink(batch)
        }
        override fun disconnectTransport() { disconnects++ }
        override fun cancel() { cancellations++ }
    }

    private fun deliver(session: CheckpointedResumableModelSession, delivery: Delivery,
        onEvent: (ModelEvent) -> Unit = {},
        onBatch: (ModelEventBatch) -> ModelDeliveryAck = { ModelDeliveryAck.ACCEPTED }) {
        when (delivery) {
            Delivery.NEXT -> session.next(outputs, onEvent)
            Delivery.RESUME -> session.resume(onEvent)
            Delivery.NEXT_ACK -> session.nextAcknowledged(outputs, onBatch)
            Delivery.RESUME_ACK -> session.resumeAcknowledged(onBatch)
        }
    }

    @Test fun checkpointedGuardForwardsExactBatchOutputsAndBothAckValues() {
        for (delivery in listOf(Delivery.NEXT_ACK, Delivery.RESUME_ACK)) {
            for (ack in ModelDeliveryAck.entries) {
                val original = Session(event, batch)
                val guarded = guardControllerVideoModelSession(original) { false } as CheckpointedResumableModelSession
                var received = 0
                deliver(guarded, delivery, onBatch = {
                    received++; assertSame(batch, it); assertSame(batch.checkpoint, it.checkpoint); ack
                })
                assertEquals(1, received)
                assertSame(ack, original.ack) // REJECTED must not become a successful Unit/ACCEPTED.
                assertEquals(1, original.admissions)
                if (delivery == Delivery.NEXT_ACK) assertSame(outputs, original.outputs)
                else assertNull(original.outputs)
                assertEquals(0, original.cancellations)
            }
        }
    }

    @Test fun canResumeIsLiveAndDisconnectIsNotFinalCancel() {
        val original = Session(event, batch)
        val guarded = guardControllerVideoModelSession(original) { false } as CheckpointedResumableModelSession
        assertFalse(guarded.canResume)
        original.canResume = true
        assertTrue(guarded.canResume)
        guarded.disconnectTransport()
        assertEquals(1, original.disconnects); assertEquals(0, original.cancellations)
        var deliveries = 0
        guarded.resumeAcknowledged { deliveries++; ModelDeliveryAck.ACCEPTED }
        assertEquals(1, deliveries)
        guarded.cancel()
        assertEquals(1, original.disconnects); assertEquals(1, original.cancellations)
        original.canResume = false
        assertFalse(guarded.canResume)
    }

    @Test fun ordinarySessionIsNotUpgradedAndKeepsOriginalOutputsAndEvent() {
        var nexts = 0; var cancels = 0; var received = 0
        val original = object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                nexts++; assertSame(this@ControllerVideoModelSessionGuardTest.outputs, outputs); sink(event)
            }
            override fun cancel() { cancels++ }
        }
        val guarded = guardControllerVideoModelSession(original) { false }
        assertFalse(guarded is ResumableModelSession)
        assertFalse(guarded is CheckpointedResumableModelSession)
        guarded.next(outputs) { received++; assertSame(event, it) }
        guarded.cancel()
        assertEquals(1, nexts); assertEquals(1, received); assertEquals(1, cancels)
    }

    @Test fun ordinarySessionStillCancelsOnRevokedAdmissionAndEvent() {
        for (revokeDuringDelivery in listOf(false, true)) {
            var revoked = !revokeDuringDelivery
            var nexts = 0; var cancels = 0
            val original = object : ModelSession {
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                    nexts++; revoked = true; sink(event)
                }
                override fun cancel() { cancels++ }
            }
            val guarded = guardControllerVideoModelSession(original) { revoked }
            val failure = assertThrows(InterruptedException::class.java) {
                guarded.next(outputs) { fail("revoked ordinary event delivered") }
            }
            assertEquals("video_model_cancelled", failure.message)
            assertEquals(if (revokeDuringDelivery) 1 else 0, nexts)
            assertEquals(1, cancels)
        }
    }

    @Test fun legacyResumableSessionIsNotUpgradedToCheckpointed() {
        var resumes = 0
        val original = object : ResumableModelSession {
            override val canResume = true
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) = sink(event)
            override fun resume(sink: (ModelEvent) -> Unit) { resumes++; sink(event) }
            override fun cancel() {}
        }
        val guarded = guardControllerVideoModelSession(original) { false }
        assertTrue(guarded is ResumableModelSession)
        assertFalse(guarded is CheckpointedResumableModelSession)
        (guarded as ResumableModelSession).resume { assertSame(event, it) }
        assertEquals(1, resumes)
    }

    @Test fun revokedRuntimeRejectsEveryAdmissionAndCancelsExactSession() {
        for (delivery in Delivery.entries) {
            val original = Session(event, batch)
            val guarded = guardControllerVideoModelSession(original) { true } as CheckpointedResumableModelSession
            val failure = assertThrows(InterruptedException::class.java) {
                deliver(guarded, delivery, { fail("revoked event delivered") }, { fail("revoked batch delivered"); ModelDeliveryAck.ACCEPTED })
            }
            assertEquals("video_model_cancelled", failure.message)
            assertEquals(0, original.admissions)
            assertEquals(1, original.cancellations)
            assertNull(original.ack)
        }
    }

    @Test fun revocationBetweenAdmissionAndDeliveryRejectsEventsAndBatchesAndCancels() {
        for (delivery in Delivery.entries) {
            var revoked = false
            val original = Session(event, batch).apply { beforeDelivery = { revoked = true } }
            val guarded = guardControllerVideoModelSession(original) { revoked } as CheckpointedResumableModelSession
            val failure = assertThrows(InterruptedException::class.java) {
                deliver(guarded, delivery, { fail("revoked event delivered") }, { fail("revoked batch delivered"); ModelDeliveryAck.ACCEPTED })
            }
            assertEquals("video_model_cancelled", failure.message)
            assertEquals(1, original.admissions)
            assertEquals(1, original.cancellations)
            assertNull(original.ack)
        }
    }

    @Test fun lateCallbacksAfterRevocationCannotReachSinkOrInventAck() {
        for (delivery in Delivery.entries) {
            var revoked = false
            var received = 0
            val original = Session(event, batch)
            val guarded = guardControllerVideoModelSession(original) { revoked } as CheckpointedResumableModelSession
            deliver(guarded, delivery, { received++ }, { received++; ModelDeliveryAck.REJECTED })
            val previousAck = original.ack
            revoked = true
            assertThrows(InterruptedException::class.java) {
                if (delivery == Delivery.NEXT || delivery == Delivery.RESUME) original.eventSink!!(event)
                else original.batchSink!!(batch)
            }
            assertEquals(1, received)
            assertEquals(1, original.cancellations)
            assertSame(previousAck, original.ack)
        }
    }
}
