package app.daiyu.android.media

import app.daiyu.android.storage.GenerationAttemptBinding
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Offline concurrency only. Publication callbacks model rename/SQL, not real SQLite or codecs. */
class GenerationAssetPublicationGateTest {
    private fun owner() = GenerationAssetOwner("account",
        GenerationAttemptBinding("conversation", "assistant", "run", "call", "generate_images"))
    private fun await(latch: CountDownLatch) { check(latch.await(5, TimeUnit.SECONDS)) }
    private fun join(thread: Thread) { thread.join(5000); assertFalse("worker did not exit", thread.isAlive) }
    private fun cancelled(action: () -> Unit) {
        try { action(); fail("expected cancellation") }
        catch (failure: GenerationAssetException) { assertEquals(GenerationAssetFailure.CANCELLED, failure.failure) }
    }

    @Test fun localRevokeDoesNotRevokeSiblingOrRecoveredHistoryFence() {
        val gate = GenerationAssetPublicationGate()
        val writer = GenerationAssetFence(owner(), gate) { true }
        val reader = GenerationAssetFence(owner(), gate) { true }
        writer.revokeAdmission()
        writer.revoke()
        cancelled { writer.admit { fail("writer published") } }
        assertEquals("accepted history", reader.admit { "accepted history" })
        reader.revoke()
        assertEquals(7, GenerationAssetFence(owner(), gate) { true }.admit { 7 })
    }

    @Test fun ownerSealRejectsEveryFenceIncludingFutureReadersBeforeValidation() {
        val gate = GenerationAssetPublicationGate()
        val validated = AtomicBoolean(false)
        val first = GenerationAssetFence(owner(), gate) { validated.set(true); true }
        val second = GenerationAssetFence(owner(), gate) { validated.set(true); true }
        gate.sealAdmission()
        gate.revokeOnWorker()
        for (fence in listOf(first, second, GenerationAssetFence(owner(), gate) { true })) {
            cancelled { fence.admit { fail("sealed owner published") } }
        }
        assertFalse(validated.get())
    }

    @Test fun sealIsNonblockingAndClosesValidatorToPublicationWindowAcrossFences() {
        val gate = GenerationAssetPublicationGate()
        val validating = CountDownLatch(1)
        val release = CountDownLatch(1)
        val published = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>()
        val writer = GenerationAssetFence(owner(), gate) {
            validating.countDown(); await(release); true
        }
        val reader = GenerationAssetFence(owner(), gate) { true }
        val worker = Thread {
            try { writer.admit { published.set(true) } }
            catch (error: Throwable) { failure.set(error) }
        }
        worker.start()
        try {
            await(validating)
            // Must return even with a validator holding the publication monitor.
            gate.sealAdmission()
        } finally { release.countDown() }
        join(worker)
        gate.revokeOnWorker()
        assertEquals(GenerationAssetFailure.CANCELLED, (failure.get() as GenerationAssetException).failure)
        assertFalse(published.get())
        cancelled { reader.admit { fail("sibling published") } }
    }

    @Test fun physicalOwnerRevokeWaitsForAlreadyAdmittedPublicationBeforeOwnerMutation() {
        val gate = GenerationAssetPublicationGate()
        val writer = GenerationAssetFence(owner(), gate) { true }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val revoking = CountDownLatch(1)
        val revoked = CountDownLatch(1)
        val published = AtomicBoolean(false)
        val observed = AtomicBoolean(false)
        val error = AtomicReference<Throwable?>()
        val publisher = Thread {
            try { writer.admit { entered.countDown(); await(release); published.set(true) } }
            catch (failure: Throwable) { error.set(failure) }
        }
        val revoker = Thread {
            revoking.countDown()
            gate.revokeOnWorker()
            observed.set(published.get()) // parent may now mutate/delete the owner
            revoked.countDown()
        }
        publisher.start()
        try {
            await(entered)
            gate.sealAdmission()
            revoker.start()
            await(revoking)
            assertFalse("revoke bypassed in-flight publication", revoked.await(100, TimeUnit.MILLISECONDS))
        } finally { release.countDown() }
        join(publisher); join(revoker)
        assertNull(error.get())
        assertTrue(observed.get())
        cancelled { writer.admit { fail("post-revoke publication") } }
    }

    @Test fun legacyConstructorStillUsesIndependentGates() {
        val first = GenerationAssetFence(owner()) { true }
        val second = GenerationAssetFence(owner()) { true }
        assertNotSame(first.publicationGate, second.publicationGate)
        first.publicationGate.revokeOnWorker()
        assertTrue(second.admit { true })
    }
}
