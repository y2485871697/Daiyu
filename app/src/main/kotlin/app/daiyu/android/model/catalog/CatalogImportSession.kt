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
    private val isRemoteModel: (SavedCatalogModel<T>) -> Boolean = { false },
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
        internal var receiving = false
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
    suspend fun begin(providerIdentity: String, connectionSaved: Boolean = true): Request? {
        invalidate()
        if (!connectionSaved) {
            update(State(status = "请先保存提供商设置，再拉取模型")); return null
        }
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

    suspend fun fetched(
        request: Request,
        models: List<RemoteModel>?,
        removeObsolete: suspend (List<CatalogModelSelection<T>>, () -> Boolean) -> CatalogLocalEditResult =
            { _, _ -> CatalogLocalEditResult.FAILED },
    ) {
        if (!isLoading(request) || request.receiving) return
        request.receiving = true
        if (models == null) { update(State(status = "获取失败，请重试")); return }
        val remote = ModelSelectionState.capture(request.providerIdentity, request.revision, models).models
        // An empty/blank response is not authoritative evidence that all models were removed.
        if (remote.isEmpty()) { update(State(status = "模型目录为空")); return }
        var snapshot = readSnapshot(request)
        if (!isLoading(request)) return
        if (snapshot == null || snapshot.providerIdentity != request.providerIdentity || snapshot.revision != request.revision) {
            stale(request); return
        }
        val remoteIds = remote.map { remoteModelKey(it.id) }.toSet()
        val obsolete = snapshot.models.filter { isRemoteModel(it) && remoteModelKey(it.id) !in remoteIds }
        if (obsolete.isNotEmpty()) {
            val before = snapshot
            val tickets = obsolete.map { CatalogModelSelection(before.providerIdentity, before.revision, it) }
            val result = try { removeObsolete(tickets, request::isCancelled) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { CatalogLocalEditResult.FAILED }
            if (!isLoading(request)) return
            when (result) {
                CatalogLocalEditResult.STALE -> { stale(request); return }
                CatalogLocalEditResult.CANCELLED -> { invalidate(); return }
                CatalogLocalEditResult.FAILED -> { update(State(status = "过时模型未清理，请重试")); return }
                CatalogLocalEditResult.APPLIED -> Unit
            }
            snapshot = readSnapshot(request)
            if (!isLoading(request)) return
            val expected = before.copy(revision = Math.addExact(before.revision, 1L), models = before.models - obsolete.toSet())
            if (snapshot != expected) { stale(request); return }
            request.revision = expected.revision
        }
        val existingIds = snapshot.models.map { remoteModelKey(it.id) }.toSet()
        val selection = ModelSelectionState.capture(request.providerIdentity, request.revision,
            remote.filterNot { remoteModelKey(it.id) in existingIds })
        val cleanup = obsolete.takeIf { it.isNotEmpty() }?.let { "已清理 ${it.size} 个过时模型" }
        update(if (selection.totalCount == 0) State(status = listOfNotNull(cleanup, "没有可添加的新模型").joinToString("；"))
            else State(selection = selection, status = cleanup))
    }

    private fun stale(request: Request) {
        request.cancel(); active = null
        update(State(status = "服务商或目录已变化，请重新获取"))
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
