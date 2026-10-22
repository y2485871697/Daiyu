package app.daiyu.android.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
    app.daiyu.android.ui.navigation.PageBackHandler { back() }
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
        modifier = Modifier.testTag("mcp-settings"), actions = {
            app.daiyu.android.ui.haptics.miuix.TextButton(
                text = if (selected == null) "添加服务器" else "刷新",
                enabled = !busy && snapshot != null,
                modifier = Modifier.testTag(if (selected == null) "mcp-settings-add" else "mcp-refresh:${selected.id}"),
                onClick = {
                    if (selected == null) edit(null)
                    else snapshot?.let { refresh(it, selected) }
                })
        }) {
        item(key = "status") {
            top.yukonga.miuix.kmp.basic.Text(status,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp).testTag("mcp-settings-status"),
                style = top.yukonga.miuix.kmp.theme.MiuixTheme.textStyles.body2)
        }
        if (selected == null) {
            item(key = "servers-heading") { SettingsSectionTitle("已配置服务器（${snapshot?.servers?.size ?: 0}）") }
            if (snapshot?.servers?.isEmpty() == true) item(key = "empty") { SettingsNote("尚未添加服务器") }
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
                        onClick = if (busy) null else ({ edit(selected) }), tag = "mcp-settings-edit")
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
                        onClick = if (busy) null else ({ deleting = true }))
                }
            }
        }
    }
    if (editing) WindowDialog(show = true, title = if (selected == null) "添加服务器" else "连接设置",
        onDismissRequest = { editing = false; bearer = "" }) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(label, { label = it }, label = { Text("名称") },
                enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("mcp-server-label"))
            OutlinedTextField(endpoint, { endpoint = it }, label = { Text("HTTPS MCP 地址") },
                enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("mcp-server-endpoint"))
            OutlinedTextField(bearer, { bearer = it }, label = { Text(if (selected == null) "Bearer 令牌（可选）" else "Bearer 令牌（留空保留）") },
                visualTransformation = PasswordVisualTransformation(), enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("mcp-server-bearer"))
            app.daiyu.android.ui.haptics.miuix.TextButton(text = "保存", onClick = { save(false) },
                enabled = !busy && snapshot != null, modifier = Modifier.testTag("mcp-settings-save"))
            app.daiyu.android.ui.haptics.miuix.TextButton(text = "取消", onClick = { editing = false; bearer = "" })
        }
    }
    if (deleting && selected != null) DaiyuConfirmDialog(title = "删除服务器", confirmText = "删除",
        message = "删除 ${selected.label} 及其凭据？", destructive = true,
        onDismiss = { deleting = false }, onConfirm = {
            id = selected.id
            save(true)
        })
}
