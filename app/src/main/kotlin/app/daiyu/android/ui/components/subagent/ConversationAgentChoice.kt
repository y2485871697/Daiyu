package app.daiyu.android.ui.components.subagent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import app.daiyu.android.ui.components.PreviewDialog
import app.daiyu.android.ui.components.previewGlassBlur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable internal fun SubAgentChoiceWindow(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    if (!LocalConversationAgentStyle.current) {
        WindowDialog(show = true, title = title, onDismissRequest = onDismiss) { content() }
        return
    }
    val colors = conversationAgentColors()
    PreviewDialog(onDismiss) {
        Surface(Modifier.align(Alignment.Center).padding(24.dp).heightIn(max = (maxHeight - 48.dp).coerceAtLeast(0.dp)).widthIn(max = 320.dp).fillMaxWidth()
            .clip(RoundedCornerShape(22.dp)).previewGlassBlur(RoundedCornerShape(22.dp)), shape = RoundedCornerShape(22.dp), color = androidx.compose.ui.graphics.Color.Transparent,
            border = androidx.compose.foundation.BorderStroke(1.dp, colors.edge)) {
            Column(Modifier.background(Brush.linearGradient(if (colors.dark) listOf(androidx.compose.ui.graphics.Color(0xed302a3d), androidx.compose.ui.graphics.Color(0xef201c2b))
                else listOf(androidx.compose.ui.graphics.Color(0xe8ffffff), androidx.compose.ui.graphics.Color(0xeaf7f3fb))))) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 15.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f), fontSize = 12.sp, lineHeight = 18.sp, color = colors.accent)
                    ConversationAgentIconButton(onClick = onDismiss, modifier = Modifier.size(29.dp)) { Icon(Icons.Rounded.Close, "关闭", Modifier.size(16.dp), tint = colors.muted) }
                }
                HorizontalDivider(Modifier.padding(horizontal = 12.dp), color = colors.line)
                Box(Modifier.padding(6.dp)) { content() }
            }
        }
    }
}

@Composable internal fun ConversationAgentChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = conversationAgentColors()
    Row(Modifier.fillMaxWidth().heightIn(min = 46.dp).background(if (selected) colors.soft else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(13.dp))
        .clickable(role = Role.RadioButton, interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick).padding(horizontal = 12.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 12.sp, lineHeight = 18.sp, color = colors.text)
        Box(Modifier.size(15.dp).border(1.4.dp, if (selected) colors.accent else colors.muted.copy(alpha = .65f), CircleShape), contentAlignment = Alignment.Center) {
            if (selected) Box(Modifier.size(7.dp).background(colors.accent, CircleShape))
        }
    }
}
