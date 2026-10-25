package app.daiyu.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.daiyu.android.ui.haptics.selectable
import app.daiyu.android.ui.icons.DaiyuIcons
import app.daiyu.android.ui.screens.chat.conversation.ChatSelectionSheet
import top.yukonga.miuix.kmp.basic.Text

@Composable
internal fun OptionSelectionSheet(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    ChatSelectionSheet(title = title, onDismiss = onDismiss, subtitle = subtitle, showCloseButton = false) {
        Column(modifier.fillMaxWidth().semantics { paneTitle = title }
            .verticalScroll(rememberScrollState()).selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(3.dp), content = content)
    }
}

@Composable
internal fun OptionSelectionItem(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = usagePalette()
    val selectedBackground = if (colors.dark) colors.bubble else Color(0xFFEFEDFB)
    Row(modifier.fillMaxWidth().alpha(if (enabled) 1f else .45f).clip(RoundedCornerShape(12.dp))
        .background(if (selected) selectedBackground else Color.Transparent)
        .selectable(selected = selected, enabled = enabled, role = Role.RadioButton,
            interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
        .heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), color = if (selected) colors.accent else colors.text,
            fontSize = 14.sp, lineHeight = 20.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
        Box(Modifier.size(22.dp).clip(CircleShape)
            .background(if (selected) colors.accent else Color.Transparent), contentAlignment = Alignment.Center) {
            if (selected) Icon(DaiyuIcons.Check, "已选择", Modifier.size(14.dp), tint = if (colors.dark) Color(0xFF211735) else Color.White)
        }
    }
}
