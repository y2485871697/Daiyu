package app.daiyu.android.ui.screens.settings

import app.daiyu.android.ui.components.managedInput
import app.daiyu.android.ui.components.imeCursorBrush

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

/** Visiting/saving never connects; explicit refresh only discovers metadata. Neither grants execution. */
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
    fun current() = owner.current() && ownerCurrent()
    LaunchedEffect(owner) {
        val result = withContext(Dispatchers.IO) { runCatching { store.snapshot() } }
        if (current()) {
            snapshot = result.getOrNull(); busy = false
            status = if (snapshot == null) "配置读取失败，请重新打开设置。" else "保存只配置服务器，不授予工具权限。"
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
    app.daiyu.android.ui.navigation.PageBackHandler(previewEnabled = !editing && selectedId == null) { back() }
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
                    if (sameConnection) old!!.catalog else emptyList(), if (sameConnection) old!!.allowedTools else emptySet())
            }
        } catch (_: Exception) { status = "配置无效，请检查名称和 HTTPS 地址。"; return }
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { check(owner.current()); store.save(value, owner, before) }
            }
            if (current()) {
                busy = false; bearer = ""
                if (result.isSuccess) {
                    snapshot = result.getOrThrow(); editing = false; deleting = false
                    selectedId = if (remove) null else id
                    status = "已保存；旧的本轮批准已失效，尚未连接服务器。"
                }
                else status = "保存失败或页面/配置已过期；请重新打开设置。"
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
                if (result.isSuccess) { snapshot = result.getOrThrow(); status = "工具目录已刷新；未授予任何工具权限。" }
                else status = "目录刷新失败或已过期；保留上次目录，未授予权限。"
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
                app.daiyu.android.ui.haptics.miuix.TextButton(text = "刷新",
                    enabled = !busy && snapshot != null,
                    modifier = Modifier.testTag("mcp-refresh:${selected.id}"),
                    onClick = { snapshot?.let { refresh(it, selected) } })
            }
        }) {
        if (busy || status != "保存只配置服务器，不授予工具权限。") item(key = "status") {
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
            item(key = "tools-heading") { SettingsSectionTitle("工具（${selected.catalog.size}）") }
            if (selected.catalog.isEmpty()) item(key = "tools-empty") { SettingsNote("尚无工具，请刷新工具目录。") }
            selected.catalog.forEach { tool ->
                item(key = "tool:${tool.name}") {
                    SettingsCard { SettingsRow(tool.name, summary = tool.description) }
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
    if (editing) WindowDialog(show = true, title = if (selected == null) "添加服务器" else "连接设置",
        onDismissRequest = { editing = false; bearer = "" }) {
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
        message = "删除 ${selected.label} 及其凭据？", destructive = true,
        onDismiss = { deleting = false }, onConfirm = {
            id = selected.id
            save(true)
        })
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
