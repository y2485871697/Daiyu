package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal data class ChatActionColors(
    val sendContainer: Color,
    val sendContent: Color,
    val appendContainer: Color,
    val appendContent: Color,
)

@Composable
internal fun chatActionColors(): ChatActionColors {
    val colors = MiuixTheme.colorScheme
    val dark = colors.background.luminance() < .3f
    return ChatActionColors(
        sendContainer = if (dark) Color(0xFF7862ED) else Color(0xFFE9E3FB),
        sendContent = if (dark) Color(0xFFF1ECFF) else Color(0xFF8D7AC9),
        appendContainer = if (dark) Color(0xFFB4A3F7) else Color(0xFF7663BD),
        appendContent = Color.White,
    )
}
