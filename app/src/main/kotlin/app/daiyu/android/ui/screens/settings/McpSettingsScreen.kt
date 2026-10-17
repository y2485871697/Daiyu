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

/** Visiting/saving this page never connects or grants execution. Secret is never prefilled. */
@Composable internal fun McpSettingsScreen(store: McpSettingsStore, owner: McpSettingsOwner,
    ownerCurrent: () -> Boolean, onBack: () -> Unit) {
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
            else list + McpServer(id, label, endpoint, if (keepSecret && bearer.isEmpty()) old?.bearer.orEmpty() else bearer)
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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("mcp-settings"),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("MCP 服务器", style = MaterialTheme.typography.headlineSmall)
        Text("仅支持受限 Streamable HTTP JSON-RPC 2025-03-26。SSE、分页目录、图片/资源内容不支持。连接成功不代表兼容所有服务器。")
        Text("配置和凭据仅保存在 noBackup 私有加密存储，不导出到备份。每轮仍须在聊天入口明确批准精确工具和参数。")
        Text(status, Modifier.testTag("mcp-settings-status"))
        snapshot?.servers?.forEach { server ->
            TextButton(enabled = !busy, onClick = {
                id = server.id; label = server.label; endpoint = server.endpoint; bearer = ""; keepSecret = true
            }, modifier = Modifier.testTag("mcp-server:${server.id}")) { Text("编辑 ${server.id} · ${server.label}") }
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
            modifier = Modifier.testTag("mcp-settings-save")) { Text("保存服务器（不连接/不授权）") }
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
