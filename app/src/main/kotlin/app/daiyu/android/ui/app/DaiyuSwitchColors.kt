package app.daiyu.android.ui.app

import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun daiyuSwitchColors(base: SwitchColors = SwitchDefaults.colors()): SwitchColors {
    val colors = MiuixTheme.colorScheme
    val disabledOutline = colors.onSurfaceVariantSummary.copy(alpha = .38f)
    return base.copy(
        uncheckedThumbColor = colors.onSurfaceVariantSummary,
        uncheckedTrackColor = colors.surface,
        uncheckedBorderColor = colors.onSurfaceVariantSummary,
        disabledUncheckedThumbColor = disabledOutline,
        disabledUncheckedTrackColor = colors.surface,
        disabledUncheckedBorderColor = disabledOutline,
    )
}
