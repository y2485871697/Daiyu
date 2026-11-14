package app.daiyu.android.model.catalog

import java.util.Collections
import java.util.Locale

internal fun remoteModelKey(id: String): String = id.trim().lowercase(Locale.ROOT)

/** 一次成功获取的目录，绑定宿主供应商身份和修订号，排除凭据。
 * 切换供应商、修改草稿或重新获取时丢弃；比较 ID 忽略大小写，发送时保留原拼写。
 */
internal class ModelSelectionState private constructor(
    val providerIdentity: String,
    val expectedRevision: Long,
    val models: List<RemoteModel>,
    val query: String,
    val selectedIds: Set<String>,
) {
    val visibleModels: List<RemoteModel>
        get() {
            val needle = query.trim()
            return if (needle.isEmpty()) models else models.filter {
                it.id.contains(needle, ignoreCase = true) || it.displayName.contains(needle, ignoreCase = true)
            }
        }
    val selectedModels: List<RemoteModel> get() = models.filter { it.id in selectedIds }
    val totalCount: Int get() = models.size
    val visibleCount: Int get() = visibleModels.size
    val selectedCount: Int get() = selectedIds.size
    val canConfirm: Boolean get() = selectedIds.isNotEmpty()

    fun withQuery(value: String): ModelSelectionState = updated(query = value)

    fun toggle(id: String): ModelSelectionState {
        if (models.none { it.id == id }) return this
        return updated(ids = if (id in selectedIds) selectedIds - id else selectedIds + id)
    }

    /** Bulk operations affect search results only, preserving selections hidden by the filter. */
    fun selectAllVisible(): ModelSelectionState = updated(ids = selectedIds + visibleModels.map { it.id })

    fun invertVisible(): ModelSelectionState {
        val visibleIds = visibleModels.map { it.id }.toSet()
        return updated(ids = (selectedIds - visibleIds) + (visibleIds - selectedIds))
    }

    private fun updated(query: String = this.query, ids: Set<String> = selectedIds) = ModelSelectionState(
        providerIdentity, expectedRevision, models, query,
        Collections.unmodifiableSet(LinkedHashSet(ids)),
    )

    companion object {
        /** Empty/failing fetches are not imports. First occurrence of a normalized ID wins. */
        fun capture(providerIdentity: String, expectedRevision: Long, models: List<RemoteModel>): ModelSelectionState {
            require(providerIdentity.isNotBlank())
            require(expectedRevision >= 0)
            val unique = models.asSequence().filter { it.id.isNotBlank() }
                .map { it.copy(id = it.id.trim(), displayName = it.displayName.ifBlank { it.id.trim() }) }
                .distinctBy { remoteModelKey(it.id) }.toList()
            return ModelSelectionState(providerIdentity, expectedRevision,
                Collections.unmodifiableList(unique), "", emptySet())
        }
    }
}
