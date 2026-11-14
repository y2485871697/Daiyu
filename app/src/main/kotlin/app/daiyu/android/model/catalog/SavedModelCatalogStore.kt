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
 * 宿主目录存储接口，compareAndAppend 在一个原子操作中复查供应商身份和 expectedRevision。
 * 提交前检查取消，原样保留旧记录，仅追加新记录并推进修订号；所有目录编辑都推进该号。
 * 失败、过期、取消或抛错时保留持久及已发布状态；提交后的取消仍报告成功。
 * 导入保持当前模型及活动设置不变。先读取再无条件保存不满足原子提交要求。
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

/** 只为选中且本地不存在的 ID 创建记录，已有名称、能力和请求配置保持不变。
 * 宿主提供存储和无副作用的默认值工厂，工厂返回独立记录，保存与注册由提交步骤处理。
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
