package app.daiyu.android.execution

import org.junit.Assert.*
import org.junit.Test

class UserExecutionLeasesTest {
    @Test fun modelAdmissionWaitsForConfirmedForegroundOwner() {
        val leases = UserExecutionLeases()
        val first = leases.acquire { fail("not stopped") }
        assertFalse(first.awaitStarted(0))
        val owner = leases.attach()
        assertFalse(first.awaitStarted(0))
        leases.started(owner)
        assertTrue(first.awaitStarted(0))
        val second = leases.acquire { fail("not stopped") }
        assertTrue(second.awaitStarted(0))
        first.close(); second.close()
        assertEquals(0, leases.count())
        assertTrue(leases.retireIfIdle(owner))
    }

    @Test fun notificationStopIsOnceOnlyAndKeepsLeaseUntilPhysicalDrain() {
        val leases = UserExecutionLeases()
        var stopped = 0
        val first = leases.acquire { stopped++ }
        val owner = leases.attach()
        leases.started(owner)
        leases.requestStop(owner).forEach { it() }
        assertEquals(1, stopped)
        assertEquals(1, leases.count())
        assertFalse(leases.retireIfIdle(owner))
        assertTrue(leases.requestStop(owner).isEmpty())
        first.close()
        assertTrue(leases.retireIfIdle(owner))
    }

    @Test fun serviceLossRevokesWaitingAdmissionAndDoesNotReplayCallbacks() {
        val leases = UserExecutionLeases()
        var stops = 0
        val lease = leases.acquire { stops++ }
        val owner = leases.attach()
        leases.revoke(owner).forEach { it() }
        assertFalse(lease.awaitStarted(0))
        assertEquals(1, stops)
        assertTrue(leases.revoke(owner).isEmpty())
        leases.started(owner)
        assertFalse(lease.awaitStarted(0))
        lease.close()
        assertEquals(0, leases.count())
    }

    @Test fun lateServiceDestroyCannotStopAReplacementOrClaimItsLease() {
        val leases = UserExecutionLeases()
        val old = leases.acquire { fail("released old lease") }
        val oldOwner = leases.attach()
        leases.started(oldOwner)
        old.close()
        assertTrue(leases.retireIfIdle(oldOwner))
        var replacementStops = 0
        val next = leases.acquire { replacementStops++ }
        val nextOwner = leases.attach()
        leases.started(nextOwner)
        assertTrue(leases.revoke(oldOwner).isEmpty())
        assertFalse(leases.retireIfIdle(oldOwner))
        assertTrue(next.awaitStarted(0))
        assertEquals(0, replacementStops)
        assertEquals(1, leases.count())
        next.close()
        assertTrue(leases.retireIfIdle(nextOwner))
    }

    @Test fun waitingAndBackgroundChildrenDoNotEndTheParentServiceLease() {
        val leases = UserExecutionLeases()
        var childStops = 0
        val parent = leases.acquire {}
        val owner = leases.attach()
        leases.started(owner)
        val child = leases.acquire { childStops++ }
        assertFalse(leases.retireIfIdle(owner))
        parent.close()
        assertEquals(1, leases.count())
        assertFalse(leases.retireIfIdle(owner))
        assertTrue(child.awaitStarted(0))
        leases.requestStop(owner).forEach { it() }
        assertEquals(1, childStops)
        child.close()
        assertTrue(leases.retireIfIdle(owner))
    }

    @Test fun doubleCloseAndStaleConfirmationCannotPromoteNewUnstartedWork() {
        val leases = UserExecutionLeases()
        val first = leases.acquire {}
        val old = leases.attach()
        leases.started(old)
        first.close(); first.close()
        assertTrue(leases.retireIfIdle(old))
        val next = leases.acquire {}
        val owner = leases.attach()
        leases.started(old)
        assertFalse(next.awaitStarted(0))
        leases.started(owner)
        assertTrue(next.awaitStarted(0))
        next.close()
    }
}
