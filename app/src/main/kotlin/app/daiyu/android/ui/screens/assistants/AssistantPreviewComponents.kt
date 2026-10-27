package app.daiyu.android.ui.screens.assistants

import app.daiyu.android.ui.app.DaiyuPalette
import app.daiyu.android.ui.app.destructiveColors
import app.daiyu.android.ui.app.resolveAppearanceActions
import app.daiyu.android.ui.app.LocalAppearanceDark
import app.daiyu.android.ui.components.imeCursorBrush
import app.daiyu.android.ui.components.managedInput

import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.ui.app.LocalAppearanceSettings
import app.daiyu.android.ui.app.resolvedPrimaryAccent
import app.daiyu.android.ui.app.styleColor
import app.daiyu.android.ui.app.readableAccent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import app.daiyu.android.ui.haptics.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import app.daiyu.android.ui.haptics.toggleable
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import app.daiyu.android.ui.components.PreviewDialog
import app.daiyu.android.ui.components.defaultBottomSheetHeight
import app.daiyu.android.ui.components.previewGlassBlur
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.basic.Text

/** Assistant surfaces share the app's device-tuned density and retain system font scaling. */
internal data class AssistantPreviewPalette(val dark: Boolean, val appearance: AppearanceSettings = AppearanceSettings()) {
    private val actions = resolveAppearanceActions(appearance, dark)
    private val destructive = destructiveColors(dark)
    val dangerContainer = destructive.container
    val onDangerContainer = destructive.content
    val dangerBorder = destructive.border
    val text = if (dark) Color(0xFFE0DCE8) else Color(0xFF36333F)
    val toolbar = if (dark) Color(0xFFB2A9C7) else Color(0xFF716A82)
    val muted = if (dark) Color(0xFF9C92A8) else Color(0xFF968B9F)
    val accent = appearance.resolvedPrimaryAccent?.let { readableAccent(it, dark) } ?: if (dark) Color(0xFFC6B6D5) else Color(0xFF82718F)
    val line = appearance.styleColor(if (dark) Color(0x29C5B1DB) else Color(0x299B89AA))
    val soft = appearance.styleColor(if (dark) Color(0x17C4ACD7) else Color(0x14A18DB9))
    val secondary = appearance.secondaryAccentColor?.let(::Color) ?: if (dark) Color(0xFFC6B6D5) else Color(0xFF82718F)
    val secondaryContainer = actions.secondaryContainer
    val onSecondary = actions.onSecondary
    val fieldBackground = appearance.styleColor(if (dark) Color(0x22C4ACD7) else Color(0x1FA18DB9))
    val primary = actions.primary
    val onPrimary = actions.onPrimary
    val danger = if (dark) Color(0xFFDF9CA9) else Color(0xFFAE687A)
    val border = appearance.styleColor(if (dark) Color(0x26DCCDFF) else Color(0xEDFFFFFF))
    val background = appearance.styleColor(if (dark) Color(0xFF101218) else Color.White)
    val gradient = (if (dark) listOf(Color(0xF22E283B), Color(0xF21B1825))
        else listOf(Color(0xF0FFFFFF), Color(0xF0F8F4FF))).map(appearance::styleColor)
}

@Composable internal fun assistantPreviewPalette() =
    AssistantPreviewPalette(LocalAppearanceDark.current, LocalAppearanceSettings.current)

@Composable internal fun assistantPreviewScale(): Float =
    1f

@Composable
internal fun AssistantPreviewDialog(
    show: Boolean,
    title: String,
    onDismiss: () -> Unit,
    onBack: (() -> Unit)? = null,
    showCloseButton: Boolean = true,
    onOutsideDismiss: () -> Unit = onDismiss,
    footer: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!show) return
    val density = LocalDensity.current
    PreviewDialog(onDismiss, bottomSheet = true, onOutsideDismiss = onOutsideDismiss) {
        val sheetHeight = defaultBottomSheetHeight(maxHeight)
        CompositionLocalProvider(LocalDensity provides density) {
            AssistantPreviewSheet(title, onDismiss, onBack, showCloseButton, footer,
                modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp)
                    .fillMaxWidth().height(sheetHeight),
                content = content)
        }
    }
}

