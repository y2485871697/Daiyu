package app.daiyu.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import app.daiyu.android.ui.components.DaiyuAlertDialog as AlertDialog
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.daiyu.android.BrowserDraftSnapshot
import app.daiyu.android.ControllerBrowserPreparation
import app.daiyu.android.assistants.AssistantProfile
import app.daiyu.android.browser.BrowserRunPreview
import app.daiyu.android.browser.BrowserSettings
import app.daiyu.android.browser.session.BrowserAccess
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Optional composer adjunct, OFF at each fresh mount. Not wired to ordinary SEND or ask_user.
 * Parent owns reservation, worker, notifications and DB acceptance. It must pass an observed state
 * (not just the same preparation reference) on every lifecycle change. No callback clears a draft:
 * the parent consumes BrowserSendReceipt against its current owner/generation/revision/text first.
 * No approval/settings are saved by this UI, and no native object is constructed here.
 */
@Composable
internal fun BrowserApprovalHost(
    draft: BrowserDraftSnapshot,
    assistant: AssistantProfile,
    settings: BrowserSettings,
    preparation: ControllerBrowserPreparation?,
    state: ControllerBrowserPreparation.State?,
    onPrepare: (allowedActions: Set<String>, writeArguments: List<String>) -> Boolean,
    onConfirm: (ControllerBrowserPreparation, BrowserRunPreview, BrowserDraftSnapshot) -> Boolean,
    onCancel: (ControllerBrowserPreparation) -> Unit,
    modifier: Modifier = Modifier,
    problem: String? = null,
    childApprovalReady: Boolean = true,
    childApproval: (@Composable () -> Unit)? = null,
) {
    var enabled by remember { mutableStateOf(false) }
    var readActions by remember { mutableStateOf("") }
    var writes by remember { mutableStateOf(listOf<String>()) }
    var error by remember { mutableStateOf<String?>(null) }
    val cancel by rememberUpdatedState(onCancel)
    val currentDraft by rememberUpdatedState(draft)
    DisposableEffect(preparation) {
        onDispose { preparation?.let { cancel(it) } }
    }
    val matches = preparation == null || (preparation.draft == draft &&
        preparation.assistant == assistant && preparation.settings == settings)
    LaunchedEffect(preparation, draft, assistant, settings) {
        if (!matches) preparation?.let { cancel(it) }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(checked = enabled, enabled = preparation == null, modifier = Modifier.testTag("browser-enable"),
                onCheckedChange = { enabled = it })
            Text("仅本次浏览器访问（默认关闭）")
        }
        if (enabled) {
            Text("保存 FULL 只设置能力上限，不是本次批准。普通发送、模型文本和 ask_user 回复都不会授权。")
            Text("当前上限：${settings.access.name}；history／cookie 继续拒绝。fetch 仅在本页列出完整精确参数并明确批准后获准；主运行批准不覆盖 child，child 须另行独立批准。")
            OutlinedTextField(value = readActions, onValueChange = { readActions = it },
                enabled = preparation == null, modifier = Modifier.fillMaxWidth(),
                label = { Text("明确选择读取动作（英文逗号分隔，无默认选择）") },
                supportingText = { Text("例如 list_tabs。空 session 不会自动创建标签或导航。new_tab / close_tab 也是写操作。") })
            writes.forEachIndexed { index, raw ->
                OutlinedTextField(value = raw, onValueChange = { value ->
                    writes = writes.toMutableList().also { it[index] = value }
                }, enabled = preparation == null, modifier = Modifier.fillMaxWidth(),
                    label = { Text("写操作 ${index + 1}：完整参数 JSON 对象") })
                TextButton(enabled = preparation == null, onClick = {
                    writes = writes.filterIndexed { position, _ -> position != index }
                }) { Text("移除此写操作") }
            }
            TextButton(enabled = preparation == null, onClick = { writes = writes + "" }) {
                Text("添加一个精确写操作（不填充默认参数）")
            }
            Button(enabled = preparation == null && draft.text.isNotBlank() &&
                settings.access != BrowserAccess.DISABLED, onClick = {
                try {
                    val reads = readActions.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                    // Preserve every byte in the submitted raw JSON; parsing only discovers action.
                    val actions = reads + writes.map { raw ->
                        val parser = JSONTokener(raw)
                        val json = parser.nextValue()
                        require(json is JSONObject && parser.nextClean() == '\u0000')
                        val action = json.get("action")
                        require(action is String && action.isNotBlank())
                        action
                    }
                    error = if (onPrepare(actions, writes.toList())) null else
                        "无法预览：请检查动作、参数和当前运行状态。未发起浏览器请求。"
                } catch (_: Exception) {
                    error = "每个写操作必须是带 action 的完整 JSON 对象；没有自动修复、默认允许或发送。"
                }
            }) { Text("冻结本次运行并预览（尚未授权）") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
    if (preparation != null) {
        val preview = preparation.preview
        val previewReady = state == ControllerBrowserPreparation.State.PREVIEW && matches
        val canConfirm = previewReady && childApprovalReady
        AlertDialog(
            onDismissRequest = { cancel(preparation) },
            title = { Text(if (previewReady) "确认本次浏览器访问" else "浏览器准备：${state?.name ?: "等待状态"}") },
            text = {
                Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("会话：${preview.identity.conversationId.value}\n运行：${preview.identity.runId.value}" +
                        "\n助手消息：${preview.identity.assistantMessageId.value}" +
                        "\n助手配置：${preview.identity.assistantId} / ${preparation.assistant.name}")
                    Text("编辑器代次：${preparation.draft.owner.editorGeneration}；草稿版本：${preparation.draft.owner.draftRevision}")
                    Text("模型：${preparation.provider.model}；协议：${preparation.provider.protocol}")
                    Text("设置上限：${preview.settings.access.name}；本次访问：${preview.grant.access.name}" +
                        "\nowner：${preview.grant.owner.kind} / ${preview.grant.owner.sessionId}")
                    Text("本次完整动作清单（未列出的动作一律不授权）：")
                    SelectionContainer { Text(preview.allowedActions.sorted().joinToString("\n")) }
                    Text("原样发送的完整用户文本：")
                    SelectionContainer { Text(preparation.text) }
                    Text("冻结的助手指令：")
                    SelectionContainer { Text(preparation.assistant.prompt) }
                    if (preview.writes.isEmpty()) {
                        Text("无写操作。不创建标签、不导航；空 session 的读取可能返回无标签错误。")
                    }
                    preview.writes.forEachIndexed { index, write ->
                        Text("写操作 ${index + 1}：${write.effect.operation}")
                        Text("完整参数 JSON（不截断、不用 hash 替代）：")
                        SelectionContainer { Text(write.arguments, Modifier.testTag("browser-write-${index + 1}")) }
                        Text("实际校验的完整写效果：")
                        SelectionContainer {
                            Text("namespace: ${write.effect.namespace}\noperation: ${write.effect.operation}" +
                                "\ntarget: ${write.effect.target}\nchange: ${write.effect.change}")
                        }
                    }
                    childApproval?.invoke()
                    Text("本按钮仅批准以上本次主运行的精确动作及参数（包括清单中的 exact fetch）；cookie、history 继续拒绝。child 必须通过独立批准页，本按钮不授予 child 权限。" +
                        "停止不撤销已发生副作用；UNKNOWN 保留资源及草稿，不自动重试。")
                    if (!matches) Text("编辑器／助手／设置已变更：此预览失效，不能批准。",
                        color = MaterialTheme.colorScheme.error)
                    (problem ?: error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                Button(enabled = canConfirm, modifier = Modifier.testTag("browser-confirm"), onClick = {
                    if (!onConfirm(preparation, preview, currentDraft)) {
                        error = "预览或草稿版本已失效；未重复发送。"
                    }
                }) { Text(if (canConfirm) "明确批准本次精确访问并发送" else if (previewReady && !childApprovalReady)
                    "请先独立批准子任务" else "等待接受／清理回执") }
            },
            dismissButton = {
                TextButton(onClick = { cancel(preparation) }) {
                    Text(if (canConfirm) "拒绝／取消" else "停止准备（不重试）")
                }
            }
        )
    }
}
