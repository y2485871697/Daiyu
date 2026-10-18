package app.daiyu.android.storage

import android.content.Context
import app.daiyu.android.model.catalog.*
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal data class ProviderEditorSlot(val slotId: String, val registryRevision: Long)
internal data class ProviderRunConfiguration(val editor: ProviderEditorSlot, val settings: ModelSettings, val configurationRevision: Long = -1)

/** Fixed-slot access over the existing credential and catalog stores.
 * Worker lock order is registry -> catalog -> settings. Never do network IO or acquire the
 * Controller monitor inside withEditor; never retain its bare store beyond the callback.
 */
internal class ProviderRepository(
    private val registry: ProviderRegistry,
    private val resolveStore: (String) -> ModelSettingsStore,
) {
    constructor(context: Context, primary: ModelSettingsStore = ModelSettingsStore(context)) : this(
        ProviderRegistry(File(context.filesDir, "provider-registry/state-v1.bin")), stores(context, primary))
    fun snapshot() = registry.snapshot()
    fun register(expectedRevision: Long, name: String, admit: () -> Unit = {}) = registry.change(expectedRevision) { old ->
        admit(); ProviderRegistry.requireValidName(name)
        val id = UUID.randomUUID().toString()
        check(old.providers.none { it.slotId == id })
        old.copy(providers = old.providers + RegisteredProvider(id, name, false))
    }
    fun rename(expectedRevision: Long, slotId: String, name: String, admit: () -> Unit = {}) = registry.change(expectedRevision) { old ->
        admit(); requireProvider(old, slotId); ProviderRegistry.requireValidName(name)
        old.copy(providers = old.providers.map { if (it.slotId == slotId) it.copy(name = name) else it })
    }
    fun setEnabled(expectedRevision: Long, slotId: String, enabled: Boolean, admit: () -> Unit = {}) = registry.change(expectedRevision) { old ->
        admit(); requireProvider(old, slotId)
        old.copy(providers = old.providers.map { if (it.slotId == slotId) it.copy(enabled = enabled) else it },
            activeSlotId = old.activeSlotId.takeUnless { !enabled && it == slotId })
    }
    /** Logical deletion, not secure erasure. Removed slot IDs cannot be registered again. */
    fun delete(expectedRevision: Long, slotId: String, admit: () -> Unit = {}) = registry.change(expectedRevision) { old ->
        admit(); requireProvider(old, slotId)
        old.copy(providers = old.providers.filterNot { it.slotId == slotId },
            activeSlotId = old.activeSlotId.takeUnless { it == slotId })
    }
    fun activate(expectedRevision: Long, slotId: String, admit: () -> Unit = {}) = registry.change(expectedRevision) { old ->
        admit(); check(requireProvider(old, slotId).enabled) { "提供商未启用" }
        resolveStore(slotId).load().validate()
        old.copy(activeSlotId = slotId)
    }
    fun editor(slotId: String): ProviderEditorSlot = registry.access { state ->
        requireProvider(state, slotId); ProviderEditorSlot(slotId, state.revision)
    }
    fun activeEditor(): ProviderEditorSlot = registry.access { state ->
        val id = checkNotNull(state.activeSlotId) { "未选择活动提供商" }
        check(requireProvider(state, id).enabled)
        ProviderEditorSlot(id, state.revision)
    }
    fun <T> withEditor(editor: ProviderEditorSlot, block: (ModelSettingsStore) -> T): T = registry.access { state ->
        if (!matches(state, editor)) throw StaleProviderRegistryException()
        block(resolveStore(editor.slotId))
    }
    fun editing(editor: ProviderEditorSlot): EditingSettings = withEditor(editor) { it.loadForEditing() }
    fun save(editor: ProviderEditorSlot, value: ModelSettings, expected: EditingSettings?,
        protocolChangeAuthorized: Boolean, requestOptionsReplacementAuthorized: Boolean, headersReplacementAuthorized: Boolean = false, admit: () -> Unit = {},
    ): Unit = withEditor(editor) { store ->
        store.modelCatalog.readConfiguration {
            admit()
            if (expected != null) check(store.loadForEditing() == expected) { "模型配置已变化，请重新打开设置" }
            store.save(value, protocolChangeAuthorized, requestOptionsReplacementAuthorized, headersReplacementAuthorized)
        }
    }
    fun catalogSnapshot(editor: ProviderEditorSlot): SavedModelCatalogSnapshot<CatalogModelParameters> =
        withEditor(editor) { it.modelCatalogSnapshot() }
    fun catalogPersistence(editor: ProviderEditorSlot): SavedModelCatalogPersistence<CatalogModelParameters> =
        object : SavedModelCatalogPersistence<CatalogModelParameters> {
            override fun read(providerIdentity: String) = try {
                withEditor(editor) { store ->
                    store.modelCatalog.readConfiguration {
                        store.checkCatalogConfiguration()
                        store.modelCatalog.read(providerIdentity)
                    }
                }
            } catch (_: StaleProviderRegistryException) { null }
            override fun compareAndAppend(providerIdentity: String, expectedRevision: Long,
                additions: List<SavedCatalogModel<CatalogModelParameters>>, isCancelled: () -> Boolean,
            ): CatalogCommitResult = try {
                withEditor(editor) { store ->
                    store.modelCatalog.readConfiguration {
                        store.checkCatalogConfiguration()
                        store.modelCatalog.compareAndAppend(providerIdentity, expectedRevision, additions, isCancelled)
                    }
                }
            } catch (_: StaleProviderRegistryException) { CatalogCommitResult.STALE }
        }
    fun freezeActive(): ProviderRunConfiguration = registry.access { state ->
        val id = checkNotNull(state.activeSlotId) { "未选择活动提供商" }
        check(requireProvider(state, id).enabled) { "提供商未启用" }
        val store = resolveStore(id)
        store.modelCatalog.readConfiguration { revision ->
            ProviderRunConfiguration(ProviderEditorSlot(id, state.revision), store.load().also { it.validate() }, revision)
        }
    }
    fun isCurrentActive(frozen: ProviderRunConfiguration): Boolean = try {
        registry.access { state ->
            if (!matches(state, frozen.editor) || state.activeSlotId != frozen.editor.slotId ||
                !requireProvider(state, frozen.editor.slotId).enabled) false
            else {
                val store = resolveStore(frozen.editor.slotId)
                store.modelCatalog.readConfiguration { revision ->
                    revision == frozen.configurationRevision && store.load() == frozen.settings
                }
            }
        }
    } catch (_: Exception) { false }
    private fun matches(state: ProviderRegistrySnapshot, editor: ProviderEditorSlot) =
        state.revision == editor.registryRevision && state.providers.any { it.slotId == editor.slotId }
    private fun requireProvider(state: ProviderRegistrySnapshot, slotId: String) =
        checkNotNull(state.providers.singleOrNull { it.slotId == slotId }) { "提供商已删除" }
    companion object {
        private fun stores(context: Context, primary: ModelSettingsStore): (String) -> ModelSettingsStore {
            val stores = ConcurrentHashMap<String, ModelSettingsStore>()
            stores[ProviderRegistry.PRIMARY] = primary
            return { id ->
                require(ProviderRegistry.validSlotId(id))
                stores.computeIfAbsent(id) { ModelSettingsStore(context, "provider-$id-key", "provider-$id-model") }
            }
        }
    }
}
