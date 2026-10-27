package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.daiyu.android.ui.components.imeCursorBrush
import app.daiyu.android.ui.components.managedInput
import app.daiyu.android.ui.haptics.clickable
import app.daiyu.android.ui.icons.DaiyuIcons
import app.daiyu.android.ui.screens.assistants.AssistantPreviewButton
import app.daiyu.android.ui.screens.assistants.AssistantPreviewDialog
import top.yukonga.miuix.kmp.basic.Text

internal data class ComposerShortcutChoice(val id: String, val title: String, val description: String = "")

@Composable
internal fun ComposerShortcutPicker(kind: ComposerShortcutKind, initialQuery: String,
    choices: List<ComposerShortcutChoice>?, error: String?, onChoose: (ComposerShortcutChoice) -> Unit,
    onDismiss: () -> Unit) {
    var query by remember { mutableStateOf(initialQuery) }
    val colors = chatChromeColors()
    val conversation = kind == ComposerShortcutKind.CONVERSATION
    val filtered = choices?.filter { query.isBlank() ||
        listOf(it.id, it.title, it.description).any { value -> value.contains(query.trim(), ignoreCase = true) } }
    AssistantPreviewDialog(true, if (conversation) "引用会话" else "选择 Skill", onDismiss,
        showCloseButton = false, footer = {
            AssistantPreviewButton("完成", onDismiss, Modifier.weight(1f).testTag("reference-picker-done"), primary = true)
        }) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(13.dp))
            .background(colors.accent.copy(alpha = .04f)).border(1.dp, colors.line, RoundedCornerShape(13.dp))
            .padding(horizontal = 11.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(DaiyuIcons.Search, null, Modifier.size(18.dp), tint = colors.muted)
            BasicTextField(query, { query = it }, singleLine = true,
                modifier = Modifier.managedInput().weight(1f).heightIn(min = 42.dp).testTag("reference-picker-search"),
                cursorBrush = imeCursorBrush(colors.accent),
                textStyle = TextStyle(color = colors.text, fontSize = 14.sp, lineHeight = 20.sp),
                decorationBox = { inner -> Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) Text(if (conversation) "搜索会话" else "搜索 Skill", color = colors.muted, fontSize = 14.sp)
                    inner()
                } })
        }
        Text(when { error != null -> error; filtered == null -> "正在加载";
            else -> "${filtered.size} ${if (conversation) "个会话" else "个 Skill"}" },
            Modifier.padding(vertical = 8.dp), fontSize = 11.sp, color = colors.muted)
        LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("reference-picker-list"),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(filtered.orEmpty(), key = { it.id }) { choice ->
                Row(Modifier.fillMaxWidth().testTag("reference-picker-item:${choice.id}")
                    .clip(RoundedCornerShape(15.dp)).background(colors.accent.copy(alpha = .05f))
                    .border(1.dp, colors.line, RoundedCornerShape(15.dp)).clickable { onChoose(choice) }
                    .padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(if (conversation) DaiyuIcons.Context else DaiyuIcons.Tool, null,
                        Modifier.size(22.dp), tint = colors.accent)
                    Column(Modifier.weight(1f)) {
                        Text(choice.title, color = colors.text, fontSize = 14.sp, lineHeight = 20.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (choice.description.isNotBlank()) Text(choice.description, color = colors.muted,
                            fontSize = 11.sp, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (filtered?.isEmpty() == true) item {
                Text(if (conversation) "暂无可引用会话" else "暂无可用 Skill", Modifier.fillMaxWidth().padding(20.dp),
                    color = colors.muted, fontSize = 13.sp)
            }
        }
    }
}
