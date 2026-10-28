package app.daiyu.android

import org.junit.Assert.*
import org.junit.Test

class ConversationConfigurationGateTest {
    @Test fun runningOwnerOnlyBlocksItsOwnConfiguration() {
        val gate = ConversationConfigurationGate()
        val owners = setOf<String?>("A")
        assertTrue(gate.blocks("A", owners))
        assertFalse(gate.blocks("B", owners))
        assertFalse(gate.blocks(null, owners))
        assertTrue(gate.blocks("A", owners))
    }
    @Test fun composerIsAnExactOwnerNotAWildcard() {
        val gate = ConversationConfigurationGate()
        val owners = setOf<String?>(null)
        assertTrue(gate.blocks(null, owners))
        assertFalse(gate.blocks("A", owners))
        assertFalse(gate.blocks("B", owners))
    }
    @Test fun multipleRetainedOwnersDoNotBlockAnIdleConversation() {
        val gate = ConversationConfigurationGate()
        val owners = setOf<String?>("A", "C")
        assertTrue(gate.blocks("A", owners)); assertTrue(gate.blocks("C", owners))
        assertNotNull(gate.reserve("B", owners))
    }
    @Test fun busyRejectionDoesNotAcquireAConfigurationOperation() {
        val gate = ConversationConfigurationGate()
        assertNull(gate.reserve("A", setOf("A")))
        assertFalse(gate.occupied)
        assertNotNull(gate.reserve("B", setOf("A")))
    }
    @Test fun reservationProtectsCommitUntilItsExactRelease() {
        val gate = ConversationConfigurationGate()
        val first = checkNotNull(gate.reserve("B", setOf("A")))
        assertTrue(gate.occupied)
        assertNull(gate.reserve("C", emptySet()))
        assertTrue(gate.release(first)); assertFalse(gate.occupied)
        val next = checkNotNull(gate.reserve("C", setOf("A")))
        assertFalse(gate.release(first)); assertTrue(gate.current(next))
        assertTrue(gate.occupied); assertTrue(gate.release(next))
    }
    @Test fun drainedOwnerRemainsBlockedUntilActuallyRemoved() {
        val gate = ConversationConfigurationGate()
        val retained = mutableSetOf<String?>("A")
        val operation = checkNotNull(gate.reserve("B", retained))
        gate.release(operation)
        assertTrue(gate.blocks("A", retained))
        assertFalse(gate.blocks("B", retained))
        retained.remove("A")
        assertNotNull(gate.reserve("A", retained))
    }
    @Test fun defaultConfigurationHasTheSameReservationLifetime() {
        val gate = ConversationConfigurationGate()
        val operation = checkNotNull(gate.reserve(null, setOf("A")))
        assertNull(operation.owner); assertTrue(gate.occupied)
        assertTrue(gate.release(operation)); assertFalse(gate.occupied)
    }
}
