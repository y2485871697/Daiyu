package app.daiyu.android.ui.screens.settings

import app.daiyu.android.ui.app.DaiyuPalette
import app.daiyu.android.ui.app.daiyuSwitchColors
import app.daiyu.android.ui.navigation.PageBackStack
import app.daiyu.android.ui.components.imeCursorBrush
import app.daiyu.android.ui.components.managedInput

import app.daiyu.android.ui.haptics.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*

import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import app.daiyu.android.ui.components.subagent.ConversationAgentHandle
import app.daiyu.android.ui.components.subagent.ConversationAgentSheet
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.sp
import app.daiyu.android.ui.components.subagent.ConversationAgentButton
import app.daiyu.android.ui.components.subagent.conversationAgentColors
import app.daiyu.android.ui.haptics.material.IconButton
import app.daiyu.android.ui.icons.DaiyuIcon
import app.daiyu.android.ui.icons.DaiyuIcons
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.ui.unit.dp
import app.daiyu.android.ui.components.DaiyuConfirmDialog
import top.yukonga.miuix.kmp.window.WindowDialog
import app.daiyu.android.mcp.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Adding a server discovers metadata once; visiting never connects and settings never execute tools. */
@Composable internal fun McpSettingsScreen(store: McpSettingsStore, owner: McpSettingsOwner,
    ownerCurrent: () -> Boolean, onBack: () -> Unit,
    managementFactory: (McpSettingsStore) -> McpManagement = { McpManagement(it) }) {
    val scope = rememberCoroutineScope()
    var snapshot by remember(owner) { mutableStateOf<McpSettings?>(null) }
    var status by remember(owner) { mutableStateOf("正在读取本地加密配置") }
    var busy by remember(owner) { mutableStateOf(true) }
    var id by remember(owner) { mutableStateOf("") }
    var label by remember(owner) { mutableStateOf("") }
    var endpoint by remember(owner) { mutableStateOf("") }
    var bearer by remember(owner) { mutableStateOf("") }
    var editing by remember(owner) { mutableStateOf(false) }
    var selectedId by remember(owner) { mutableStateOf<String?>(null) }
    var deleting by remember(owner) { mutableStateOf(false) }
    var describedTool by remember(owner, selectedId) { mutableStateOf<String?>(null) }
    fun current() = owner.current() && ownerCurrent()
    LaunchedEffect(owner) {
        val result = withContext(Dispatchers.IO) { runCatching { store.snapshot() } }
        if (current()) {
            snapshot = result.getOrNull(); busy = false
            status = if (snapshot == null) "配置读取失败，请重新打开设置。" else "能力默认可用；保存只更新服务器配置，不会执行工具。"
        }
    }
    DisposableEffect(owner) { onDispose { owner.revoke() } }
    fun back() {
        when {
            editing -> { editing = false; bearer = "" }
            selectedId != null -> selectedId = null
            else -> { owner.revoke(); onBack() }
        }
    }
    PageBackStack(selectedId?.let { "detail:$it" }) {
        app.daiyu.android.ui.navigation.PageBackHandler(previewEnabled = !editing) { back() }
        fun save(remove: Boolean) {
            val before = snapshot ?: return
            if (busy || !current()) return
            val value = try {
                val old = before.servers.singleOrNull { it.id == id }
                val list = before.servers.filterNot { it.id == id }
                if (remove) { require(old != null); list }
                else {
                    val secret = if (bearer.isEmpty()) old?.bearer.orEmpty() else bearer
                    val sameConnection = old != null && old.endpoint == endpoint && old.bearer == secret
                    list + McpServer(id, label, endpoint, secret, sameConnection && old!!.enabled,
                        if (sameConnection) old!!.catalog else emptyList(), if (sameConnection) old!!.allowedTools else emptySet(),
                        if (sameConnection) old!!.disabledTools else emptySet())
                }
            } catch (_: Exception) { status = "配置无效，请检查名称、地址（HTTPS 或本机 127.0.0.1 HTTP）和 Token 格式。"; return }
            val added = !remove && before.servers.none { it.id == id }
            val savedId = id
            busy = true
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching { check(owner.current()); store.save(value, owner, before) }
                }
                if (current()) {
                    bearer = ""
                    if (result.isSuccess) {
                        val saved = result.getOrThrow()
                        snapshot = saved; editing = false; deleting = false
                        selectedId = if (remove) null else savedId
                        status = "已保存；旧的本轮批准已失效，尚未连接服务器。"
                        if (added) {
                            status = "已添加，正在刷新工具目录。"
                            val discovery = withContext(Dispatchers.IO) {
                                runCatching { managementFactory(store).refresh(saved, savedId, owner) }
                            }
                            if (!current()) return@launch
                            if (discovery.isSuccess) {
                                snapshot = discovery.getOrThrow()
                                status = "工具目录已刷新；可提交精确任务，尚未调用工具。"
                            } else status = "服务器已保存，工具目录刷新失败；请点击刷新重试。"
                        }
                    } else status = "保存失败或页面/配置已过期；请重新打开设置。"
                    busy = false
                }
            }
        }
        fun refresh(before: McpSettings, server: McpServer) {
            if (busy || !current() || snapshot !== before) return
            busy = true
            scope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { managementFactory(store).refresh(before, server.id, owner) } }
                if (current()) {
                    busy = false
                    if (result.isSuccess) { snapshot = result.getOrThrow(); status = "工具目录已刷新；可提交精确任务，尚未调用工具。" }
                    else status = "目录刷新失败或已过期；保留上次目录，未调用工具。"
                }
            }
        }
        fun setDisabled(before: McpSettings, server: McpServer, disabled: Set<String>) {
            if (busy || !current() || snapshot !== before || disabled == server.disabledTools) return
            busy = true
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching { store.save(before.servers.map {
                        if (it.id == server.id) it.withPolicy(disabledTools = disabled) else it
                    }, owner, before) }
                }
                if (current()) {
                    busy = false
                    if (result.isSuccess) { snapshot = result.getOrThrow(); status = "工具开关已保存。" }
                    else status = "工具开关保存失败或配置已过期；请重新打开设置。"
                }
            }
        }
        val selected = snapshot?.servers?.singleOrNull { it.id == selectedId }
        fun edit(server: McpServer?) {
            if (busy || !current()) return
            id = server?.id ?: "server-${java.util.UUID.randomUUID().toString().take(24)}"
            label = server?.label.orEmpty(); endpoint = server?.endpoint.orEmpty(); bearer = ""
            editing = true
        }
        SettingsScaffoldPage(title = selected?.label ?: "MCP 服务器", onBack = { back() },
            modifier = Modifier.testTag("mcp-settings").semantics { stateDescription = status }, actions = {
                if (selected == null) {
                    IconButton(onClick = { edit(null) }, enabled = !busy && snapshot != null,
                        modifier = Modifier.size(48.dp).testTag("mcp-settings-add")) {
                        DaiyuIcon(DaiyuIcons.Add, contentDescription = "添加服务器",
                            tint = MiuixTheme.colorScheme.onSurface, size = 24.dp)
                    }
                } else {
                    app.daiyu.android.ui.haptics.miuix.IconButton(
                        onClick = { snapshot?.let { refresh(it, selected) } },
                        enabled = !busy && snapshot != null,
                        modifier = Modifier.size(48.dp).testTag("mcp-refresh:${selected.id}")) {
                        DaiyuIcon(DaiyuIcons.Refresh, contentDescription = "刷新", size = 22.dp)
                    }
                }
            }) {
            if (selected == null && (busy || status != "能力默认可用；保存只更新服务器配置，不会执行工具。")) item(key = "status") {
                top.yukonga.miuix.kmp.basic.Text(status,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp).testTag("mcp-settings-status"),
                    style = MiuixTheme.textStyles.body2)
            }
            if (selected == null) {
                item(key = "servers-heading") {
                    top.yukonga.miuix.kmp.basic.Text("服务器 · ${snapshot?.servers?.size ?: 0}",
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).testTag("mcp-settings-count"),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
                if (snapshot?.servers?.isEmpty() == true) item(key = "empty") {
                    Column(Modifier.padding(horizontal = 12.dp).fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp)).background(conversationAgentColors().soft)
                        .testTag("mcp-settings-empty").padding(horizontal = 20.dp, vertical = 28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        top.yukonga.miuix.kmp.basic.Text("还没有 MCP 服务器", style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        top.yukonga.miuix.kmp.basic.Text("点击添加服务器", style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        ConversationAgentButton("添加服务器", enabled = !busy, modifier = Modifier.testTag("mcp-settings-empty-add")) { edit(null) }
                    }
                }
                snapshot?.servers?.forEach { server ->
                    item(key = server.id) {
                        SettingsCard {
                            SettingsRow(server.label, summary = server.endpoint,
                                tag = "mcp-server:${server.id}",
                                onClick = { if (!busy && current()) selectedId = server.id })
                        }
                    }
                }
            } else {
                item(key = "server-settings") {
                    SettingsSectionTitle("服务器设置")
                    SettingsCard {
                        SettingsRow("连接设置", summary = selected.endpoint,
                            showChevron = true, onClick = if (busy) null else ({ edit(selected) }), tag = "mcp-settings-edit")
                    }
                }
                item(key = "tools-heading") {
                    McpToolsHeading(selected.catalog.size, enabled = !busy && selected.catalog.isNotEmpty(),
                        onEnableAll = { snapshot?.let { setDisabled(it, selected, emptySet()) } },
                        onDisableAll = { snapshot?.let { setDisabled(it, selected, selected.catalog.map { tool -> tool.name }.toSet()) } })
                }
                if (selected.catalog.isEmpty()) item(key = "tools-empty") { SettingsNote("尚无工具，请刷新工具目录。") }
                selected.catalog.forEach { tool ->
                    item(key = "tool:${tool.name}") {
                        SettingsCard {
                            McpToolRow(tool.name, selected.id, checked = selected.eligible(tool.name), enabled = !busy,
                                onDescribe = { if (current()) describedTool = tool.name },
                                onCheckedChange = { checked -> snapshot?.let { before ->
                                    setDisabled(before, selected, if (checked) selected.disabledTools - tool.name else selected.disabledTools + tool.name)
                                } })
                        }
                    }
                }
                item(key = "delete") {
                    SettingsSectionTitle("服务器管理")
                    SettingsCard {
                        SettingsRow("删除服务器", tag = "mcp-settings-delete",
                            showChevron = true, onClick = if (busy) null else ({ deleting = true }))
                    }
                }
            }
        }
        selected?.catalog?.singleOrNull { it.name == describedTool }?.let { tool ->
            McpToolDescriptionSheet(tool, onDismiss = { describedTool = null })
        }
        if (editing) WindowDialog(show = true, title = if (selected == null) "添加服务器" else "连接设置",
            onDismissRequest = { editing = false; bearer = "" }) {
                app.daiyu.android.diagnostics.ObserveDialogFrames("dialog")
            val colors = conversationAgentColors()
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("mcp-settings-form"),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                McpConnectionField(label, { label = it }, "名称", enabled = !busy,
                    modifier = Modifier.managedInput().testTag("mcp-server-label"), cursorBrush = imeCursorBrush(colors.accent))
                McpConnectionField(endpoint, { endpoint = it }, "服务器地址", enabled = !busy,
                    modifier = Modifier.managedInput().testTag("mcp-server-endpoint"), cursorBrush = imeCursorBrush(colors.accent))
                McpConnectionField(bearer, { bearer = it }, if (selected == null) "Bearer Token（可选）" else "Bearer Token（留空保留）",
                    enabled = !busy, visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.managedInput().testTag("mcp-server-bearer"), cursorBrush = imeCursorBrush(colors.accent))
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).testTag("mcp-settings-actions"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ConversationAgentButton("取消", modifier = Modifier.weight(1f).fillMaxHeight().testTag("mcp-settings-cancel")) {
                        editing = false; bearer = ""
                    }
                    ConversationAgentButton(if (selected == null) "添加服务器" else "保存", primary = true,
                        enabled = !busy && snapshot != null,
                        modifier = Modifier.weight(1f).fillMaxHeight().testTag("mcp-settings-save")) { save(false) }
                }
            }
        }
        if (deleting && selected != null) DaiyuConfirmDialog(title = "删除服务器", confirmText = "删除",
            message = "删除 ${selected.label} 及其凭据？", destructive = true, deletionAppearance = true,
            onDismiss = { deleting = false }, onConfirm = {
                id = selected.id
                save(true)
            })
    }
}

