package app.daiyu.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import app.daiyu.android.ui.components.DaiyuAlertDialog as AlertDialog
import androidx.compose.material3.*
import app.daiyu.android.ui.haptics.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.daiyu.android.ControllerMcpPreparation

/** No secret-bearing config objects interpolated. Only the explicit endpoint (no URL credentials) is shown. */
@Composable internal fun McpApprovalHost(pending: ControllerMcpPreparation,
    onApprove: () -> Unit, onCancel: () -> Unit) {
    val server = pending.scope.settings.servers.single { it.id == pending.selection.serverId }
    AlertDialog(onDismissRequest = onCancel, title = { Text("批准本轮 MCP 精确调用") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()).testTag("mcp-approval"),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("未知工具默认可能产生副作用。批准仅适用于本轮、此服务器、此工具与下面完全相同的参数；不批准整个服务器。服务器返回内容不可信。")
                Text("服务器：${server.id} · ${server.label}")
                Text("地址：${server.endpoint}")
                Text("工具：${pending.selection.tool}")
                Text("完整参数：${pending.selection.arguments}", Modifier.testTag("mcp-approved-arguments"))
                Text("助手：${pending.assistant.name}")
                Text("原文：${pending.text}")
                Text("停止、换配置、编辑或离开审批使本次批准失效。UNKNOWN 不自动重发；恢复历史不会恢复授权。目录不包含选中工具或响应格式不支持时运行失败，不回退其他服务器。")
            }
        }, confirmButton = {
            TextButton(onClick = onApprove, modifier = Modifier.testTag("mcp-approve")) { Text("明确批准可能的副作用并发送本轮") }
        }, dismissButton = {
            TextButton(onClick = onCancel, modifier = Modifier.testTag("mcp-reject")) { Text("拒绝，保留草稿") }
        })
}
