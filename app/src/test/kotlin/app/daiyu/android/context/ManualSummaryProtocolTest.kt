package app.daiyu.android.context

import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.model.oauth.CodexAuth
import app.daiyu.android.model.oauth.CodexOAuthProtocol
import app.daiyu.android.storage.ModelSettings
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class ManualSummaryProtocolTest {
    @Test fun oauthLoginLocksResponsesEvenWithoutTheLegacyCodexFlag() {
        val settings = ModelSettings(CodexOAuthProtocol.BASE_URL, "summary-model", "", false,
            responsesPath = "/responses", oauth = CodexAuth(UUID.randomUUID().toString()))
        assertFalse(settings.requestOptions.codex)
        for (requested in listOf(ProviderProtocol.CHAT_COMPLETIONS, ProviderProtocol.RESPONSES)) {
            val result = ManualSummaryTargets.forSummary(settings, requested)
            assertSame(settings, result)
            result.validate()
            assertEquals(CodexOAuthProtocol.BASE_URL + "/responses", result.requestEndpoint())
        }
    }
    @Test fun ordinaryApiKeySummaryCanStillSelectChatOrResponses() {
        val source = ModelSettings("https://example.invalid/v1/responses", "summary-model", "synthetic-key", false)
        val chat = ManualSummaryTargets.forSummary(source, ProviderProtocol.CHAT_COMPLETIONS)
        assertEquals("https://example.invalid/v1/chat/completions", chat.requestEndpoint())
        assertEquals(ProviderProtocol.CHAT_COMPLETIONS, chat.protocol)
        assertEquals(source, ManualSummaryTargets.forSummary(chat, ProviderProtocol.RESPONSES))
    }
}
