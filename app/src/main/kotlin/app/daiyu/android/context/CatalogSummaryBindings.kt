package app.daiyu.android.context

import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.storage.ProviderCatalogRunTarget
import app.daiyu.android.storage.ProviderEditorSlot
import org.json.JSONObject

/** Versioned, non-secret execution ticket. Display labels are never used to resolve a model. */
internal object CatalogSummaryBindings {
    private const val PREFIX = "catalog-summary:v1:"
    fun encode(target: ProviderCatalogRunTarget): SummaryModelBinding = SummaryModelBinding(
        PREFIX + JSONObject().put("version", 1).put("slot", target.editor.slotId)
            .put("registryRevision", target.editor.registryRevision)
            .put("catalogIdentity", target.catalogIdentity).put("catalogRevision", target.catalogRevision)
            .put("apiModel", target.apiModel).put("protocol", target.protocol.wireName), target.localIdentity)

    fun decode(binding: SummaryModelBinding): ProviderCatalogRunTarget? {
        if (!binding.providerId.startsWith("catalog-summary:")) return null
        require(binding.providerId.startsWith(PREFIX)) { "摘要目录绑定版本不可用" }
        val json = configurationObject(binding.providerId.removePrefix(PREFIX))
        json.requireFields(setOf("version", "slot", "registryRevision", "catalogIdentity", "catalogRevision", "apiModel", "protocol"))
        require(json.configurationLong("version") == 1L)
        val protocol = ProviderProtocol.entries.single { it.wireName == json.configurationString("protocol") }
        return ProviderCatalogRunTarget(
            ProviderEditorSlot(json.configurationString("slot"), json.configurationLong("registryRevision")),
            json.configurationString("catalogIdentity"), json.configurationLong("catalogRevision"),
            binding.modelId, json.configurationString("apiModel"), protocol)
    }
}
