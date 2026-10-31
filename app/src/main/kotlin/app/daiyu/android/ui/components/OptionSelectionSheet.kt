package app.daiyu.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
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
    itemSpacing: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    ChatSelectionSheet(title = title, onDismiss = onDismiss, subtitle = subtitle, showCloseButton = false, titleFontSize = 20.sp) {
        Column(modifier.fillMaxWidth().semantics { paneTitle = title }
            .verticalScroll(rememberScrollState()).selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(itemSpacing)) {
            content()
        }
    }
}

@Composable
internal fun OptionSelectionItem(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    cardStyle: Boolean = false,
    summary: String? = null,
    onClick: () -> Unit,
) {
    val colors = usagePalette()
    val background = when {
        selected -> SolidColor(colors.selectionContainer)
        cardStyle -> Brush.linearGradient(colors.sheet)
        else -> SolidColor(Color.Transparent)
    }
    val shape = if (cardStyle) RoundedCornerShape(16.dp) else RoundedCornerShape(12.dp)
    val selectedContent = colors.selectionContent
    val indicatorColor = if (cardStyle) selectedContent else colors.accent
    Row(modifier.fillMaxWidth().alpha(if (enabled) 1f else .45f).clip(shape)
        .background(background)
        .then(if (cardStyle) Modifier.border(1.dp, if (selected) colors.selectionContent else colors.edge, shape) else Modifier)
        .selectable(selected = selected, enabled = enabled, role = Role.RadioButton,
            interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
        .heightIn(min = if (cardStyle) 48.dp else 56.dp)
        .padding(horizontal = 12.dp, vertical = if (cardStyle) 12.dp else 14.dp),
        horizontalArrangement = Arrangement.spacedBy(if (cardStyle) 12.dp else 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text, color = if (selected) selectedContent else colors.text,
                fontSize = if (cardStyle) 14.sp else 16.sp,
                lineHeight = if (cardStyle) 20.sp else 24.sp,
                fontWeight = if (cardStyle && selected) FontWeight.Medium else FontWeight.Normal)
            summary?.let { Text(it, color = colors.muted, fontSize = 14.sp, lineHeight = 20.sp) }
        }
        Box(Modifier.size(22.dp).clip(CircleShape)
            .background(if (selected) indicatorColor else Color.Transparent), contentAlignment = Alignment.Center) {
            if (selected) Icon(DaiyuIcons.Check, "已选择", Modifier.size(14.dp), tint = if (cardStyle) colors.selectionContainer else colors.onPrimary)
        }
    }
}
