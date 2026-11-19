package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
internal fun MessageReferenceChips(references: List<ComposerReferences.Reference>) {
    val colors = conversationColors()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        references.forEachIndexed { index, reference ->
            Row(Modifier.widthIn(max = 280.dp).testTag("message-reference:$index"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(when (reference.kind) {
                    "目录" -> DaiyuIcons.Folder
                    "会话" -> DaiyuIcons.Context
                    else -> DaiyuIcons.File
                }, contentDescription = if (reference.kind == "目录") "文件夹" else reference.kind,
                    modifier = Modifier.size(18.dp).testTag("message-reference-icon:$index"),
                    tint = colors.bubbleText)
                Text(reference.label, Modifier.weight(1f, fill = false),
                    color = colors.bubbleText, fontSize = 14.sp, lineHeight = 20.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
