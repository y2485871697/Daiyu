package app.daiyu.android.model.catalog

/** User overrides are local preferences, not verified remote capability evidence. */
internal data class CatalogModelEdit(
    val id: String,
    val displayName: String,
    val contextWindow: String = "",
    val reasoning: Boolean = true,
    val vision: Boolean = true,
) {
    fun validate() {
        require(id.isNotBlank() && id == id.trim() && id.length <= 200 && id.none { it.code < 32 || it.code == 127 })
        require(displayName.isNotBlank() && displayName == displayName.trim() && displayName.length <= 200 &&
            displayName.none { it.code < 32 || it.code == 127 })
        require(contextWindow.isBlank() || contextWindow.toIntOrNull()?.let { it > 0 } == true)
    }
    fun applyTo(record: SavedCatalogModel<CatalogModelParameters>): SavedCatalogModel<CatalogModelParameters> {
        validate()
        require(id == record.id)
        return record.copy(displayName = displayName, settings = record.settings.copy(
            contextWindowOverride = contextWindow.toIntOrNull(), reasoningOverride = reasoning, visionOverride = vision))
    }
    fun newRecord(): SavedCatalogModel<CatalogModelParameters> {
        validate()
        return applyTo(SavedCatalogModel(id, displayName, CatalogModelParameters()))
    }
    companion object {
        fun from(record: SavedCatalogModel<CatalogModelParameters>) = CatalogModelEdit(record.id, record.displayName,
            record.settings.contextWindowOverride?.toString().orEmpty(), record.settings.reasoningSupported, record.settings.visionSupported)
    }
}
