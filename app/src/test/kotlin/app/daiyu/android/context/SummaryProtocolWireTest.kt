package app.daiyu.android.context

import app.daiyu.android.domain.*
import app.daiyu.android.model.*
import app.daiyu.android.model.oauth.CodexAuth
import app.daiyu.android.model.oauth.CodexOAuthProtocol
import app.daiyu.android.storage.ModelSettings
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.URL
import java.security.cert.Certificate
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

class SummaryProtocolWireTest {
    private val binding = SummaryModelBinding("selected", "summary")
    private fun settings() = ModelSettings("https://fixture.invalid/v1/responses", "summary", "synthetic-key", false,
        contextWindowOverride = 32000)
    private class Connection : HttpsURLConnection(URL("https://fixture.invalid")) {
        val sent = ByteArrayOutputStream()
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getOutputStream() = sent
        override fun getResponseCode() = 500
        override fun getErrorStream() = "{\"error\":{\"message\":\"offline fixture\"}}".byteInputStream()
    }
    private fun history() = listOf(Message(MessageId("u"), ConversationId("c"), RunId("r"), "user",
        listOf(MessageBlock.Text("u", "evidence")), RunStatus.COMPLETED))

    @Test fun actualResolverRequestsUseTheFrozenPreferenceAndNeverModifyTheProvider() {
        val source = settings(); val snapshot = source.requestSnapshot()
        val base = CatalogSummaryExecutionPolicy.configuration(binding, CatalogSummaryExecutionPolicy.summarySettings(source))
        for (protocol in listOf(ProviderProtocol.CHAT_COMPLETIONS, ProviderProtocol.RESPONSES)) {
            val wire = Connection(); var actual: URL? = null
            val resolver = ProviderSummaryModelResolver({ base }, { actual = it; wire },
                SummaryDeadlineScheduler { _, _ -> AutoCloseable {} })
            val bound = resolver.resolve(binding, null, protocol)!!
            val session = bound.transport.openWithoutTools(history(), bound.startDeadline())
            assertThrows(ModelRequestException::class.java) { session.next(emptyList()) {} }
            val suffix = if (protocol == ProviderProtocol.RESPONSES) "/responses" else "/chat/completions"
            assertEquals("https://fixture.invalid/v1$suffix", actual.toString())
            val body = JSONObject(wire.sent.toString("UTF-8"))
            assertEquals("summary", body.getString("model"))
            assertTrue(body.has(if (protocol == ProviderProtocol.RESPONSES) "input" else "messages"))
            assertFalse(body.has("tools"))
            assertEquals(snapshot, source.requestSnapshot())
            assertEquals(snapshot.protocol, base.provider.protocol)
            assertEquals(snapshot.endpoint, base.provider.endpoint)
        }
    }

    @Test fun mainAndChildAndCustomConfigurationsUseThePreferenceWithoutChangingTheirIdentityOrBudget() {
        val snapshot = settings().requestSnapshot()
        val custom = CatalogSummaryExecutionPolicy.configuration(binding, settings())
        for (protocol in listOf(ProviderProtocol.CHAT_COMPLETIONS, ProviderProtocol.RESPONSES)) {
            val configs = listOf(CatalogSummaryExecutionPolicy.chatConfiguration(snapshot, 32000, protocol),
                CatalogSummaryExecutionPolicy.childConfiguration(snapshot, 32000, null, protocol) { error("no custom model") },
                CatalogSummaryExecutionPolicy.childConfiguration(snapshot, 32000, binding, protocol) { custom })
            for (config in configs) {
                assertEquals(protocol, config.provider.protocol)
                assertEquals(snapshot.apiKey, config.provider.apiKey)
                assertEquals(32000L, config.contextWindowTokens)
                assertEquals(config.contextWindowTokens - config.maxOutputTokens - config.providerOverheadTokens, config.inputBudgetTokens)
            }
            assertEquals(binding, configs.last().binding)
        }
        assertEquals(ProviderProtocol.RESPONSES, custom.provider.protocol)
    }

    @Test fun messagesCodexAndOauthKeepTheirNativeProtocolAndCredentialReference() {
        val sources = listOf(settings().copy(endpoint = "https://fixture.invalid/v1/messages", protocol = ProviderProtocol.MESSAGES),
            settings().copy(requestOptions = ProviderRequestOptions(codex = true)),
            settings().copy(endpoint = CodexOAuthProtocol.BASE_URL, responsesPath = "/responses", apiKey = "",
                oauth = CodexAuth(UUID.randomUUID().toString())))
        for (source in sources) for (protocol in listOf(ProviderProtocol.CHAT_COMPLETIONS, ProviderProtocol.RESPONSES)) {
            val config = CatalogSummaryExecutionPolicy.chatConfiguration(source.requestSnapshot(), 32000, protocol)
            assertEquals(source.protocol, config.provider.protocol)
            assertEquals(source.requestEndpoint(), config.provider.asModelSettings().requestEndpoint())
            assertEquals(source.oauth, config.provider.oauth)
            assertEquals(source.apiKey, config.provider.apiKey)
        }
    }

    @Test fun defaultResolverKeepsExplicitConfigurationAndUnsupportedCustomPathsNeverFallBack() {
        val source = settings()
        val saved = CatalogSummaryExecutionPolicy.chatConfiguration(source.requestSnapshot(), 32000, ProviderProtocol.RESPONSES)
        val manual = saved.withSummaryProtocol(ProviderProtocol.CHAT_COMPLETIONS)
        assertEquals(ProviderProtocol.CHAT_COMPLETIONS, manual.provider.protocol)
        assertEquals(ProviderProtocol.RESPONSES, saved.provider.protocol)
        val wire = Connection(); var actual: URL? = null
        val resolver = ProviderSummaryModelResolver({ manual }, { actual = it; wire },
            SummaryDeadlineScheduler { _, _ -> AutoCloseable {} })
        val bound = resolver.resolve(manual.binding, null)!!
        val session = bound.transport.openWithoutTools(history(), bound.startDeadline())
        assertThrows(ModelRequestException::class.java) { session.next(emptyList()) {} }
        assertEquals("https://fixture.invalid/v1/chat/completions", actual.toString())
        assertThrows(IllegalArgumentException::class.java) {
            ManualSummaryTargets.forSummary(source.copy(responsesPath = "/custom-summary"), ProviderProtocol.CHAT_COMPLETIONS)
        }
    }
}
