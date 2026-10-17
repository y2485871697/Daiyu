package app.daiyu.android.model.catalog

/** Detached selection ticket, not authorization. Never resolve a stale ticket by wire ID alone. */
internal data class CatalogModelSelection<T>(
    val providerIdentity: String,
    val expectedRevision: Long,
    val record: SavedCatalogModel<T>,
)

internal fun <T> SavedModelCatalogSnapshot<T>.selectionFor(id: String): CatalogModelSelection<T>? =
    models.singleOrNull { it.id == id }?.let { CatalogModelSelection(providerIdentity, revision, it) }

/**
 * Read/consume port for already imported records. Implementations must recheck identity, revision
 * AND the complete record under the same lease as the host mutation. A read-then-save is unsafe.
 * The callback runs on the caller's worker; it must not acquire the catalog lease after a host lock.
 */
internal interface SavedModelCatalogSelectionPersistence<T> {
    fun current(): SavedModelCatalogSnapshot<T>?
    fun <R> consumeSelection(selection: CatalogModelSelection<T>, change: (SavedCatalogModel<T>) -> R): R
}
