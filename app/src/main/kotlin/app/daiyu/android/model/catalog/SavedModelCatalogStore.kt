package app.daiyu.android.model.catalog

import java.util.concurrent.CancellationException

/**
 * id is the wire model ID, not a newly allocated local/global identity. T is the host's complete
 * local record (including any existing local UUID/settings), not a replacement settings schema.
 */
internal data class SavedCatalogModel<T>(val id: String, val displayName: String, val settings: T)

/** Detached, immutable snapshot supplied by the host's existing registry. */
internal data class SavedModelCatalogSnapshot<T>(
    val providerIdentity: String,
    val revision: Long,
    val models: List<SavedCatalogModel<T>>,
)

internal enum class CatalogCommitResult { COMMITTED, STALE, CANCELLED, FAILED }

/**
 * Adapter to the host's durable registry, NOT a new provider registry or identity allocator.
 *
 * compareAndAppend is the transaction/linearization boundary. In ONE atomic operation it must:
 * - recheck that this is still the same live providerIdentity and expectedRevision;
 * - check cancellation before committing, preserve every old record byte-for-byte, append only
 *   additions, and advance the revision (all registry edits must advance that revision);
 * - leave durable AND published/in-memory state unchanged on STALE, CANCELLED, FAILED or throw.
 * It must not select a current model or save/replace ModelSettingsStore's active settings.
 * A read + unconditional save is not a valid implementation of this contract. Cancellation after
 * the transaction has committed is a success, not a failed import that supposedly wrote nothing.
 */
internal interface SavedModelCatalogPersistence<T> {
    fun read(providerIdentity: String): SavedModelCatalogSnapshot<T>?
    fun compareAndAppend(
        providerIdentity: String,
        expectedRevision: Long,
        additions: List<SavedCatalogModel<T>>,
        isCancelled: () -> Boolean,
    ): CatalogCommitResult
}

internal sealed interface CatalogImportResult {
    data class Applied(val addedCount: Int) : CatalogImportResult
    data object EmptySelection : CatalogImportResult
    data object Stale : CatalogImportResult
    data object Cancelled : CatalogImportResult
    data object Failed : CatalogImportResult
}

/**
 * Append-only import coordinator. It has no network, default providers, current-model switching,
 * or credential storage. Construct new local settings only for selected IDs absent from the local
 * snapshot; existing settings (including name, capabilities and request overrides) are untouched.
 * The host supplies both persistence and its normal new-model defaults factory. The factory
 * must be side-effect free and return a detached record; it is not a save/registration callback.
 */
internal class SavedModelCatalogStore<T>(
    private val persistence: SavedModelCatalogPersistence<T>,
    private val createModel: (RemoteModel) -> SavedCatalogModel<T>,
) {
    fun appendSelected(
        selection: ModelSelectionState,
        isCancelled: () -> Boolean = { false },
    ): CatalogImportResult {
        fun cancelled() = Thread.currentThread().isInterrupted || isCancelled()
        return try {
            if (cancelled()) return CatalogImportResult.Cancelled
            if (!selection.canConfirm) return CatalogImportResult.EmptySelection
            val current = persistence.read(selection.providerIdentity) ?: return CatalogImportResult.Stale
            if (current.providerIdentity != selection.providerIdentity || current.revision != selection.expectedRevision) {
                return CatalogImportResult.Stale
            }
            val existingIds = current.models.map { remoteModelKey(it.id) }.toSet()
            val additions = selection.selectedModels.filterNot { remoteModelKey(it.id) in existingIds }.map { remote ->
                createModel(remote).also { require(it.id == remote.id) }
            }
            if (cancelled()) return CatalogImportResult.Cancelled
            // Still perform CAS for an all-existing selection: stale sessions must not report success.
            when (persistence.compareAndAppend(selection.providerIdentity, selection.expectedRevision, additions, ::cancelled)) {
                CatalogCommitResult.COMMITTED -> CatalogImportResult.Applied(additions.size)
                CatalogCommitResult.STALE -> CatalogImportResult.Stale
                CatalogCommitResult.CANCELLED -> CatalogImportResult.Cancelled
                CatalogCommitResult.FAILED -> CatalogImportResult.Failed
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            CatalogImportResult.Cancelled
        } catch (_: CancellationException) {
            CatalogImportResult.Cancelled
        } catch (_: Exception) {
            // Do not expose adapter/factory errors, which may carry endpoints or credentials.
            CatalogImportResult.Failed
        }
    }
}
