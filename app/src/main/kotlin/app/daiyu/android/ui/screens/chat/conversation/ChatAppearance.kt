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
        appendContent = if (dark) Color(0xFF211735) else Color.White,
    )
}

internal data class ChatChromeColors(
    val text: Color,
    val muted: Color,
    val toolbar: Color,
    val accent: Color,
    val line: Color,
)

@Composable
internal fun chatChromeColors(): ChatChromeColors {
    val dark = MiuixTheme.colorScheme.background.luminance() < .3f
    return if (dark) ChatChromeColors(Color(0xFFE0DCE8), Color(0xFF7A7786), Color(0xFFB2A9C7), Color(0xFFB4A3F7), Color(0xFF2B283A))
    else ChatChromeColors(Color(0xFF36333F), Color(0xFFA19AA9), Color(0xFF716A82), Color(0xFF7663BD), Color(0xFFECE7F5))
}
