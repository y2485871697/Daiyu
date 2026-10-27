package app.daiyu.android.ui.screens.settings

import app.daiyu.android.model.ProviderProtocol
import org.junit.Assert.*
import org.junit.Test

class ProviderProtocolDraftTest {
    @Test fun changingAndRestoringEndpointPreservesAllInputsIncludingInvalidRows() {
        val draft = ModelSettingsDraftUi(providerName = "Named provider", endpoint = "https://example.invalid/v1", apiKey = "synthetic-key",
            responsesPath = "/responses", model = "model-a", customBody = "{\"temperature\":0.5}", customHeaders = "{\"x-test\":\"value\"}",
            anthropicVersion = "2023-06-01", providerEnabled = true,
            headerFieldRows = listOf(ProviderFieldRow(1, "unfinished", "")), bodyFieldRows = listOf(ProviderFieldRow(2, "also unfinished", "")),
            headerFieldsValid = false, bodyFieldsValid = false, headerFieldsError = "header error", bodyFieldsError = "body error")
        var current = draft
        for (protocol in listOf(ProviderProtocol.CHAT_COMPLETIONS, ProviderProtocol.MESSAGES, ProviderProtocol.GEMINI, ProviderProtocol.RESPONSES)) {
            current = current.selectProtocol(protocol)
            assertEquals(draft.copy(protocol = protocol, protocolChangeAuthorized = false), current)
            assertEquals(ProviderProtocol.RESPONSES, current.savedProtocol)
        }
        assertEquals(draft, current)
    }
    @Test fun switchingRevokesOnlyOldProtocolConsentAndSameChoiceIsIdentityPreserving() {
        val source = ModelSettingsDraftUi(apiKey = "synthetic-key", protocolChangeAuthorized = true,
            headersReplacementAuthorized = true, requestOptionsReplacementAuthorized = true)
        assertSame(source, source.selectProtocol(source.protocol))
        val next = source.selectProtocol(ProviderProtocol.MESSAGES)
        assertFalse(next.protocolChangeAuthorized)
        assertTrue(next.headersReplacementAuthorized); assertTrue(next.requestOptionsReplacementAuthorized)
        assertEquals(source.apiKey, next.apiKey)
    }
}
