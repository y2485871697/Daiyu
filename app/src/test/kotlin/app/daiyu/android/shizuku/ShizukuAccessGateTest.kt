package app.daiyu.android.shizuku

import org.junit.Assert.*
import org.junit.Test

class ShizukuAccessGateTest {
    private fun rejected(block: () -> Unit) { try { block(); fail("accepted invalid grant") } catch (_: IllegalStateException) {} }
    @Test fun absentServiceNeverGrants() {
        val gate = ShizukuAccessGate(); assertEquals(ShizukuAvailability.UNAVAILABLE, gate.state().availability)
        rejected { gate.acquire(2000) }
    }
    @Test fun authorizationDoesNotInventUid() {
        val gate = ShizukuAccessGate(); gate.update(Any(), 13, false, null)
        assertNull(gate.state().uid); assertEquals(ShizukuAvailability.PERMISSION_REQUIRED, gate.state().availability)
        rejected { gate.acquire(2000) }
        gate.update(Any(), 13, true, 10001)
        assertEquals(ShizukuAvailability.UNSUPPORTED, gate.state().availability)
    }
    @Test fun shellAndRootAreExplicitChoicesNotFallbacks() {
        val gate = ShizukuAccessGate(); gate.update(Any(), 13, true, 0)
        rejected { gate.acquire(2000) }; assertEquals(0, gate.acquire(0).uid)
        gate.update(Any(), 13, true, 2000)
        rejected { gate.acquire(0) }; assertEquals(2000, gate.acquire(2000).uid)
    }
    @Test fun sameUidNewServerCannotReviveOldGrant() {
        val gate = ShizukuAccessGate(); gate.update(Any(), 13, true, 2000)
        val old = gate.acquire(2000); gate.update(Any(), 13, true, 2000)
        assertFalse(gate.current(old)); assertTrue(gate.current(gate.acquire(2000)))
    }
    @Test fun revokedThenRegrantedSameServerStillInvalidatesOldGrant() {
        val server = Any(); val gate = ShizukuAccessGate(); gate.update(server, 13, true, 2000)
        val old = gate.acquire(2000); gate.update(server, 13, false, null); gate.update(server, 13, true, 2000)
        assertFalse(gate.current(old)); assertTrue(gate.current(gate.acquire(2000)))
    }
    @Test fun newExplicitConnectionRotatesGrant() {
        val gate = ShizukuAccessGate(); gate.update(Any(), 13, true, 2000)
        val old = gate.acquire(2000); gate.rotate(); assertFalse(gate.current(old))
        assertTrue(gate.current(gate.acquire(2000)))
    }
    @Test fun closeAndUnknownArePermanentForOldHandles() {
        val server = Any(); val gate = ShizukuAccessGate(); gate.update(server, 13, true, 2000)
        val old = gate.acquire(2000); gate.invalidate(); gate.update(server, 13, true, 2000)
        assertFalse(gate.current(old)); gate.close(); gate.update(server, 13, true, 2000)
        assertEquals(ShizukuAvailability.CLOSED, gate.state().availability); rejected { gate.acquire(2000) }
    }
    @Test fun oldProtocolUnsupportedEvenIfPermissionReportedGranted() {
        val gate = ShizukuAccessGate(); gate.update(Any(), 12, true, 2000)
        assertEquals(ShizukuAvailability.UNSUPPORTED, gate.state().availability); rejected { gate.acquire(2000) }
    }
}
