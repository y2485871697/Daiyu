package app.daiyu.android.ui.components.subagent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import app.daiyu.android.ui.components.WithoutPressRipple
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import app.daiyu.android.ui.components.DaiyuAlertDialog as AlertDialog
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.daiyu.android.R
import app.daiyu.android.delegation.SubAgentProvider
import app.daiyu.android.ui.haptics.TouchHaptics

internal data class SubAgentModelPickerUiState(val providerGroups: List<SubAgentModelGroupUi>, val selectedModel: SubAgentModelOptionUi?)
internal data class SubAgentModelGroupUi(val providerId: String, val providerName: String, val models: List<SubAgentModelOptionUi>)
internal data class SubAgentModelOptionUi(val id: String, val providerId: String, val displayName: String,
    val supportsImageGeneration: Boolean, val supportsVideoGeneration: Boolean)
internal object SubAgentModelPickerProjector {
    fun project(providers: List<SubAgentProvider>, selectedProviderId: String?, selectedModelId: String?): SubAgentModelPickerUiState {
        val groups = providers.filter { it.isEnabled }.mapNotNull { provider ->
            val seen = hashSetOf<String>()
            val models = provider.models.filter { it.isEnabled && !it.supportsSpeechSynthesis && seen.add(it.id) }
                .map { SubAgentModelOptionUi(it.id, provider.id, it.displayName.ifBlank { it.modelId }, it.supportsImageGeneration, it.supportsVideoGeneration) }
            models.takeIf { it.isNotEmpty() }?.let { SubAgentModelGroupUi(provider.id, provider.name, it) }
        }
        return SubAgentModelPickerUiState(groups, groups.flatMap { it.models }.firstOrNull { it.providerId == selectedProviderId && it.id == selectedModelId })
    }
}

/** Only the model chooser from TtsModelPickerDialog is migrated; no speech/voice implementation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SubAgentModelPickerDialog(state: SubAgentModelPickerUiState, show: Boolean, onDismiss: () -> Unit,
    onModelSelected: (String, String) -> Unit, title: String, onClearSelection: (() -> Unit)? = null, highlightSelection: Boolean = false) {
    if (!show) return
    if (LocalConversationAgentStyle.current) {
        ConversationSubAgentModels(state, onDismiss, onModelSelected, title, onClearSelection)
        return
    }
    val view = LocalView.current
    var expanded by remember { mutableStateOf(state.selectedModel?.providerId ?: state.providerGroups.singleOrNull()?.providerId) }
    CompositionLocalProvider(LocalRippleConfiguration provides null) {
    WithoutPressRipple {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, tonalElevation = 0.dp,
        containerColor = MaterialTheme.colorScheme.surface,
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                if (onClearSelection != null) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp))
                        .modelSelectionBackground(highlightSelection && state.selectedModel == null, MaterialTheme.colorScheme.primaryContainer)
                        .selectable(selected = state.selectedModel == null, role = Role.RadioButton,
                            interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {
                                TouchHaptics.click(view); onClearSelection()
                            }).padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (!highlightSelection) RadioButton(selected = state.selectedModel == null, onClick = null)
                        Text("无", modifier = Modifier.weight(1f).padding(start = 12.dp), color = MaterialTheme.colorScheme.onSurface)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                if (state.providerGroups.isEmpty()) Text(stringResource(R.string.subagent_provider_empty))
                state.providerGroups.forEachIndexed { index, group ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    val isExpanded = expanded == group.providerId
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button) {
                            TouchHaptics.click(view); expanded = if (isExpanded) null else group.providerId
                        }.padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(group.providerName, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Icon(if (isExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = if (isExpanded) "收起" else "展开")
                    }
                    if (isExpanded) Column(Modifier.selectableGroup()) {
                        group.models.forEach { model ->
                            val selected = state.selectedModel?.providerId == model.providerId && state.selectedModel?.id == model.id
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp))
                                .modelSelectionBackground(highlightSelection && selected, MaterialTheme.colorScheme.primaryContainer)
                                .selectable(selected = selected, role = Role.RadioButton,
                                    interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {
                                        TouchHaptics.click(view); onModelSelected(model.providerId, model.id)
                                    }).padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (!highlightSelection) RadioButton(selected = selected, onClick = null)
                                Text(model.displayName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f).padding(start = 12.dp))
                            }
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { TouchHaptics.click(view); onDismiss() }) { Text(stringResource(R.string.subagent_action_close)) } })
    }
    }
}
private fun Modifier.modelSelectionBackground(selected: Boolean, color: Color): Modifier = if (!selected) this else drawBehind {
    val inset = 1.dp.toPx(); val radius = 10.dp.toPx()
    drawRoundRect(color = color, topLeft = Offset(0f, inset), size = Size(size.width, (size.height - 2 * inset).coerceAtLeast(0f)), cornerRadius = CornerRadius(radius, radius))
}
