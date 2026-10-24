package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.daiyu.android.ui.icons.DaiyuIcons
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Shared category bodies; only the host may append trusted assets or live controls. */
@Composable
internal fun ToolCategoryContent(category: String, details: ToolCategoryDetailsUi) {
    val rich = details.rich
    val body = toolBodyLayout(category, details)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        if (rich.bodyRestricted) {
            body.prose?.let { Document(it) }
            return@Column
        }
        if (category !in setOf("terminal", "background_job", "device_read", "system_action", "generation")) Fields(body.fields)
        body.inputs.forEach { InputDocument(it) }
        if (category == "generation") Fields(body.fields)
        if (category == "device_read") body.prose?.let { Document(it) }
        if (rich.entries.isEmpty()) details.items.forEachIndexed { index, item ->
            Disclosure(item.title, "tool-task:$index") { Fields(item.fields) }
        }
        EntryList(rich.entries, "返回条目", private = category in setOf("memory", "personal_search"))
        body.documents.forEach { Document(it, showTitle = !body.plainDocuments || category == "workspace" && it.code) }
        if (category != "device_read") body.prose?.let { Document(it) }
        if (category in setOf("terminal", "background_job")) Fields(body.fields)
    }
}

@Composable
private fun InputDocument(document: ToolDisclosureUi) {
    if (document.title in setOf("路径", "范围", "来源", "目标", "读取来源", "页面 · 已去除认证和查询参数", "外部链接 · 不自动打开", "应用查询", "检索条件", "搜索条件", "技能查询", "历史查询", "资源", "授权文件URI", "授权目录URI")) {
        val label = when (document.title) {
            "页面 · 已去除认证和查询参数" -> "页面"
            "授权文件URI", "授权目录URI" -> "路径"
            "外部链接 · 不自动打开" -> "链接"
            "技能查询" -> "技能"
            "应用查询", "检索条件", "搜索条件", "历史查询" -> "查询"
            else -> document.title
        }
        Fields(listOf(ToolFieldUi(label, document.text)))
    } else Document(document, showTitle = document.title !in setOf("提示摘要", "文本摘要"))
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MiuixTheme.textStyles.footnote1.copy(fontSize = 12.sp, lineHeight = 18.sp),
        color = conversationColors().muted)
}

@Composable
private fun Fields(fields: List<ToolFieldUi>) {
    if (fields.isEmpty()) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        fields.distinct().forEach { field ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.Top) {
                Text(if (field.label == "原工具") "工具" else field.label, Modifier.width(72.dp),
                    style = MiuixTheme.textStyles.footnote1.copy(fontSize = 12.sp, lineHeight = 18.sp),
                    color = conversationColors().muted)
                Text(field.value, Modifier.weight(1f),
                    style = MiuixTheme.textStyles.footnote1.copy(fontSize = 14.sp, lineHeight = 21.sp),
                    color = conversationColors().text)
            }
        }
    }
}

@Composable
private fun EntryList(entries: List<ToolRichEntryUi>, title: String, private: Boolean = false) {
    if (entries.isEmpty()) return
    entries.groupBy { if (it.group == "记忆命中") "找到的记忆" else it.group.ifBlank { title } }.forEach { (groupTitle, grouped) ->
        val content: @Composable (List<ToolRichEntryUi>) -> Unit = { visible ->
            visible.forEachIndexed { index, entry ->
                key(index, entry.title) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        SectionTitle(entry.title)
                        Fields(entry.fields)
                        entry.documents.forEach { CategoryDocument(it) }
                        if (index != visible.lastIndex) HorizontalDivider(thickness = 1.dp, color = conversationColors().line)
                    }
                }
            }
        }
        if (private) Disclosure("$groupTitle · ${grouped.size}项", "tool-entries") { content(grouped) }
        else {
            content(grouped.take(3))
            if (grouped.size > 3) Disclosure("查看其余 ${grouped.size - 3} 项", "tool-entries") { content(grouped.drop(3)) }
        }
    }
}

