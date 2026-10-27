package app.daiyu.android.ui.screens.settings

import app.daiyu.android.ui.app.LocalAppearanceDark
import app.daiyu.android.ui.app.DaiyuPalette
import app.daiyu.android.ui.app.readableAccent
import app.daiyu.android.ui.app.colorContentColor
import app.daiyu.android.ui.app.LocalAppearanceSettings
import app.daiyu.android.ui.app.resolvedPrimaryAccent
import app.daiyu.android.ui.app.styleColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Match the collaboration switch without changing the app-wide accent or page surfaces. */
@Composable
internal fun LinuxSettingsTheme(content: @Composable () -> Unit) {
    val dark = LocalAppearanceDark.current
    val appearance = LocalAppearanceSettings.current
    val primary = DaiyuPalette.switchTrack
    val onPrimary = if (appearance.paletteSeedColor != null) colorContentColor(primary)
        else appearance.resolvedPrimaryAccent?.let(::colorContentColor) ?: Color.White
    val resolved = app.daiyu.android.ui.app.resolveAppearanceColors(appearance, dark)
    val container = if (appearance.primaryAccentColor != null || appearance.paletteSeedColor != null) resolved.primaryContainer
        else appearance.styleColor(Color(if (dark) 0xff302a36 else 0xffeee9f1))
    val onContainer = if (appearance.primaryAccentColor != null || appearance.paletteSeedColor != null)
        readableAccent(primary, dark, container)
        else Color(if (dark) 0xffc6b7d5 else 0xff756980)
    MiuixTheme(colors = MiuixTheme.colorScheme.copy(primary = primary, onPrimary = onPrimary,
        primaryVariant = primary, onPrimaryVariant = onPrimary,
        disabledPrimary = container, disabledOnPrimary = onContainer,
        disabledPrimaryButton = container, disabledOnPrimaryButton = onContainer,
        primaryContainer = container, onPrimaryContainer = onContainer)) {
        MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(primary = primary, onPrimary = onPrimary,
            primaryContainer = container, onPrimaryContainer = onContainer), content = content)
    }
}
