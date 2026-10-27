package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.runtime.Composable
import app.daiyu.android.model.catalog.CatalogModelParameters
import app.daiyu.android.model.catalog.CatalogModelSelection
import app.daiyu.android.model.catalog.SavedModelCatalogSnapshot
import app.daiyu.android.model.catalog.selectionFor
import app.daiyu.android.ui.screens.settings.ModelCatalogEntryUi

/**
 * One detached local-only picker session. No network or settings IO on the composition thread.
 * Keep the session together with the host's owner token and expected active settings. Replacing
 * it on reload replaces ALL tickets; never resolve an old click against a newly read snapshot.
 */
internal class LocalCatalogChatModelPickerSession(
    snapshot: SavedModelCatalogSnapshot<CatalogModelParameters>?,
    currentModel: String,
    providerId: String = "current",
    providers: List<ChatModelProviderUi> = emptyList(),
    providerCatalogs: Map<String, SavedModelCatalogSnapshot<CatalogModelParameters>> = emptyMap(),
) {
    private val snapshot = snapshot?.copy(models = snapshot.models.toList())
    private val catalogs = providerCatalogs.mapValues { (_, value) -> value.copy(models = value.models.toList()) }
    val state = ChatModelPickerState(
        current = currentModel,
        providerId = providerId,
        providers = providers,
        models = snapshot?.models.orEmpty().map { ModelCatalogEntryUi(it.id, it.displayName) },
        message = if (snapshot?.models.isNullOrEmpty()) "本地目录为空，请到模型设置导入" else null,
    )

    fun selectionFor(id: String): CatalogModelSelection<CatalogModelParameters>? = snapshot?.selectionFor(id)
    fun selectionFor(providerId: String, id: String): CatalogModelSelection<CatalogModelParameters>? =
        if (providerId == state.providerId) selectionFor(id) else catalogs[providerId]?.selectionFor(id)
}

/** Reuses the production searchable radio picker with the current local snapshot. */
@Composable
internal fun LocalCatalogChatModelPicker(
    session: LocalCatalogChatModelPickerSession,
    enabled: Boolean,
    loading: Boolean = false,
    message: String? = session.state.message,
    onSelect: (CatalogModelSelection<CatalogModelParameters>) -> Unit,
    onSelectOtherProvider: (String, CatalogModelSelection<CatalogModelParameters>) -> Unit,
    onDismiss: () -> Unit,
    switching: Boolean = false,
) {
    ChatModelPicker(
        state = session.state.copy(enabled = enabled && !loading, loading = loading, message = message, switching = switching),
        onSelect = { id -> if (enabled && !loading && !switching) session.selectionFor(id)?.let(onSelect) },
        onDismiss = onDismiss,
        onSelectOtherProvider = { providerId, id ->
            if (enabled && !loading && !switching) session.selectionFor(providerId, id)?.let { onSelectOtherProvider(providerId, it) }
        },
    )
}
