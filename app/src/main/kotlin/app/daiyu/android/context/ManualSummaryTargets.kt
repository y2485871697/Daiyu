package app.daiyu.android.context

import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.storage.ModelSettings
import app.daiyu.android.storage.ModelSettingsStore
import app.daiyu.android.storage.ProviderCatalogRunTarget
import app.daiyu.android.storage.ProviderEditorSlot
import app.daiyu.android.storage.ProviderRegistry
import app.daiyu.android.storage.ProviderRepository
import org.json.JSONObject

/** Non-secret tickets retained only by this manual operation, never global compression settings. */
internal object ManualSummaryTargets {
    private const val PREFIX = "manual-summary:v1:"
    private val selectable = setOf(ProviderProtocol.CHAT_COMPLETIONS, ProviderProtocol.RESPONSES)
    private data class Ticket(val editor: ProviderEditorSlot, val configurationRevision: Long,
        val apiModel: String, val protocol: ProviderProtocol, val catalog: SummaryModelBinding?) {
        val target: ProviderCatalogRunTarget?
            get() = catalog?.let { requireNotNull(CatalogSummaryBindings.decode(it)) }
    }
    fun isManual(binding: SummaryModelBinding) = binding.providerId.startsWith("manual-summary:")

    fun describe(repo: ProviderRepository, selection: ContextManagementSelection,
        conversationModel: app.daiyu.android.storage.ConversationModelBinding? = null,
    ): SummaryProfileDescriptor {
        val binding = selection.binding ?: return describeCurrent(repo, selection, conversationModel)
        val target = requireNotNull(CatalogSummaryBindings.decode(binding)) { "请选择本地目录模型" }
        return repo.withSummaryCatalogTarget(target) { current ->
            describeCurrent(repo, selection.copy(binding = CatalogSummaryBindings.encode(current)), conversationModel)
        }
    }

    private fun describeCurrent(repo: ProviderRepository, selection: ContextManagementSelection,
        conversationModel: app.daiyu.android.storage.ConversationModelBinding?,
    ): SummaryProfileDescriptor {
        require(selection.protocol in selectable) { "摘要 API 仅支持 Chat Completions / Responses" }
        val selectedBinding = selection.binding ?: conversationModel?.let { binding ->
            repo.withConversationModel(binding) { editor, store, record, revision ->
                val catalog = checkNotNull(store.localModelCatalogSnapshot())
                CatalogSummaryBindings.encode(app.daiyu.android.storage.ProviderCatalogRunTarget(editor, catalog.providerIdentity,
                    revision, record.settings.localIdentity, record.id, store.localModelCatalogProtocol()))
            }
        }
        val target = selectedBinding?.let { requireNotNull(CatalogSummaryBindings.decode(it)) { "请选择本地目录模型" } }
        val editor = target?.editor ?: repo.activeEditor()
        return repo.withEditor(editor) { store ->
            store.modelCatalog.readConfiguration { revision ->
                val model = target?.apiModel ?: store.summaryRequestMetadata().model
                descriptor(repo, encode(Ticket(editor, revision, model, selection.protocol, selectedBinding)))
            }
        }
    }

    fun descriptor(repo: ProviderRepository, binding: SummaryModelBinding): SummaryProfileDescriptor {
        val ticket = decode(binding)
        return repo.withEditor(ticket.editor) { store ->
            store.modelCatalog.readConfiguration { revision ->
                val request = forSummary(checkedMetadata(repo, ticket, store, revision), ticket.protocol)
                SummaryProfileDescriptor(binding, binding.providerId, request.requestEndpoint(), ticket.apiModel)
            }
        }
    }

    fun configuration(repo: ProviderRepository, expected: SummaryProfileDescriptor): SummaryProviderConfiguration {
        val ticket = decode(expected.binding)
        return repo.withEditor(ticket.editor) { store ->
            store.modelCatalog.readConfiguration {
                // Keep registry/catalog locks across metadata comparison and credential capture.
                if (descriptor(repo, expected.binding) != expected) throw SummaryProfileChangedException()
                val target = ticket.target
                val frozen = if (target == null) repo.freezeActive().settings else repo.freezeCatalog(target).settings
                CatalogSummaryExecutionPolicy.configuration(expected.binding,
                    CatalogSummaryExecutionPolicy.summarySettings(forSummary(frozen, ticket.protocol)))
            }
        }
    }

