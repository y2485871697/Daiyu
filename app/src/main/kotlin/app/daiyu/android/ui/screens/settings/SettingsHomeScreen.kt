package app.daiyu.android.ui.screens.settings

import app.daiyu.android.ui.components.imeCursorBrush
import app.daiyu.android.ui.components.managedInput

import android.widget.Toast
import app.daiyu.android.ui.navigation.PageBackHandler as BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
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
import app.daiyu.android.BuildConfig
import app.daiyu.android.update.GithubReleaseChecker
import app.daiyu.android.update.ReleaseCheck
import app.daiyu.android.ui.FeatureRoute
import app.daiyu.android.ui.components.MiuixScaffoldPage
import app.daiyu.android.ui.components.usagePalette
import app.daiyu.android.ui.icons.DaiyuIcon
import app.daiyu.android.ui.icons.daiyuIconColor
import app.daiyu.android.ui.icons.DaiyuIcons
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import app.daiyu.android.ui.haptics.miuix.IconButton
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
internal fun SettingsSearch(query: String, onQueryChange: (String) -> Unit,
    placeholder: String = "搜索设置", tag: String = "settings-search",
    containerColor: Color = if (LocalSettingsPageStyle.current) MiuixTheme.colorScheme.surfaceContainerHighest
        else MiuixTheme.colorScheme.surfaceContainerHigh) {
    val colors = MiuixTheme.colorScheme
    Row(Modifier.padding(horizontal = if (LocalSettingsPageStyle.current) 16.dp else 20.dp, vertical = 8.dp).fillMaxWidth()
        .background(containerColor, RoundedCornerShape(12.dp))
        .padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        DaiyuIcon(DaiyuIcons.Search, null, tint = colors.onSurfaceVariantSummary, size = 20.dp)
        BasicTextField(value = query, onValueChange = onQueryChange, singleLine = true,
            textStyle = MiuixTheme.textStyles.body1.copy(color = colors.onSurface, fontSize = 15.sp),
            cursorBrush = imeCursorBrush(colors.primary),
            modifier = Modifier.managedInput().weight(1f).padding(horizontal = 10.dp, vertical = 14.dp)
                .testTag(tag).semantics { contentDescription = placeholder },
            decorationBox = { editor ->
                Box {
                    if (query.isEmpty()) Text(placeholder, color = colors.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.body1.copy(fontSize = 15.sp))
                    editor()
                }
            })
        if (query.isNotEmpty()) IconButton(onClick = { onQueryChange("") },
            modifier = Modifier.size(48.dp).testTag("$tag-clear")) {
            DaiyuIcon(DaiyuIcons.Close, "清除搜索", tint = colors.onSurfaceVariantSummary, size = 18.dp)
        }
    }
}

@Composable
private fun SettingsHomeSection(title: String, tag: String) {
    Text(title, modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 9.dp)
        .testTag(tag), fontSize = 13.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
}

@Composable
private fun SettingsHomeCard(id: String, content: @Composable ColumnScope.() -> Unit) {
    val colors = usagePalette()
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().testTag("settings-card:$id")
        .clip(RoundedCornerShape(22.dp)).background(Brush.linearGradient(colors.sheet)), content = content)
}

@Composable
private fun SettingsHomeRow(entry: SettingsEntry, diagnostics: SettingsDiagnosticsUi,
    release: SettingsReleaseUi, onClick: () -> Unit) {
    if (entry.id == "updates") {
        SettingsRow(entry.title, showChevron = true, onClick = if (release.checking) null else onClick,
            summary = if (release.checking) "正在检查更新…" else "当前版本：${BuildConfig.VERSION_NAME}",
            leading = { SettingsIcon(entry.icon) })
        return
    }
    if (entry.id in setOf("logging", "export_logs", "clear_logs")) {
        SettingsDiagnosticRow(entry, diagnostics)
        return
    }
    SettingsRow(entry.title, onClick = if (entry.id == "donate") null else onClick,
        summary = when (entry.id) {
            "donate" -> "暂未开放，不提供支付入口"
            "accessibility" -> "查看服务与授权边界；进入设置不向模型授权"
            else -> entry.summary
        }, leading = { SettingsIcon(entry.icon) })
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
    engineeringUi: Boolean = false,
    diagnostics: app.daiyu.android.diagnostics.DiagnosticLogStore? = null,
    scrollBehavior: ScrollBehavior = MiuixScrollBehavior(),
    releaseChecker: ReleaseCheck = remember { GithubReleaseChecker() },
    onReleaseNotice: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val visit = remember { SettingsHomeVisit() }
    DisposableEffect(visit) { onDispose { visit.active = false } }
    var localPage by remember { mutableStateOf<SettingsLocalPage?>(null) }
    val diagnosticsUi = rememberSettingsDiagnostics(diagnostics) { visit.active && localPage == null }
    val release = rememberSettingsReleaseCheck(releaseChecker, BuildConfig.VERSION_NAME,
        ownerCurrent = { visit.active && localPage == null },
        onNotice = onReleaseNotice ?: { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() })
    var relayExpanded by rememberSaveable { mutableStateOf(false) }
    val catalogGroups = remember(engineeringUi) { SettingsCatalog.visibleGroups(engineeringUi) }
    val groups = catalogGroups
    val listState = rememberLazyListState()
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
            "updates" -> release.check()
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
        scrollBehavior = scrollBehavior, containerColor = settingsPageBackground(),
        listModifier = Modifier.background(settingsPageBackground())
            .then(if (engineeringUi) Modifier.semantics { this[RetainedSettingsEntryAction] = open } else Modifier),
    ) {
        groups.forEach { group ->
            item(key = "settings_group_${group.id}") { SettingsHomeSection(group.title, "settings-section:${group.id}") }
            item(key = "settings_entries_${group.id}") {
                SettingsHomeCard(group.id) {
                    group.entries.forEach { entry ->
                        when {
                            entry.id in setOf("relay_st", "relay_moyu") -> Unit
                            entry.id == "relay" -> {
                                SettingsRow(entry.title, summary = entry.summary, leading = { SettingsIcon(entry.icon) }, onClick = { open(entry) },
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
                            else -> SettingsHomeRow(entry, diagnosticsUi, release) { open(entry) }
                        }
                    }
                }
            }
        }
    }
}
