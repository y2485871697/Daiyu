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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.daiyu.android.delegation.ReasoningEffort
import app.daiyu.android.ui.components.subagent.SubAgentThinkingEffortPickerDialog
import app.daiyu.android.ui.FeatureRoute
import app.daiyu.android.ui.components.MiuixScaffoldPage
import app.daiyu.android.ui.icons.DaiyuIcon
import app.daiyu.android.ui.icons.daiyuIconColor
import app.daiyu.android.ui.icons.DaiyuIcons
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private class SettingsHomeVisit { var active = true }

@Composable
internal fun SettingsIcon(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
        DaiyuIcon(icon, contentDescription = null, tint = daiyuIconColor(icon), size = 23.dp)
    }
}

@Composable
private fun SettingsSearch(query: String, onQueryChange: (String) -> Unit) {
    val colors = MiuixTheme.colorScheme
    Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp).fillMaxWidth()
        .background(colors.surfaceContainerHigh, RoundedCornerShape(12.dp))
        .padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        DaiyuIcon(DaiyuIcons.Search, null, tint = colors.onSurfaceVariantSummary, size = 20.dp)
        BasicTextField(value = query, onValueChange = onQueryChange, singleLine = true,
            textStyle = MiuixTheme.textStyles.body1.copy(color = colors.onSurface, fontSize = 15.sp),
            cursorBrush = SolidColor(colors.primary),
            modifier = Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 14.dp)
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
private fun OriginalSettingsRow(entry: SettingsEntry, defaultReasoning: ReasoningEffort, defaultReasoningError: String?,
    onDefaultReasoningToggle: ((Boolean) -> Unit)?, diagnostics: SettingsDiagnosticsUi, onClick: () -> Unit) {
    if (entry.id in setOf("logging", "export_logs", "clear_logs")) {
        SettingsDiagnosticRow(entry, diagnostics)
        return
    }
    if (entry.id == "default_reasoning" && onDefaultReasoningToggle != null) {
        SettingsSwitchRow(entry.title, defaultReasoning !in setOf(ReasoningEffort.DEFAULT, ReasoningEffort.OFF),
            enabled = defaultReasoningError == null, tag = "settings-row:${entry.title}",
            summary = "与独立草稿共用；不改既有会话。关闭时由模型配置决定", onCheckedChange = onDefaultReasoningToggle)
        if (defaultReasoningError != null) SettingsNote(defaultReasoningError, error = true)
        return
    }
    SettingsRow(entry.title, onClick = if (entry.id == "donate") null else onClick,
        summary = when (entry.id) {
            "donate" -> "暂未开放，不提供支付入口"
            "accessibility" -> "查看服务与授权边界；进入设置不向模型授权"
            "task_surface" -> "前台、后台或每次运行询问；不改变当前运行"
            else -> null
        }, leading = { SettingsIcon(entry.icon) }, trailing = when (entry.id) {
            "telegram", "qq", "source" -> ({
                Text(when (entry.id) { "telegram" -> "Telegram"; "qq" -> "QQ"; else -> "GitHub" },
                    style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            })
            else -> null
        })
}

@Composable
internal fun DaiyuSettingsScreen(
    onBack: () -> Unit,
    onRoute: (FeatureRoute) -> Unit,
    modifier: Modifier = Modifier,
    onOpenSubAgents: (() -> Unit)? = null,
    onOpenAssistants: (() -> Unit)? = null,
    onOpenAppearance: (() -> Unit)? = null,
    onOpenHaptics: (() -> Unit)? = null,
    onOpenPlatformTools: (() -> Unit)? = null,
    onOpenVoice: (() -> Unit)? = null,
    defaultReasoning: ReasoningEffort = ReasoningEffort.DEFAULT,
    defaultReasoningError: String? = null,
    onDefaultReasoningChange: ((ReasoningEffort) -> Unit)? = null,
    engineeringUi: Boolean = false,
    diagnostics: app.daiyu.android.diagnostics.DiagnosticLogStore? = null,
) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val visit = remember { SettingsHomeVisit() }
    DisposableEffect(visit) { onDispose { visit.active = false } }
    var localPage by remember { mutableStateOf<SettingsLocalPage?>(null) }
    val diagnosticsUi = rememberSettingsDiagnostics(diagnostics) { visit.active && localPage == null }
    var query by rememberSaveable { mutableStateOf("") }
    var relayExpanded by rememberSaveable { mutableStateOf(false) }
    var choosingDefaultReasoning by remember { mutableStateOf(false) }
    val defaultReasoningToggle: ((Boolean) -> Unit)? = onDefaultReasoningChange?.let { change -> { enabled ->
        if (visit.active && localPage == null && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            if (enabled) { focus.clearFocus(); choosingDefaultReasoning = true } else change(ReasoningEffort.DEFAULT)
        }
    } }
    SubAgentThinkingEffortPickerDialog(show = choosingDefaultReasoning, effort = defaultReasoning,
        options = ReasoningEffort.entries.filter { it !in setOf(ReasoningEffort.DEFAULT, ReasoningEffort.OFF) },
        description = "为新会话显式选择默认强度；不修改既有会话或运行中的请求。关闭开关仅取消额外指定，不强制关闭模型推理。",
        onDismiss = { choosingDefaultReasoning = false }, onEffortChange = { effort ->
            if (visit.active && choosingDefaultReasoning && localPage == null &&
                lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                onDefaultReasoningChange?.invoke(effort)
                choosingDefaultReasoning = false
            }
        })
    val catalogGroups = remember(engineeringUi) { SettingsCatalog.visibleGroups(engineeringUi) }
    val groups = remember(query, catalogGroups) { filterSettingsGroups(query, catalogGroups) }
    val listState = rememberLazyListState()
    LaunchedEffect(query) { listState.scrollToItem(0) }
    val openedPage = localPage
    if (openedPage != null) {
        val localBack: () -> Unit = {
            if (visit.active && localPage == openedPage) {
                localPage = if (openedPage == SettingsLocalPage.SHIZUKU_ACCESS) SettingsLocalPage.ENHANCEMENTS else null
            }
        }
        BackHandler(onBack = localBack)
        if (openedPage == SettingsLocalPage.TASK_SURFACE) {
            TaskSurfaceSettingsPanel(onBack = localBack, modifier = modifier)
        } else if (openedPage == SettingsLocalPage.WORKSPACE) {
            WorkspaceSettingsScreen(onBack = localBack, modifier = modifier)
        } else if (openedPage == SettingsLocalPage.UPDATES) {
            SettingsReleasePage(remember { app.daiyu.android.update.GithubReleaseChecker() },
                currentVersion = app.daiyu.android.BuildConfig.VERSION_NAME, onBack = localBack, modifier = modifier)
        } else {
            SettingsSystemAccessPage(openedPage, onBack = localBack,
                onOpenShizuku = {
                    if (visit.active && localPage == openedPage) localPage = SettingsLocalPage.SHIZUKU_ACCESS
                },
                onRoute = { route -> if (visit.active && localPage == openedPage) onRoute(route) },
                modifier = modifier)
        }
        return
    }
    val open: (SettingsEntry) -> Unit = open@ { entry ->
        if (!visit.active || localPage != null || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@open
        focus.clearFocus()
        if (entry.localPage != null) {
            diagnosticsUi.retirePicker()
            localPage = entry.localPage
            return@open
        }
        when (entry.id) {
            "assistants" -> onOpenAssistants?.invoke()
            "subagents" -> onOpenSubAgents?.invoke()
            "appearance" -> onOpenAppearance?.invoke()
            "haptics" -> onOpenHaptics?.invoke()
            "voice" -> onOpenVoice?.invoke()
            "platform_tools" -> onOpenPlatformTools?.invoke()
            "source" -> openProjectSource(context)
            "telegram", "qq", "relay_st", "relay_moyu" -> openRetainedSettingsLink(context, entry.id)
            "relay" -> {
                relayExpanded = !relayExpanded
            }
            else -> entry.route?.let(onRoute)
        }
    }
    MiuixScaffoldPage(title = "设置", onBack = onBack, modifier = modifier, listState = listState,
        listModifier = if (engineeringUi) Modifier.semantics { this[RetainedSettingsEntryAction] = open } else Modifier,
    ) {
        item(key = "settings_search") { SettingsSearch(query) { query = it } }
        if (query.isNotBlank()) item(key = "search_heading") {
            SettingsSectionTitle("搜索结果 · ${groups.sumOf { it.entries.size }}")
        }
        if (groups.isEmpty()) item(key = "settings_empty") { SettingsNote("没有匹配的设置") }
        groups.forEach { group ->
            item(key = "settings_group_${group.id}") { SettingsSectionTitle(group.title) }
            item(key = "settings_entries_${group.id}") {
                SettingsCard {
                    group.entries.forEach { entry ->
                        when {
                            entry.id in setOf("relay_st", "relay_moyu") && query.isBlank() -> Unit
                            entry.id == "relay" && query.isBlank() -> {
                                SettingsRow(entry.title, leading = { SettingsIcon(entry.icon) }, onClick = { open(entry) },
                                    trailing = {
                                        DaiyuIcon(if (relayExpanded) DaiyuIcons.ChevronDown else DaiyuIcons.ChevronRight,
                                            if (relayExpanded) "收起" else "展开", size = 20.dp)
                                    })
                                if (relayExpanded) Column(Modifier.padding(start = 66.dp)) {
                                    group.entries.filter { it.id in setOf("relay_st", "relay_moyu") }.forEach { child ->
                                        SettingsRow(child.title, onClick = { open(child) })
                                    }
                                }
                            }
                            else -> OriginalSettingsRow(entry, defaultReasoning, defaultReasoningError,
                                defaultReasoningToggle, diagnosticsUi) { open(entry) }
                        }
                    }
                }
            }
        }
    }
}
