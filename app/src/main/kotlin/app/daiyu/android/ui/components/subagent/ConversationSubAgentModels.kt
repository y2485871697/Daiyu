package app.daiyu.android.ui.components.subagent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalView
import app.daiyu.android.ui.haptics.TouchHaptics

@Composable internal fun ConversationSubAgentModels(state: SubAgentModelPickerUiState, onDismiss: () -> Unit,
    onModelSelected: (String, String) -> Unit, title: String, onClearSelection: (() -> Unit)?) {
    val colors = conversationAgentColors()
    val view = LocalView.current
    var expanded by remember { mutableStateOf(state.providerGroups.filter { state.selectedModel == null || it.providerId == state.selectedModel.providerId }.map { it.providerId }.toSet()) }
    SubAgentConfigurationWindow(title, onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()).selectableGroup()) {
            if (onClearSelection != null) ConversationModelOption("无", state.selectedModel == null, symbol = false) { TouchHaptics.click(view); onClearSelection() }
            if (state.providerGroups.isEmpty()) Text("没有可用模型", fontSize = 10.sp, color = colors.muted, modifier = Modifier.padding(vertical = 20.dp))
            state.providerGroups.forEach { group ->
                val open = group.providerId in expanded
                Row(Modifier.fillMaxWidth().padding(top = 7.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    TouchHaptics.click(view); expanded = if (open) expanded - group.providerId else expanded + group.providerId
                }.padding(horizontal = 3.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(group.providerName, Modifier.weight(1f), fontSize = 10.sp, color = colors.accent)
                    Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (open) "收起" else "展开", Modifier.size(11.dp), tint = colors.accent)
                }
                if (open) group.models.forEach { model ->
                    ConversationModelOption(model.displayName, state.selectedModel?.providerId == model.providerId && state.selectedModel?.id == model.id) {
                        TouchHaptics.click(view); onModelSelected(model.providerId, model.id)
                    }
                }
            }
        }
        HorizontalDivider(Modifier.padding(top = 10.dp), color = colors.line)
        ConversationAgentButton("关闭", modifier = Modifier.fillMaxWidth().padding(top = 10.dp), onClick = onDismiss)
    }
}

@Composable private fun ConversationModelOption(label: String, selected: Boolean, symbol: Boolean = true, onClick: () -> Unit) {
    val colors = conversationAgentColors()
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 40.dp)
            .background(if (selected) colors.soft else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(10.dp))
            .selectable(selected, interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = if (selected) 9.dp else 4.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (symbol) Icon(Icons.Rounded.ViewInAr, null, Modifier.size(15.dp), tint = colors.accent)
            Text(label, Modifier.weight(1f), fontSize = 11.sp, color = colors.text)
            if (selected) Icon(Icons.Rounded.Check, null, Modifier.size(15.dp), tint = colors.accent)
        }
        HorizontalDivider(color = colors.line)
    }
}