@Composable
internal fun AssistantPreviewSheet(
    title: String,
    onClose: () -> Unit,
    onBack: (() -> Unit)? = null,
    showCloseButton: Boolean = true,
    footer: @Composable RowScope.() -> Unit = {},
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = assistantPreviewPalette()
    val s = assistantPreviewScale()
    val shape = RoundedCornerShape(25.dp * s)
    Column(modifier.testTag("assistant-sheet").clip(shape).previewGlassBlur(shape = shape, radius = 28f * s, saturation = 1.3f)
        .drawWithCache {
            val length = size.width * .642788f + size.height * .766044f
            val delta = Offset(length * .642788f / 2, length * .766044f / 2)
            val center = Offset(size.width / 2, size.height / 2)
            val brush = Brush.linearGradient(colors.gradient, start = center - delta, end = center + delta)
            onDrawBehind { drawRect(brush) }
        }.border(1.dp, colors.border, shape)
        .pointerInput(Unit) { detectTapGestures(onTap = {}) }
        .padding(1.dp).padding(start = 15.dp * s, end = 15.dp * s, top = 10.dp * s, bottom = 16.dp * s)) {
        Box(Modifier.align(Alignment.CenterHorizontally).width(29.dp * s).height(3.dp * s)
            .clip(CircleShape).background(colors.toolbar.copy(alpha = .24f)))
        Spacer(Modifier.height(17.dp * s))
        Row(Modifier.fillMaxWidth().testTag("assistant-sheet-heading").padding(bottom = 13.dp * s).heightIn(min = 40.dp * s), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp * s)) {
            if (onBack != null) AssistantPreviewIcon(AssistantPreviewIcons.Back, "返回", onBack)
            Text(title, modifier = Modifier.weight(1f), color = colors.text,
                fontSize = (18f * s).sp, lineHeight = (26f * s).sp,
                fontWeight = FontWeight(650), letterSpacing = (-.3f * s).sp)
            if (showCloseButton) AssistantPreviewIcon(AssistantPreviewIcons.Close, "关闭", onClose)
        }
        Column(Modifier.fillMaxWidth().weight(1f), content = content)
        Spacer(Modifier.height(10.dp * s))
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
        Row(Modifier.fillMaxWidth().testTag("assistant-sheet-footer").padding(top = 10.dp * s), horizontalArrangement = Arrangement.spacedBy(8.dp * s), content = footer)
    }
}

@Composable
internal fun AssistantPreviewIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    val s = assistantPreviewScale()
    Box(Modifier.size(40.dp * s).clip(RoundedCornerShape(9.dp * s))
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Icon(icon, label, Modifier.size(20.dp * s), tint = assistantPreviewPalette().muted)
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
    compact: Boolean = false,
    secondary: Boolean = false,
    deletionAppearance: Boolean = false,
) {
    val colors = assistantPreviewPalette()
    val s = assistantPreviewScale()
    val cornerRadius = ButtonDefaults.CornerRadius * s
    val deletion = deletionAppearance && !actionRow
    val surface = if (actionRow) Modifier.clip(RoundedCornerShape(11.dp * s)) else Modifier
        .squircleSurface(color = when { deletion -> colors.dangerContainer; primary -> colors.primary; secondary -> colors.secondaryContainer; danger -> Color(0x0BAE687A); else -> colors.soft },
            cornerRadius = cornerRadius)
        .squircleBorder(width = 1.dp,
            color = when { deletion -> colors.dangerBorder; primary -> colors.primary; secondary -> colors.secondaryContainer; danger -> Color(0x40AE687A); else -> colors.line },
            cornerRadius = cornerRadius)
    val foreground = when { deletion -> colors.onDangerContainer; danger -> colors.danger; primary -> if (actionRow) colors.accent else colors.onPrimary; secondary -> colors.onSecondary; else -> colors.accent }
    val click = Modifier.clickable(enabled = enabled, interactionSource = remember { MutableInteractionSource() },
        indication = null, role = if (compact) Role.Button else null, onClick = onClick)
    Row(modifier.heightIn(min = (if (actionRow || compact) 48 else 44).dp * s).alpha(if (enabled) 1f else .4f)
        .then(if (compact) click.padding(vertical = 8.dp * s) else Modifier)
        .then(surface)
        .then(if (compact) Modifier else click)
        .padding(1.dp).padding(horizontal = 10.dp * s, vertical = (if (compact) 4 else 8).dp * s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (actionRow) Arrangement.Start else Arrangement.Center) {
        if (icon != null) { Icon(icon, null, Modifier.size(18.dp * s), tint = foreground); Spacer(Modifier.width(6.dp * s)) }
        Text(text, color = foreground, fontSize = (14f * s).sp, lineHeight = (20f * s).sp,
            modifier = if (actionRow) Modifier.weight(1f) else Modifier)
        // .ap-actions .ap-button svg:last-child { margin-left:auto; width:10px; opacity:.5 }
        if (actionRow) Icon(AssistantPreviewIcons.Chevron, null, Modifier.size(14.dp * s).alpha(.5f), tint = foreground)
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
    placeholder: String = "",
    readOnly: Boolean = false,
    labelAction: (@Composable () -> Unit)? = null,
) {
    val colors = assistantPreviewPalette()
    val s = assistantPreviewScale()
    var focused by remember { mutableStateOf(false) }
    val textStyle = TextStyle(color = colors.text, fontSize = (14f * s).sp,
        lineHeight = (21f * s).sp,
        fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default)
    Column(modifier.padding(bottom = bottomSpacing)) {
        if (label.isNotBlank()) Row(Modifier.fillMaxWidth().padding(bottom = 5.dp * s),
            verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = colors.text, fontSize = (13f * s).sp, lineHeight = (20f * s).sp,
                modifier = Modifier.weight(1f))
            labelAction?.invoke()
        }
        BasicTextField(value, onValueChange, enabled = enabled, readOnly = readOnly,
            singleLine = singleLine, minLines = minLines, maxLines = maxLines,
            textStyle = textStyle, cursorBrush = imeCursorBrush(colors.accent),
            modifier = fieldModifier.managedInput().fillMaxWidth().heightIn(min = minHeight, max = maxHeight).onFocusChanged { focused = it.isFocused }
                .clip(RoundedCornerShape(11.dp * s)).background(colors.fieldBackground)
                .border(1.dp, if (focused) colors.accent else colors.line, RoundedCornerShape(11.dp * s)),
            decorationBox = { innerTextField ->
                Box(Modifier.padding(1.dp).padding(horizontal = 10.dp * s, vertical = 9.dp * s)) {
                    if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, color = colors.muted,
                        fontSize = textStyle.fontSize, lineHeight = textStyle.lineHeight,
                        fontFamily = textStyle.fontFamily)
                    innerTextField()
                }
            })
    }
}

