package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ChildTaskFrame(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = conversationColors()
    val shape = RoundedCornerShape(8.dp)
    Column(modifier.fillMaxWidth().clip(shape).background(colors.soft).border(1.dp, colors.line, shape)
        .padding(horizontal = 9.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(5.dp), content = content)
}

@Composable
internal fun ChildTaskProgressContent(text: String?, status: String, tag: String, agentName: String) {
    val body = text ?: when (status) {
        "进行中" -> "正在处理任务…"
        "排队中" -> "等待开始任务"
        "已完成" -> "任务已完成，暂无结果正文"
        "状态未确认" -> "暂时无法确认任务状态"
        "失败" -> "任务未完成"
        "未执行" -> "任务未执行"
        else -> "暂无新的任务进度"
    }
    var opened by remember(tag) { mutableStateOf(false) }
    val preview = remember(body) { toolOutputPreview(body) }
    var layoutOverflow by remember(body) { mutableStateOf(false) }
    val truncated = preview.truncated || layoutOverflow
    val click = if (truncated) Modifier.conversationClickable(onClickLabel = "查看${agentName}的任务内容") {
        opened = true
    } else Modifier
    BasicText(preview.text, Modifier.fillMaxWidth().heightIn(min = 20.dp)
        .testTag("tool-child-progress:$tag").then(click),
        style = MiuixTheme.textStyles.footnote1.copy(color = conversationColors().text),
        maxLines = 3, overflow = TextOverflow.Ellipsis,
        onTextLayout = { layoutOverflow = it.hasVisualOverflow })
    if (opened && truncated) CompositionLocalProvider(LocalToolOutputContext provides ToolOutputContext(agentName, status)) {
        ToolOutputDialog(ToolDisclosureUi(agentName, body), onDismiss = { opened = false },
            titleOverride = agentName, plainText = true)
    }
}

@Composable
internal fun ChildTaskReceiptContent(details: ToolCategoryDetailsUi) {
    val rich = details.rich
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        if (rich.bodyRestricted) {
            ChildTaskFrame { Text(rich.prose?.text ?: "任务暂不可查看", style = MiuixTheme.textStyles.footnote1) }
            return@Column
        }
        rich.entries.forEachIndexed { index, entry ->
            ChildTaskFrame(Modifier.testTag("tool-child-receipt:$index")) {
                val status = entry.fields.firstOrNull { it.label == "状态" }?.value ?: "状态未确认"
                Row(Modifier.fillMaxWidth().heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(entry.title, Modifier.weight(1f), style = MiuixTheme.textStyles.footnote1,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(status, style = MiuixTheme.textStyles.footnote1,
                        color = if (status in setOf("状态未确认", "失败", "未执行")) conversationColors().error else conversationColors().muted)
                }
                ChildTaskProgressContent(entry.documents.firstOrNull()?.text, status, "receipt-$index", entry.title)
            }
        }
        if (rich.entries.isEmpty()) rich.prose?.let { notice ->
            ChildTaskFrame { Text(notice.text, style = MiuixTheme.textStyles.footnote1, color = conversationColors().text) }
        }
    }
}
