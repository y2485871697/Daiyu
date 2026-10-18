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
    var keepSecret by remember(owner) { mutableStateOf(true) }
    fun current() = owner.current() && ownerCurrent()
    LaunchedEffect(owner) {
        val result = withContext(Dispatchers.IO) { runCatching { store.snapshot() } }
        if (current()) {
            snapshot = result.getOrNull(); busy = false
            status = if (snapshot == null) "私有配置不可读；未连接任何服务器。可明确清除后重新配置。" else "保存只配置服务器，不授予工具权限。"
        }
    }
    DisposableEffect(owner) { onDispose { owner.revoke() } }
    androidx.activity.compose.BackHandler { owner.revoke(); onBack() }
    fun save(remove: Boolean) {
        val before = snapshot ?: return
        if (busy || !current()) return
        val value = try {
            val old = before.servers.singleOrNull { it.id == id }
            val list = before.servers.filterNot { it.id == id }
            if (remove) { require(old != null); list }
            else {
                val secret = if (keepSecret && bearer.isEmpty()) old?.bearer.orEmpty() else bearer
                val sameConnection = old != null && old.endpoint == endpoint && old.bearer == secret
                list + McpServer(id, label, endpoint, secret, sameConnection && old!!.enabled,
                    if (sameConnection) old!!.catalog else emptyList(), if (sameConnection) old!!.allowedTools else emptySet())
            }
        } catch (_: Exception) { status = "配置无效；需要唯一 ID、名称和无查询参数的 HTTPS 地址。"; return }
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { check(owner.current()); store.save(value, owner, before) }
            }
            if (current()) {
                busy = false; bearer = ""
                if (result.isSuccess) { snapshot = result.getOrThrow(); status = "已保存；旧的本轮批准已失效，尚未连接服务器。" }
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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("mcp-settings"),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("MCP 服务器", style = MaterialTheme.typography.headlineSmall)
        Text("仅支持受限 Streamable HTTP JSON-RPC 2025-03-26。SSE、分页目录、图片/资源内容不支持。连接成功不代表兼容所有服务器。")
        Text("配置和凭据仅保存在 noBackup 私有加密存储，不导出到备份。此页只发现并展示工具目录，不启用服务器、不授予工具权限，也不调用远端工具；每轮批准仅在聊天入口进行。")
        Text(status, Modifier.testTag("mcp-settings-status"))
        snapshot?.let { rendered -> rendered.servers.forEach { server ->
            TextButton(enabled = !busy, onClick = {
                if (snapshot === rendered && current() && !busy) {
                    id = server.id; label = server.label; endpoint = server.endpoint; bearer = ""; keepSecret = true
                }
            }, modifier = Modifier.testTag("mcp-server:${server.id}")) { Text("编辑 ${server.id} · ${server.label}") }
            TextButton(onClick = { refresh(rendered, server) }, enabled = !busy,
                modifier = Modifier.testTag("mcp-refresh:${server.id}")) { Text("刷新工具目录") }
            if (server.catalog.isEmpty()) Text("尚无已发现工具；请显式刷新目录。")
            server.catalog.forEach { tool ->
                Column(Modifier.fillMaxWidth().padding(start = 12.dp)) {
                    Text(tool.name)
                    Text(tool.description)
                    Text("参数 schema（远端资料）：${tool.inputSchema}")
                    Text(if (tool.readOnlyHint == true) "远端声明只读；仍按写操作审批" else "远端未声明只读，可能产生副作用")
                }
            }
        }
        }
        OutlinedTextField(id, { id = it }, label = { Text("服务器 ID（小写字母开头）") },
            enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("mcp-server-id"))
        OutlinedTextField(label, { label = it }, label = { Text("名称") },
            enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("mcp-server-label"))
        OutlinedTextField(endpoint, { endpoint = it }, label = { Text("HTTPS MCP 地址（不含查询、凭据或片段）") },
            enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("mcp-server-endpoint"))
        OutlinedTextField(bearer, { bearer = it }, label = { Text("Bearer 令牌（可留空，不回显已存值）") },
            visualTransformation = PasswordVisualTransformation(), enabled = !busy,
            modifier = Modifier.fillMaxWidth().testTag("mcp-server-bearer"))
        Row { Checkbox(keepSecret, { keepSecret = it }, enabled = !busy); Text("留空时保留此 ID 已存令牌；取消勾选并留空会移除令牌") }
        Button(onClick = { save(false) }, enabled = !busy && snapshot != null,
            modifier = Modifier.testTag("mcp-settings-save")) { Text("保存服务器配置（不连接/不授权）") }
        TextButton(onClick = { save(true) }, enabled = !busy && snapshot != null,
            modifier = Modifier.testTag("mcp-settings-delete")) { Text("删除此服务器及凭据") }
        TextButton(onClick = {
            if (!busy && current()) {
                busy = true
                scope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching { store.clear(owner) } }
                    if (current()) {
                        snapshot = result.getOrNull(); bearer = ""; busy = false
                        status = if (result.isSuccess) "全部 MCP 配置与凭据已清除；需重新配置并批准。" else "清除未确认；未连接服务器。"
                    }
                }
            }
        }, enabled = !busy, modifier = Modifier.testTag("mcp-settings-clear")) { Text("明确清除全部 MCP 配置与凭据（用于修复）") }
        TextButton(onClick = { owner.revoke(); onBack() }, modifier = Modifier.testTag("mcp-settings-back")) { Text("返回设置") }
    }
}