@Composable
internal fun ToolDocument(document: ToolDisclosureUi) = Document(document)

@Composable
private fun CategoryDocument(document: ToolDisclosureUi) = Document(document)

@Composable
private fun Document(document: ToolDisclosureUi, showTitle: Boolean = true) {
    var opened by remember(document) { mutableStateOf(false) }
    val log = document.title in setOf("标准输出", "标准错误", "已收到输出", "任务日志", "守护日志", "运行回执")
    val preview = toolOutputPreview(document.text, tail = log)
    val tappable = preview.truncated
    val colors = conversationColors()
    val code = document.code || log || preview.truncated
    val style = MiuixTheme.textStyles.footnote1.copy(fontSize = if (code) 13.sp else 14.sp,
        lineHeight = if (code) 20.sp else 21.sp,
        fontFamily = if (code) FontFamily.Monospace else FontFamily.Default)
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        if (showTitle && document.title.isNotBlank()) SectionTitle(toolDocumentTitle(document.title))
        val shape = RoundedCornerShape(8.dp)
        // A prose return is a paragraph, not another nested card.
        val surface = if (code) Modifier.fillMaxWidth().clip(shape)
            .background(colors.soft).border(1.dp, colors.line, shape)
        else Modifier.fillMaxWidth()
        if (tappable) Box(surface.testTag("tool-document:${document.title}")
            .conversationClickable(onClickLabel = "查看${document.title}") { opened = true }) {
            Text(preview.text, modifier = Modifier.fillMaxWidth()
                .padding(start = 9.dp, end = 9.dp, top = 8.dp, bottom = 12.dp)
                .testTag("tool-output-preview:${document.title}"), style = style, maxLines = 3,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, color = colors.text)
            val hintBackground = if (colors.dark) Color(0xFF191B23) else Color(0xFFFAF9FC)
            Text("点按查看完整输出", Modifier.align(Alignment.BottomEnd).fillMaxWidth().testTag("tool-output-hint:${document.title}")
                .background(Brush.verticalGradient(listOf(Color.Transparent, hintBackground)))
                .padding(start = 9.dp, end = 9.dp, top = 7.dp, bottom = 5.dp),
                style = MiuixTheme.textStyles.footnote1.copy(fontSize = 12.sp, lineHeight = 18.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End), color = colors.muted)
        } else SelectionContainer {
            if (code) Box(surface.testTag("tool-document-frame:${document.title}")) {
                Text(document.text, Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)
                    .testTag("tool-document:${document.title}"), style = style, color = colors.text)
            } else Text(document.text, surface.padding(vertical = 2.dp)
                .testTag("tool-document:${document.title}"), style = style, color = colors.text)
        }
    }
    if (opened) ToolOutputDialog(document, onDismiss = { opened = false })

}

@Composable
private fun Disclosure(title: String, tag: String, body: @Composable () -> Unit) {
    var open by remember(title) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        ToolDisclosureHeader(title, open, Modifier.testTag(tag)) { open = !open }
        if (open) body()
    }
}

@Composable
internal fun ToolDisclosureHeader(title: String, expanded: Boolean, modifier: Modifier = Modifier, compact: Boolean = false, onClick: () -> Unit) {
    val colors = conversationColors()
    Row(modifier.fillMaxWidth().heightIn(min = if (compact) 32.dp else 48.dp)
        .conversationClickable(onClick = onClick)
        .semantics { stateDescription = if (expanded) "已展开" else "已折叠" }
        .padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Icon(if (expanded) DaiyuIcons.ChevronDown else DaiyuIcons.ChevronRight, null,
            Modifier.size(14.dp).testTag("tool-disclosure-arrow:$title"), tint = colors.muted)
        Text(title, style = MiuixTheme.textStyles.footnote1.copy(fontSize = 12.sp, lineHeight = 18.sp), color = colors.muted)
    }
}
