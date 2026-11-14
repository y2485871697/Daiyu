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

/** 顶栏交互模式开关。宿主提供当前值并负责读写偏好，点击时通过 onCheckedChange 回传选择。 */
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
