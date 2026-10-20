package app.daiyu.android.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Scoped final preview tokens: .glass-drawer and .drawer-* (style-00.css 234–300).
 * CSS #RRGGBBAA is deliberately converted to Compose #AARRGGBB, not made opaque.
 * Fixed CSS geometry stays in dp; only percentage widths follow the container.
 */
internal data class ConversationPanePreviewPalette(val dark: Boolean) {
    val text = if (dark) Color(0xFFE0DCE8) else Color(0xFF36333F)
    val accent = if (dark) Color(0xFFC6B7D5) else Color(0xFF655A72)
    val toolbar = if (dark) Color(0xFFC0B4CD) else Color(0xFF6F647C)
    val muted = if (dark) Color(0xFF9890A3) else Color(0xFF8C8394)
    val bubble = if (dark) Color(0x1CC0ACD7) else Color(0x1E81738E)
    val line = if (dark) Color(0x2BC3B0D9) else Color(0x2881738E)
    val border = if (dark) Color(0x26D7C5FF) else Color(0xDCFFFFFF)
    val search = if (dark) Color(0x06FFFFFF) else Color(0x7AFFFFFF)
    val searchBorder = if (dark) Color(0x16C7B4EB) else Color(0x1CA28CBD)
    val action = if (dark) Color(0x17AC93C2) else Color(0x1A81738E)
    val actionBorder = if (dark) Color(0x1FAC93C2) else Color(0x2081738E)
    val folder = if (dark) Color(0xFF786A89) else Color(0xFF786C83)
    val folderText = if (dark) Color(0xFFF5EFFA) else Color.White
    val folderBorder = if (dark) Color(0x2BD4BEDE) else folder
    val surface = if (dark) Color(0xFF1A1926) else Color(0xFFFAF8FF)
    val footerBorder = if (dark) Color(0x1AC8B5EB) else Color(0x1C9C84BF)
    val scrim = if (dark) Color(0x66000000) else Color(0x26241B35)
    val danger = Color(0xFFBC6575)
    val gradient = if (dark) listOf(Color(0xF22C2738), Color(0xEC211E2B), Color(0xF2242130))
        else listOf(Color(0xF0FFFFFF), Color(0xE8F6F1FC), Color(0xEDF8F5FB))
}

@Composable internal fun conversationPanePreviewPalette() =
    ConversationPanePreviewPalette(MiuixTheme.colorScheme.background.luminance() < .5f)

@Composable internal fun conversationPanePreviewScale() =
    1f
