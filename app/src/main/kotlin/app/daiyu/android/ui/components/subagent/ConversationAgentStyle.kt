package app.daiyu.android.ui.components.subagent

import app.daiyu.android.ui.components.defaultBottomSheetHeight

import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import app.daiyu.android.ui.haptics.clickable
import app.daiyu.android.ui.haptics.toggleable
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import app.daiyu.android.ui.components.PreviewDialog
import app.daiyu.android.ui.components.previewGlassBlur
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*

import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleSurface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Default-off: settings continue to use their original appearance. */
internal val LocalConversationAgentStyle = staticCompositionLocalOf { false }

internal data class ConversationAgentColors(val dark: Boolean) {
    val text = Color(if (dark) 0xffe0dce8 else 0xff36333f)
    val muted = Color(if (dark) 0xff9d92aa else 0xff938a9b)
    val accent = Color(if (dark) 0xffc6b7d5 else 0xff756980)
    val strong = Color(if (dark) 0xffb4a3f7 else 0xff7663bd)
    val line = Color(if (dark) 0x28c3b0d9 else 0x2681738e)
    val field = Color(if (dark) 0x06ffffff else 0x60ffffff)
    val soft = Color(if (dark) 0x14c0acd7 else 0x1281738e)
    val taskLine = Color(if (dark) 0xff2b283a else 0xffece7f5)
    val taskMuted = Color(if (dark) 0xff7a7786 else 0xffa19aa9)
    val gradient = if (dark) listOf(Color(0xf22e283b), Color(0xf21b1825)) else listOf(Color(0xf0ffffff), Color(0xf0f8f4ff))
    val edge = Color(if (dark) 0x26dccdff else 0xedffffff)
}
@Composable internal fun conversationAgentColors() = ConversationAgentColors(MaterialTheme.colorScheme.background.luminance() < .5f)

/** Floating glass sheet with rounded corners. */
@Composable
internal fun ConversationAgentSheet(onDismiss: () -> Unit, radius: Int = 25, modifier: Modifier = Modifier,
    useDefaultHeight: Boolean = false,
    content: @Composable ColumnScope.() -> Unit) {
    val colors = conversationAgentColors()
    val shape = RoundedCornerShape(radius.dp)
    PreviewDialog(onDismiss, bottomSheet = true) {
        val heightModifier = if (useDefaultHeight) {
            Modifier.padding(10.dp).height(defaultBottomSheetHeight(maxHeight))
        } else Modifier.heightIn(max = (maxHeight - 20.dp).coerceAtLeast(0.dp)).padding(10.dp)
        Column(modifier.align(Alignment.BottomCenter).then(heightModifier).fillMaxWidth().testTag("conversation-agent-sheet")
            .shadow(18.dp, shape, ambientColor = Color(0x24352044), spotColor = Color(0x24352044))
            .clip(shape)
            .previewGlassBlur(shape)
            .pointerInput(Unit) { detectTapGestures(onTap = {}) }
            .background(Brush.linearGradient(colors.gradient))
            .border(1.dp, colors.edge, shape)) {
            CompositionLocalProvider(LocalContentColor provides colors.text, content = { content() })
        }
    }
}

@Composable internal fun ConversationAgentHandle() {
    val colors = conversationAgentColors()
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(Modifier.padding(bottom = 17.dp).size(29.dp, 3.dp).background(colors.accent.copy(alpha = .24f), CircleShape))
    }
}

@Composable internal fun ConversationAgentSwitch(checked: Boolean, enabled: Boolean, onCheckedChange: ((Boolean) -> Unit)? = null) {
    val colors = conversationAgentColors()
    val shape = RoundedCornerShape(20.dp)
    Box(Modifier.size(48.dp).then(if (onCheckedChange != null) Modifier.toggleable(value = checked,
        enabled = enabled, role = Role.Switch, interactionSource = remember { MutableInteractionSource() },
        indication = null, onValueChange = onCheckedChange) else Modifier), contentAlignment = Alignment.Center) {
        Box(Modifier.size(36.dp, 22.dp).alpha(if (enabled) 1f else .4f).clip(shape)
            .background(if (checked) Color(0xff82728f) else Color(0x28b4aabb))
            .border(1.dp, if (checked) Color(0xff82728f) else colors.line, shape),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart) {
            Box(Modifier.padding(horizontal = 2.dp).size(16.dp).background(if (checked) Color.White else Color(0xffa69baa), CircleShape))
        }
    }
}

@Composable internal fun ConversationAgentButton(text: String, enabled: Boolean = true, primary: Boolean = false,
    modifier: Modifier = Modifier, primaryColor: Color = conversationAgentColors().accent,
    onPrimaryColor: Color = if (conversationAgentColors().dark) Color(0xff211735) else Color.White, onClick: () -> Unit) {
    val colors = conversationAgentColors()
    Box(modifier.defaultMinSize(minHeight = 48.dp).alpha(if (enabled) 1f else .4f)
        .squircleSurface(color = if (primary) primaryColor else colors.soft,
            cornerRadius = ButtonDefaults.CornerRadius)
        .squircleBorder(width = 1.dp, color = if (primary) primaryColor else colors.line,
            cornerRadius = ButtonDefaults.CornerRadius)
        .clickable(enabled = enabled, interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick)
        .padding(horizontal = 10.dp, vertical = 7.dp), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 14.sp, lineHeight = 20.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = if (primary) onPrimaryColor else colors.accent)
    }
}

@Composable internal fun ConversationAgentTypography(content: @Composable () -> Unit) {
    val colors = conversationAgentColors()
    fun style(size: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(fontSize = size.sp, lineHeight = (size * 1.6f).sp, fontWeight = weight)
    MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(onSurface = colors.text, onSurfaceVariant = colors.muted,
        primary = colors.accent, outline = colors.line, outlineVariant = colors.line), typography = MaterialTheme.typography.copy(
        titleLarge = style(18, FontWeight.SemiBold), titleMedium = style(14, FontWeight.Medium), titleSmall = style(13, FontWeight.Medium),
        bodyLarge = style(14), bodyMedium = style(14), bodySmall = style(12), labelSmall = style(11), labelMedium = style(13)), content = content)
}

@Composable internal fun ConversationAgentIconButton(onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, content: @Composable () -> Unit) {
    Box(modifier.clip(RoundedCornerShape(9.dp)).alpha(if (enabled) 1f else .4f)
        .clickable(enabled = enabled, role = Role.Button, interactionSource = remember { MutableInteractionSource() },
            indication = null, onClick = onClick), contentAlignment = Alignment.Center) { content() }
}
