package app.daiyu.android.storage

import app.daiyu.android.model.ProviderProtocol
import java.util.UUID

internal data class ProviderCatalogRunTarget(
    val editor: ProviderEditorSlot,
    val catalogIdentity: String,
    val catalogRevision: Long,
    val localIdentity: String,
    val apiModel: String,
    val protocol: ProviderProtocol,
) {
    init {
        require(ProviderRegistry.validSlotId(editor.slotId) && editor.registryRevision >= 0)
        require(UUID.fromString(catalogIdentity).toString() == catalogIdentity && catalogRevision >= 0)
        require(UUID.fromString(localIdentity).toString() == localIdentity)
        require(apiModel.isNotBlank() && apiModel.length <= 200)
        require(protocol == ProviderProtocol.RESPONSES || protocol == ProviderProtocol.MESSAGES)
    }
}
