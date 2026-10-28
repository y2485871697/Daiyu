package app.daiyu.android.ui.components

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DialogActionLeaseTest {
    @Test fun revokedLeaseNeverBecomesCurrentAfterResume() {
        val old = DialogActionLease()
        assertTrue(old.isActive)
        old.revoke()
        old.revoke()
        val resumed = DialogActionLease()
        assertFalse(old.isActive)
        assertTrue(resumed.isActive)
        resumed.revoke()
        assertFalse(old.isActive)
        assertFalse(resumed.isActive)
    }

    @Test fun queuedOldActionCannotWriteOrDismissRetainedPresentation() {
        val old = DialogActionLease()
        var writes = 0
        var open = true
        val oldSave = { if (old.isActive) writes++ }
        val oldDismiss = { if (old.isActive) open = false }
        old.revoke()
        val resumed = DialogActionLease()
        oldSave(); oldDismiss()
        assertTrue(open)
        assertEquals(0, writes)
        if (resumed.isActive) writes++
        assertEquals(1, writes)
        assertTrue(open)
        if (resumed.isActive) open = false
        assertFalse(open)
    }

    @Test fun workerSeesRevocationBeforeQueuedAdmission() {
        val executor = Executors.newSingleThreadExecutor()
        val queued = CountDownLatch(1)
        val release = CountDownLatch(1)
        val lease = DialogActionLease()
        try {
            val result = executor.submit<Boolean> {
                queued.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                lease.isActive
            }
            assertTrue(queued.await(5, TimeUnit.SECONDS))
            lease.revoke()
            release.countDown()
            assertFalse(result.get(5, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun repeatedBackgroundTransitionsNeverReviveAnyOldGrant() {
        val old = mutableListOf<DialogActionLease>()
        repeat(20) {
            val current = DialogActionLease()
            assertTrue(current.isActive)
            assertTrue(old.none { it.isActive })
            current.revoke()
            old += current
        }
        assertTrue(old.none { it.isActive })
    }
}
