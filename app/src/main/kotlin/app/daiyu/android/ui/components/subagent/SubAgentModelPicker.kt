package app.daiyu.android.ui.components.subagent

import androidx.compose.runtime.Composable
import app.daiyu.android.delegation.SubAgentProvider
import app.daiyu.android.ui.screens.chat.conversation.ChatModelPicker
import app.daiyu.android.ui.screens.chat.conversation.ChatModelPickerState
import app.daiyu.android.ui.screens.chat.conversation.ChatModelProviderUi
import app.daiyu.android.ui.screens.settings.ModelCatalogEntryUi

internal data class SubAgentModelPickerUiState(val providerGroups: List<SubAgentModelGroupUi>, val selectedModel: SubAgentModelOptionUi?)
internal data class SubAgentModelGroupUi(val providerId: String, val providerName: String, val models: List<SubAgentModelOptionUi>)
internal data class SubAgentModelOptionUi(val id: String, val providerId: String, val displayName: String,
    val supportsImageGeneration: Boolean, val supportsVideoGeneration: Boolean, val apiModelId: String = id)
internal object SubAgentModelPickerProjector {
    fun project(providers: List<SubAgentProvider>, selectedProviderId: String?, selectedModelId: String?): SubAgentModelPickerUiState {
        val groups = providers.filter { it.isEnabled }.mapNotNull { provider ->
            val seen = hashSetOf<String>()
            val models = provider.models.filter { it.isEnabled && !it.supportsSpeechSynthesis && seen.add(it.id) }
                .map { SubAgentModelOptionUi(it.id, provider.id, it.displayName.ifBlank { it.modelId }, it.supportsImageGeneration, it.supportsVideoGeneration, it.modelId) }
            models.takeIf { it.isNotEmpty() }?.let { SubAgentModelGroupUi(provider.id, provider.name, it) }
        }
        return SubAgentModelPickerUiState(groups, groups.flatMap { it.models }.firstOrNull { it.providerId == selectedProviderId && it.id == selectedModelId })
    }
}

@Composable
internal fun SubAgentModelPickerDialog(state: SubAgentModelPickerUiState, show: Boolean, onDismiss: () -> Unit,
    onModelSelected: (String, String) -> Unit, title: String) {
    if (!show) return
    val providers = state.providerGroups.map { group ->
        ChatModelProviderUi(group.providerId, group.providerName,
            group.models.map { ModelCatalogEntryUi(it.id, it.displayName, it.apiModelId) })
    }
    val providerId = state.selectedModel?.providerId ?: providers.firstOrNull()?.id.orEmpty()
    fun select(provider: String, model: String) {
        if (state.providerGroups.singleOrNull { it.providerId == provider }?.models?.singleOrNull { it.id == model } != null)
            onModelSelected(provider, model)
    }
    ChatModelPicker(
        state = ChatModelPickerState(current = state.selectedModel?.id.orEmpty(), providerId = providerId,
            models = providers.singleOrNull { it.id == providerId }?.models.orEmpty(), providers = providers,
            message = if (providers.isEmpty()) "没有可用模型" else null),
        onSelect = { select(providerId, it) }, onDismiss = onDismiss,
        onSelectOtherProvider = { provider, model -> select(provider, model) }, title = title, closeOnSelect = true,
    )
}
