package app.daiyu.android.ui.components

import app.daiyu.android.ui.app.colorContentColor
import app.daiyu.android.ui.app.resolveAppearanceActions
import app.daiyu.android.ui.app.resolveAppearanceColors
import app.daiyu.android.ui.app.LocalAppearanceDark
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.ui.app.LocalAppearanceSettings
import app.daiyu.android.ui.app.readableAccent
import app.daiyu.android.ui.app.resolvedPrimaryAccent
import app.daiyu.android.ui.app.styleColor

import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import app.daiyu.android.ui.haptics.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text

/** Local usage/context presentation only. Never installs a theme above settings. */
internal data class UsagePalette(val dark: Boolean, val appearance: AppearanceSettings = AppearanceSettings()) {
    private val theme = resolveAppearanceColors(appearance, dark)
    private val actions = resolveAppearanceActions(appearance, dark)
    val primary = appearance.resolvedPrimaryAccent ?: Color(if (dark) 0xffc6b6d5 else 0xff82718f)
    val onPrimary = if (appearance.primaryAccentColor != null) colorContentColor(primary) else Color(if (dark) 0xff211735 else 0xffffffff)
    val selectionContainer = if (appearance.primaryAccentColor != null) theme.primaryContainer else appearance.styleColor(Color(if (dark) 0xff28262f else 0xffefedfb))
    val selectionContent = if (appearance.primaryAccentColor != null) theme.onPrimaryContainer else Color(if (dark) 0xffc6b6d5 else 0xff82718f)
    val secondaryContainer get() = if (appearance.secondaryAccentColor != null) actions.secondaryContainer else bubble
    val onSecondaryContainer get() = readableAccent(actions.secondary, dark, secondaryContainer)
    val text = Color(if (dark) 0xffe0dce8 else 0xff36333f)
    val muted = Color(if (dark) 0xff918997 else 0xff968b9f)
    val accent = appearance.resolvedPrimaryAccent?.let { readableAccent(it, dark) } ?: Color(if (dark) 0xffc6b6d5 else 0xff82718f)
    val toolbar = Color(if (dark) 0xffb2a9c7 else 0xff716a82)
    val line = appearance.styleColor(Color(if (dark) 0xff393540 else 0xffe6e1e8))
    val bubble = appearance.styleColor(Color(if (dark) 0xff28262f else 0xfff1eff3))
    val background = appearance.styleColor(Color(if (dark) 0xff101218 else 0xffffffff))
    val sheet = listOf(Color(if (dark) 0xf22e283b else 0xf0ffffff), Color(if (dark) 0xf21b1825 else 0xf0f8f4ff)).map(appearance::styleColor)
    val edge = appearance.styleColor(Color(if (dark) 0x26dccdff else 0xedffffff))
}

@Composable
internal fun usagePalette() = UsagePalette(LocalAppearanceDark.current, LocalAppearanceSettings.current)

@Composable
internal fun UsageGlassDialog(
    title: String,
    onDismiss: () -> Unit,
    compact: Boolean = false,
    adaptiveWidth: Boolean = false,
    useDefaultHeight: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = usagePalette()
    val shape = RoundedCornerShape(if (compact) 16.dp else 25.dp)
    PreviewDialog(onDismiss, bottomSheet = !compact) {
        val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
        val cardWidth = if (adaptiveWidth) 218.dp * fontScale else 218.dp
        val available = (maxHeight - 30.dp).coerceAtLeast(0.dp)
        Column(
            Modifier.align(if (compact) Alignment.Center else Alignment.BottomCenter)
                .padding(10.dp)
                .widthIn(max = if (compact) cardWidth else 550.dp).fillMaxWidth()
                .then(if (useDefaultHeight && !compact) Modifier.height(defaultBottomSheetHeight(maxHeight))
                    else Modifier.heightIn(max = minOf(if (compact) 620.dp else 550.dp, available)))
                .testTag("usage-glass-dialog").clip(shape)
                .previewGlassBlur(shape)
                .pointerInput(Unit) { detectTapGestures(onTap = {}) }
                .background(Brush.linearGradient(colors.sheet))
                .border(1.dp, colors.edge, shape)
                .padding(horizontal = if (compact) 14.dp else 15.dp)
                .padding(top = if (compact) 8.dp else 14.dp, bottom = if (compact) 10.dp else 15.dp),
        ) {
            if (!compact) Box(Modifier.align(Alignment.CenterHorizontally).padding(bottom = 17.dp)
                .size(29.dp, 3.dp).clip(RoundedCornerShape(4.dp)).background(colors.toolbar.copy(alpha = .24f)))
            Text(title, fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, color = colors.text,
                modifier = Modifier.padding(bottom = if (compact) 10.dp else 10.dp))
            content()
        }
    }
}

@Composable
internal fun UsageSurface(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = usagePalette()
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp))
        .background(colors.bubble.copy(alpha = .62f)).border(1.dp, colors.line, RoundedCornerShape(15.dp))
        .padding(9.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
internal fun UsageMetric(label: String, value: String, modifier: Modifier = Modifier, total: Boolean = false) {
    val colors = usagePalette()
    Row(modifier.fillMaxWidth().padding(horizontal = 2.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = if (total) 14.sp else 13.sp,
            color = if (total) colors.toolbar else colors.text, lineHeight = 20.sp)
        Text(value, fontSize = if (total) 16.sp else 14.sp, fontWeight = if (total) FontWeight.SemiBold else FontWeight.Medium,
            textAlign = TextAlign.End,
            color = if (total) colors.text else colors.accent, lineHeight = 22.sp)
    }
}

@Composable
internal fun UsageAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    plain: Boolean = false,
) {
    val colors = usagePalette()
    val shape = RoundedCornerShape(11.dp)
    Box(modifier.alpha(if (enabled) 1f else .4f).clip(shape)
        .background(if (plain) Color.Transparent else if (primary) colors.primary else colors.secondaryContainer)
        .then(if (plain) Modifier else Modifier.border(1.dp, if (primary) colors.primary else colors.line, shape))
        .clickable(enabled = enabled, role = Role.Button, interactionSource = remember { MutableInteractionSource() },
            indication = null, onClick = onClick)
        .heightIn(min = if (plain) 40.dp else 44.dp).padding(horizontal = if (plain) 0.dp else 10.dp, vertical = 8.dp),
        contentAlignment = if (plain) Alignment.CenterStart else Alignment.Center) {
        Text(text, fontSize = if (plain) 13.sp else 14.sp, lineHeight = 20.sp,
            color = if (plain) colors.muted else if (primary) colors.onPrimary else colors.onSecondaryContainer)
    }
}

@Composable
internal fun UsageBody(
    text: String,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = androidx.compose.ui.text.TextStyle.Default,
    color: Color = Color.Unspecified,
    fontSize: androidx.compose.ui.unit.TextUnit = 13.sp,
    fontWeight: FontWeight? = null,
    textAlign: androidx.compose.ui.text.style.TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(text, modifier, style = style,
        color = if (color != Color.Unspecified) color else if (style.color != Color.Unspecified) style.color else usagePalette().text,
        fontSize = if (style.fontSize != androidx.compose.ui.unit.TextUnit.Unspecified) style.fontSize else fontSize,
        fontWeight = fontWeight ?: style.fontWeight,
        textAlign = textAlign, maxLines = maxLines)
}

@Composable
internal fun UsageDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(usagePalette().line))
}
