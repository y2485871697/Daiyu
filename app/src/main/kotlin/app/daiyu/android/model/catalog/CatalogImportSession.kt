package app.daiyu.android.model.catalog

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Settings import lifecycle. Call on the host UI thread except performCommit/cancellation
 * predicates. begin/fetched suspend for IO reads, then resume on that same UI context and recheck
 * the request token. Tokens, not URLs or result ordering, identify requests. No credential is retained.
 */
internal class CatalogImportSession<T>(
    private val persistence: SavedModelCatalogPersistence<T>,
    createModel: (RemoteModel) -> SavedCatalogModel<T>,
    private val onChange: (State) -> Unit = {},
) {
    data class State(
        val loading: Boolean = false,
        val saving: Boolean = false,
        val selection: ModelSelectionState? = null,
        val status: String? = null,
        val error: String? = null,
    )
    class Request internal constructor(val providerIdentity: String) {
        var revision: Long = -1
            internal set
        private val cancelled = AtomicBoolean(false)
        fun isCancelled(): Boolean = cancelled.get()
        internal fun cancel() { cancelled.set(true) }
    }
    class Commit internal constructor(internal val request: Request, internal val selection: ModelSelectionState)
    var state = State()
        private set
    private val store = SavedModelCatalogStore(persistence, createModel)
    private var active: Request? = null
    private var committing: Commit? = null

    /** Snapshot is captured on IO before the host starts the network request. */
    suspend fun begin(providerIdentity: String): Request? {
        invalidate()
        val request = Request(providerIdentity)
        active = request
        update(State(loading = true))
        val snapshot = readSnapshot(request)
        // Edits, a newer fetch or disposal can invalidate this request while the OS lock blocks.
        if (!isLoading(request)) return null
        if (snapshot == null || snapshot.providerIdentity != providerIdentity) {
            request.cancel(); active = null
            update(State(status = "服务商目录不可用，请重新打开设置")); return null
        }
        request.revision = snapshot.revision
        return request
    }

    suspend fun fetched(request: Request, models: List<RemoteModel>?) {
        if (!isLoading(request)) return
        if (models == null) { update(State(status = "获取失败，请重试")); return }
        val snapshot = readSnapshot(request)
        // Recheck on UI, not just before dispatching IO. Never resurrect an obsolete draft/dialog.
        if (!isLoading(request)) return
        if (snapshot == null || snapshot.providerIdentity != request.providerIdentity || snapshot.revision != request.revision) {
            request.cancel(); active = null
            update(State(status = "服务商或目录已变化，请重新获取")); return
        }
        val selection = ModelSelectionState.capture(request.providerIdentity, request.revision, models)
        update(if (selection.totalCount == 0) State(status = "模型目录为空") else State(selection = selection))
    }

    private fun isLoading(request: Request) = active === request && !request.isCancelled() && state.loading

    private suspend fun readSnapshot(request: Request): SavedModelCatalogSnapshot<T>? = withContext(Dispatchers.IO) {
        if (request.isCancelled()) null
        else try { persistence.read(request.providerIdentity) } catch (_: Exception) { null }
    }

    fun rejected(request: Request) {
        if (active === request && state.loading && !request.isCancelled()) update(State(status = "当前不可查询，请重试"))
    }

    fun select(selection: ModelSelectionState) {
        val old = state.selection ?: return
        if (state.saving || old.providerIdentity != selection.providerIdentity || old.expectedRevision != selection.expectedRevision ||
            old.models !== selection.models) return
        update(state.copy(selection = selection, error = null))
    }

    fun prepareCommit(selection: ModelSelectionState): Commit? {
        val request = active ?: return null
        if (request.isCancelled() || state.saving || state.selection !== selection || !selection.canConfirm) return null
        return Commit(request, selection).also { committing = it; update(state.copy(saving = true, error = null)) }
    }

    /** Worker-side. The store checks the token inside the durable transaction as well. */
    fun performCommit(commit: Commit): CatalogImportResult = store.appendSelected(commit.selection, commit.request::isCancelled)

    /** Applied wins over a subsequent cancellation; there is no rollback or second save. */
    fun completed(commit: Commit, result: CatalogImportResult) {
        if (committing !== commit || active !== commit.request) return
        committing = null
        when (result) {
            is CatalogImportResult.Applied -> {
                active = null
                update(State(status = "已添加 ${result.addedCount} 个模型"))
            }
            CatalogImportResult.Stale -> {
                commit.request.cancel(); active = null
                update(State(status = "服务商或目录已变化，请重新获取"))
            }
            CatalogImportResult.Cancelled -> { active = null; update(State()) }
            CatalogImportResult.EmptySelection -> update(state.copy(saving = false))
            CatalogImportResult.Failed -> update(state.copy(saving = false, error = "模型未添加，请重试"))
        }
    }

    /** Back/outside/cancel are disabled during saving; host disposal or draft changes still fence it. */
    fun dismiss() { if (!state.saving) invalidate() }
    fun invalidate() {
        active?.cancel(); active = null; committing = null
        update(State())
    }
    private fun update(next: State) { state = next; onChange(next) }
}
