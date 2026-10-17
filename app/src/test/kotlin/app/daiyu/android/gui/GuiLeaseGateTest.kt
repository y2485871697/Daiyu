package app.daiyu.android.gui

import org.junit.Assert.*
import org.junit.Test

class GuiLeaseGateTest {
    private var time = 100L
    private fun gate() = GuiLeaseGate { time }.also { it.connect() }
    private val owner = GuiOwner("session", "run")
    private fun lease(gate: GuiLeaseGate, owner: GuiOwner = this.owner) =
        (gate.acquire(owner) as GuiResult.Value<GuiLeaseGate.Lease>).value
    private fun observation(id: String = "obs", generation: Long = 3) = GuiObservation(id,
        "example.app", 1, generation, time, listOf(GuiNode(0, null, GuiBounds(0, 0, 40, 40),
            "Button", "", "Button", "example:id/button", true, true, true, false, true, false)), false)
    private fun code(result: GuiResult<*>) = (result as GuiResult.Rejected).code

    @Test fun disconnectedAndReboundNeverResurrectOldLease() {
        val gate = GuiLeaseGate { time }
        assertEquals("SERVICE_DISCONNECTED_NOT_EXECUTED", code(gate.acquire(owner)))
        gate.connect()
        val old = lease(gate)
        gate.approveReads(old, GuiReadApproval(1, 1))
        gate.disconnect()
        assertFalse(old.valid) // pure contextual admission can reject even cached ledger replay
        assertEquals("SERVICE_DISCONNECTED_NOT_EXECUTED", gate.rejection(old)?.code)
        gate.connect()
        val fresh = lease(gate)
        assertEquals("FOREGROUND_BUSY", gate.rejection(old)?.code)
        assertEquals("OBSERVATION_NOT_AUTHORIZED", code(gate.consumeRead(fresh, false)))
        assertEquals("SCREENSHOT_NOT_EXPLICITLY_AUTHORIZED", code(gate.consumeRead(fresh, true)))
    }
    @Test fun singleOwnerRejectsEvenSameIdsAndOldCloseDoesNotReleaseNewOwner() {
        val gate = gate()
        val first = lease(gate)
        assertEquals("FOREGROUND_BUSY", code(gate.acquire(owner)))
        assertEquals("FOREGROUND_BUSY", code(gate.acquire(GuiOwner("other", "run"))))
        gate.close(first)
        val second = lease(gate, GuiOwner("other", "run"))
        gate.close(first)
        assertEquals("FOREGROUND_BUSY", code(gate.acquire(owner)))
        assertNull(gate.rejection(second))
    }
    @Test fun observationNeverImplicitlyGrantsScreenshotAndBothBudgetsAreFinite() {
        val gate = gate(); val lease = lease(gate)
        assertEquals("OBSERVATION_NOT_AUTHORIZED", code(gate.consumeRead(lease, false)))
        gate.approveReads(lease, GuiReadApproval(1, 0))
        assertTrue(gate.consumeRead(lease, false) is GuiResult.Value)
        assertEquals("OBSERVATION_NOT_AUTHORIZED", code(gate.consumeRead(lease, false)))
        assertEquals("SCREENSHOT_NOT_EXPLICITLY_AUTHORIZED", code(gate.consumeRead(lease, true)))
        gate.approveReads(lease, GuiReadApproval(0, 1))
        assertTrue(gate.consumeRead(lease, true) is GuiResult.Value)
        assertEquals("SCREENSHOT_NOT_EXPLICITLY_AUTHORIZED", code(gate.consumeRead(lease, true)))
    }
    @Test fun exactActionCannotChangeNodeTextOperationOrObservation() {
        val gate = gate(); val lease = lease(gate)
        gate.publish(lease, observation())
        val input = GuiAction("obs", GuiOperation.INPUT, 0, "approved")
        assertTrue(gate.approveAction(lease, input, 3) is GuiResult.Value)
        assertEquals("EXACT_ACTION_NOT_AUTHORIZED", code(gate.consumeAction(lease, input.copy(text = "different"), 3)))
        assertEquals("EXACT_ACTION_NOT_AUTHORIZED", code(gate.consumeAction(lease, GuiAction("obs", GuiOperation.TAP, 0), 3)))
        assertEquals("OBSERVATION_ID_MISMATCH", code(gate.consumeAction(lease, input.copy(observationId = "other"), 3)))
        assertEquals("NODE_NOT_IN_OBSERVATION", code(gate.approveAction(lease, input.copy(nodeId = 8), 3)))
        assertTrue(gate.consumeAction(lease, input, 3) is GuiResult.Value)
        assertEquals("OBSERVATION_REQUIRED", code(gate.consumeAction(lease, input, 3)))
    }
    @Test fun generationExpiryAndNewObservationInvalidateGrants() {
        val gate = gate(); val lease = lease(gate)
        val action = GuiAction("obs", GuiOperation.BACK)
        gate.publish(lease, observation())
        gate.approveAction(lease, action, 3)
        assertEquals("OBSERVATION_STALE", gate.validateObservation(lease, "obs", 4)?.code)
        time += 10_001
        assertEquals("OBSERVATION_EXPIRED", gate.validateObservation(lease, "obs", 3)?.code)
        gate.publish(lease, observation("next"))
        assertTrue(gate.approvedActions(lease).isEmpty())
        assertEquals("OBSERVATION_ID_MISMATCH", code(gate.consumeAction(lease, action, 3)))
        val nextAction = action.copy(observationId = "next")
        assertEquals("EXACT_ACTION_NOT_AUTHORIZED", code(gate.consumeAction(lease, nextAction, 3)))
    }
    @Test fun actionSnapshotIsDefensivelyCopied() {
        val gate = gate(); val lease = lease(gate)
        gate.publish(lease, observation())
        gate.approveAction(lease, GuiAction("obs", GuiOperation.HOME), 3)
        val frozen = gate.approvedActions(lease)
        gate.approveAction(lease, GuiAction("obs", GuiOperation.BACK), 3)
        assertEquals(1, frozen.size)
        assertEquals(2, gate.approvedActions(lease).size)
    }
    @Test fun revokedLeaseStillOwnsCleanupSlotButNeverAdmissionAndCannotClearNewOwner() {
        val gate = gate()
        val old = lease(gate)
        old.valid = false // overlay disposal failed after synchronous revocation
        assertTrue(gate.ownsSlot(old))
        assertEquals("GUI_AUTHORIZATION_REVOKED", code(gate.consumeRead(old, false)))
        assertEquals("FOREGROUND_BUSY", code(gate.acquire(owner)))
        gate.close(old)
        val next = lease(gate)
        assertFalse(gate.ownsSlot(old))
        assertTrue(gate.ownsSlot(next))
        gate.close(old)
        assertNull(gate.rejection(next))
    }
    @Test fun effectIdentityIncludesOwnerObservationNodeAndCompleteText() {
        val action = GuiAction("obs", GuiOperation.INPUT, 0, "approved")
        assertNotEquals(action.effect(owner), action.copy(text = "different").effect(owner))
        assertNotEquals(action.effect(owner), action.copy(nodeId = 1).effect(owner))
        assertNotEquals(action.effect(owner), action.copy(observationId = "new").effect(owner))
        assertNotEquals(action.effect(owner), action.effect(GuiOwner("other", "run")))
    }
}