@Composable
private fun McpConnectionField(value: String, onValueChange: (String) -> Unit, hint: String,
    enabled: Boolean, modifier: Modifier, cursorBrush: Brush,
    visualTransformation: VisualTransformation = VisualTransformation.None) {
    val colors = MiuixTheme.colorScheme
    val style = MiuixTheme.textStyles.body1.copy(fontSize = 14.sp, lineHeight = 21.sp, color = colors.onSurface)
    BasicTextField(value, onValueChange, enabled = enabled, singleLine = true,
        visualTransformation = visualTransformation, textStyle = style, cursorBrush = cursorBrush,
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = hint }
            .clip(RoundedCornerShape(16.dp)).background(colors.surfaceContainerHigh),
        decorationBox = { editor ->
            Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Text(hint, style = style, color = colors.onSurfaceVariantSummary)
                editor()
            }
        })
}

@Composable
private fun McpToolsHeading(count: Int, enabled: Boolean, onEnableAll: () -> Unit, onDisableAll: () -> Unit) {
    val grouped = LocalSettingsPageStyle.current
    val style = MiuixTheme.textStyles.body2.copy(fontSize = if (grouped) 13.sp else 14.sp,
        fontWeight = if (grouped) FontWeight.Medium else FontWeight.SemiBold)
    val color = if (grouped) MiuixTheme.colorScheme.onSurfaceVariantSummary else MiuixTheme.colorScheme.onSurface
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp)
        .padding(top = if (grouped) 16.dp else 20.dp, bottom = if (grouped) 1.dp else 2.dp)) {
        top.yukonga.miuix.kmp.basic.Text("工具（$count）", style = style, color = color,
            modifier = Modifier.weight(1f).alignByBaseline().testTag("mcp-tools-title").padding(vertical = 8.dp))
        top.yukonga.miuix.kmp.basic.Text("全部开启", style = style, color = color.copy(alpha = if (enabled) 1f else .38f),
            modifier = Modifier.alignByBaseline().testTag("mcp-tools-enable-all")
                .clickable(enabled = enabled, role = Role.Button, onClick = onEnableAll).padding(vertical = 8.dp))
        Spacer(Modifier.width(16.dp))
        top.yukonga.miuix.kmp.basic.Text("全部关闭", style = style, color = color.copy(alpha = if (enabled) 1f else .38f),
            modifier = Modifier.alignByBaseline().testTag("mcp-tools-disable-all")
                .clickable(enabled = enabled, role = Role.Button, onClick = onDisableAll).padding(vertical = 8.dp))
    }
}