@Composable
internal fun AssistantPreviewSwitch(checked: Boolean, enabled: Boolean, label: String,
    modifier: Modifier = Modifier, onToggle: (Boolean) -> Unit) {
    val colors = assistantPreviewPalette()
    val s = assistantPreviewScale()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp * s)) {
        Text(label, color = colors.muted, fontSize = (12f * s).sp, lineHeight = (18f * s).sp)
        Box(modifier.size(36.dp * s, 22.dp * s).alpha(if (enabled) 1f else .35f)
            .toggleable(checked, enabled = enabled, role = Role.Switch,
                interactionSource = remember { MutableInteractionSource() }, indication = null, onValueChange = onToggle),
            contentAlignment = Alignment.Center) {
            Box(Modifier.size(36.dp * s, 22.dp * s).clip(CircleShape)
                .background(if (checked) (colors.appearance.resolvedPrimaryAccent ?: if (colors.dark) Color(0xFFB3A0C7) else Color(0xFFA18BB8)) else colors.soft)
                .border(1.dp, if (checked) Color.Transparent else colors.line, CircleShape)
                .padding(horizontal = 3.dp * s)) {
                Box(Modifier.align(if (checked) Alignment.CenterEnd else Alignment.CenterStart)
                    .size(16.dp * s).testTag("assistant-preview-switch-thumb")
                    .clip(CircleShape).background(if (checked) DaiyuPalette.switchThumb else colors.muted))
            }
        }
    }
}

@Composable
internal fun AssistantPreviewSection(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = assistantPreviewPalette()
    val s = assistantPreviewScale()
    val legendOffset = with(LocalDensity.current) { -(17.6.sp.toDp() / 2) }
    var legendWidth by remember(title) { mutableStateOf(0) }
    Box(modifier.fillMaxWidth().padding(bottom = 14.dp * s, top = if (title == null) 0.dp else 7.dp * s)) {
        Column(Modifier.fillMaxWidth().drawWithCache {
            val stroke = 1.dp.toPx()
            val radius = CornerRadius((14.dp * s).toPx())
            val center = Offset(size.width / 2, size.height / 2)
            val delta = (size.width + size.height) / 4
            val brush = Brush.linearGradient(listOf(colors.soft, Color.Transparent),
                start = center - Offset(delta, delta), end = center + Offset(delta, delta))
            val borderOffset = Offset(stroke / 2, stroke / 2)
            val borderSize = Size(size.width - stroke, size.height - stroke)
            val borderRadius = CornerRadius(radius.x - stroke / 2)
            onDrawBehind {
                drawRoundRect(brush, cornerRadius = radius)
                if (title == null) drawRoundRect(colors.line, topLeft = borderOffset, size = borderSize,
                    cornerRadius = borderRadius, style = Stroke(stroke))
                else clipRect(left = (10.dp * s).toPx(), top = 0f,
                    right = (10.dp * s).toPx() + legendWidth, bottom = stroke, clipOp = ClipOp.Difference) {
                    drawRoundRect(colors.line, topLeft = borderOffset, size = borderSize,
                        cornerRadius = borderRadius, style = Stroke(stroke))
                }
            }
        }.padding(1.dp).padding(horizontal = 10.dp * s, vertical = 12.dp * s), content = content)
        if (title != null) Box(Modifier.testTag("assistant-section-legend:$title")
            .offset(x = 10.dp * s, y = legendOffset * s)
            .onSizeChanged { legendWidth = it.width }.padding(horizontal = 5.dp * s)) {
            Text(title, color = colors.text, fontSize = (11f * s).sp,
                lineHeight = (17.6f * s).sp, fontWeight = FontWeight(550))
        }
    }
}
