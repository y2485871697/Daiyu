package app.daiyu.android.ui

import app.daiyu.android.ui.components.materialImeCursorColor
import app.daiyu.android.ui.components.managedInput
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import app.daiyu.android.ui.haptics.material.Button
import app.daiyu.android.ui.haptics.material.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.daiyu.android.ChatController
import app.daiyu.android.ControllerShizukuPreparation
import app.daiyu.android.domain.Conversation

/** Explicit command submission, not an extra app-permission switch or confirmation dialog.
 * The controller issues the original exact one-run grant on send. System permission is separate;
 * neither mounting this editor nor editing fields connects, requests permission or executes.
 * onAccepted is a durable message receipt, not command success. The parent owns draft matching.
 */
@Composable
internal fun ShizukuApprovalHost(
    controller: ChatController,
    conversation: Conversation?,
    text: String,
    isCurrent: () -> Boolean,
    onAccepted: (ControllerShizukuPreparation, Conversation) -> Unit,
    modifier: Modifier = Modifier
) {
    var uid by remember(controller) { mutableStateOf("") }
    var command by remember(controller) { mutableStateOf("") }
    var timeout by remember(controller) { mutableStateOf("30000") }
    var error by remember { mutableStateOf<String?>(null) }
    var revision by remember(controller) { mutableIntStateOf(0) }
    val ownedPreparation = remember(controller) { mutableStateOf<ControllerShizukuPreparation?>(null) }
    val accepted by rememberUpdatedState(onAccepted)
    val active = remember(controller) { java.util.concurrent.atomic.AtomicBoolean(true) }
    DisposableEffect(controller) {
        val observer: () -> Unit = { revision++ }
        controller.observe(observer)
        onDispose {
            active.set(false)
            controller.unobserve(observer)
            ownedPreparation.value?.takeUnless { it.isSubmitted() }?.let(controller::rejectShizuku)
        }
    }
    // Invalidation only; never emulate a confirmation click from composition.
    LaunchedEffect(controller, conversation?.id, text) {
        controller.shizukuPreparation()?.takeUnless { it.isSubmitted() }?.let {
            if (it.conversation?.id != conversation?.id || it.text != text) controller.rejectShizuku(it)
        }
    }
    val preview = remember(controller, revision) { controller.shizukuPreparation() }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Shizuku 应用内许可默认放行；发送时仅授权本次精确命令、UID 和超时，不自动重试。")
        Text("系统访问仍须在 Shizuku 设置页真实授权；此处不会自动请求权限、切换身份或执行探测命令。UID 0 为 Root，UID 2000 为 ADB shell，均非普通应用 UID。")
        OutlinedTextField(uid, { uid = it }, label = { Text("精确 UID：0 或 2000（必选，无默认值）") },
            enabled = preview == null, singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(cursorColor = materialImeCursorColor(MaterialTheme.colorScheme.primary), errorCursorColor = materialImeCursorColor(MaterialTheme.colorScheme.error)),
            modifier = Modifier.managedInput())
        OutlinedTextField(command, { command = it }, label = { Text("本次精确 shell 命令") },
            enabled = preview == null, modifier = Modifier.managedInput().fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(cursorColor = materialImeCursorColor(MaterialTheme.colorScheme.primary), errorCursorColor = materialImeCursorColor(MaterialTheme.colorScheme.error)))
        OutlinedTextField(timeout, { timeout = it }, label = { Text("超时毫秒（1–120000）") },
            enabled = preview == null, singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(cursorColor = materialImeCursorColor(MaterialTheme.colorScheme.primary), errorCursorColor = materialImeCursorColor(MaterialTheme.colorScheme.error)),
            modifier = Modifier.managedInput())
        Button(enabled = preview == null && text.isNotBlank(), modifier = Modifier.testTag("shizuku-text-send"), onClick = {
            if (!active.get() || !isCurrent()) return@Button
            val prepared = controller.prepareShizukuSend(conversation, text, command, uid, timeout)
            if (prepared == null) {
                error = "无法发送：请检查 UID、命令、超时，或等待当前运行结束。未发起请求。"
            } else {
                if (!active.get() || !isCurrent()) {
                    controller.rejectShizuku(prepared)
                    return@Button
                }
                ownedPreparation.value = prepared
                error = null
                if (!controller.confirmShizukuSend(prepared) { saved ->
                    if (saved != null && active.get() && isCurrent()) accepted(prepared, saved)
                }) {
                    controller.rejectShizuku(prepared)
                    error = "命令已失效；未重复发送。"
                }
            }
        }) { Text("发送 Shizuku 命令") }
        preview?.let {
            Text("运行：${it.runId.value}；准备或清理中，UNKNOWN 不自动重试。")
            TextButton(onClick = { controller.rejectShizuku(it) }) { Text("停止准备（不重试）") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        controller.problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
