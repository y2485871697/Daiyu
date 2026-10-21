package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import app.daiyu.android.ui.haptics.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.daiyu.android.ui.icons.DaiyuIcons
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Controlled top-bar action. The owner reads/saves preferences outside composition; neither rendering
 * nor tapping mutates local state here. This is not a run control or a replacement question surface.
 */
@Composable
internal fun InteractiveModeToggle(
    enabled: Boolean,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint = if (checked) MiuixTheme.colorScheme.primary
        else MiuixTheme.colorScheme.onSurfaceVariantSummary
    // Match ChatIconButton's 48dp circular touch target and 20dp outline icon.
    Box(
        modifier.size(48.dp).clip(CircleShape).testTag("interactive-mode-toggle")
            .semantics {
                contentDescription = "交互模式"
                stateDescription = if (checked) "已开启" else "已关闭"
            }
            .toggleable(value = checked, enabled = enabled, role = Role.Switch,
                onValueChange = onCheckedChange),
        contentAlignment = Alignment.Center,
    ) {
        Icon(DaiyuIcons.Help, contentDescription = null, modifier = Modifier.size(20.dp),
            tint = if (enabled) tint else tint.copy(alpha = 0.38f))
    }
}