    private fun checkedMetadata(repo: ProviderRepository, ticket: Ticket, store: ModelSettingsStore,
        revision: Long): ModelSettings {
        if (revision != ticket.configurationRevision) throw SummaryProfileChangedException()
        val target = ticket.target
        if (target == null) {
            if (repo.activeEditor() != ticket.editor) throw SummaryProfileChangedException()
            return store.summaryRequestMetadata().also {
                if (it.model != ticket.apiModel) throw SummaryProfileChangedException()
            }
        }
        repo.catalogEndpoint(target)
        val metadata = store.summaryRequestMetadata(target.apiModel)
        val record = checkNotNull(store.modelCatalog.readRegisteredModel(target.apiModel)) { "摘要模型不在本地目录中" }
        return record.settings.applyTo(metadata, record.id)
    }

    internal fun forSummary(settings: ModelSettings, requested: ProviderProtocol): ModelSettings {
        require(settings.protocol in setOf(ProviderProtocol.CHAT_COMPLETIONS, ProviderProtocol.RESPONSES,
            ProviderProtocol.MESSAGES)) { "摘要模型尚不支持 ${settings.protocol.wireName}" }
        val effective = if (settings.protocol == ProviderProtocol.MESSAGES || settings.requestOptions.codex || settings.oauth != null)
            settings.protocol else requested
        if (effective == settings.protocol) return settings
        require(effective in selectable)
        val from = if (settings.protocol == ProviderProtocol.RESPONSES) "/responses" else "/chat/completions"
        val to = if (effective == ProviderProtocol.RESPONSES) "/responses" else "/chat/completions"
        val endpoint = settings.requestEndpoint().trimEnd('/')
        require(endpoint.endsWith(from)) { "自定义请求路径不能自动切换摘要 API，请选择原协议" }
        return settings.copy(endpoint = endpoint.removeSuffix(from) + to, responsesPath = "", protocol = effective)
    }

    private fun encode(ticket: Ticket): SummaryModelBinding {
        val json = JSONObject().put("version", 1).put("slot", ticket.editor.slotId)
            .put("registryRevision", ticket.editor.registryRevision)
            .put("configurationRevision", ticket.configurationRevision)
            .put("apiModel", ticket.apiModel).put("protocol", ticket.protocol.wireName)
            .put("catalog", ticket.catalog?.let(::encodeBinding) ?: JSONObject.NULL)
        return SummaryModelBinding(PREFIX + json, ticket.target?.localIdentity ?: ticket.apiModel)
    }
    private fun decode(binding: SummaryModelBinding): Ticket {
        require(binding.providerId.startsWith(PREFIX)) { "手动摘要绑定版本不可用" }
        val json = configurationObject(binding.providerId.removePrefix(PREFIX))
        json.requireFields(setOf("version", "slot", "registryRevision", "configurationRevision", "apiModel", "protocol", "catalog"))
        require(json.configurationLong("version") == 1L)
        val editor = ProviderEditorSlot(json.configurationString("slot"), json.configurationLong("registryRevision"))
        require(ProviderRegistry.validSlotId(editor.slotId) && editor.registryRevision >= 0)
        val revision = json.configurationLong("configurationRevision")
        val model = json.configurationString("apiModel")
        require(revision >= -1 && model.isNotBlank() && model.length <= 200)
        val protocol = ProviderProtocol.entries.single { it.wireName == json.configurationString("protocol") }
        require(protocol in selectable)
        val catalog = if (json.isNull("catalog")) null else decodeBinding(json.getJSONObject("catalog"))
        return Ticket(editor, revision, model, protocol, catalog).also { ticket ->
            val target = ticket.target
            if (target == null) require(binding.modelId == model)
            else require(target.editor == editor && target.catalogRevision == revision &&
                target.apiModel == model && target.localIdentity == binding.modelId)
        }
    }
}
