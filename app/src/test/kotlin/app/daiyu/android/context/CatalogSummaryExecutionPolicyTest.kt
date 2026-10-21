package app.daiyu.android.context

import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.model.ProviderRequestOptions
import app.daiyu.android.storage.ModelSettings
import org.junit.Assert.*
import org.junit.Test

class CatalogSummaryExecutionPolicyTest {
    private val binding = SummaryModelBinding("summary-provider", "summary-only")
    private fun settings() = ModelSettings("https://summary.invalid", "summary-only", "synthetic-key", true,
        deviceStatusEnabled = true, contextWindowOverride = 32768)

    @Test fun defaultChatSummaryKeepsFrozenIdentityAndProjectsChatOptions() {
        val settings = ModelSettings("https://chat.invalid/v1/responses", "chat-model", "fixture-key",
            batteryEnabled = true, deviceStatusEnabled = true, contextWindowOverride = 64000,
            requestOptions = ProviderRequestOptions(
                customBody = """{"reasoning":{"effort":"low"},"max_output_tokens":9000,"temperature":0.5}""",
                openAiWebSearchEnabled = true))
        val snapshot = settings.requestSnapshot()
        val result = CatalogSummaryExecutionPolicy.chatConfiguration(snapshot, 32000)
        assertEquals(snapshot.endpoint, result.provider.endpoint)
        assertEquals(snapshot.protocol, result.provider.protocol)
        assertEquals(snapshot.model, result.provider.model)
        assertEquals(snapshot.apiKey, result.provider.apiKey)
        assertEquals(32000L, result.contextWindowTokens)
        assertFalse(result.provider.batteryEnabled); assertFalse(result.provider.deviceStatusEnabled)
        assertFalse(result.provider.requestOptions.openAiWebSearchEnabled)
        val body = org.json.JSONObject(result.provider.requestOptions.customBody)
        assertEquals(setOf("temperature"), body.keys().asSequence().toSet())
        assertTrue(snapshot.requestOptions.customBody.contains("reasoning"))
    }

    @Test fun defaultSummaryPreservesTheFrozenEffectiveWireModel() {
        val source = settings().copy(model = "configured-A", contextWindowOverride = null,
            requestOptions = ProviderRequestOptions(customBody = """{"model":"wire-B"}"""))
        val snapshot = source.requestSnapshot()
        val result = CatalogSummaryExecutionPolicy.chatConfiguration(snapshot, 32000)
        assertEquals("wire-B", result.provider.model)
        assertEquals("wire-B", result.apiModel)
        assertEquals("configured-A", snapshot.model)
        assertEquals("wire-B", org.json.JSONObject(snapshot.requestOptions.customBody).getString("model"))
    }

    @Test fun unknownCapacityNeverBorrowsMainWindow() {
        try {
            CatalogSummaryExecutionPolicy.configuration(binding, settings().copy(contextWindowOverride = null))
            fail("unknown summary capacity")
        } catch (failure: SummaryProfileUnavailableException) {
            assertEquals(SummaryProfileUnavailableReason.INVALID_CONFIGURATION, failure.reason)
        }
    }

    @Test fun selectedCapacityAndTriStateAreFrozenWithoutChangingOriginalSettings() {
        for (protocol in listOf(ProviderProtocol.RESPONSES, ProviderProtocol.MESSAGES))
            for (reasoning in listOf<Boolean?>(null, false, true)) {
                val source = settings().copy(protocol = protocol, reasoningOverride = reasoning, visionOverride = false)
                val config = CatalogSummaryExecutionPolicy.configuration(binding, source)
                assertEquals(32768L, config.contextWindowTokens)
                assertEquals(config.contextWindowTokens - config.maxOutputTokens - config.reasoningReserveTokens - config.providerOverheadTokens,
                    config.inputBudgetTokens)
                assertEquals(CatalogSummaryExecutionPolicy.OUTPUT_CAP_TOKENS, config.maxOutputTokens)
                assertEquals(reasoning, config.provider.reasoningOverride)
                assertEquals(false, config.provider.visionOverride)
                assertFalse(config.provider.asModelSettings().batteryEnabled)
                assertFalse(config.provider.asModelSettings().deviceStatusEnabled)
                assertTrue(source.batteryEnabled && source.deviceStatusEnabled)
                assertEquals(source.apiKey, config.provider.asModelSettings().apiKey)
            }
    }

    @Test fun InsufficientCapacityAndCustomOverridesAreRejected() {
        val invalid = listOf(settings().copy(contextWindowOverride = 4096),
            settings().copy(requestOptions = ProviderRequestOptions(customBody = """{"max_output_tokens":99999}""")),
            settings().copy(requestOptions = ProviderRequestOptions(customBody = """{"model":"other"}""")))
        for (source in invalid) {
            var rejected = false
            try { CatalogSummaryExecutionPolicy.configuration(binding, source) } catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
        }
        val large = CatalogSummaryExecutionPolicy.configuration(binding, settings().copy(contextWindowOverride = Int.MAX_VALUE))
        assertTrue(large.inputBudgetTokens > Int.MAX_VALUE.toLong() - 10000)
    }
}
