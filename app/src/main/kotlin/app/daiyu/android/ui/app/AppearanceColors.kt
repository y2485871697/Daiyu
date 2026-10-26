package app.daiyu.android.ui.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import app.daiyu.android.data.model.AppearancePaletteStyle
import app.daiyu.android.data.model.AppearanceSettings
import kotlin.math.max
import kotlin.math.min
import top.yukonga.miuix.kmp.theme.Colors

internal val AppearanceSettings.resolvedPrimaryAccent: Color?
    get() = primaryAccentColor?.let(::Color)

internal fun colorHsv(color: Color): FloatArray {
    val high = max(color.red, max(color.green, color.blue))
    val low = min(color.red, min(color.green, color.blue))
    val delta = high - low
    val hue = when {
        delta == 0f -> 0f
        high == color.red -> 60f * (((color.green - color.blue) / delta) % 6f)
        high == color.green -> 60f * ((color.blue - color.red) / delta + 2f)
        else -> 60f * ((color.red - color.green) / delta + 4f)
    }
    return floatArrayOf((hue + 360f) % 360f, if (high == 0f) 0f else delta / high, high)
}

internal fun colorContentColor(background: Color): Color =
    if (background.luminance() > .179f) Color.Black else Color.White

internal fun readableAccent(color: Color, dark: Boolean): Color {
    val surface = if (dark) Color(0xFF191B23) else Color.White
    var result = color
    repeat(16) {
        val lighter = max(result.luminance(), surface.luminance())
        val darker = min(result.luminance(), surface.luminance())
        if ((lighter + .05f) / (darker + .05f) >= 4.5f) return result
        result = lerp(result, if (dark) Color.White else Color.Black, .15f)
    }
    return result
}

/** Recolor surfaces without letting a background reset alter either accent. */
internal fun AppearanceSettings.styleColor(original: Color): Color {
    if (paletteSeedColor == null) return original
    val seed = colorHsv(Color(paletteSeedColor))
    val base = colorHsv(original)
    val strength = when (paletteStyle) {
        AppearancePaletteStyle.MONOCHROME -> 0f
        AppearancePaletteStyle.NEUTRAL -> .35f
        AppearancePaletteStyle.TONAL_SPOT -> .8f
        AppearancePaletteStyle.VIBRANT -> 1.5f
        AppearancePaletteStyle.EXPRESSIVE -> 1.1f
        AppearancePaletteStyle.RAINBOW -> .9f
        AppearancePaletteStyle.FRUIT_SALAD -> 1.2f
        AppearancePaletteStyle.FIDELITY -> 1.25f
        AppearancePaletteStyle.CONTENT -> 1f
    }
    val hueOffset = when (paletteStyle) {
        AppearancePaletteStyle.EXPRESSIVE -> 60f
        AppearancePaletteStyle.RAINBOW -> base[0] - 270f
        AppearancePaletteStyle.FRUIT_SALAD -> -50f
        else -> 0f
    }
    val saturation = (max(base[1], .035f) * seed[1] * 2f * strength).coerceIn(0f, .42f)
    return Color.hsv((seed[0] + hueOffset + 360f) % 360f, saturation, base[2], original.alpha)
}

internal fun resolveAppearanceColors(appearance: AppearanceSettings, dark: Boolean): Colors {
    val base = if (dark) DaiyuPalette.dark else DaiyuPalette.light
    val primary = appearance.resolvedPrimaryAccent ?: base.primary
    val secondary = appearance.secondaryAccentColor?.let(::Color)
    return base.copy(
        primary = primary,
        onPrimary = appearance.resolvedPrimaryAccent?.let(::colorContentColor) ?: base.onPrimary,
        primaryVariant = primary,
        onPrimaryVariant = appearance.resolvedPrimaryAccent?.let(::colorContentColor) ?: base.onPrimaryVariant,
        primaryContainer = appearance.resolvedPrimaryAccent?.copy(alpha = .12f) ?: base.primaryContainer,
        onPrimaryContainer = appearance.resolvedPrimaryAccent?.let { readableAccent(it, dark) } ?: base.onPrimaryContainer,
        secondary = secondary?.copy(alpha = .14f) ?: base.secondary,
        onSecondary = secondary?.let { readableAccent(it, dark) } ?: base.onSecondary,
        secondaryVariant = secondary?.copy(alpha = .10f) ?: base.secondaryVariant,
        onSecondaryVariant = secondary?.let { readableAccent(it, dark) } ?: base.onSecondaryVariant,
        secondaryContainer = secondary?.copy(alpha = .10f) ?: base.secondaryContainer,
        onSecondaryContainer = secondary?.let { readableAccent(it, dark) } ?: base.onSecondaryContainer,
        secondaryContainerVariant = secondary?.copy(alpha = .10f) ?: base.secondaryContainerVariant,
        onSecondaryContainerVariant = secondary?.let { readableAccent(it, dark) } ?: base.onSecondaryContainerVariant,
        background = if (dark && appearance.pureBlackEnabled) Color.Black else appearance.styleColor(base.background),
        surface = if (dark && appearance.pureBlackEnabled) Color.Black else appearance.styleColor(base.surface),
        surfaceVariant = appearance.styleColor(base.surfaceVariant),
        surfaceContainer = appearance.styleColor(base.surfaceContainer),
        surfaceContainerHigh = appearance.styleColor(base.surfaceContainerHigh),
        surfaceContainerHighest = appearance.styleColor(base.surfaceContainerHighest),
        outline = appearance.styleColor(base.outline),
        dividerLine = appearance.styleColor(base.dividerLine),
    )
}
