package app.daiyu.android.storage

import android.content.Context
import app.daiyu.android.model.catalog.*
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.map

internal data class ProviderEditorSlot(val slotId: String, val registryRevision: Long)
internal data class ProviderRunConfiguration(val editor: ProviderEditorSlot, val settings: ModelSettings, val configurationRevision: Long = -1,
    val contextReceiptIdentity: String? = null, val localModelIdentity: String? = null)

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
    // Configuration edits must re-emit even when the provider IDs have not changed.
    fun providerIdsFlow() = registry.changesFlow.map { snapshot().providers.map { it.slotId } }
    fun register(expectedRevision: Long, name: String, admit: () -> Unit = {}) = registry.change(expectedRevision) { old ->
        admit(); ProviderRegistry.requireValidName(name)
        val id = UUID.randomUUID().toString()
        check(old.providers.none { it.slotId == id })
        old.copy(providers = old.providers + RegisteredProvider(id, name, false))
    }
    fun registerConfigured(expectedRevision: Long, name: String, value: ModelSettings,
        protocolChangeAuthorized: Boolean, enabled: Boolean = false, admit: () -> Unit,
    ) = registry.change(expectedRevision) { old ->
        admit(); ProviderRegistry.requireValidName(name); value.validateConnection()
        val id = UUID.randomUUID().toString()
        check(old.providers.none { it.slotId == id })
        resolveStore(id).save(value, protocolChangeAuthorized, allowUnselectedModel = true)
        old.copy(providers = old.providers + RegisteredProvider(id, name, enabled))
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
    fun delete(expectedRevision: Long, slotId: String, admit: () -> Unit = {}) =
        deleteSelected(expectedRevision, setOf(slotId), admit)

    fun deleteSelected(expectedRevision: Long, slotIds: Set<String>, admit: () -> Unit = {}): ProviderRegistrySnapshot {
        val selected = slotIds.toSet()
        require(selected.isNotEmpty())
        return registry.change(expectedRevision) { old ->
            admit(); selected.forEach { requireProvider(old, it) }
            old.copy(providers = old.providers.filterNot { it.slotId in selected },
                activeSlotId = old.activeSlotId.takeUnless { it in selected })
        }
    }
    fun activate(expectedRevision: Long, slotId: String, admit: () -> Unit = {}) = registry.change(expectedRevision) { old ->
        admit(); check(requireProvider(old, slotId).enabled) { "提供商未启用" }
        resolveStore(slotId).load().validate()
        old.copy(activeSlotId = slotId)
    }
    /** Selection and activation use the displayed registry revision and exact local catalog ticket. */
    fun selectActiveCatalogModel(expectedActive: ProviderEditorSlot?, expectedSettings: ModelSettings?,
        target: ProviderEditorSlot, targetSettings: ModelSettings?, ticket: CatalogModelSelection<CatalogModelParameters>,
        cancelled: () -> Boolean,
    ): Unit = registry.access { state ->
        if (!matches(state, target) || (expectedActive != null && !matches(state, expectedActive)))
            throw StaleProviderRegistryException()
        check(state.activeSlotId == expectedActive?.slotId) { "活动提供商已变化，请重新打开选择器" }
        if (expectedActive != null) check(resolveStore(expectedActive.slotId).load() == expectedSettings) {
            "模型配置已变化，请重新打开选择器"
        }
        check(requireProvider(state, target.slotId).enabled) { "提供商未启用" }
        val store = resolveStore(target.slotId)
        store.modelCatalog.readConfiguration {
            check(!cancelled()) { "模型选择页面已关闭" }
            store.selectCatalogModel(targetSettings ?: store.load(), ticket, cancelled)
            if (state.activeSlotId != target.slotId) registry.change(state.revision) {
                it.copy(activeSlotId = target.slotId)
            }
        }
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
        protocolChangeAuthorized: Boolean, requestOptionsReplacementAuthorized: Boolean, headersReplacementAuthorized: Boolean = false, providerName: String? = null, providerEnabled: Boolean? = null, admit: () -> Unit = {},
    ): Unit = registry.access { state ->
        if (!matches(state, editor)) throw StaleProviderRegistryException()
        providerName?.let { ProviderRegistry.requireValidName(it) }
        val store = resolveStore(editor.slotId)
        store.modelCatalog.readConfiguration {
            admit()
            if (expected != null) check(store.loadForEditing() == expected) { "模型配置已变化，请重新打开设置" }
            store.save(value, protocolChangeAuthorized, requestOptionsReplacementAuthorized, headersReplacementAuthorized,
                allowUnselectedModel = true)
            val provider = requireProvider(state, editor.slotId)
            val updated = provider.copy(name = providerName ?: provider.name, enabled = providerEnabled ?: provider.enabled)
            if (updated != provider) registry.change(state.revision) { old ->
                old.copy(providers = old.providers.map { if (it.slotId == editor.slotId) updated else it },
                    activeSlotId = old.activeSlotId.takeUnless { !updated.enabled && it == editor.slotId })
            } else registry.notifyChanged()
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
    fun catalogEndpoint(target: ProviderCatalogRunTarget): String = withCatalogTarget(target) { store, _, _ ->
        store.catalogRequestEndpoint(target.apiModel)
    }
    fun freezeCatalog(target: ProviderCatalogRunTarget, checkDescriptor: (String) -> Unit = {}): ProviderRunConfiguration =
        withCatalogTarget(target) { store, revision, provider ->
            checkDescriptor(store.catalogRequestEndpoint(target.apiModel))
            ProviderRunConfiguration(target.editor, store.loadForRun(target.apiModel).also { it.validate() }.copy(usageIdentity = app.daiyu.android.model.UsageModelIdentity(provider.slotId, provider.name)), revision)
        }
    private fun <T> withCatalogTarget(target: ProviderCatalogRunTarget, block: (ModelSettingsStore, Long, RegisteredProvider) -> T): T = registry.access { state ->
        if (!matches(state, target.editor)) throw StaleProviderRegistryException()
        check(requireProvider(state, target.editor.slotId).enabled) { "提供商未启用" }
        val store = resolveStore(target.editor.slotId)
        store.modelCatalog.readConfiguration { revision ->
            check(revision == target.catalogRevision) { "模型目录已变化" }
            check(store.localModelCatalogProtocol() == target.protocol) { "模型协议已变化" }
            val catalog = checkNotNull(store.localModelCatalogSnapshot()) { "模型目录不可用" }
            check(catalog.providerIdentity == target.catalogIdentity) { "提供商连接已变化" }
            val record = checkNotNull(catalog.models.singleOrNull { it.id == target.apiModel }) { "摘要模型已删除" }
            check(record.settings.localIdentity == target.localIdentity) { "摘要模型记录已变化" }
            block(store, revision, requireProvider(state, target.editor.slotId))
        }
    }
    /** UI metadata only; never decrypts keys/headers, validates credentials or creates a request. */
    internal fun contextUsageModel(): app.daiyu.android.ui.model.ContextUsageModel = registry.access { state ->
        val id = checkNotNull(state.activeSlotId)
        check(requireProvider(state, id).enabled)
        val store = resolveStore(id)
        store.modelCatalog.readConfiguration { revision ->
            val metadata = store.summaryRequestMetadata()
            check(metadata.model.isNotBlank())
            val window = store.modelCatalog.readRegisteredModel(metadata.model)?.settings?.contextWindowOverride
            app.daiyu.android.ui.model.ContextUsageModel(id, metadata.model, revision, window?.toLong(), state.revision,
                contextReceiptIdentity(store, metadata))
        }
    }
    fun freezeActive(): ProviderRunConfiguration = registry.access { state ->
        val id = checkNotNull(state.activeSlotId) { "未选择活动提供商" }
        check(requireProvider(state, id).enabled) { "提供商未启用" }
        val store = resolveStore(id)
        store.modelCatalog.readConfiguration { revision ->
            val settings = store.loadForRun().also { it.validate() }
            ProviderRunConfiguration(ProviderEditorSlot(id, state.revision), settings.copy(
                usageIdentity = app.daiyu.android.model.UsageModelIdentity(id, requireProvider(state, id).name)), revision,
                contextReceiptIdentity(store, settings),
                store.localModelCatalogSnapshot()?.models?.singleOrNull { it.id == settings.model }?.settings?.localIdentity)
        }
    }
    private fun contextReceiptIdentity(store: ModelSettingsStore, settings: ModelSettings): String? {
        val catalog = store.localModelCatalogSnapshot() ?: return null
        val record = catalog.models.singleOrNull { it.id == settings.model } ?: return null
        return app.daiyu.android.ui.model.contextReceiptIdentity(catalog.providerIdentity,
            record.settings.localIdentity, settings.protocol.wireName, settings.requestEndpoint(), settings.requestOptions.customBody)
    }
    fun isCurrentActive(frozen: ProviderRunConfiguration): Boolean = try {
        registry.access { state ->
            if (!matches(state, frozen.editor) || state.activeSlotId != frozen.editor.slotId ||
                !requireProvider(state, frozen.editor.slotId).enabled) false
            else {
                val store = resolveStore(frozen.editor.slotId)
                store.modelCatalog.readConfiguration { revision ->
                    revision == frozen.configurationRevision && store.loadForRun() == frozen.settings.copy(usageIdentity = null)
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
