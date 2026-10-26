package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.ui.app.LocalAppearanceSettings
import app.daiyu.android.ui.app.readableAccent
import app.daiyu.android.ui.app.resolvedPrimaryAccent
import app.daiyu.android.ui.app.styleColor
import androidx.compose.runtime.Composable
import app.daiyu.android.ui.screens.assistants.assistantPreviewPalette
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
    val buttons = assistantPreviewPalette()
    val secondary = LocalAppearanceSettings.current.secondaryAccentColor?.let(::Color)
    return ChatActionColors(
        sendContainer = if (dark) buttons.primary else secondary?.copy(alpha = .14f) ?: Color(0xFFE8E7EE),
        sendContent = if (dark) buttons.onPrimary else buttons.accent,
        appendContainer = buttons.primary,
        appendContent = buttons.onPrimary,
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
    val base = if (dark) ChatChromeColors(Color(0xFFE0DCE8), Color(0xFF7A7786), Color(0xFFB2A9C7), Color(0xFFB4A3F7), Color(0xFF2B283A))
    else ChatChromeColors(Color(0xFF36333F), Color(0xFFA19AA9), Color(0xFF716A82), Color(0xFF7663BD), Color(0xFFECE7F5))
    val appearance = LocalAppearanceSettings.current
    return base.copy(accent = appearance.resolvedPrimaryAccent?.let { readableAccent(it, dark) } ?: base.accent, line = appearance.styleColor(base.line))
}
