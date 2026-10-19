package app.daiyu.android.context

import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.storage.ProviderCatalogRunTarget
import app.daiyu.android.storage.ProviderEditorSlot
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class CatalogSummaryBindingsTest {
    private fun target() = ProviderCatalogRunTarget(ProviderEditorSlot("primary", 3),
        UUID.randomUUID().toString(), 7, UUID.randomUUID().toString(), "summary-only", ProviderProtocol.RESPONSES)

    @Test fun roundTripKeepsExactTargetAndOldBindingsRemainLegacy() {
        val target = target()
        val encoded = CatalogSummaryBindings.encode(target)
        assertEquals(target, CatalogSummaryBindings.decode(encoded))
        assertEquals(encoded, decodeBinding(encodeBinding(encoded)))
        assertNull(CatalogSummaryBindings.decode(SummaryModelBinding("legacy", "model")))
        assertFalse(encoded.providerId.contains("apiKey"))
    }

    @Test fun MalformedDirectoryTicketsNeverFallBackToLegacy() {
        val binding = CatalogSummaryBindings.encode(target())
        val prefix = "catalog-summary:v1:"
        val valid = JSONObject(binding.providerId.removePrefix(prefix))
        val invalid = listOf("catalog-summary:v2:{}", prefix + "not-json",
            prefix + JSONObject(valid.toString()).put("extra", true),
            prefix + JSONObject(valid.toString()).put("registryRevision", "3"),
            prefix + JSONObject(valid.toString()).put("catalogIdentity", "not-uuid"),
            prefix + JSONObject(valid.toString()).put("version", 2))
        for (raw in invalid) {
            var rejected = false
            try { CatalogSummaryBindings.decode(binding.copy(providerId = raw)) } catch (_: Exception) { rejected = true }
            assertTrue(raw, rejected)
        }
    }
}
