package app.daiyu.android.ui.screens.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import app.daiyu.android.ui.app.resolveAppearanceActions
import app.daiyu.android.ui.app.LocalAppearanceDark
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ProviderSettingsTheme(content: @Composable () -> Unit) {
    val accent = resolveAppearanceActions(LocalAppearanceDark.current)
    val colors = MiuixTheme.colorScheme.copy(primary = accent.primary, onPrimary = accent.onPrimary,
        primaryContainer = accent.primaryContainer, onPrimaryContainer = accent.onPrimaryContainer)
    MiuixTheme(colors = colors) {
        MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(primary = accent.primary,
            onPrimary = accent.onPrimary, primaryContainer = accent.primaryContainer, onPrimaryContainer = accent.onPrimaryContainer),
            content = content)
    }
}
