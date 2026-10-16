package app.daiyu.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.daiyu.android.ChatController
import app.daiyu.android.ControllerShizukuPreparation
import app.daiyu.android.domain.Conversation

/** Optional composer adjunct, OFF on every fresh mount; does not replace the normal/image SEND.
 * Mount once per controller beside the parent's composer. Parent owns the editor and navigation.
 * onAccepted is a durable message receipt, not command success. Compare preview.conversationId
 * and preview.text to the submitted editor before clearing it; never clear image attachments here.
 * OS Shizuku permission is deliberately managed separately (existing Shizuku settings screen).
 */
@Composable
internal fun ShizukuApprovalHost(
    controller: ChatController,
    conversation: Conversation?,
    text: String,
    onAccepted: (ControllerShizukuPreparation, Conversation) -> Unit,
    modifier: Modifier = Modifier
) {
    var enabled by remember(controller) { mutableStateOf(false) }
    var uid by remember(controller) { mutableStateOf("") }
    var command by remember(controller) { mutableStateOf("") }
    var timeout by remember(controller) { mutableStateOf("30000") }
    var error by remember { mutableStateOf<String?>(null) }
    var revision by remember(controller) { mutableIntStateOf(0) }
    val accepted by rememberUpdatedState(onAccepted)
    DisposableEffect(controller) {
        val observer: () -> Unit = { revision++ }
        controller.observe(observer)
        onDispose {
            controller.unobserve(observer)
            controller.shizukuPreparation()?.takeUnless { it.isSubmitted() }?.let(controller::rejectShizuku)
        }
    }
    // A changed owner/editor never inherits a previous approval. Submitted runs keep their IDs.
    LaunchedEffect(controller, conversation?.id, text) {
        controller.shizukuPreparation()?.takeUnless { it.isSubmitted() }?.let {
            if (it.conversation?.id != conversation?.id || it.text != text) controller.rejectShizuku(it)
        }
    }
    val preview = remember(controller, revision) { controller.shizukuPreparation() }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(checked = enabled, enabled = preview == null, onCheckedChange = { enabled = it })
            Text("本次可选 Shizuku 命令（默认关闭）")
        }
        if (enabled) {
            Text("普通发送及图片发送不会获得此授权。须先在 Shizuku 设置页明确授权系统访问；此处不会自动请求权限或执行探测命令。")
            OutlinedTextField(uid, { uid = it }, label = { Text("精确 UID：0 或 2000（必选，无默认值）") },
                enabled = preview == null, singleLine = true)
            OutlinedTextField(command, { command = it }, label = { Text("本次精确 shell 命令") },
                enabled = preview == null, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(timeout, { timeout = it }, label = { Text("超时毫秒（1–120000）") },
                enabled = preview == null, singleLine = true)
            Button(enabled = preview == null && text.isNotBlank(), onClick = {
                error = if (controller.prepareShizukuSend(conversation, text, command, uid, timeout) == null)
                    "无法准备：请检查 UID、命令、超时，或等待当前运行结束。未发起请求。" else null
            }) { Text("预览命令及本次运行") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
    if (preview != null) {
        val submitted = preview.isSubmitted()
        AlertDialog(
            onDismissRequest = { controller.rejectShizuku(preview) },
            title = { Text(if (submitted) "Shizuku 运行准备中" else "确认本次写操作授权") },
            text = {
                Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("会话：${preview.conversationId.value}\n运行：${preview.runId.value}")
                    Text(if (preview.uid == 0)
                        "UID 0：root 身份，命令可能读写系统和其他应用数据，影响整个设备。"
                    else "UID 2000：ADB shell 身份，不是普通应用沙箱；仍可能修改设备设置及数据。")
                    Text("超时：${preview.timeoutMillis}ms。超时/停止不代表副作用已撤销；UNKNOWN 不自动重试。")
                    Text("完整命令（原样，不作转义、修正或截断）：")
                    SelectionContainer { Text(preview.command) }
                    Text("即将持久保存并发送给模型的完整用户消息：")
                    SelectionContainer { Text(preview.messageText) }
                    Text("仅授权本次精确命令、UID 和超时；模型修改任何一项均无写授权。系统权限不等于此确认。")
                    controller.problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                Button(enabled = !submitted, onClick = {
                    if (!controller.confirmShizukuSend(preview) { saved ->
                        if (saved != null) accepted(preview, saved)
                    }) error = "预览已失效；未重复发送。"
                }) { Text(if (submitted) "等待接受/清理回执" else "同意本次写操作并发送") }
            },
            dismissButton = {
                TextButton(onClick = { controller.rejectShizuku(preview) }) {
                    Text(if (submitted) "停止准备（不重试）" else "拒绝 / 取消")
                }
            }
        )
    }
}
