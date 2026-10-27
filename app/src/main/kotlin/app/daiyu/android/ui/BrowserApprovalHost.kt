package app.daiyu.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.daiyu.android.BrowserDraftSnapshot
import app.daiyu.android.ControllerBrowserPreparation
import app.daiyu.android.assistants.AssistantProfile
import app.daiyu.android.browser.BrowserDefaultPermission
import app.daiyu.android.browser.BrowserRunPreview
import app.daiyu.android.browser.BrowserSettings
import app.daiyu.android.browser.session.BrowserAccess
import app.daiyu.android.ui.components.DaiyuAlertDialog as AlertDialog
import app.daiyu.android.ui.haptics.material.Button
import app.daiyu.android.ui.haptics.material.TextButton

/** Optional run inspector. Permission comes from the trusted production preparation, not a
 * mount-local switch or a synthetic button click. Shared SEND can use confirmDefault directly.
 * Parent retains reservation/worker/DB acceptance and consumes only an exact draft receipt.
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
    var error by remember { mutableStateOf<String?>(null) }
    val cancel by rememberUpdatedState(onCancel)
    val currentDraft by rememberUpdatedState(draft)
    DisposableEffect(preparation) { onDispose { preparation?.let { cancel(it) } } }
    val matches = preparation == null || (preparation.draft == draft &&
        preparation.assistant == assistant && preparation.settings == settings)
    LaunchedEffect(preparation, draft, assistant, settings) {
        if (!matches) preparation?.let { cancel(it) }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("本次浏览器默认许可：${settings.access.name}")
        Text("普通浏览动作无需重复批准；实际 URL、标签和参数在调用时严格校验，不自动创建目标。删除、付款、发布等业务确认仍须保留。history 默认允许记录；cookie 仅经同源校验后私有 offload，不返回明文。")
        Button(enabled = preparation == null && draft.text.isNotBlank() && settings.access != BrowserAccess.DISABLED,
            modifier = Modifier.testTag("browser-prepare"), onClick = {
                error = if (onPrepare(BrowserDefaultPermission.availableActions(settings.access), emptyList())) null
                    else "无法准备当前运行；未发起浏览器请求。"
            }) { Text("查看本次浏览器运行") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    if (preparation != null) {
        val preview = preparation.preview
        val ready = state == ControllerBrowserPreparation.State.PREVIEW && matches
        val canSend = ready && childApprovalReady
        AlertDialog(
            onDismissRequest = { cancel(preparation) },
            title = { Text(if (ready) "本次浏览器运行" else "浏览器准备：${state?.name ?: "等待状态"}") },
            text = {
                Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("会话：${preview.identity.conversationId.value}\n运行：${preview.identity.runId.value}" +
                        "\n助手消息：${preview.identity.assistantMessageId.value}" +
                        "\n助手配置：${preview.identity.assistantId} / ${preparation.assistant.name}")
                    Text("编辑器代次：${preparation.draft.owner.editorGeneration}；草稿版本：${preparation.draft.owner.draftRevision}")
                    Text("模型：${preparation.provider.model}；协议：${preparation.provider.protocol}")
                    Text("访问：${preview.grant.access.name}；owner：${preview.grant.owner.kind} / ${preview.grant.owner.sessionId}")
                    Text("本次动作范围（调用时仍解析精确 effect）：")
                    SelectionContainer { Text(preview.allowedActions.sorted().joinToString("\n")) }
                    SelectionContainer { Text(preparation.text) }
                    SelectionContainer { Text(preparation.assistant.prompt) }
                    preview.writes.forEachIndexed { index, write ->
                        SelectionContainer { Text(write.arguments, Modifier.testTag("browser-write-${index + 1}")) }
                        SelectionContainer { Text("namespace: ${write.effect.namespace}\noperation: ${write.effect.operation}" +
                            "\ntarget: ${write.effect.target}\nchange: ${write.effect.change}") }
                    }
                    childApproval?.invoke()
                    Text("权限仅属于当前运行。child 按父权限交集独立绑定，不可借用父／sibling 的标签或授权。停止不撤销已发生效果；UNKNOWN 不自动重试。")
                    if (!matches) Text("编辑器／助手／设置已变更：此运行准备失效。", color = MaterialTheme.colorScheme.error)
                    (problem ?: error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                Button(enabled = canSend, modifier = Modifier.testTag("browser-confirm"), onClick = {
                    if (!onConfirm(preparation, preview, currentDraft)) error = "运行或草稿版本已失效；未重复发送。"
                }) { Text(if (canSend) "发送" else "等待接受／清理回执") }
            },
            dismissButton = { TextButton(onClick = { cancel(preparation) }) { Text("取消／停止（不重试）") } },
        )
    }
}
