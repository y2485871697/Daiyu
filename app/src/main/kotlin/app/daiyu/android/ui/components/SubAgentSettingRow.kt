package app.daiyu.android.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import app.daiyu.android.ui.haptics.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.*

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.daiyu.android.ui.icons.daiyuIconColor

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SubAgentSettingRow(label: String, value: String, icon: ImageVector, description: String,
    enabled: Boolean = true, dropdown: Boolean = false, badge: String? = null, onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null, modifier: Modifier = Modifier, divider: Boolean = false) {
    if (app.daiyu.android.ui.components.subagent.LocalConversationAgentStyle.current) {
        val colors = app.daiyu.android.ui.components.subagent.conversationAgentColors()
        Column(modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .semantics { contentDescription = description }
                .alpha(if (enabled) 1f else .4f)
                .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                    enabled = enabled, role = Role.Button, onClick = onClick, onLongClick = onLongClick)
                .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // Match the new model-name icon tint, not the legacy per-glyph palette.
                Icon(icon, null, Modifier.size(20.dp), tint = colors.accent)
                Text(label, Modifier.widthIn(min = 72.dp, max = 100.dp),
                    fontSize = 14.sp, lineHeight = 22.sp, color = colors.text)
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    Text(value, fontSize = 14.sp, lineHeight = 22.sp, color = colors.text,
                        textAlign = TextAlign.End, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (label != "模型" && !badge.isNullOrBlank()) Text(badge, fontSize = 11.sp, lineHeight = 16.sp,
                        color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                trailing?.invoke()
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, Modifier.size(18.dp), tint = colors.accent)
            }
            if (divider) HorizontalDivider(color = colors.line)
        }
        return
    }
    val colors = MaterialTheme.colorScheme
    val alpha = if (enabled) 1f else 0.38f
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = description }
        .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
            enabled = enabled, role = Role.Button, onClick = onClick, onLongClick = onLongClick)
        .padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(22.dp), tint = daiyuIconColor(icon).copy(alpha = alpha))
        Text(label, Modifier.widthIn(min = 72.dp, max = 100.dp), style = MaterialTheme.typography.bodyMedium, color = colors.onSurface.copy(alpha = alpha))
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!badge.isNullOrBlank()) Surface(shape = RoundedCornerShape(6.dp), color = colors.surfaceVariant) {
                Text(badge, modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, color = colors.onSurface.copy(alpha = alpha))
            }
            Text(value, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyMedium, maxLines = 3,
                overflow = TextOverflow.Ellipsis, color = colors.onSurface.copy(alpha = alpha))
        }
        trailing?.invoke()
        Icon(if (dropdown) Icons.Rounded.ExpandMore else Icons.AutoMirrored.Rounded.KeyboardArrowRight, null,
            Modifier.size(18.dp), tint = colors.onSurface.copy(alpha = alpha))
    }
}
