package app.daiyu.android.ui.components

import app.daiyu.android.ui.app.resolveAppearanceColors
import app.daiyu.android.ui.app.LocalAppearanceDark
import app.daiyu.android.ui.app.LocalAppearanceSettings
import app.daiyu.android.ui.app.readableAccent
import app.daiyu.android.ui.app.resolvedPrimaryAccent
import app.daiyu.android.ui.app.styleColor
import app.daiyu.android.data.model.AppearanceSettings
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Scoped final preview tokens: .glass-drawer and .drawer-* (style-00.css 234–300).
 * CSS #RRGGBBAA is deliberately converted to Compose #AARRGGBB, not made opaque.
 * Typography and control sizes are tuned separately for device readability.
 */
internal data class ConversationPanePreviewPalette(val dark: Boolean, val appearance: AppearanceSettings = AppearanceSettings()) {
    private val theme = resolveAppearanceColors(appearance, dark)
    val text = if (dark) Color(0xFFE0DCE8) else Color(0xFF36333F)
    val accent = appearance.resolvedPrimaryAccent?.let { readableAccent(it, dark) } ?: if (dark) Color(0xFFC6B7D5) else Color(0xFF655A72)
    val toolbar = if (dark) Color(0xFFC0B4CD) else Color(0xFF6F647C)
    val muted = if (dark) Color(0xFF9890A3) else Color(0xFF8C8394)
    val selectionContainer = if (appearance.primaryAccentColor != null) theme.primaryContainer
        else appearance.styleColor(if (dark) Color(0x2AC0ACD7) else Color(0x2A81738E))
    val selectionContent = if (appearance.primaryAccentColor != null) theme.onPrimaryContainer else text
    val selectionBorder = if (appearance.primaryAccentColor != null) theme.onPrimaryContainer.copy(alpha = .35f)
        else appearance.styleColor(if (dark) Color(0x40C3B0D9) else Color(0x3881738E))
    val utilityContainer = appearance.styleColor(if (dark) Color(0x1CC0ACD7) else Color(0x1E81738E))
    val line = appearance.styleColor(if (dark) Color(0x2BC3B0D9) else Color(0x2881738E))
    val border = appearance.styleColor(if (dark) Color(0x26D7C5FF) else Color(0xDCFFFFFF))
    val action = appearance.styleColor(if (dark) Color(0x17AC93C2) else Color(0x1A81738E))
    val actionBorder = appearance.styleColor(if (dark) Color(0x1FAC93C2) else Color(0x2081738E))
    val folderIdle = appearance.styleColor(if (dark) Color(0x24C0ACD7) else Color(0x2281738E))
    val folder = if (appearance.primaryAccentColor != null) theme.primaryContainer else if (dark) Color(0xFF786A89) else Color(0xFF786C83)
    val folderText = if (appearance.primaryAccentColor != null) theme.onPrimaryContainer else if (dark) Color(0xFFF5EFFA) else Color.White
    val folderBorder = appearance.resolvedPrimaryAccent?.copy(alpha = .35f) ?: if (dark) Color(0x2BD4BEDE) else folder
    val surface = appearance.styleColor(if (dark) Color(0xFF1A1926) else Color(0xFFFAF8FF))
    val footerBorder = appearance.styleColor(if (dark) Color(0x1AC8B5EB) else Color(0x1C9C84BF))
    val scrim = if (dark) Color(0x66000000) else Color(0x26241B35)
    val fallbackSurface = appearance.styleColor(if (dark) Color(0xFF24202F) else Color(0xFFF8F4FC))
    val danger = Color(0xFFBC6575)
    val gradient = (if (dark) listOf(Color(0xF22C2738), Color(0xEC211E2B), Color(0xF2242130))
        else listOf(Color(0xF0FFFFFF), Color(0xE8F6F1FC), Color(0xEDF8F5FB))).map(appearance::styleColor)
}

@Composable internal fun conversationPanePreviewPalette() =
    ConversationPanePreviewPalette(LocalAppearanceDark.current, LocalAppearanceSettings.current)

@Composable internal fun conversationPanePreviewScale() =
    1f
