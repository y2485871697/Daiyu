package app.daiyu.android.ui.screens.chat.conversation

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
    Column(Modifier.fillMaxWidth().testTag("tool-category:$category"), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        SectionTitle(details.heading)
        if (rich.operation != details.heading) SectionTitle(rich.operation)
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
private fun CategoryDocument(document: ToolDisclosureUi) {
    if (document.text.length > 500 || document.text.count { it == '\n' } > 5) Document(document)
    else Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionTitle(document.title)
        SelectionContainer {
            Text(document.text, Modifier.fillMaxWidth().background(MiuixTheme.colorScheme.surfaceVariant.copy(alpha = .35f)).padding(8.dp),
                style = if (document.code) MiuixTheme.textStyles.footnote1.copy(fontFamily = FontFamily.Monospace)
                    else MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun Document(document: ToolDisclosureUi) {
    var longer by remember(document) { mutableStateOf(false) }
    Disclosure("查看${document.title} · 已脱敏", "tool-document:${document.title}") {
        SelectionContainer {
            Text(document.text, Modifier.fillMaxWidth()
                .heightIn(max = if (longer) 360.dp else 160.dp)
                .background(MiuixTheme.colorScheme.surfaceVariant.copy(alpha = .35f))
                .verticalScroll(rememberScrollState()).padding(8.dp),
                style = if (document.code) MiuixTheme.textStyles.footnote1.copy(fontFamily = FontFamily.Monospace)
                    else MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurface)
        }
        if (document.text.length > 500 || document.text.count { it == '\n' } > 5) {
            Text(if (longer) "收起长内容" else "展开长内容", Modifier.heightIn(min = 44.dp).clickable(role = Role.Button) { longer = !longer }
                .padding(vertical = 5.dp), color = MiuixTheme.colorScheme.primary, style = MiuixTheme.textStyles.footnote1)
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
