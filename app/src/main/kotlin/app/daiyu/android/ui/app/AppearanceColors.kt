package app.daiyu.android.ui.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import app.daiyu.android.data.model.AppearancePaletteStyle
import app.daiyu.android.data.model.UserBubbleMode
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
    // Seed brightness changes the environment without crossing theme boundaries.
    val value = (base[2] * (.94f + .04f * seed[2]) + .02f * seed[2]).coerceIn(0f, 1f)
    return Color.hsv((seed[0] + hueOffset + 360f) % 360f, saturation, value, original.alpha)
}

internal fun resolveAppearanceColors(appearance: AppearanceSettings, dark: Boolean): Colors {
    val base = if (dark) DaiyuPalette.dark else DaiyuPalette.light
    var colors = base.copy(
        background = if (dark && appearance.pureBlackEnabled) Color.Black else appearance.styleColor(base.background),
        surface = if (dark && appearance.pureBlackEnabled) Color.Black else appearance.styleColor(base.surface),
        surfaceVariant = appearance.styleColor(base.surfaceVariant),
        surfaceContainer = appearance.styleColor(base.surfaceContainer),
        surfaceContainerHigh = appearance.styleColor(base.surfaceContainerHigh),
        surfaceContainerHighest = appearance.styleColor(base.surfaceContainerHighest),
        outline = appearance.styleColor(base.outline),
        dividerLine = appearance.styleColor(base.dividerLine),
    )
    val surface = colors.surface
    val primary = appearance.resolvedPrimaryAccent?.copy(alpha = 1f)
    val secondary = appearance.secondaryAccentColor?.let { Color(it).copy(alpha = 1f) }
    fun tint(accent: Color, amount: Float): Color = lerp(surface, accent, amount)
    fun mutedContent(fill: Color): Color = lerp(fill, colorContentColor(fill), .38f)
    if (primary != null) {
        val container = tint(primary, .12f)
        val disabledContent = mutedContent(container)
        val onFill = colorContentColor(primary)
        colors = colors.copy(
            primary = primary, onPrimary = onFill,
            primaryVariant = primary, onPrimaryVariant = onFill,
            primaryContainer = container, onPrimaryContainer = readableAccent(primary, dark, container),
            disabledPrimary = container, disabledOnPrimary = disabledContent,
            disabledPrimaryButton = container, disabledOnPrimaryButton = disabledContent,
            disabledPrimarySlider = tint(primary, .38f),
            sliderKeyPoint = primary.copy(alpha = .30f), sliderKeyPointForeground = primary,
        )
    }
    if (secondary != null) {
        val container = tint(secondary, .14f)
        val variant = tint(secondary, .10f)
        val disabledVariant = tint(secondary, .08f)
        colors = colors.copy(
            secondary = secondary, onSecondary = colorContentColor(secondary),
            secondaryVariant = variant, onSecondaryVariant = readableAccent(secondary, dark, variant),
            secondaryContainer = container, onSecondaryContainer = readableAccent(secondary, dark, container),
            secondaryContainerVariant = variant, onSecondaryContainerVariant = readableAccent(secondary, dark, variant),
            disabledSecondary = tint(secondary, .12f), disabledSecondaryVariant = disabledVariant,
            disabledOnSecondaryVariant = mutedContent(disabledVariant),
        )
    }
    val tertiary = secondary ?: primary
    if (tertiary != null) {
        val container = tint(tertiary, .12f)
        colors = colors.copy(tertiaryContainer = container,
            onTertiaryContainer = readableAccent(tertiary, dark, container), tertiaryContainerVariant = container)
    }
    return colors
}

/** Interaction roles shared by buttons, not owned by any business screen. */
internal data class AppearanceActionColors(
    val primary: Color, val onPrimary: Color,
    val secondary: Color, val onSecondary: Color,
    val secondaryContainer: Color,
    val primaryContainer: Color, val onPrimaryContainer: Color,
)

internal fun resolveAppearanceActions(appearance: AppearanceSettings, dark: Boolean): AppearanceActionColors {
    val surface = resolveAppearanceColors(appearance, dark).surface
    val primary = appearance.resolvedPrimaryAccent ?: if (dark) Color(0xFFB6A4C7) else Color(0xFF8E7C9E)
    val secondary = appearance.secondaryAccentColor?.let(::Color)
        ?: if (dark) Color(0xFFC6B6D5) else Color(0xFF82718F)
    val container = appearance.secondaryAccentColor?.let { lerp(surface, Color(it), .14f) }
        ?: appearance.styleColor(if (dark) Color(0x17C4ACD7) else Color(0x14A18DB9))
    return AppearanceActionColors(primary,
        if (appearance.primaryAccentColor != null) colorContentColor(primary)
            else if (dark) Color(0xFF261F30) else Color.White,
        secondary,
        if (appearance.secondaryAccentColor != null) readableAccent(secondary, dark, container) else secondary,
        container,
        appearance.primaryAccentColor?.let { lerp(surface, Color(it), .12f) }
            ?: appearance.styleColor(if (dark) Color(0x17C4ACD7) else Color(0x14A18DB9)),
        appearance.primaryAccentColor?.let { readableAccent(Color(it), dark, lerp(surface, Color(it), .12f)) }
            ?: if (dark) Color(0xFFC6B6D5) else Color(0xFF82718F))
}

internal data class DestructiveColors(val container: Color, val content: Color, val border: Color)

internal fun destructiveColors(dark: Boolean): DestructiveColors {
    val colors = if (dark) DaiyuPalette.dark else DaiyuPalette.light
    return DestructiveColors(colors.errorContainer, colors.onErrorContainer, colors.error.copy(alpha = .28f))
}

internal data class UserBubbleColors(val container: Color, val content: Color)

internal fun resolveUserBubbleColors(appearance: AppearanceSettings, dark: Boolean): UserBubbleColors {
    val text = if (dark) Color(0xFFE0DCE8) else Color(0xFF36333F)
    val themed = appearance.styleColor(if (dark) Color(0xFF26233D) else Color(0xFFF0ECFC))
    return when (appearance.userBubbleMode) {
        UserBubbleMode.OFF -> UserBubbleColors(Color.Transparent, text)
        UserBubbleMode.THEME -> UserBubbleColors(themed, text)
        UserBubbleMode.CUSTOM -> appearance.userBubbleColor?.let {
            val fill = Color(it).copy(alpha = 1f)
            UserBubbleColors(fill, colorContentColor(fill))
        } ?: UserBubbleColors(themed, text)
    }
}
