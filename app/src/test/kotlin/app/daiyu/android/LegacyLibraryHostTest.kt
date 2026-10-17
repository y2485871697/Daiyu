package app.daiyu.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyLibraryHostTest {
    @Test fun historySwitchSynchronouslyRejectsPriorEpoch() {
        val owner = LegacyLibraryOwner { true }
        val first = requireNotNull(owner.current())
        val second = requireNotNull(owner.rotate())
        assertNotEquals(first, second)
        assertFalse(owner.accepts(first))
        assertTrue(owner.accepts(second))
    }

    @Test fun departureCannotBeRevivedBySameRouteReturning() {
        var current = true
        val owner = LegacyLibraryOwner { current }
        val first = requireNotNull(owner.current())
        current = false
        assertFalse(owner.accepts(first))
        current = true
        assertNull(owner.current())
        assertNull(owner.rotate())
        assertFalse(owner.accepts(first))
    }

    @Test fun revokeIsTerminalEvenWithoutAnyWorkerCompletion() {
        val owner = LegacyLibraryOwner { true }
        val epoch = requireNotNull(owner.current())
        owner.revoke()
        assertFalse(owner.accepts(epoch))
        assertNull(owner.current())
        assertNull(owner.rotate())
    }

    @Test fun failedOwnerPredicateFailsClosed() {
        val owner = LegacyLibraryOwner { error("not current") }
        assertNull(owner.current())
        assertNull(owner.rotate())
    }

    @Test fun confirmationCanBeConsumedOnlyOnceEvenIfQueueRejects() {
        val owner = LegacyLibraryOwner { true }
        val ticket = LegacyLibraryOnce(owner, requireNotNull(owner.current()))
        assertTrue(ticket.consume())
        assertFalse(ticket.consume())
    }

    @Test fun pickerCallbackCannotBindToReplacementHistory() {
        val owner = LegacyLibraryOwner { true }
        val picker = LegacyLibraryOnce(owner, requireNotNull(owner.current()))
        owner.rotate()
        assertFalse(picker.consume())
        assertFalse(picker.consume())
    }

    @Test fun savePickerAfterRevokeRemainsDenied() {
        val owner = LegacyLibraryOwner { true }
        val picker = LegacyLibraryOnce(owner, requireNotNull(owner.current()))
        owner.revoke()
        assertFalse(picker.consume())
    }

    @Test fun separateVisitsNeverShareFence() {
        val first = LegacyLibraryOwner { true }
        val firstEpoch = requireNotNull(first.current())
        first.revoke()
        val second = LegacyLibraryOwner { true }
        val secondEpoch = requireNotNull(second.current())
        assertFalse(first.accepts(firstEpoch))
        assertTrue(second.accepts(secondEpoch))
    }
}
