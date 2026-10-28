package app.daiyu.android.ui.app

import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/** Default lavender and charcoal surfaces. */
internal object DaiyuPalette {
    val selectionAccent: Color
        @Composable get() = resolveSelectionAccent(LocalAppearanceDark.current)

    val switchTrack: Color
        @Composable get() = Color(0xFF82728F)

    val switchThumb: Color = Color.White

    val light: Colors = lightColorScheme(
        primary = Color(0xFF7563B4),
        onPrimary = Color.White,
        primaryVariant = Color(0xFF7563B4),
        onPrimaryVariant = Color(0xFFE9E3FB),
        error = Color(0xFFC13F51),
        onError = Color.White,
        errorContainer = Color(0xFFFBECEF),
        onErrorContainer = Color(0xFF7A2434),
        disabledPrimary = Color(0xFFE8E3F1),
        disabledOnPrimary = Color(0xFFFAF8FF),
        disabledPrimaryButton = Color(0xFFE8E3F1),
        disabledOnPrimaryButton = Color.White,
        disabledPrimarySlider = Color(0xFFC8BCE5),
        primaryContainer = Color(0xFFF0ECFC),
        onPrimaryContainer = Color(0xFF7563B4),
        secondary = Color(0xFFECE7F5),
        secondaryVariant = Color(0xFFF4F0FB),
        onSecondaryVariant = Color(0xFF716A82),
        disabledSecondary = Color(0xFFF4F0FB),
        disabledSecondaryVariant = Color(0xFFFAF8FF),
        disabledOnSecondaryVariant = Color(0xFFA19AA9),
        secondaryContainer = Color(0xFFF4F0FB),
        onSecondaryContainer = Color(0xFF82798F),
        secondaryContainerVariant = Color(0xFFF4F0FB),
        onSecondaryContainerVariant = Color(0xFF82798F),
        tertiaryContainer = Color(0xFFF0ECFC),
        onTertiaryContainer = Color(0xFF7563B4),
        tertiaryContainerVariant = Color(0xFFF0ECFC),
        background = Color(0xFFFFFFFF),
        onBackground = Color(0xFF36333F),
        onBackgroundVariant = Color(0xFF82798F),
        surface = Color.White,
        onSurface = Color(0xFF36333F),
        surfaceVariant = Color(0xFFFAF8FF),
        onSurfaceSecondary = Color(0xFF716A82),
        onSurfaceVariantSummary = Color(0xFF716A82),
        onSurfaceVariantActions = Color(0xFF82798F),
        disabledOnSurface = Color(0xFFA19AA9),
        surfaceContainer = Color.White,
        onSurfaceContainer = Color(0xFF36333F),
        onSurfaceContainerVariant = Color(0xFF82798F),
        surfaceContainerHigh = Color(0xFFF4F0FB),
        onSurfaceContainerHigh = Color(0xFF82798F),
        surfaceContainerHighest = Color(0xFFECE7F5),
        onSurfaceContainerHighest = Color(0xFF36333F),
        outline = Color(0xFFECE7F5),
        dividerLine = Color(0xFFECE7F5),
        sliderKeyPoint = Color(0x4D9384D2),
        sliderKeyPointForeground = Color(0xFFA493D7),
    )

    val dark: Colors = darkColorScheme(
        primary = Color(0xFFA491F1),
        onPrimary = Color(0xFF211A38),
        primaryVariant = Color(0xFFA491F1),
        onPrimaryVariant = Color(0xFFDCD2FC),
        error = Color(0xFFFFA6B1),
        onError = Color(0xFF4C1823),
        errorContainer = Color(0xFF3D222B),
        onErrorContainer = Color(0xFFFFD9DE),
        disabledPrimary = Color(0xFF353044),
        disabledOnPrimary = Color(0xFF81798E),
        disabledPrimaryButton = Color(0xFF353044),
        disabledOnPrimaryButton = Color(0xFF81798E),
        disabledPrimarySlider = Color(0xFF5E507D),
        primaryContainer = Color(0xFF26233D),
        onPrimaryContainer = Color(0xFFDCD2FC),
        secondary = Color(0xFF2B283A),
        secondaryVariant = Color(0xFF242130),
        onSecondaryVariant = Color(0xFFC8BFD7),
        secondaryContainer = Color(0xFF242130),
        onSecondaryContainer = Color(0xFFA29AAD),
        secondaryContainerVariant = Color(0xFF242130),
        onSecondaryContainerVariant = Color(0xFFA29AAD),
        tertiaryContainer = Color(0xFF26233D),
        onTertiaryContainer = Color(0xFFA491F1),
        tertiaryContainerVariant = Color(0xFF26233D),
        background = Color(0xFF101218),
        onBackground = Color(0xFFE0DCE8),
        onBackgroundVariant = Color(0xFFA29AAD),
        surface = Color(0xFF191B23),
        onSurface = Color(0xFFE0DCE8),
        surfaceVariant = Color(0xFF191B23),
        onSurfaceSecondary = Color(0xFFC8BFD7),
        onSurfaceVariantSummary = Color(0xFFA29AAD),
        onSurfaceVariantActions = Color(0xFFA29AAD),
        disabledOnSurface = Color(0xFF7A7786),
        surfaceContainer = Color(0xFF191B23),
        onSurfaceContainer = Color(0xFFE0DCE8),
        onSurfaceContainerVariant = Color(0xFFA29AAD),
        surfaceContainerHigh = Color(0xFF242130),
        onSurfaceContainerHigh = Color(0xFFA29AAD),
        surfaceContainerHighest = Color(0xFF393347),
        onSurfaceContainerHighest = Color(0xFFE0DCE8),
        outline = Color(0xFF393347),
        dividerLine = Color(0xFF393347),
        sliderKeyPoint = Color(0x4DA491F1),
        sliderKeyPointForeground = Color(0xFFA491F1),
    )
}
