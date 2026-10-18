package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import app.daiyu.android.ui.icons.DaiyuIcons
import app.daiyu.android.ui.icons.daiyuIconColor
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ChatTopBar(title: String, balanceState: app.daiyu.android.balance.BalanceState?, actions: ChatScreenActions, modifier: Modifier = Modifier, assistantLabel: String = "助手",
    interactiveModeEnabled: Boolean = false, interactiveModeChangeEnabled: Boolean = true) {
    var expanded by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        ChatIconButton(DaiyuIcons.Menu, "打开侧边栏", actions.onOpenSidebar)
        Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
            Text(title.ifBlank { "新会话" }, Modifier.testTag("chat-title"),
                color = MiuixTheme.colorScheme.onSurface,
                style = MiuixTheme.textStyles.title3.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(assistantLabel, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.footnote1.copy(fontSize = 12.sp),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        app.daiyu.android.balance.ui.ProviderBalanceIndicator(
            state = balanceState,
            modifier = Modifier.testTag("chat-provider-balance").widthIn(max = 116.dp)
                .padding(horizontal = 8.dp, vertical = 5.dp),
            animateChanges = true,
        )
        InteractiveModeToggle(enabled = interactiveModeChangeEnabled, checked = interactiveModeEnabled,
            onCheckedChange = actions.onInteractiveModeChange)
        ChatIconButton(DaiyuIcons.NewChat, "新建会话", { actions.onMenuAction(ChatMenuAction.NEW_CHAT) }, tint = daiyuIconColor(DaiyuIcons.NewChat))
        Box {
            ChatIconButton(DaiyuIcons.More, "更多", { actions.onMore(); expanded = true })
            ChatOverflowMenu(expanded, { expanded = false }, actions.onMenuAction)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChatIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    onLongClick: (() -> Unit)? = null,
) {
    Box(modifier.size(48.dp).clip(CircleShape).combinedClickable(
        enabled = enabled, role = Role.Button, onClickLabel = label,
        onLongClick = onLongClick, onClick = onClick,
    ), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(20.dp),
            tint = if (enabled) tint else tint.copy(alpha = 0.38f))
    }
}
