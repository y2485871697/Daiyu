package app.daiyu.android.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.daiyu.android.ui.FeatureRoute
import app.daiyu.android.ui.components.MiuixScaffoldPage
import app.daiyu.android.ui.icons.DaiyuIcon
import app.daiyu.android.ui.icons.DaiyuIcons
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SettingsIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, emphasized: Boolean = false) {
    val colors = MiuixTheme.colorScheme
    Box(Modifier.size(38.dp).background(
        if (emphasized) colors.primaryContainer else colors.surfaceVariant,
        RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
        DaiyuIcon(icon, contentDescription = null,
            tint = if (emphasized) colors.primary else colors.onSurfaceSecondary, size = 20.dp)
    }
}

@Composable
private fun SettingsSearch(query: String, onQueryChange: (String) -> Unit) {
    val colors = MiuixTheme.colorScheme
    Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp).fillMaxWidth()
        .background(colors.surfaceContainerHigh, RoundedCornerShape(18.dp))
        .padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        DaiyuIcon(DaiyuIcons.Search, null, tint = colors.onSurfaceVariantSummary, size = 20.dp)
        BasicTextField(value = query, onValueChange = onQueryChange, singleLine = true,
            textStyle = MiuixTheme.textStyles.body1.copy(color = colors.onSurface, fontSize = 15.sp),
            cursorBrush = SolidColor(colors.primary),
            modifier = Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 16.dp)
                .testTag("settings-search").semantics { contentDescription = "搜索设置" },
            decorationBox = { editor ->
                Box {
                    if (query.isEmpty()) Text("搜索设置", color = colors.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.body1.copy(fontSize = 15.sp))
                    editor()
                }
            })
        if (query.isNotEmpty()) IconButton(onClick = { onQueryChange("") },
            modifier = Modifier.size(48.dp).testTag("settings-search-clear")) {
            DaiyuIcon(DaiyuIcons.Close, "清除搜索", tint = colors.onSurfaceVariantSummary, size = 18.dp)
        }
    }
}

@Composable
private fun OriginalSettingsRow(entry: SettingsEntry, onClick: () -> Unit) {
    SettingsRow(entry.title, onClick = onClick, leading = { SettingsIcon(entry.icon) })
}

@Composable
internal fun DaiyuSettingsScreen(
    onBack: () -> Unit,
    onRoute: (FeatureRoute) -> Unit,
    modifier: Modifier = Modifier,
    onOpenSubAgents: (() -> Unit)? = null,
    onOpenAssistants: (() -> Unit)? = null,
    onOpenAppearance: (() -> Unit)? = null,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var section by rememberSaveable { mutableStateOf<String?>(null) }
    var relayExpanded by rememberSaveable { mutableStateOf(false) }
    val groups = remember(query) { filterSettingsGroups(query) }
    val listState = rememberLazyListState()
    LaunchedEffect(section, query) { listState.scrollToItem(0) }
    val focus = LocalFocusManager.current
    val selectedGroup = SettingsCatalog.groups.find { it.id == section }
    val pageTitle = selectedGroup?.title ?: "设置"
    val back: () -> Unit = { if (section != null) section = null else onBack() }
    BackHandler(enabled = section != null) { section = null }
    val open: (SettingsEntry) -> Unit = { entry ->
        focus.clearFocus()
        when (entry.id) {
            "assistants" -> onOpenAssistants?.invoke()
            "subagents" -> onOpenSubAgents?.invoke()
            "appearance" -> onOpenAppearance?.invoke()
            "relay" -> {
                if (section != "about") { section = "about"; relayExpanded = true }
                else relayExpanded = !relayExpanded
            }
            else -> entry.route?.let(onRoute)
        }
    }
    MiuixScaffoldPage(title = pageTitle, onBack = back, modifier = modifier, listState = listState) {
        if (selectedGroup != null) {
            item(key = "category_intro") { SettingsNote(selectedGroup.summary) }
            item(key = "category_entries") {
                SettingsCard {
                    selectedGroup.entries.filter { relayExpanded || it.id !in setOf("relay_st", "relay_moyu") }
                        .forEachIndexed { index, entry ->
                            if (index > 0) SettingsDivider()
                            OriginalSettingsRow(entry) { open(entry) }
                        }
                }
            }
        } else {
            item(key = "settings_search") { SettingsSearch(query) { query = it } }
            if (query.isNotBlank()) {
                item(key = "search_heading") { SettingsSectionTitle("搜索结果 · ${groups.sumOf { it.entries.size }}") }
                if (groups.isEmpty()) item(key = "settings_empty") {
                    Column(Modifier.fillMaxWidth().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SettingsIcon(DaiyuIcons.Search)
                        Text("没有匹配的设置", color = MiuixTheme.colorScheme.onSurface,
                            style = MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.Medium))
                        Text("试试“模型”“语音”或“备份”", color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            style = MiuixTheme.textStyles.body2)
                    }
                }
                groups.forEach { group ->
                    item(key = "search_group_${group.id}") { SettingsSectionTitle(group.title) }
                    group.entries.forEach { entry ->
                        item(key = "search_entry_${entry.id}") {
                            SettingsCard(Modifier.padding(bottom = 6.dp)) {
                                OriginalSettingsRow(entry) { open(entry) }
                            }
                        }
                    }
                }
            } else {
                item(key = "settings_common") {
                    SettingsSectionTitle("常用设置")
                    SettingsCard {
                        listOf("providers", "appearance", "assistants", "subagents").forEachIndexed { index, id ->
                            if (index > 0) SettingsDivider()
                            val entry = SettingsCatalog.entry(id)
                            SettingsRow(entry.title, onClick = { open(entry) },
                                summary = when (id) {
                                    "providers" -> "服务地址、模型与请求参数"
                                    "appearance" -> "主题、界面缩放与显示"
                                    "assistants" -> "身份、记忆与专属技能"
                                    else -> "模型、预设与协作配置"
                                }, leading = { SettingsIcon(entry.icon, emphasized = true) })
                        }
                    }
                }
                item(key = "settings_tools") {
                    SettingsSectionTitle("工具与数据")
                    SettingsCard {
                        listOf("skills", "usage", "backup").forEachIndexed { index, id ->
                            if (index > 0) SettingsDivider()
                            val entry = SettingsCatalog.entry(id)
                            SettingsRow(entry.title, onClick = { open(entry) },
                                leading = { SettingsIcon(entry.icon) })
                        }
                    }
                }
                item(key = "settings_categories_heading") { SettingsSectionTitle("全部设置") }
                item(key = "settings_categories") {
                    SettingsCard {
                        SettingsCatalog.groups.forEachIndexed { index, group ->
                            if (index > 0) SettingsDivider()
                            SettingsRow(group.title, summary = group.summary,
                                onClick = { section = group.id }, leading = { SettingsIcon(group.entries.first().icon) })
                        }
                    }
                }
            }
        }
    }
}
