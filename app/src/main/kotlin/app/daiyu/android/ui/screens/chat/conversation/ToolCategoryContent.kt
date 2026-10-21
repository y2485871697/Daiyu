package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import app.daiyu.android.ui.components.PreviewDialog
import app.daiyu.android.ui.components.usagePalette
import app.daiyu.android.ui.components.previewGlassBlur
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.daiyu.android.ui.icons.DaiyuIcons
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Shared category bodies; only the host may append trusted assets or live controls. */
@Composable
internal fun ToolCategoryContent(category: String, details: ToolCategoryDetailsUi) {
    val rich = details.rich
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        when (category) {
            "terminal", "background_job" -> {
                val documents = rich.documents.filter { it.title != "工作目录" }
                documents.forEach { CategoryDocument(it) }
                if (documents.none { it.title != "命令" && it.title != "会话输入" }) rich.prose?.let { Document(it) }
                Fields((details.fields + rich.fields).filter { it.label in setOf("退出码", "返回状态", "命令完成确认") }.distinct())
                Disclosure("运行环境", "tool-runtime") {
                    Fields((rich.fields + details.fields).filter { it.label !in setOf("退出码", "返回状态", "命令完成确认") }.distinct())
                    rich.documents.filter { it.title == "工作目录" }.forEach { Document(it) }
                }
            }
            "delegation", "child_control" -> {
                Fields(details.fields + rich.fields)
                details.items.forEachIndexed { index, item ->
                    Disclosure(item.title, "tool-task:$index") { Fields(item.fields) }
                }
                EntryList(rich.entries, "工具返回任务记录")
                rich.documents.forEach { CategoryDocument(it) }
            }
            "memory", "personal_search" -> {
                Fields(details.fields + rich.fields)
                EntryList(rich.entries, if (category == "memory") "记忆命中" else "检索命中")
                rich.documents.forEach { CategoryDocument(it) }
            }
            "files", "workspace" -> {
                Fields(details.fields + rich.fields)
                EntryList(rich.entries, if (category == "files") "目录与文件" else "工作区条目")
                rich.documents.forEach { CategoryDocument(it) }
            }
            "skills" -> {
                Fields(rich.fields + details.fields)
                EntryList(rich.entries, "技能与资源")
                rich.documents.forEach { CategoryDocument(it) }
            }
            "browser", "gui" -> {
                Fields(rich.fields + details.fields)
                EntryList(rich.entries, if (category == "browser") "标签与元素" else "观察节点")
                rich.documents.forEach { CategoryDocument(it) }
            }
            "apps" -> {
                Fields(rich.fields + details.fields)
                EntryList(rich.entries, "匹配应用")
                rich.documents.forEach { CategoryDocument(it) }
            }
            "image_read", "generation", "speech" -> {
                Fields(rich.fields + details.fields)
                EntryList(rich.entries, "媒体记录")
                rich.documents.forEach { CategoryDocument(it) }
            }
            "device_read", "system_action" -> {
                rich.prose?.let { Document(it) }
            }
            "context" -> {
                Fields(rich.fields + details.fields)
                EntryList(rich.entries, "记录")
                rich.documents.forEach { CategoryDocument(it) }
            }
            "mcp", "generic", "user_question" -> {
                Fields(rich.fields + details.fields)
                EntryList(rich.entries, "工具目录")
                rich.documents.forEach { CategoryDocument(it) }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MiuixTheme.textStyles.footnote1.copy(fontSize = 8.sp, lineHeight = 13.sp),
        color = conversationColors().muted)
}

@Composable
private fun Fields(fields: List<ToolFieldUi>) {
    fields.distinct().forEach { field ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.Top) {
            Text(field.label, Modifier.width(42.dp),
                style = MiuixTheme.textStyles.footnote1.copy(fontSize = 8.sp, lineHeight = 13.2.sp),
                color = conversationColors().muted)
            Text(field.value, Modifier.weight(1f),
                style = MiuixTheme.textStyles.footnote1.copy(fontSize = 8.sp, lineHeight = 13.2.sp),
                color = conversationColors().text)
        }
    }
}

@Composable
private fun EntryList(entries: List<ToolRichEntryUi>, title: String) {
    if (entries.isEmpty()) return
    entries.groupBy { it.group.ifBlank { title } }.forEach { (groupTitle, grouped) ->
    Disclosure("$groupTitle · ${grouped.size}项", "tool-entries") {
        grouped.forEachIndexed { index, entry ->
            key(index, entry.title) {
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    SectionTitle(entry.title)
                    Fields(entry.fields)
                    entry.documents.forEach { CategoryDocument(it) }
                    if (index != grouped.lastIndex) HorizontalDivider(thickness = 1.dp, color = conversationColors().line)
                }
            }
        }
    }
    }
}

@Composable
internal fun ToolDocument(document: ToolDisclosureUi) = Document(document)

@Composable
private fun CategoryDocument(document: ToolDisclosureUi) = Document(document)

