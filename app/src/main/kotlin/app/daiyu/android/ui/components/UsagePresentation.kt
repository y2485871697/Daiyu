package app.daiyu.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Local usage/context presentation only. Never installs a theme above settings. */
internal data class UsagePalette(val dark: Boolean) {
    val text = Color(if (dark) 0xffe0dce8 else 0xff36333f)
    val muted = Color(if (dark) 0xff7a7786 else 0xffa19aa9)
    val accent = Color(if (dark) 0xffb4a3f7 else 0xff7663bd)
    val toolbar = Color(if (dark) 0xffb2a9c7 else 0xff716a82)
    val line = Color(if (dark) 0xff2b283a else 0xffece7f5)
    val bubble = Color(if (dark) 0xff26233d else 0xfff0ecfc)
    val background = Color(if (dark) 0xff101218 else 0xffffffff)
    val sheet = listOf(Color(if (dark) 0xf22e283b else 0xf0ffffff), Color(if (dark) 0xf21b1825 else 0xf0f8f4ff))
    val edge = Color(if (dark) 0x26dccdff else 0xedffffff)
}

@Composable
internal fun usagePalette() = UsagePalette(MiuixTheme.colorScheme.surface.luminance() < 0.5f)

@Composable
internal fun UsageGlassDialog(
    title: String,
    onDismiss: () -> Unit,
    compact: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = usagePalette()
    PreviewDialog(onDismiss) {
        val available = (maxHeight - 30.dp).coerceAtLeast(0.dp)
        Column(
            Modifier.align(if (compact) Alignment.Center else Alignment.BottomCenter)
                .padding(10.dp).widthIn(max = if (compact) 218.dp else 550.dp).fillMaxWidth()
                .heightIn(max = minOf(if (compact) 620.dp else 550.dp, available))
                .clip(RoundedCornerShape(if (compact) 16.dp else 25.dp))
                .previewGlassBlur(RoundedCornerShape(if (compact) 16.dp else 25.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .background(Brush.linearGradient(colors.sheet))
                .border(1.dp, colors.edge, RoundedCornerShape(if (compact) 16.dp else 25.dp))
                .padding(horizontal = if (compact) 14.dp else 15.dp)
                .padding(top = if (compact) 8.dp else 14.dp, bottom = if (compact) 10.dp else 15.dp),
        ) {
            if (!compact) Box(Modifier.align(Alignment.CenterHorizontally).padding(bottom = 17.dp)
                .size(29.dp, 3.dp).clip(RoundedCornerShape(4.dp)).background(colors.toolbar.copy(alpha = .24f)))
            Text(title, fontSize = 17.sp, lineHeight = 23.8.sp, fontWeight = FontWeight.SemiBold, color = colors.text,
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
        Text(label, Modifier.weight(1f), fontSize = if (total) 11.sp else 10.sp,
            color = if (total) colors.toolbar else colors.text, lineHeight = 16.sp)
        Text(value, fontSize = if (total) 12.sp else 11.sp, fontWeight = FontWeight.Medium,
            color = if (total) colors.text else colors.accent, lineHeight = 17.sp)
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
        .background(if (plain) Color.Transparent else if (primary) colors.accent else colors.bubble)
        .then(if (plain) Modifier else Modifier.border(1.dp, if (primary) colors.accent else colors.line, shape))
        .clickable(enabled = enabled, role = Role.Button, interactionSource = remember { MutableInteractionSource() },
            indication = null, onClick = onClick)
        .heightIn(min = if (plain) 28.dp else 34.dp).padding(horizontal = if (plain) 0.dp else 10.dp, vertical = 8.dp),
        contentAlignment = if (plain) Alignment.CenterStart else Alignment.Center) {
        Text(text, fontSize = if (plain) 12.sp else 10.sp, lineHeight = if (plain) 20.4.sp else 16.sp,
            color = if (primary) Color(if (colors.dark) 0xff211735 else 0xffffffff) else if (plain) colors.muted else colors.accent)
    }
}

@Composable
internal fun UsageBody(
    text: String,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = androidx.compose.ui.text.TextStyle.Default,
    color: Color = Color.Unspecified,
    fontSize: androidx.compose.ui.unit.TextUnit = 10.sp,
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
