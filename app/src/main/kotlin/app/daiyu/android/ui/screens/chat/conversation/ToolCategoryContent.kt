package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
                Disclosure("运行环境", "tool-runtime") {
                    Fields(rich.fields)
                    rich.documents.filter { it.title == "工作目录" }.forEach { Document(it) }
                }
                Fields(details.fields)
                rich.documents.filter { it.title != "工作目录" }.forEach { CategoryDocument(it) }
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
            "device_read", "system_action", "context" -> {
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
    Text(text, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
}

@Composable
private fun Fields(fields: List<ToolFieldUi>) {
    fields.distinct().forEach { field ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.Top) {
            Text(field.label, Modifier.width(80.dp), style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Text(field.value, Modifier.weight(1f), style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurface)
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
                    if (index != grouped.lastIndex) HorizontalDivider(color = MiuixTheme.colorScheme.outline.copy(alpha = .4f))
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
    val style = if (document.code) MiuixTheme.textStyles.footnote1.copy(fontFamily = FontFamily.Monospace)
        else MiuixTheme.textStyles.footnote1
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionTitle(document.title)
        val shape = RoundedCornerShape(8.dp)
        val surface = Modifier.fillMaxWidth().clip(shape)
            .background(MiuixTheme.colorScheme.surfaceVariant)
            .border(1.dp, MiuixTheme.colorScheme.outline, shape)
        if (tappable) Column(surface.testTag("tool-document:${document.title}")
            .clickable(role = Role.Button, onClickLabel = "查看${document.title}") { opened = true }.padding(8.dp)) {
            Text(preview.text, modifier = Modifier.testTag("tool-output-preview:${document.title}"), style = style, maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                color = MiuixTheme.colorScheme.onSurface)
            Text("点按查看完整输出", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        } else SelectionContainer {
            Text(document.text, surface.padding(8.dp), style = style, color = MiuixTheme.colorScheme.onSurface)
        }
    }
    if (opened) top.yukonga.miuix.kmp.window.WindowDialog(show = true, title = document.title,
        onDismissRequest = { opened = false }) {
        Column(Modifier.fillMaxWidth().testTag("tool-output-dialog"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("${document.text.count { it == '\n' } + 1} 行")
            SelectionContainer {
                Text(document.text, Modifier.fillMaxWidth().heightIn(max = minOf(480.dp,
                    (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * .55f).dp))
                    .verticalScroll(rememberScrollState()).testTag("tool-output-text"),
                    style = style, color = MiuixTheme.colorScheme.onSurface)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                top.yukonga.miuix.kmp.basic.TextButton(text = if (copied) "已复制" else "复制全文",
                    modifier = Modifier.testTag("tool-output-copy"), onClick = {
                        runCatching { clipboard.setText(androidx.compose.ui.text.AnnotatedString(document.text)) }
                            .onSuccess { copied = true }
                            .onFailure { android.widget.Toast.makeText(context, "复制失败，可长按选择文本", android.widget.Toast.LENGTH_SHORT).show() }
                    })
                top.yukonga.miuix.kmp.basic.TextButton(text = "关闭", onClick = { opened = false },
                    modifier = Modifier.testTag("tool-output-close"))
            }
        }
    }
}

@Composable
private fun Disclosure(title: String, tag: String, body: @Composable () -> Unit) {
    var open by remember(title) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth().testTag(tag).heightIn(min = 44.dp).clickable(role = Role.Button) { open = !open }.padding(vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(if (open) DaiyuIcons.ChevronDown else DaiyuIcons.ChevronRight, null, Modifier.size(13.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Text(title, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.primary)
        }
        if (open) body()
    }
}
