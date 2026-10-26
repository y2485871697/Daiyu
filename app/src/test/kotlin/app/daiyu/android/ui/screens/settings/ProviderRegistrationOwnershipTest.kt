package app.daiyu.android.ui.screens.settings

import org.junit.Assert.*
import org.junit.Test

class ProviderRegistrationOwnershipTest {
    private fun reject(action: () -> Unit) { try { action(); fail("must reject") } catch (_: IllegalStateException) {} }
    @Test fun registrationReceiptSurvivesPauseAndPreventsSecondRegistration() {
        var discarded = 0
        val state = ProviderRegistrationOwnership { discarded++ }
        state.beginSave(); state.registered("provider-1"); state.finishSave()
        // UI result may be ignored while its original foreground permit is revoked.
        assertEquals("provider-1", state.registeredSlot)
        reject { state.beginSave() }
        state.finishSave(); state.revoke()
        assertEquals("provider-1", state.registeredSlot); assertEquals(0, discarded)
    }
    @Test fun laterBalanceFailureCannotReturnCredentialOwnershipToDraft() {
        var discarded = 0
        val state = ProviderRegistrationOwnership { discarded++ }
        state.beginSave(); state.registered("provider-1"); state.finishSave()
        runCatching { error("balance save failed") }
        state.revoke(); reject { state.beginSave() }
        assertEquals(0, discarded)
    }
    @Test fun leavingDuringSaveDefersCleanupUntilOutcomeIsKnown() {
        var discarded = 0
        val state = ProviderRegistrationOwnership { discarded++ }
        state.beginSave(); state.revoke(); assertEquals(0, discarded)
        state.finishSave(); assertEquals(1, discarded)
    }
    @Test fun leavingDuringAdmittedRegistrationKeepsTransferredCredential() {
        var discarded = 0
        val state = ProviderRegistrationOwnership { discarded++ }
        state.beginSave(); state.revoke(); state.registered("provider-1"); state.finishSave()
        assertEquals(0, discarded); assertEquals("provider-1", state.registeredSlot)
    }
    @Test fun failedUnpublishedSaveCanRetryBeforeTheDraftIsClosed() {
        var discarded = 0
        val state = ProviderRegistrationOwnership { discarded++ }
        state.beginSave(); state.finishSave(); state.beginSave(); state.finishSave()
        state.revoke(); state.revoke(); assertEquals(1, discarded)
    }
}
