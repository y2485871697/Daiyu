package app.daiyu.android.ui.screens.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import app.daiyu.android.ui.screens.assistants.assistantPreviewPalette
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ProviderSettingsTheme(content: @Composable () -> Unit) {
    val accent = assistantPreviewPalette()
    val colors = MiuixTheme.colorScheme.copy(primary = accent.primary, onPrimary = accent.onPrimary,
        primaryContainer = accent.soft, onPrimaryContainer = accent.accent)
    MiuixTheme(colors = colors) {
        MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(primary = accent.primary,
            onPrimary = accent.onPrimary, primaryContainer = accent.soft, onPrimaryContainer = accent.accent),
            content = content)
    }
}
