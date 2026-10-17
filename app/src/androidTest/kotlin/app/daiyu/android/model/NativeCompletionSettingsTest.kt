package app.daiyu.android.model

import android.test.InstrumentationTestCase
import app.daiyu.android.storage.CredentialUnavailableException
import app.daiyu.android.storage.ModelSettings
import app.daiyu.android.storage.ModelSettingsStore
import app.daiyu.android.testContext
import app.daiyu.android.ui.screens.settings.ModelSettingsDraftUi
import app.daiyu.android.ui.screens.settings.selectProtocol
import app.daiyu.android.model.sessiongateway.SessionGatewayRule
import app.daiyu.android.title.TitleModelCoordinator
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID

/** Offline setting/credential binding regressions, including both new wire protocols. */
@Suppress("DEPRECATION")
class NativeCompletionSettingsTest : InstrumentationTestCase() {
    private val alias = "app.daiyu.android.test.native-completion.${UUID.randomUUID()}"
    private val context by lazy { testContext(instrumentation.targetContext, "$alias-") }
    private val store get() = ModelSettingsStore(context, alias)
    private val protocols = listOf(ProviderProtocol.CHAT_COMPLETIONS, ProviderProtocol.GEMINI)
    private fun settings(protocol: ProviderProtocol) = ModelSettings("https://provider.invalid", "fixture", "synthetic-${protocol.wireName}", false, protocol = protocol)
    private fun rejected(task: () -> Unit) { try { task(); fail("must explicitly reject") } catch (_: IllegalArgumentException) {} }
    override fun tearDown() {
        try {
            val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            java.util.Collections.list(keys.aliases()).filter { it.startsWith("$alias.") }.forEach(keys::deleteEntry)
            context.getSharedPreferences("model-settings", 0).edit().clear().commit()
        } finally { super.tearDown() }
    }

    fun testExplicitProtocolSaveReopenSnapshotAndCatalogIdentity() {
        protocols.forEach { protocol ->
            val next = settings(protocol)
            val before = HashMap(context.getSharedPreferences("model-settings", 0).all)
            rejected { store.save(next) }
            assertEquals(before, context.getSharedPreferences("model-settings", 0).all)
            store.save(next, protocolChangeAuthorized = true)
            assertEquals(protocol.wireName, context.getSharedPreferences("model-settings", 0).getString("protocol", null))
            assertEquals(next, store.load()); assertEquals(next, store.loadForEditing().settings)
            assertEquals(protocol, store.load().requestSnapshot().protocol)
            assertEquals(next.requestEndpoint(), store.load().requestEndpoint())
            assertEquals(protocol.wireName, store.nonSecretConfiguration()["protocol"])
            assertFalse(JSONObject(store.nonSecretConfiguration()).toString().contains(next.apiKey))
            assertEquals(next.model, store.modelCatalogSnapshot().models.single().id)
        }
    }

    fun testRestoreMetadataRequiresCredentialRebindAndDoesNotExecuteOrImportKey() {
        store.save(settings(ProviderProtocol.CHAT_COMPLETIONS), protocolChangeAuthorized = true)
        val plan = store.prepareNonSecretRestore(JSONObject().put("protocol", "gemini").put("endpoint", "https://google.invalid/v1beta")
            .put("model", "models/gemini-fixture").put("responses-path", ""))
        assertTrue(plan.credentialRebindRequired)
        store.applyNonSecretRestore(plan, authorized = true)
        val editing = store.loadForEditing()
        assertEquals(ProviderProtocol.GEMINI, editing.settings.protocol)
        assertEquals("", editing.settings.apiKey); assertTrue(editing.credentialUnavailable)
        try { store.load(); fail("old encrypted key cannot move across protocol") } catch (_: CredentialUnavailableException) {}
        val rebound = editing.settings.copy(apiKey = "explicit-new-key")
        store.save(rebound, protocolChangeAuthorized = true)
        assertEquals(rebound, store.load())
        assertEquals("https://google.invalid/v1beta/models/gemini-fixture:streamGenerateContent?alt=sse", store.load().requestEndpoint())
    }

    fun testDraftProtocolChangeClearsKeyPathAndPreviousConsentWithoutSilentlyResettingOptions() {
        val initial = ModelSettingsDraftUi(endpoint = "https://provider.invalid", apiKey = "old-key", responsesPath = "/responses",
            customBody = "{\"temperature\":0.4}", protocolChangeAuthorized = true)
        protocols.forEach { protocol ->
            val selected = initial.selectProtocol(protocol)
            assertEquals(protocol, selected.protocol); assertEquals(ProviderProtocol.RESPONSES, selected.savedProtocol)
            assertEquals("", selected.apiKey); assertEquals("", selected.responsesPath); assertFalse(selected.protocolChangeAuthorized)
            assertEquals(initial.customBody, selected.customBody)
            val authorized = selected.copy(apiKey = "new-key", protocolChangeAuthorized = true)
            assertEquals(authorized, authorized.selectProtocol(protocol))
        }
    }

    fun testOptionsFiveGatewayFieldsRoundTripAndUnsupportedModesFailClosed() {
        val rule = SessionGatewayRule("^fixture$", "/v1/chat/completions", SessionGatewayRule.DEFAULT_RETENTION,
            SessionGatewayRule.DEFAULT_SOURCE, "prompt_cache_key")
        val options = ProviderRequestOptions("{\"temperature\":0.2}", sessionGateway = rule)
        val chat = settings(ProviderProtocol.CHAT_COMPLETIONS).copy(requestOptions = options)
        store.save(chat, protocolChangeAuthorized = true)
        assertEquals(rule, store.load().requestOptions.sessionGateway)
        assertEquals(options, ProviderRequestOptions.decode(options.encode()))
        protocols.forEach { protocol ->
            rejected { ProviderRequestOptions(speedMode = GptSpeedMode.FAST).validate(protocol) }
            rejected { ProviderRequestOptions(codex = true).validate(protocol) }
            rejected { ProviderRequestOptions("{\"tools\":[]}").validate(protocol) }
        }
        rejected { ProviderRequestOptions("{\"contents\":[]}").validate(ProviderProtocol.GEMINI) }
        rejected { ProviderRequestOptions("{\"service_tier\":\"auto\"}").validate(ProviderProtocol.GEMINI) }
        rejected { ProviderRequestOptions("{\"generationConfig\":{\"candidateCount\":2}}").validate(ProviderProtocol.GEMINI) }
        rejected { ProviderRequestOptions("{\"n\":2}").validate(ProviderProtocol.CHAT_COMPLETIONS) }
        rejected { SessionGatewayRule(keyField = "contents").validate() }
    }

    fun testTitleRequestSnapshotPreservesInstructionsOverloadAndUsesNativeBudgetFields() {
        protocols.forEach { protocol ->
            val frozen = settings(protocol).requestSnapshot()
            val request = TitleModelCoordinator.requestSnapshot(frozen)
            assertEquals(frozen.protocol, request.protocol); assertEquals(frozen.apiKey, request.apiKey)
            assertEquals(frozen.endpoint, request.endpoint); assertEquals(frozen.model, request.model)
            val body = JSONObject(request.requestOptions.customBody)
            if (protocol == ProviderProtocol.CHAT_COMPLETIONS) assertTrue(body.has("max_completion_tokens"))
            else assertTrue(body.getJSONObject("generationConfig").has("maxOutputTokens"))
            assertTrue(ModelFactory.create(request, "native title instructions") is NativeCompletionModel)
        }
    }
}
