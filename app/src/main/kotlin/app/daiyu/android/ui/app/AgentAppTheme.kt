package app.daiyu.android.ui.app

import app.daiyu.android.ui.haptics.ProvideTouchHaptics
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import app.daiyu.android.ui.components.PreviewBackdropHost
import app.daiyu.android.ui.components.WithoutPressRipple
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun AgentAppTheme(
    appearance: AppearanceSettings,
    applyInterfaceScale: Boolean,
    onResolvedDarkModeChange: (Boolean) -> Unit = {},
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val isDark = when (appearance.themeMode) {
        AppearanceThemeMode.SYSTEM -> systemDark
        AppearanceThemeMode.LIGHT -> false
        AppearanceThemeMode.DARK -> true
    }
    val themedColors = remember(appearance, isDark) { resolveAppearanceColors(appearance, isDark) }

    LaunchedEffect(isDark) { onResolvedDarkModeChange(isDark) }

    MiuixTheme(colors = themedColors) {
        val platformDensity = LocalDensity.current
        val appDensity = remember(platformDensity, appearance.interfaceScale, applyInterfaceScale) {
            if (applyInterfaceScale) {
                Density(
                    density = platformDensity.density * appearance.interfaceScale,
                    fontScale = platformDensity.fontScale,
                )
            } else {
                platformDensity
            }
        }
        val miuixColors = MiuixTheme.colorScheme
        val materialColors = if (isDark) {
            darkColorScheme(
                primary = miuixColors.primary,
                onPrimary = miuixColors.onPrimary,
                primaryContainer = miuixColors.primaryContainer,
                onPrimaryContainer = miuixColors.onPrimaryContainer,
                secondary = miuixColors.secondary,
                onSecondary = miuixColors.onSecondary,
                secondaryContainer = miuixColors.secondaryContainer,
                onSecondaryContainer = miuixColors.onSecondaryContainer,
                background = miuixColors.background,
                onBackground = miuixColors.onBackground,
                surface = miuixColors.surface,
                onSurface = miuixColors.onSurface,
                surfaceVariant = miuixColors.surfaceVariant,
                onSurfaceVariant = miuixColors.onSurfaceSecondary,
                error = miuixColors.error,
                onError = miuixColors.onError,
                errorContainer = miuixColors.errorContainer,
                onErrorContainer = miuixColors.onErrorContainer,
                outline = miuixColors.outline,
                outlineVariant = miuixColors.outline,
                surfaceTint = miuixColors.primary,
                surfaceContainer = miuixColors.surfaceContainer,
                surfaceContainerHigh = miuixColors.surfaceContainerHigh,
                surfaceContainerHighest = miuixColors.surfaceContainerHighest,
                surfaceContainerLow = miuixColors.surface,
                surfaceContainerLowest = miuixColors.surface,
            )
        } else {
            lightColorScheme(
                primary = miuixColors.primary,
                onPrimary = miuixColors.onPrimary,
                primaryContainer = miuixColors.primaryContainer,
                onPrimaryContainer = miuixColors.onPrimaryContainer,
                secondary = miuixColors.secondary,
                onSecondary = miuixColors.onSecondary,
                secondaryContainer = miuixColors.secondaryContainer,
                onSecondaryContainer = miuixColors.onSecondaryContainer,
                background = miuixColors.background,
                onBackground = miuixColors.onBackground,
                surface = miuixColors.surface,
                onSurface = miuixColors.onSurface,
                surfaceVariant = miuixColors.surfaceVariant,
                onSurfaceVariant = miuixColors.onSurfaceSecondary,
                error = miuixColors.error,
                onError = miuixColors.onError,
                errorContainer = miuixColors.errorContainer,
                onErrorContainer = miuixColors.onErrorContainer,
                outline = miuixColors.outline,
                outlineVariant = miuixColors.outline,
                surfaceTint = miuixColors.primary,
                surfaceContainer = miuixColors.surfaceContainer,
                surfaceContainerHigh = miuixColors.surfaceContainerHigh,
                surfaceContainerHighest = miuixColors.surfaceContainerHighest,
                surfaceContainerLow = miuixColors.surface,
                surfaceContainerLowest = miuixColors.surface,
            )
        }

        CompositionLocalProvider(
            LocalAppearanceSettings provides appearance,
            LocalBlurEnabled provides appearance.blurEnabled,
            LocalTopBarBlurStyle provides appearance.topBarBlurStyle,
            LocalPlatformDensity provides platformDensity,
            LocalDensity provides appDensity,
        ) {
            // Retained Material components and Markdown share the native Miuix palette.
            MaterialTheme(
                colorScheme = materialColors,
                shapes = Shapes(
                    extraSmall = RoundedCornerShape(4.dp),
                    small = RoundedCornerShape(8.dp),
                    medium = RoundedCornerShape(12.dp),
                    large = RoundedCornerShape(16.dp),
                    extraLarge = RoundedCornerShape(20.dp),
                ),
            ) {
                ProvideTouchHaptics { WithoutPressRipple { PreviewBackdropHost(content) } }
            }
        }
    }
}
