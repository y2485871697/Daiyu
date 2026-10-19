package app.daiyu.android.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.combinedClickable
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
    onLongClick: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null, modifier: Modifier = Modifier) {
    if (app.daiyu.android.ui.components.subagent.LocalConversationAgentStyle.current) {
        val colors = app.daiyu.android.ui.components.subagent.conversationAgentColors()
        val model = label == "模型"
        val speed = label == "GPT 速度"
        val thinking = label == "思考深度"
        val plain = model || speed
        Column(modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            if (!speed) Text(label, fontSize = 10.sp, lineHeight = 16.sp, color = colors.text, modifier = Modifier.padding(bottom = 5.dp))
            Row(Modifier.fillMaxWidth().semantics { contentDescription = description }
                .heightIn(min = if (thinking) 31.dp else 0.dp)
                .then(if (plain) Modifier else Modifier.background(colors.soft, RoundedCornerShape(10.dp)).border(1.dp, colors.line, RoundedCornerShape(10.dp)))
                .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                    enabled = enabled, role = Role.Button, hapticFeedbackEnabled = false, onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = if (plain) 0.dp else 10.dp, vertical = if (model || thinking) 7.dp else if (speed) 10.dp else 9.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                if (model || label == "思考深度") Icon(icon, null, Modifier.size(if (model) 12.dp else 13.dp), tint = colors.accent)
                if (speed) Text(label, Modifier.weight(1f), fontSize = 11.sp, lineHeight = 17.6.sp, color = colors.text)
                Text(value, if (speed) Modifier else Modifier.weight(1f),
                    fontSize = if (model || thinking || speed) 10.sp else 11.sp,
                    lineHeight = if (thinking) 14.sp else if (speed) 16.sp else 16.5.sp,
                    color = colors.text.copy(alpha = if (enabled) 1f else .4f),
                    maxLines = if (model || thinking || speed) 1 else 2, overflow = TextOverflow.Ellipsis)
                trailing?.invoke()
                Icon(Icons.Rounded.ExpandMore, null, Modifier.size(12.dp), tint = colors.muted)
            }
            if (plain) HorizontalDivider(color = colors.line)
            if (!badge.isNullOrBlank()) Text(badge, fontSize = 9.sp, lineHeight = 14.4.sp, color = colors.muted, modifier = Modifier.padding(top = 4.dp))
        }
        return
    }
    val colors = MaterialTheme.colorScheme
    val alpha = if (enabled) 1f else 0.38f
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = description }
        .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
            enabled = enabled, role = Role.Button, hapticFeedbackEnabled = false, onClick = onClick, onLongClick = onLongClick)
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
