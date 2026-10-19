package app.daiyu.android.ui.screens.assistants

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import app.daiyu.android.ui.components.PreviewDialog
import app.daiyu.android.ui.components.previewGlassBlur
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Local-only translation of preview-agent-cards: .glass-sheet and .ap-* (CSS 89–98, 528–601).
 * CSS pixels map directly to dp geometry and sp typography.
 * Original CSS type ratios are preserved; Android fontScale remains in charge of accessibility.
 * No application theme or settings component is changed by these tokens.
 */
internal data class AssistantPreviewPalette(val dark: Boolean) {
    val text = if (dark) Color(0xFFE0DCE8) else Color(0xFF36333F)
    val muted = if (dark) Color(0xFF9C92A8) else Color(0xFF968B9F)
    val accent = if (dark) Color(0xFFC6B6D5) else Color(0xFF82718F)
    val line = if (dark) Color(0x29C5B1DB) else Color(0x299B89AA)
    val soft = if (dark) Color(0x17C4ACD7) else Color(0x14A18DB9)
    val primary = if (dark) Color(0xFFB6A4C7) else Color(0xFF8E7C9E)
    val onPrimary = if (dark) Color(0xFF261F30) else Color.White
    val danger = if (dark) Color(0xFFDF9CA9) else Color(0xFFAE687A)
    val border = if (dark) Color(0x26DCCDFF) else Color(0xEDFFFFFF)
    val background = if (dark) Color(0xFF101218) else Color.White
    val gradient = if (dark) listOf(Color(0xF22E283B), Color(0xF21B1825))
        else listOf(Color(0xF0FFFFFF), Color(0xF0F8F4FF))
}

@Composable internal fun assistantPreviewPalette() =
    AssistantPreviewPalette(MiuixTheme.colorScheme.background.luminance() < .5f)

@Composable internal fun assistantPreviewScale(): Float =
    1f

@Composable
internal fun AssistantPreviewDialog(
    show: Boolean,
    title: String,
    onDismiss: () -> Unit,
    onBack: (() -> Unit)? = null,
    footer: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!show) return
    PreviewDialog(onDismiss) {
        AssistantPreviewSheet(title, onDismiss, onBack, footer,
            modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp)
                .fillMaxWidth().height(minOf(520.dp, (maxHeight - 48.dp).coerceAtLeast(0.dp))),
            content = content)
    }
}

@Composable
internal fun AssistantPreviewSheet(
    title: String,
    onClose: () -> Unit,
    onBack: (() -> Unit)? = null,
    footer: @Composable RowScope.() -> Unit = {},
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = assistantPreviewPalette()
    val s = assistantPreviewScale()
    val shape = RoundedCornerShape(25.dp * s)
    Column(modifier.clip(shape).previewGlassBlur(shape = shape, radius = 28f * s, saturation = 1.3f)
        .drawWithCache {
            val length = size.width * .642788f + size.height * .766044f
            val delta = Offset(length * .642788f / 2, length * .766044f / 2)
            val center = Offset(size.width / 2, size.height / 2)
            val brush = Brush.linearGradient(colors.gradient, start = center - delta, end = center + delta)
            onDrawBehind { drawRect(brush) }
        }.border(1.dp, colors.border, shape)
        .pointerInput(Unit) { detectTapGestures(onTap = {}) }
        .padding(start = 15.dp * s, end = 15.dp * s, top = 10.dp * s, bottom = 16.dp * s)) {
        Box(Modifier.align(Alignment.CenterHorizontally).width(29.dp * s).height(3.dp * s)
            .clip(CircleShape).background(colors.accent.copy(alpha = .24f)))
        Spacer(Modifier.height(17.dp * s))
        Row(Modifier.fillMaxWidth().padding(bottom = 13.dp * s), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp * s)) {
            if (onBack != null) AssistantPreviewIcon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", onBack)
            Text(title, modifier = Modifier.weight(1f), color = colors.text,
                fontSize = (16f * s).sp, fontWeight = FontWeight.Medium)
            AssistantPreviewIcon(Icons.Rounded.Close, "关闭", onClose)
        }
        Column(Modifier.fillMaxWidth().weight(1f), content = content)
        Spacer(Modifier.height(10.dp * s))
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
        Row(Modifier.fillMaxWidth().padding(top = 10.dp * s), horizontalArrangement = Arrangement.spacedBy(8.dp * s), content = footer)
    }
}

@Composable
internal fun AssistantPreviewIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    val s = assistantPreviewScale()
    Box(Modifier.size(28.dp * s).clip(RoundedCornerShape(9.dp * s))
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Icon(icon, label, Modifier.size(15.dp * s), tint = assistantPreviewPalette().muted)
    }
}

