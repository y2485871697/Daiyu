package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.ui.haptics.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Chat-local preview colors; deliberately does not change the application theme. */
internal data class ConversationColors(
    val dark: Boolean,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val bubble: Color,
    val surface: Color,
    val line: Color,
    val soft: Color,
    val success: Color,
    val error: Color,
    val warning: Color,
)

@Composable
internal fun conversationColors(): ConversationColors {
    val dark = MiuixTheme.colorScheme.surfaceVariant.luminance() < 0.5f
    return if (dark) ConversationColors(true, Color(0xFFE0DCE8), Color(0xFF8F879B),
        Color(0xFFBBAACB), Color(0xFF26233D), Color(0x85282331), Color(0x24C2ADDB),
        Color(0x13BEA8D7), Color(0xFF9AB3A4), Color(0xFFD896A1), Color(0xFFC3AF8A))
    else ConversationColors(false, Color(0xFF36333F), Color(0xFF918997),
        Color(0xFF756980), Color(0xFFF0ECFC), Color(0x8CFAF8FC), Color(0x2692819F),
        Color(0x108A779A), Color(0xFF6B8574), Color(0xFFAD626E), Color(0xFFA48D65))
}

/** Keep native click semantics and interaction tracking, without a ripple indication. */
@Composable
internal fun Modifier.conversationClickable(
    enabled: Boolean = true,
    role: Role? = Role.Button,
    onClickLabel: String? = null,
    clickFeedback: Boolean = true,
    onClick: () -> Unit,
): Modifier = clickable(
    interactionSource = remember { MutableInteractionSource() },
    indication = null,
    enabled = enabled,
    role = role,
    onClickLabel = onClickLabel,
    clickFeedback = clickFeedback,
    onClick = onClick,
)

@Composable
internal fun ConversationIconButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    minWidth: Dp = 25.dp,
    minHeight: Dp = 25.dp,
    content: @Composable () -> Unit,
) {
    Box(Modifier.size(minWidth, minHeight).conversationClickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center) { content() }
}

@Composable
internal fun ConversationTextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(text, modifier.conversationClickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 7.dp),
        style = MiuixTheme.textStyles.footnote1.copy(fontSize = 11.sp, lineHeight = 17.sp),
        color = conversationColors().accent)
}
