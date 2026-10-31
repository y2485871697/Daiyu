package app.daiyu.android.domain.delegation

import org.junit.Assert.*
import org.junit.Test

class ChildModelPoolTest {
    @Test fun zeroLimitRestoresDefaultMaximumAfterResize() {
        val pool = ChildModelPool(4)
        for (limit in listOf(2, 0, 1)) {
            pool.resize(limit)
            assertEquals(if (limit == 0) Int.MAX_VALUE else limit, pool.semaphore.availablePermits())
        }
    }

    @Test fun shrinkParksOnlyLaterLeasesAtSafeBoundaryAndKeepsSameSemaphore() {
        val pool = ChildModelPool(4); val original = pool.semaphore
        val leases = List(4) { pool.lease() }
        leases.forEach { assertTrue(it.tryAcquire()); assertTrue(it.leaveBoundary()) }
        pool.resize(2)
        assertSame(original, pool.semaphore); assertEquals(-2, original.availablePermits())
        assertFalse(leases[0].yieldRequested); assertFalse(leases[1].yieldRequested)
        assertTrue(leases[2].yieldRequested); assertTrue(leases[3].yieldRequested)
        leases[2].enterBoundary(); leases[3].enterBoundary()
        assertEquals(0, original.availablePermits())
        assertFalse(leases[2].tryAcquire()); assertFalse(pool.lease().tryAcquire())
        leases[0].close(); assertTrue(leases[2].tryAcquire()); assertTrue(leases[2].leaveBoundary())
        leases[1].close(); assertTrue(leases[3].tryAcquire()); assertTrue(leases[3].leaveBoundary())
        leases.forEach { it.close(); it.close() }
        assertEquals(2, original.availablePermits())
    }
    @Test fun expandingDoesNotResumeUserPauseOrDuplicateReleasedPermits() {
        val pool = ChildModelPool(2); val leases = List(2) { pool.lease() }
        leases.forEach { assertTrue(it.tryAcquire()); assertTrue(it.leaveBoundary()) }
        pool.resize(1); leases[1].enterBoundary(); assertFalse(leases[1].tryAcquire())
        pool.resize(2); assertTrue(leases[1].tryAcquire()); assertTrue(leases[1].leaveBoundary())
        leases[1].close(); leases[1].close(); leases[0].close()
        assertEquals(2, pool.semaphore.availablePermits())
    }
}