@Composable
internal fun AssistantPreviewButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    danger: Boolean = false,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    actionRow: Boolean = false,
) {
    val colors = assistantPreviewPalette()
    val s = assistantPreviewScale()
    val shape = RoundedCornerShape(11.dp * s)
    val foreground = when { danger -> colors.danger; primary -> colors.onPrimary; else -> colors.accent }
    Row(modifier.heightIn(min = (if (actionRow) 42 else 33).dp * s).alpha(if (enabled) 1f else .4f)
        .clip(shape).background(when { actionRow -> Color.Transparent; primary -> colors.primary
            danger -> Color(0x0BAE687A); else -> colors.soft })
        .border(1.dp, when { actionRow -> Color.Transparent; primary -> colors.primary; danger -> Color(0x40AE687A); else -> colors.line }, shape)
        .clickable(enabled = enabled, interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
        .padding(horizontal = 10.dp * s, vertical = 8.dp * s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (actionRow) Arrangement.Start else Arrangement.Center) {
        if (icon != null) { Icon(icon, null, Modifier.size(13.dp * s), tint = foreground); Spacer(Modifier.width(8.dp * s)) }
        Text(text, color = foreground, fontSize = (10f * s).sp, lineHeight = 15.sp)
    }
}

@Composable
internal fun AssistantPreviewField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else 10,
    monospace: Boolean = false,
    fieldModifier: Modifier = Modifier,
    minHeight: Dp = Dp.Unspecified,
    maxHeight: Dp = Dp.Unspecified,
    bottomSpacing: Dp = 13.dp,
) {
    val colors = assistantPreviewPalette()
    val s = assistantPreviewScale()
    var focused by remember { mutableStateOf(false) }
    Column(modifier.padding(bottom = bottomSpacing)) {
        if (label.isNotBlank()) Text(label, color = colors.text, fontSize = (10f * s).sp,
            modifier = Modifier.padding(bottom = 5.dp * s))
        BasicTextField(value, onValueChange, enabled = enabled, singleLine = singleLine, minLines = minLines, maxLines = maxLines,
            textStyle = TextStyle(color = colors.text, fontSize = (11f * s).sp,
                lineHeight = (18.15f * s).sp,
                fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default),
            cursorBrush = SolidColor(colors.accent),
            modifier = fieldModifier.fillMaxWidth().heightIn(min = minHeight, max = maxHeight).onFocusChanged { focused = it.isFocused }
                .clip(RoundedCornerShape(11.dp * s)).background(colors.soft)
                .border(1.dp, if (focused) colors.accent else colors.line, RoundedCornerShape(11.dp * s))
                .padding(horizontal = 10.dp * s, vertical = 9.dp * s))
    }
}

@Composable
internal fun AssistantPreviewSwitch(checked: Boolean, enabled: Boolean, label: String, onToggle: (Boolean) -> Unit) {
    val colors = assistantPreviewPalette()
    val s = assistantPreviewScale()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp * s)) {
        Text(label, color = colors.muted, fontSize = (9f * s).sp)
        Box(Modifier.size(29.dp * s, 18.dp * s).alpha(if (enabled) 1f else .35f)
            .toggleable(checked, enabled = enabled, role = Role.Switch,
                interactionSource = remember { MutableInteractionSource() }, indication = null, onValueChange = onToggle),
            contentAlignment = Alignment.Center) {
            Box(Modifier.size(29.dp * s, 18.dp * s).clip(CircleShape)
                .background(if (checked) (if (colors.dark) Color(0xFFB3A0C7) else Color(0xFFA18BB8)) else colors.soft)
                .border(1.dp, if (checked) Color.Transparent else colors.line, CircleShape)) {
                Box(Modifier.align(Alignment.CenterStart).offset(x = (if (checked) 13 else 2).dp * s)
                    .size(12.dp * s).clip(CircleShape).background(if (checked) Color.White else colors.muted))
            }
        }
    }
}

@Composable
internal fun AssistantPreviewSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    val colors = assistantPreviewPalette()
    val s = assistantPreviewScale()
    Box(Modifier.fillMaxWidth().padding(bottom = 14.dp * s, top = 7.dp * s)) {
        Column(Modifier.fillMaxWidth().border(1.dp, colors.line, RoundedCornerShape(14.dp * s))
            .background(Brush.linearGradient(listOf(colors.soft, Color.Transparent)), RoundedCornerShape(14.dp * s))
            .padding(horizontal = 10.dp * s, vertical = 12.dp * s), content = content)
        Text(title, color = colors.text, fontSize = (11f * s).sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.offset(x = 10.dp * s, y = (-9).dp * s).background(colors.background).padding(horizontal = 5.dp * s))
    }
}
