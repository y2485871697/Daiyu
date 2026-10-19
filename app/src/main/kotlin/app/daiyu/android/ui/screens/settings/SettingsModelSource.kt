package app.daiyu.android.ui.screens.settings

import android.content.Context
import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.model.catalog.CatalogModelParameters
import app.daiyu.android.model.catalog.CatalogModelSelection
import app.daiyu.android.model.catalog.selectionFor
import app.daiyu.android.storage.ModelSettings
import app.daiyu.android.storage.ProviderEditorSlot
import app.daiyu.android.storage.ProviderRepository

internal data class SettingsModelChoice(
    val provider: ProviderEditorSlot,
    val providerName: String,
    val protocol: ProviderProtocol,
    val selection: CatalogModelSelection<CatalogModelParameters>,
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
    fun choices(protocols: Set<ProviderProtocol>): List<SettingsModelChoice> {
        val state = providers.snapshot()
        val result = state.providers.filter { it.enabled }.flatMap { provider ->
            val editor = providers.editor(provider.slotId)
            check(editor.registryRevision == state.revision)
            providers.withEditor(editor) { store ->
                store.modelCatalog.readConfiguration {
                    val protocol = store.localModelCatalogProtocol()
                    val catalog = store.localModelCatalogSnapshot()
                    if (protocol !in protocols || catalog == null) emptyList() else catalog.models.map { record ->
                        SettingsModelChoice(editor, provider.name, protocol, checkNotNull(catalog.selectionFor(record.id)))
                    }
                }
            }
        }
        check(providers.snapshot().revision == state.revision)
        return result
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
