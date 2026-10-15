package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import app.daiyu.android.ui.icons.DaiyuIcons
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ChatTopBar(title: String, balanceText: String?, actions: ChatScreenActions, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        ChatIconButton(DaiyuIcons.Menu, "打开侧边栏", actions.onOpenSidebar)
        Text(title.ifBlank { "新会话" }, Modifier.testTag("chat-title").weight(1f).padding(horizontal = 4.dp),
            color = MiuixTheme.colorScheme.onSurface, style = MiuixTheme.textStyles.title3,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!balanceText.isNullOrBlank()) {
            Text(balanceText, Modifier.clip(RoundedCornerShape(50)).background(MiuixTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 10.dp, vertical = 5.dp),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary, style = MiuixTheme.textStyles.footnote1,
                maxLines = 1)
        }
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
