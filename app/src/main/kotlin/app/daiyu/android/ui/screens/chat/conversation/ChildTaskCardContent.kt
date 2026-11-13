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
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import app.daiyu.android.ui.icons.DaiyuIcons
import app.daiyu.android.ui.screens.settings.settingsDisclosureRotation
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
internal fun ChildTaskProgressContent(text: String?, status: String, tag: String, agentName: String, expanded: Boolean = false) {
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
    val truncated = !expanded && (preview.truncated || layoutOverflow)
    val click = if (truncated) Modifier.conversationClickable(onClickLabel = "查看${agentName}的任务内容") {
        opened = true
    } else Modifier
    BasicText(if (expanded) body else preview.text, Modifier.fillMaxWidth().heightIn(min = 20.dp)
        .testTag("tool-child-progress:$tag").then(click),
        style = MiuixTheme.textStyles.footnote1.copy(color = conversationColors().text),
        maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
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
                var expanded by rememberSaveable(index, entry.title) { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth().heightIn(min = 28.dp).testTag("tool-child-receipt-header:$index")
                    .conversationClickable(onClickLabel = if (expanded) "收起子任务" else "展开子任务") { expanded = !expanded }
                    .semantics { stateDescription = if (expanded) "已展开" else "已折叠" }, verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(entry.title, Modifier.weight(1f), style = MiuixTheme.textStyles.footnote1,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(status, style = MiuixTheme.textStyles.footnote1,
                        color = if (status in setOf("状态未确认", "失败", "未执行")) conversationColors().error else conversationColors().muted)
                    Icon(DaiyuIcons.ChevronDown, null, Modifier.size(14.dp)
                        .settingsDisclosureRotation(expanded, expandedDegrees = 180f).testTag("tool-child-receipt-arrow:$index"),
                        tint = conversationColors().muted)
                }
                ChildTaskProgressContent(entry.documents.firstOrNull()?.text, status, "receipt-$index", entry.title, expanded)
            }
        }
        if (rich.entries.isEmpty()) rich.prose?.let { notice ->
            ChildTaskFrame { Text(notice.text, style = MiuixTheme.textStyles.footnote1, color = conversationColors().text) }
        }
    }
}
