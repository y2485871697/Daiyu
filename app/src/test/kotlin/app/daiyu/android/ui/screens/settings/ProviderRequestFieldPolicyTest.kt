package app.daiyu.android.ui.screens.settings

import app.daiyu.android.model.ProviderProtocol
import org.junit.Assert.*
import org.junit.Test

class ProviderRequestFieldPolicyTest {
    @Test fun onlyOpenAiCreationDefaultsEnableSessionIdentity() {
        for (protocol in ProviderProtocol.entries) {
            val expected = protocol in setOf(ProviderProtocol.CHAT_COMPLETIONS, ProviderProtocol.RESPONSES)
            assertEquals(expected, newProviderSessionGateway(protocol, false).enabled)
            assertFalse(newProviderSessionGateway(protocol, true).enabled)
        }
    }
    @Test fun newDraftUsesPolicyAndOauthRemainsOpenAiEnabled() {
        for (protocol in ProviderProtocol.entries) {
            val draft = NewProviderDraft(protocol, discardOAuth = {})
            assertEquals(newProviderSessionGateway(protocol, false), draft.draft.sessionGateway)
        }
        assertTrue(NewProviderDraft(ProviderProtocol.RESPONSES, oauthOnly = true, discardOAuth = {}).draft.sessionGateway.enabled)
        assertFalse(NewProviderDraft(ProviderProtocol.CHAT_COMPLETIONS, speechOnly = true, discardOAuth = {}).draft.sessionGateway.enabled)
    }
    @Test fun hiddenBodyIsExcludedFromValidationWithoutMutatingOriginalDraft() {
        for (protocol in listOf(ProviderProtocol.RESPONSES, ProviderProtocol.CHAT_COMPLETIONS, ProviderProtocol.GEMINI)) {
            val draft = ModelSettingsDraftUi(protocol = protocol, customBody = "bad-json", bodyFieldsValid = false,
                bodyFieldsError = "hidden error", bodyFieldRows = listOf(ProviderFieldRow(0, "marker", "bad-json")))
            val effective = draft.providerRequestDraft(false)
            assertEquals("", effective.customBody)
            assertTrue(effective.bodyFieldsValid)
            assertNull(effective.bodyFieldsError)
            assertNull(effective.bodyFieldRows)
            assertEquals(draft.sessionGateway, effective.sessionGateway)
            assertNull(effective.inlineFieldsSaveError())
            assertEquals("bad-json", draft.customBody)
            assertFalse(draft.bodyFieldsValid)
        }
    }
    @Test fun anthropicRetainsItsBodyErrorsAndCustomHeaders() {
        val draft = ModelSettingsDraftUi(protocol = ProviderProtocol.MESSAGES, bodyFieldsValid = false,
            bodyFieldsError = "visible body error", customHeaders = """{"x-project":"retained"}""")
        assertSame(draft, draft.providerRequestDraft(false))
        assertEquals("visible body error", draft.providerRequestDraft(false).inlineFieldsSaveError())
    }
    @Test fun speechIgnoresBothHiddenFieldErrorsAndNeverChangesSource() {
        val draft = ModelSettingsDraftUi(protocol = ProviderProtocol.MESSAGES, customBody = "bad-body", customHeaders = "bad-headers",
            headerFieldsValid = false, bodyFieldsValid = false, headerFieldsError = "hidden", bodyFieldsError = "hidden",
            headerFieldRows = listOf(ProviderFieldRow(0, "", "")), bodyFieldRows = listOf(ProviderFieldRow(0, "", "")))
        val effective = draft.providerRequestDraft(true)
        assertEquals("", effective.customBody)
        assertEquals("", effective.customHeaders)
        assertTrue(effective.headerFieldsValid && effective.bodyFieldsValid)
        assertNull(effective.headerFieldRows); assertNull(effective.bodyFieldRows)
        assertNull(effective.inlineFieldsSaveError())
        assertEquals("bad-headers", draft.customHeaders)
    }
}