@Composable
private fun McpToolRow(title: String, serverId: String, checked: Boolean, enabled: Boolean,
    onDescribe: () -> Unit, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).heightIn(min = 64.dp).testTag("mcp-tool:$serverId:$title")
            .clickable(role = Role.Button, onClick = onDescribe).padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            top.yukonga.miuix.kmp.basic.Text(title, modifier = Modifier.weight(1f), maxLines = 1,
                overflow = TextOverflow.Ellipsis, style = MiuixTheme.textStyles.body1.copy(fontSize = 16.sp, fontWeight = FontWeight.Medium),
                color = MiuixTheme.colorScheme.onSurface)
            DaiyuIcon(DaiyuIcons.ChevronRight, contentDescription = null,
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary, size = 20.dp)
        }
        SettingsSwitch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange,
            colors = daiyuSwitchColors().copy(
                checkedThumbColor = MiuixTheme.colorScheme.onPrimary,
                checkedTrackColor = DaiyuPalette.switchTrack,
                checkedBorderColor = DaiyuPalette.switchTrack,
            ),
            modifier = Modifier.testTag("mcp-tool-enabled:$serverId:$title").semantics { contentDescription = title })
    }
}

@Composable
private fun McpToolDescriptionSheet(tool: McpRemoteTool, onDismiss: () -> Unit) {
    val colors = conversationAgentColors()
    ConversationAgentSheet(onDismiss, useDefaultHeight = true) {
        Column(Modifier.fillMaxSize().testTag("mcp-tool-description-sheet").padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 16.dp)) {
            ConversationAgentHandle()
            Text(tool.name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = colors.text,
                modifier = Modifier.testTag("mcp-tool-description-title").padding(bottom = 16.dp))
            Column(Modifier.weight(1f).fillMaxWidth().testTag("mcp-tool-description-scroll").verticalScroll(rememberScrollState())) {
                Text(tool.description.ifBlank { "此工具暂无描述。" }, color = colors.text, fontSize = 14.sp, lineHeight = 22.sp,
                    modifier = Modifier.testTag("mcp-tool-description"))
            }
            ConversationAgentButton("完成", primary = true, onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp).testTag("mcp-tool-description-done"))
        }
    }
}
