package app.daiyu.android.ui.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import app.daiyu.android.data.model.AppearanceSettings
import kotlin.math.max
import kotlin.math.min
import top.yukonga.miuix.kmp.theme.Colors

internal fun resolveSelectionAccent(dark: Boolean): Color =
    Color(if (dark) 0xFFC6B6D5 else 0xFF82718F)

internal fun colorContentColor(background: Color): Color =
    if (background.luminance() > .179f) Color.Black else Color.White

internal fun readableAccent(
    color: Color,
    dark: Boolean,
    surface: Color = if (dark) Color(0xFF191B23) else Color.White,
): Color {
    val background = surface.compositeOver(if (dark) Color.Black else Color.White)
    val target = colorContentColor(background)
    var result = color.compositeOver(background)
    repeat(24) {
        val lighter = max(result.luminance(), background.luminance())
        val darker = min(result.luminance(), background.luminance())
        if ((lighter + .05f) / (darker + .05f) >= 4.5f) return result
        result = lerp(result, target, .15f)
    }
    return target
}

internal fun resolveAppearanceColors(appearance: AppearanceSettings, dark: Boolean): Colors {
    val base = if (dark) DaiyuPalette.dark else DaiyuPalette.light
    return base.copy(
        background = if (dark && appearance.pureBlackEnabled) Color.Black else base.background,
        surface = if (dark && appearance.pureBlackEnabled) Color.Black else base.surface,
    )
}

/** Default interaction roles shared by buttons, not owned by any business screen. */
internal data class AppearanceActionColors(
    val primary: Color, val onPrimary: Color,
    val secondary: Color, val onSecondary: Color,
    val secondaryContainer: Color,
    val primaryContainer: Color, val onPrimaryContainer: Color,
)

internal fun resolveAppearanceActions(dark: Boolean): AppearanceActionColors {
    val primary = if (dark) Color(0xFFB6A4C7) else Color(0xFF8E7C9E)
    val secondary = if (dark) Color(0xFFC6B6D5) else Color(0xFF82718F)
    val container = if (dark) Color(0x17C4ACD7) else Color(0x14A18DB9)
    return AppearanceActionColors(primary,
        if (dark) Color(0xFF261F30) else Color.White,
        secondary, secondary, container, container, secondary)
}

internal data class DestructiveColors(val container: Color, val content: Color, val border: Color)

internal fun destructiveColors(): DestructiveColors {
    val red = Color(0xFF92454F)
    return DestructiveColors(red, Color.White, red)
}
