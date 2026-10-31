package app.daiyu.android.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import app.daiyu.android.ui.haptics.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.*

import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import app.daiyu.android.delegation.SubAgentTaskTier

@Composable
internal fun SubAgentChoiceField(label: String, value: String, enabled: Boolean, description: String,
    onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val textColor = if (enabled) colors.onSurface else colors.onSurface.copy(alpha = 0.38f)
    Surface(modifier = modifier, shape = RoundedCornerShape(12.dp), color = Color.Transparent,
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = if (enabled) 0.65f else 0.25f))) {
        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).semantics { contentDescription = description }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = textColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(value, style = MaterialTheme.typography.bodyMedium, color = textColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Rounded.ExpandMore, null, Modifier.size(18.dp), tint = textColor)
        }
    }
}

/** Action menus remain anchored; value choices use OptionSelectionSheet. */
@Composable
internal fun SubAgentDropdownMenu(expanded: Boolean, onDismissRequest: () -> Unit, modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset.Zero, content: @Composable ColumnScope.() -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest, modifier = modifier, offset = offset,
        shape = RoundedCornerShape(12.dp), containerColor = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 0.dp, shadowElevation = 0.dp,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))) {
        Column(Modifier.width(IntrinsicSize.Max).widthIn(min = 96.dp, max = 220.dp), content = content)
    }
}
@Composable
internal fun SubAgentSelectionItem(text: String, selected: Boolean, onClick: () -> Unit) {
    OptionSelectionItem(text, selected, onClick = onClick)
}
@Composable
internal fun SubAgentTaskTierButton(label: String, tier: SubAgentTaskTier?, enabled: Boolean,
    onTierSelected: (SubAgentTaskTier) -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    var expanded by remember { mutableStateOf(false) }
    val latestEnabled by rememberUpdatedState(enabled)
    val latestSelection by rememberUpdatedState(onTierSelected)
    LaunchedEffect(enabled) { if (!enabled) expanded = false }
    Box(modifier) {
        if (compact) {
            Box(Modifier.widthIn(min = 48.dp, max = 108.dp).heightIn(min = 48.dp)
                .semantics { contentDescription = "设置${label}任务分工" }
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                    enabled = enabled, role = Role.Button,
                    onClick = { if (latestEnabled) { expanded = !expanded } }), contentAlignment = Alignment.Center) {
                Row(Modifier.clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (enabled) 1f else 0.38f))
                    .padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(tier?.label ?: "设置分工", modifier = Modifier.weight(1f, fill = false), maxLines = 2,
                        overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f))
                    Icon(Icons.Rounded.ExpandMore, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f))
                }
            }
        } else SubAgentChoiceField(label, tier?.label ?: "未设置分工", enabled,
            if (label == "任务分工") "设置任务分工" else "设置${label}任务分工",
            onClick = { if (latestEnabled) { expanded = !expanded } })
        if (enabled && expanded) OptionSelectionSheet("任务分工", { expanded = false }) {
            SubAgentTaskTier.entries.forEach { option ->
                SubAgentSelectionItem(option.label, option == tier) { if (latestEnabled) { latestSelection(option) } }
            }
        }
    }
}
