package app.daiyu.android.ui.screens.settings

import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.model.oauth.CodexAuth
import app.daiyu.android.model.oauth.CodexOAuthProtocol
import org.junit.Assert.*
import org.junit.Test

class ProviderOAuthDraftTest {
    private val first = CodexAuth("00000000-0000-0000-0000-000000000001")
    private val second = CodexAuth("00000000-0000-0000-0000-000000000002")
    private val discarded = mutableListOf<CodexAuth>()
    private fun draft() = NewProviderDraft(ProviderProtocol.RESPONSES, oauthOnly = true, discardOAuth = discarded::add)

    @Test fun creationStartsUnsignedWithOriginalNameAndFixedCodexEndpoint() {
        val owner = draft()
        assertTrue(owner.oauthOnly)
        assertEquals("OpenAI Codex", owner.draft.providerName)
        assertEquals(CodexOAuthProtocol.BASE_URL, owner.draft.endpoint)
        assertEquals("/responses", owner.draft.responsesPath)
        assertEquals(ProviderProtocol.RESPONSES, owner.draft.protocol)
        assertNull(owner.draft.oauth)
        assertEquals("", owner.draft.apiKey)
        assertNull(owner.registration.registeredSlot)
        owner.revoke()
        assertTrue(discarded.isEmpty())
    }
    @Test fun loginAttachesToSameDraftOwnerAndPreservesUserConfiguration() {
        val owner = draft()
        owner.draft = owner.draft.copy(providerName = "My Codex", customBody = "{\"temperature\":0.7}",
            providerEnabled = true)
        val before = owner.draft
        owner.attachOAuth(first)
        assertEquals(before.copy(oauth = first), owner.draft)
        assertNull(owner.registration.registeredSlot)
        assertTrue(discarded.isEmpty())
        owner.revoke()
        assertEquals(listOf(first), discarded)
    }
    @Test fun replacingLoginDiscardsOldUnregisteredCredentialExactlyOnce() {
        val owner = draft()
        owner.attachOAuth(first)
        owner.attachOAuth(first)
        assertTrue(discarded.isEmpty())
        owner.attachOAuth(second)
        assertEquals(listOf(first), discarded)
        owner.revoke(); owner.revoke()
        assertEquals(listOf(first, second), discarded)
    }
    @Test fun successfulRegistrationTransfersCredentialOwnership() {
        val owner = draft()
        owner.attachOAuth(first)
        owner.registration.beginSave()
        owner.registration.registered("synthetic-slot")
        owner.registration.finishSave()
        owner.revoke()
        assertTrue(discarded.isEmpty())
        assertEquals("synthetic-slot", owner.registration.registeredSlot)
    }
    @Test fun failedSaveAndClosedDraftDiscardTheAttachedCredential() {
        val owner = draft()
        owner.attachOAuth(first)
        owner.registration.beginSave()
        owner.revoke()
        assertTrue(discarded.isEmpty())
        owner.registration.finishSave()
        assertEquals(listOf(first), discarded)
    }
    @Test fun nonOAuthCreationKeepsItsExistingEmptyApiKeyDraft() {
        val owner = NewProviderDraft(ProviderProtocol.RESPONSES, discardOAuth = discarded::add)
        assertFalse(owner.oauthOnly)
        assertEquals("", owner.draft.providerName)
        assertNull(owner.draft.oauth)
        owner.revoke()
        assertTrue(discarded.isEmpty())
    }
}
