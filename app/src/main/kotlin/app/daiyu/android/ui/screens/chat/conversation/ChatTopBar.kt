package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.ui.icons.ChatPreviewIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.platform.LocalDensity
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
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ChatTopBar(title: String, balanceState: app.daiyu.android.balance.BalanceState?, actions: ChatScreenActions, modifier: Modifier = Modifier, assistantLabel: String = "助手",
    interactiveModeEnabled: Boolean = false, interactiveModeChangeEnabled: Boolean = true,
    automaticCompression: Boolean? = null, automaticCompressionPending: Boolean = false, modelLabel: String = "") {
    var expanded by remember { mutableStateOf(false) }
    val chrome = chatChromeColors()
    Row(modifier.testTag("chat-top-bar").fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 3.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp), verticalAlignment = Alignment.CenterVertically) {
        ChatIconButton(ChatPreviewIcons.Menu, "打开侧边栏", actions.onOpenSidebar,
            Modifier.size(width = 17.dp, height = 28.dp), tint = chrome.text, iconSize = 17.dp)
        val titleBlockHeight = with(LocalDensity.current) { 17.3.sp.toDp() + 11.5.sp.toDp() } + 4.dp
        Column(Modifier.weight(1f).heightIn(min = titleBlockHeight)) {
            Text(title.ifBlank { "新会话" }, Modifier.testTag("chat-title"),
                color = chrome.text,
                style = MiuixTheme.textStyles.title3.copy(fontSize = 12.sp, lineHeight = 17.3.sp, fontWeight = FontWeight(650)),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOf(assistantLabel, modelLabel).filter { it.isNotBlank() }.joinToString(" · "),
                modifier = Modifier.testTag("chat-subtitle").padding(top = 4.dp), color = chrome.muted,
                style = MiuixTheme.textStyles.footnote1.copy(fontSize = 8.sp, lineHeight = 11.5.sp),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        ChatIconButton(ChatPreviewIcons.SubAgents, "本会话协作", { actions.onMenuAction(ChatMenuAction.COLLABORATION) }, modifier = Modifier.size(28.dp), tint = chrome.toolbar, iconSize = 18.dp)
        Box {
            val moreShape = RoundedCornerShape(10.dp)
            val dark = MiuixTheme.colorScheme.background.luminance() < .3f
            val activeBackground = if (dark) Color(0xFF26233D) else Color(0xFFF0ECFC)
            ChatIconButton(ChatPreviewIcons.More, "更多", { actions.onMore(); expanded = true },
                Modifier.size(28.dp).semantics { selected = expanded }
                    .background(if (expanded) activeBackground else Color.Transparent, moreShape)
                    .then(if (expanded) Modifier.border(1.dp, Color(0x0D9980BE), moreShape) else Modifier),
                tint = if (expanded) chrome.accent else chrome.text, iconSize = 17.dp, shape = moreShape)
            ChatOverflowMenu(expanded, { expanded = false }, actions.onMenuAction,
                interactiveModeEnabled, interactiveModeChangeEnabled, automaticCompression, automaticCompressionPending)
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
    containerColor: Color? = null,
    iconSize: androidx.compose.ui.unit.Dp? = null,
    containerSize: androidx.compose.ui.unit.Dp = 20.dp,
    shape: Shape = CircleShape,
) {
    Box(modifier.size(48.dp).clip(shape).combinedClickable(
        enabled = enabled, role = Role.Button, onClickLabel = label,
        onLongClick = onLongClick, onClick = onClick,
    ), contentAlignment = Alignment.Center) {
        Box(if (containerColor == null) Modifier else Modifier.size(containerSize).background(containerColor, CircleShape),
            contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(iconSize ?: if (containerColor == null) 20.dp else 12.dp),
                tint = if (enabled) tint else tint.copy(alpha = 0.38f))
        }
    }
}
