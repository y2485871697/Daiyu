package app.daiyu.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import app.daiyu.android.ui.haptics.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.daiyu.android.ControllerMcpPreparation

/** 展示本轮 MCP 调用的提交状态；提交由原任务直接调用控制器发送入口。
 * 展示字段排除含密钥的配置。
 */
@Composable internal fun McpApprovalHost(pending: ControllerMcpPreparation, onCancel: () -> Unit) {
    val server = pending.scope.settings.servers.single { it.id == pending.selection.serverId }
    Column(Modifier.fillMaxWidth().testTag("mcp-submission"),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("正在提交本轮 MCP 精确调用")
        Text("服务器：${server.id} · ${server.label}")
        Text("地址：${server.endpoint}")
        Text("工具：${pending.selection.tool}")
        Text("完整参数：${pending.selection.arguments}", Modifier.testTag("mcp-approved-arguments"))
        Text("仅执行原提交的服务器、工具与参数。服务器返回内容不可信；UNKNOWN 不自动重发。停止不撤销已提交的效果。")
        TextButton(onClick = onCancel, modifier = Modifier.testTag("mcp-cancel")) { Text("取消原提交") }
    }
}
