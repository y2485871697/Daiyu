package app.daiyu.android.model

import app.daiyu.android.delegation.ReasoningEffort
import app.daiyu.android.model.catalog.CatalogModelParameters
import app.daiyu.android.storage.ModelSettings
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RuntimeModelOverridesTest {
    private fun settings() = ModelSettings("https://example.invalid", "model-a", "synthetic-key", false)
    private fun rejected(action: () -> Unit) {
        try { action(); fail("must reject") } catch (_: IllegalArgumentException) { }
    }

    @Test fun snapshotAndSearchCopiesPreserveAllThreeStates() {
        for (reasoning in listOf<Boolean?>(null, false, true)) for (vision in listOf<Boolean?>(null, false, true)) {
            val source = settings().copy(endpoint = "https://api.openai.com/v1", contextWindowOverride = 8192, reasoningOverride = reasoning,
                visionOverride = vision, requestOptions = ProviderRequestOptions(openAiWebSearchEnabled = true))
            val snapshot = ProviderSnapshot.capture(source, source.protocol, imageInputSupported = true)
            assertEquals(source, snapshot.asModelSettings())
            assertEquals(vision ?: true, snapshot.imageInputSupported)
            val copy = snapshot.withoutNativeSearch()
            assertEquals(8192, copy.contextWindowOverride)
            assertEquals(reasoning, copy.reasoningOverride)
            assertEquals(vision, copy.visionOverride)
            assertEquals(snapshot.imageInputSupported, copy.imageInputSupported)
        }
    }

    @Test fun switchingToDefaultModelEnablesCapabilitiesWithoutInheritingPreviousOverrides() {
        val source = settings().copy(contextWindowOverride = 8192, reasoningOverride = true, visionOverride = true)
        val explicit = CatalogModelParameters(contextWindowOverride = 4096, reasoningOverride = false,
            visionOverride = false).applyTo(source, "model-b")
        assertEquals(4096, explicit.contextWindowOverride)
        assertEquals(false, explicit.reasoningOverride)
        assertEquals(false, explicit.visionOverride)
        val automatic = CatalogModelParameters().applyTo(explicit, "model-c")
        assertNull(automatic.contextWindowOverride)
        assertNull(automatic.reasoningOverride)
        assertNull(automatic.visionOverride)
        assertEquals(true, automatic.defaultVisionSupported)
        assertFalse(automatic.hasModelOverrides)
        assertEquals(source.apiKey, automatic.apiKey)
        assertEquals(true, ProviderSnapshot.capture(automatic).imageInputSupported)
        assertEquals(false, ProviderSnapshot.capture(explicit).imageInputSupported)
        val snapshot = ProviderSnapshot.capture(automatic)
        val high = ReasoningRequestPolicy.freeze(snapshot, ReasoningEffort.HIGH)
        assertNull(high.reasoningOverride)
        assertEquals(true, high.imageInputSupported)
        assertEquals("high", JSONObject(high.requestOptions.customBody).getJSONObject("reasoning").getString("effort"))
    }

    @Test fun disabledReasoningRejectsBothPreferenceAndCustomActivation() {
        val snapshot = ProviderSnapshot.capture(settings().copy(reasoningOverride = false))
        assertEquals(snapshot, ReasoningRequestPolicy.freeze(snapshot, ReasoningEffort.DEFAULT))
        assertEquals(false, ReasoningRequestPolicy.freeze(snapshot, ReasoningEffort.OFF).reasoningOverride)
        rejected { ReasoningRequestPolicy.freeze(snapshot, ReasoningEffort.HIGH) }
        rejected { ReasoningRequestPolicy.validate(JSONObject("""{"reasoning":{"effort":"high"}}"""), ProviderProtocol.RESPONSES, false) }
        ReasoningRequestPolicy.validate(JSONObject("""{"thinking":{"type":"disabled"}}"""), ProviderProtocol.MESSAGES, false)
        rejected { ReasoningRequestPolicy.validate(JSONObject("""{"thinking":{"type":"enabled","budget_tokens":1024}}"""), ProviderProtocol.MESSAGES, false) }
        val allowed = ProviderSnapshot.capture(settings().copy(reasoningOverride = true))
        assertEquals(allowed, ReasoningRequestPolicy.freeze(allowed, ReasoningEffort.DEFAULT))
        rejected { ReasoningRequestPolicy.freeze(allowed, ReasoningEffort.MAX) }
    }

    @Test fun modelOverridesBindCustomBodyAndFinalRequestToExactModel() {
        val options = ProviderRequestOptions(customBody = """{"model":"model-b"}""")
        // The existing unbound text-only merge contract remains unchanged.
        settings().copy(requestOptions = options).validate()
        rejected { settings().copy(requestOptions = options, contextWindowOverride = 8192).validate() }
        rejected { ImageRequestPreflight.bytes(JSONObject("""{"model":"model-b"}"""),
            settings().copy(visionOverride = false), null) { false } }
        ImageRequestPreflight.bytes(JSONObject("""{"model":"model-a"}"""),
            settings().copy(contextWindowOverride = 8192), null) { false }
    }
}
