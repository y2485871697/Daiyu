package app.daiyu.android.terminal

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import junit.framework.TestCase

class LinuxSettingsAdmissionTest : TestCase() {
    fun testFixedSnapshotAndMonotoneDenialDoNotMutateCallerSet() {
        val old = LinuxGenerationRef("local", "old")
        val added = LinuxGenerationRef("local", "added")
        val source = mutableSetOf(old)
        val gate = LinuxSettingsAdmission(source)
        source += added
        assertEquals(setOf(old), gate.current())
        assertFalse(gate.admit(added) { fail("new catalog entry is not a current startup capability"); true })
        assertTrue(gate.admit(old) { true })
        gate.deny(old)
        gate.deny(old)
        assertTrue(source.contains(old))
        assertTrue(gate.current().isEmpty())
        assertFalse(gate.admit(old) { fail("revoked entry cannot run"); true })
    }

    fun testRevocationSerializesWithWholeAdmissionNotOnlyPredicateRead() {
        val reference = LinuxGenerationRef("local", "generation")
        val gate = LinuxSettingsAdmission(setOf(reference))
        val executor = Executors.newFixedThreadPool(2)
        val entered = CountDownLatch(1)
        val finishAdmission = CountDownLatch(1)
        val revokeStarted = CountDownLatch(1)
        val revokeFinished = CountDownLatch(1)
        try {
            val open = executor.submit<Boolean> {
                gate.admit(reference) {
                    entered.countDown()
                    check(finishAdmission.await(5, TimeUnit.SECONDS))
                    true
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val revoke = executor.submit {
                revokeStarted.countDown()
                gate.deny(reference)
                revokeFinished.countDown()
            }
            assertTrue(revokeStarted.await(5, TimeUnit.SECONDS))
            assertFalse(revokeFinished.await(100, TimeUnit.MILLISECONDS))
            finishAdmission.countDown()
            assertTrue(open.get(5, TimeUnit.SECONDS))
            revoke.get(5, TimeUnit.SECONDS)
            assertFalse(gate.admit(reference) { fail("no admission after revocation returns"); true })
        } finally {
            finishAdmission.countDown()
            executor.shutdownNow()
        }
    }
}