@Composable
private fun Document(document: ToolDisclosureUi) {
    var opened by remember(document) { mutableStateOf(false) }
    var copied by remember(document) { mutableStateOf(false) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val log = document.title in setOf("标准输出", "标准错误", "已收到输出", "任务日志", "守护日志", "运行回执")
    val preview = toolOutputPreview(document.text, tail = log)
    val tappable = log || preview.truncated
    val colors = conversationColors()
    val code = document.code || log || preview.truncated
    val style = MiuixTheme.textStyles.footnote1.copy(fontSize = if (code) 8.5.sp else 8.sp,
        lineHeight = if (code) 14.sp else 13.6.sp,
        fontFamily = if (code) FontFamily.Monospace else FontFamily.Default)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (document.title.isNotBlank()) SectionTitle(document.title)
        val shape = RoundedCornerShape(8.dp)
        // A prose return is a paragraph, not another nested card.
        val surface = if (code) Modifier.fillMaxWidth().clip(shape)
            .background(colors.soft).border(1.dp, colors.line, shape)
        else Modifier.fillMaxWidth()
        if (tappable) Box(surface.testTag("tool-document:${document.title}")
            .conversationClickable(onClickLabel = "查看${document.title}") { opened = true }) {
            Text(preview.text, modifier = Modifier.fillMaxWidth()
                .padding(start = 9.dp, end = 9.dp, top = 8.dp, bottom = 14.dp)
                .testTag("tool-output-preview:${document.title}"), style = style, maxLines = 3,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, color = colors.text)
            val hintBackground = if (colors.dark) Color(0xFF191B23) else Color(0xFFFAF9FC)
            Text("点按查看完整输出", Modifier.align(Alignment.BottomEnd).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, hintBackground)))
                .padding(start = 9.dp, end = 9.dp, top = 7.dp, bottom = 5.dp),
                style = MiuixTheme.textStyles.footnote1.copy(fontSize = 10.sp, lineHeight = 13.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End), color = colors.muted)
        } else SelectionContainer {
            Text(document.text, (if (code) surface.padding(horizontal = 9.dp, vertical = 8.dp)
                else surface.padding(vertical = 2.dp)).testTag("tool-document:${document.title}"), style = style, color = colors.text)
        }
    }
    if (opened) PreviewDialog({ opened = false }) {
        val palette = usagePalette()
        val shape = RoundedCornerShape(18.dp)
        val viewportHeight = maxHeight
        Column(Modifier.align(Alignment.Center).width(minOf(maxWidth * .92f, 620.dp, (maxWidth - 24.dp).coerceAtLeast(0.dp)))
            .heightIn(max = viewportHeight * .82f).clip(shape).previewGlassBlur(shape)
            .background(Brush.linearGradient(palette.sheet)).border(1.dp, palette.edge, shape)
            .conversationClickable {}.padding(16.dp).testTag("tool-output-dialog"),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(document.title, Modifier.weight(1f), fontSize = 15.sp, lineHeight = 21.sp,
                    fontWeight = FontWeight.SemiBold, color = palette.text)
                Box(Modifier.size(40.dp).testTag("tool-output-close").conversationClickable { opened = false }, contentAlignment = Alignment.Center) {
                    Icon(DaiyuIcons.Close, "关闭", Modifier.size(16.dp), tint = palette.muted)
                }
            }
            Text("${document.text.count { it == '\n' } + 1} 行", fontSize = 11.sp, lineHeight = 16.5.sp, color = palette.muted)
            SelectionContainer(Modifier.weight(1f, fill = false)) {
                Text(document.text, Modifier.fillMaxWidth().heightIn(max = viewportHeight * .58f)
                    .verticalScroll(rememberScrollState()).testTag("tool-output-text"),
                    style = style.copy(fontSize = 12.sp, lineHeight = 19.2.sp), color = palette.text)
            }
            Box(Modifier.align(Alignment.End).heightIn(min = 40.dp).clip(RoundedCornerShape(10.dp))
                .border(1.dp, palette.line, RoundedCornerShape(10.dp)).testTag("tool-output-copy")
                .conversationClickable {
                    runCatching { clipboard.setText(androidx.compose.ui.text.AnnotatedString(document.text)) }
                        .onSuccess { copied = true }
                        .onFailure { android.widget.Toast.makeText(context, "复制失败，可长按选择文本", android.widget.Toast.LENGTH_SHORT).show() }
                }.padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                Text(if (copied) "已复制" else "复制全文", fontSize = 12.sp, lineHeight = 18.sp, color = palette.accent)
            }
        }
    }

}

@Composable
private fun Disclosure(title: String, tag: String, body: @Composable () -> Unit) {
    var open by remember(title) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth().testTag(tag).heightIn(min = 22.dp)
            .conversationClickable { open = !open }.padding(vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(if (open) DaiyuIcons.ChevronDown else DaiyuIcons.ChevronRight, null, Modifier.size(10.dp), tint = conversationColors().muted)
            Text(title, style = MiuixTheme.textStyles.footnote1.copy(fontSize = 7.5.sp, lineHeight = 12.sp),
                color = conversationColors().muted)
        }
        if (open) body()
    }
}
