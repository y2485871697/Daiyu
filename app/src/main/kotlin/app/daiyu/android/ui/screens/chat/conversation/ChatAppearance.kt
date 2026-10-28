package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.ui.app.resolveAppearanceColors
import app.daiyu.android.ui.app.LocalAppearanceDark
import app.daiyu.android.ui.app.LocalAppearanceSettings
import app.daiyu.android.ui.app.resolvedPrimaryAccent
import app.daiyu.android.ui.app.styleColor
import androidx.compose.runtime.Composable
import app.daiyu.android.ui.screens.assistants.assistantPreviewPalette
import androidx.compose.ui.graphics.Color

internal data class ChatActionColors(
    val sendContainer: Color,
    val sendContent: Color,
    val appendContainer: Color,
    val appendContent: Color,
)

@Composable
internal fun chatActionColors(): ChatActionColors {
    val buttons = assistantPreviewPalette()
    val customPrimary = LocalAppearanceSettings.current.primaryAccentColor != null
    val filled = LocalAppearanceDark.current || customPrimary
    return ChatActionColors(
        sendContainer = if (filled) buttons.primary else Color(0xFFE8E7EE),
        sendContent = if (filled) buttons.onPrimary else buttons.accent,
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
    val dark = LocalAppearanceDark.current
    val base = if (dark) ChatChromeColors(Color(0xFFE0DCE8), Color(0xFF7A7786), Color(0xFFB2A9C7), Color(0xFFB4A3F7), Color(0xFF2B283A))
    else ChatChromeColors(Color(0xFF36333F), Color(0xFFA19AA9), Color(0xFF716A82), Color(0xFF7663BD), Color(0xFFECE7F5))
    val appearance = LocalAppearanceSettings.current
    if (appearance.paletteSeedColor != null) {
        val theme = resolveAppearanceColors(appearance, dark)
        return base.copy(line = theme.dividerLine, accent = appearance.resolvedPrimaryAccent ?: base.accent)
    }
    return base.copy(accent = appearance.resolvedPrimaryAccent ?: base.accent, line = appearance.styleColor(base.line))
}
