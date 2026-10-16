package app.daiyu.android.ui.icons

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal enum class DaiyuFeatureColor(val light: Color, val dark: Color) {
    BLUE(Color(0xFF2466D8), Color(0xFF9ABEFF)),
    VIOLET(Color(0xFF7950C4), Color(0xFFC4ACFF)),
    ROSE(Color(0xFFC14568), Color(0xFFFFAAC2)),
    AMBER(Color(0xFFA36316), Color(0xFFECC078)),
    TEAL(Color(0xFF087D89), Color(0xFF80D2D8)),
}

internal fun featureColorFor(icon: ImageVector): DaiyuFeatureColor = when (icon.name.substringAfterLast('.')) {
    "Assistant", "SubAgent", "Reasoning", "Model", "AutoAwesome", "Psychology", "Group", "AccountTree", "RocketLaunch" -> DaiyuFeatureColor.VIOLET
    "Palette", "Pet", "Image", "Video", "Vision", "Favorite" -> DaiyuFeatureColor.ROSE
    "Folder", "File", "Backup", "Balance", "Export", "Download", "FolderOpen", "Bolt", "Keyboard", "Notes" -> DaiyuFeatureColor.AMBER
    "Voice", "Speak", "Chart", "Vibration", "Terminal", "Skills", "Code", "Mic", "History", "ChatBubbleOutline", "Forum" -> DaiyuFeatureColor.TEAL
    else -> DaiyuFeatureColor.BLUE
}

@Composable
internal fun daiyuIconColor(icon: ImageVector): Color {
    val accent = featureColorFor(icon)
    return if (MiuixTheme.colorScheme.background.luminance() < 0.5f) accent.dark else accent.light
}
