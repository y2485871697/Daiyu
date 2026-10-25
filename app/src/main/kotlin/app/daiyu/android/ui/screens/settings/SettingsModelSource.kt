package app.daiyu.android.ui.screens.settings

import android.content.Context
import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.model.catalog.CatalogModelParameters
import app.daiyu.android.model.catalog.CatalogModelSelection
import app.daiyu.android.model.catalog.selectionFor
import app.daiyu.android.voice.providers.SpeechModelCapabilities
import app.daiyu.android.storage.ModelSettings
import app.daiyu.android.storage.ProviderEditorSlot
import app.daiyu.android.storage.ProviderRepository

internal data class SettingsModelChoice(
    val provider: ProviderEditorSlot,
    val providerName: String,
    val protocol: ProviderProtocol,
    val selection: CatalogModelSelection<CatalogModelParameters>,
    val endpoint: String = "",
) {
    val label: String get() = "$providerName / ${selection.record.displayName}"
    val key: String get() = "${provider.slotId}/${selection.record.settings.localIdentity}"
}

/** Directory browsing never opens credentials or changes the main chat model. */
internal class SettingsModelSource(context: Context, private val providers: ProviderRepository) {
    private val labels = context.getSharedPreferences("settings-model-selection", Context.MODE_PRIVATE)
    fun label(feature: String): String? = labels.getString("$feature.label", null)
    fun protocol(feature: String): ProviderProtocol? = ProviderProtocol.entries.firstOrNull { it.wireName == labels.getString("$feature.protocol", null) }
    fun key(feature: String): String? = labels.getString("$feature.key", null)
    fun remember(feature: String, choice: SettingsModelChoice) {
        check(labels.edit().putString("$feature.label", choice.label).putString("$feature.key", choice.key).putString("$feature.protocol", choice.protocol.wireName).commit())
    }
    fun choices(protocols: Set<ProviderProtocol>, includeSpeech: Boolean = false): List<SettingsModelChoice> {
        val state = providers.snapshot()
        val result = state.providers.filter { it.enabled }.flatMap { provider ->
            val editor = providers.editor(provider.slotId)
            check(editor.registryRevision == state.revision)
            providers.withEditor(editor) { store ->
                store.modelCatalog.readConfiguration {
                    val protocol = store.localModelCatalogProtocol()
                    val catalog = store.localModelCatalogSnapshot()
                    val endpoint = store.nonSecretConfiguration()["endpoint"] as? String ?: ""
                    if (protocol !in protocols || catalog == null ||
                        (!includeSpeech && app.daiyu.android.voice.providers.DoubaoSpeech.isOpenspeech(endpoint))) emptyList() else catalog.models.map { record ->
                        SettingsModelChoice(editor, provider.name, protocol, checkNotNull(catalog.selectionFor(record.id)),
                            store.nonSecretConfiguration()["endpoint"] as? String ?: "")
                    }
                }
            }
        }
        check(providers.snapshot().revision == state.revision)
        return result
    }
    fun speechChoices(): List<SettingsSpeechModelChoice> {
        val state = providers.snapshot()
        val cached = choices(SettingsModelProtocols - ProviderProtocol.MESSAGES, includeSpeech = true)
            .filter { SpeechModelCapabilities.readAloud(it.selection.record.id) }
        val result = state.providers.filter { it.enabled }.flatMap { provider ->
            val editor = providers.editor(provider.slotId)
            check(editor.registryRevision == state.revision)
            providers.withEditor(editor) { store ->
                store.modelCatalog.readConfiguration { revision ->
                    val endpoint = store.nonSecretConfiguration()["endpoint"] as? String ?: ""
                    if (store.localModelCatalogProtocol() == ProviderProtocol.MESSAGES ||
                        SpeechModelCapabilities.host(endpoint).let { it == "chatgpt.com" || it.endsWith(".chatgpt.com") }) emptyList()
                    else {
                        val connection = SettingsVoiceProviderChoice(editor, provider.name, endpoint, revision)
                        val existing = cached.filter { it.provider.slotId == provider.slotId }
                        val ids = existing.map { it.selection.record.id.lowercase() }.toSet()
                        val current = store.nonSecretConfiguration()["model"] as? String ?: ""
                        val extras = SpeechModelCapabilities.builtInModels(endpoint).toMutableList()
                        if (SpeechModelCapabilities.readAloud(current) && extras.none { it.first.equals(current, true) }) extras += current to current
                        extras.filter { it.first.lowercase() !in ids }.map { (id, name) ->
                            SettingsSpeechModelChoice(connection, id, name)
                        } + existing.map { SettingsSpeechModelChoice(connection, it.selection.record.id, it.selection.record.displayName, it) }
                    }
                }
            }
        }
        check(providers.snapshot().revision == state.revision)
        return result
    }
    fun <T> withSpeechModel(choice: SettingsSpeechModelChoice, consume: (ModelSettings) -> T): T =
        providers.withEditor(choice.connection.provider) { store ->
            check(providers.snapshot().providers.single { it.slotId == choice.connection.provider.slotId }.enabled)
            store.modelCatalog.readConfiguration { revision ->
                check(revision == choice.connection.configurationRevision)
                val current = store.load()
                check(current.endpoint == choice.connection.endpoint)
                val cached = choice.cached
                val value = if (cached != null) {
                    val catalog = checkNotNull(store.localModelCatalogSnapshot())
                    check(catalog.providerIdentity == cached.selection.providerIdentity && catalog.revision == cached.selection.expectedRevision)
                    check(store.localModelCatalogProtocol() == cached.protocol)
                    val record = checkNotNull(catalog.models.singleOrNull { it == cached.selection.record })
                    record.settings.applyTo(current, record.id)
                } else {
                    check(SpeechModelCapabilities.builtInModels(current.endpoint).any { it.first == choice.model } || current.model == choice.model)
                    current.copy(model = choice.model)
                }
                require(SpeechModelCapabilities.readAloud(value.model))
                checkSpeechConnection(value)
                consume(value)
            }
        }
    private fun checkSpeechConnection(value: ModelSettings) {
        val uri = java.net.URI(value.endpoint)
        require(value.protocol != ProviderProtocol.MESSAGES && uri.scheme == "https" && !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null && uri.rawFragment == null && uri.rawQuery == null)
        require(value.apiKey.isNotBlank() && value.apiKey.length <= 8192 && value.apiKey.none { it == '\r' || it == '\n' })
        require(!uri.host.equals("chatgpt.com", true) && !uri.host.endsWith(".chatgpt.com", true))
    }
    fun providerName(id: String): String? = providers.snapshot().providers.singleOrNull { it.slotId == id }?.name
    fun voiceProviders(engine: String): List<SettingsVoiceProviderChoice> {
        val state = providers.snapshot()
        val result = state.providers.filter { it.enabled }.mapNotNull { provider ->
            val editor = providers.editor(provider.slotId)
            check(editor.registryRevision == state.revision)
            providers.withEditor(editor) { store ->
                store.modelCatalog.readConfiguration { revision ->
                    val endpoint = store.nonSecretConfiguration()["endpoint"] as? String ?: ""
                    val host = SpeechModelCapabilities.host(endpoint)
                    val eligible = when (engine) {
                        "DOUBAO" -> host == "openspeech.bytedance.com"
                        "MIMO" -> host.contains("xiaomimimo")
                        else -> false
                    }
                    if (eligible) SettingsVoiceProviderChoice(editor, provider.name, endpoint, revision) else null
                }
            }
        }
        check(providers.snapshot().revision == state.revision)
        return result
    }
    fun <T> withVoiceProvider(choice: SettingsVoiceProviderChoice, consume: (ModelSettings) -> T): T =
        providers.withEditor(choice.provider) { store ->
            check(providers.snapshot().providers.single { it.slotId == choice.provider.slotId }.enabled)
            store.modelCatalog.readConfiguration { revision ->
                check(revision == choice.configurationRevision)
                val value = store.load()
                check(value.endpoint == choice.endpoint)
                checkSpeechConnection(value)
                consume(value)
            }
        }
    /** Credentials exist only in this worker callback after the displayed ticket is rechecked. */
    fun <T> withModel(choice: SettingsModelChoice, consume: (ModelSettings) -> T): T =
        providers.withEditor(choice.provider) { store ->
            check(providers.snapshot().providers.single { it.slotId == choice.provider.slotId }.enabled)
            store.modelCatalog.readConfiguration {
                val current = checkNotNull(store.localModelCatalogSnapshot())
                check(current.providerIdentity == choice.selection.providerIdentity && current.revision == choice.selection.expectedRevision)
                val record = checkNotNull(current.models.singleOrNull { it == choice.selection.record })
                check(store.localModelCatalogProtocol() == choice.protocol)
                consume(record.settings.applyTo(store.load(), record.id).also { it.validate() })
            }
        }
}

internal data class SettingsVoiceProviderChoice(
    val provider: ProviderEditorSlot,
    val name: String,
    val endpoint: String,
    val configurationRevision: Long,
)

internal data class SettingsSpeechModelChoice(
    val connection: SettingsVoiceProviderChoice,
    val model: String,
    val name: String,
    val cached: SettingsModelChoice? = null,
) {
    val identity: String get() = cached?.selection?.record?.settings?.localIdentity.orEmpty()
}
