package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.daiyu.android.ui.icons.DaiyuIcons
import top.yukonga.miuix.kmp.basic.Text

@Composable
internal fun ComposerReferenceChips(references: List<ComposerReferences.Reference>, enabled: Boolean, visualEnabled: Boolean = enabled,
    onRemove: (ComposerReferences.Reference) -> Unit) {
    if (references.isEmpty()) return
    val colors = chatChromeColors()
    Row(Modifier.fillMaxWidth().testTag("chat-reference-chips").padding(horizontal = 13.dp, vertical = 6.dp)
        .horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        references.forEachIndexed { index, reference ->
            Row(Modifier.testTag("chat-reference:$index").widthIn(max = 220.dp)
                .background(colors.accent.copy(alpha = .08f), RoundedCornerShape(10.dp))
                .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Icon(when (reference.kind) { "目录" -> DaiyuIcons.Folder; "会话" -> DaiyuIcons.Context; else -> DaiyuIcons.File },
                    null, Modifier.size(14.dp), tint = colors.accent)
                Text("${reference.kind} · ${reference.label}", Modifier.weight(1f, fill = false),
                    fontSize = 10.sp, lineHeight = 14.sp, color = colors.text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                ChatIconButton(DaiyuIcons.Close, "移除${reference.kind}引用：${reference.label}", { onRemove(reference) },
                    Modifier.size(22.dp).testTag("chat-reference-remove:$index"), enabled = enabled, visualEnabled = visualEnabled,
                    iconSize = 11.dp, tint = colors.muted)
            }
        }
    }
}
