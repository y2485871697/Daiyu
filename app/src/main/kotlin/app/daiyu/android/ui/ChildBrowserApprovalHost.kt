package app.daiyu.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.daiyu.android.ControllerBrowserPreparation
import app.daiyu.android.ControllerChildBrowserPlan
import app.daiyu.android.browser.BrowserDefaultPermission
import app.daiyu.android.ui.components.materialImeCursorColor
import app.daiyu.android.ui.components.managedInput
import app.daiyu.android.ui.haptics.material.TextButton

/** An optional child task is not auto-created. Its prompt/identity are explicit, while ordinary
 * browser permission is inherited by production policy, not by a second approval switch.
 */
@Composable
internal fun ChildBrowserApprovalHost(
    parent: ControllerBrowserPreparation,
    prepare: (ControllerBrowserPreparation, String, Set<String>, List<String>) -> ControllerChildBrowserPlan?,
) {
    var prompt by remember(parent) { mutableStateOf("") }
    var plan by remember(parent) { mutableStateOf(parent.childPlan) }
    var error by remember(parent) { mutableStateOf<String?>(null) }
    val editable = parent.state == ControllerBrowserPreparation.State.PREVIEW && plan == null
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("可选工具型子任务：默认继承本次父运行浏览器权限交集，无需另行批准。普通 run_child_models 仍是 text-only。")
        Text("标签和授权按 child 隔离；不承诺 WebView 全局 cookie／账户隔离，不向模型返回 cookie。")
        if (plan == null) {
            OutlinedTextField(prompt, { prompt = it }, enabled = editable,
                label = { Text("子任务完整提示词") }, modifier = Modifier.managedInput().testTag("child-browser-prompt"),
                colors = OutlinedTextFieldDefaults.colors(cursorColor = materialImeCursorColor(MaterialTheme.colorScheme.primary),
                    errorCursorColor = materialImeCursorColor(MaterialTheme.colorScheme.error)))
            TextButton(enabled = editable && prompt.isNotBlank(), modifier = Modifier.testTag("child-browser-preview"), onClick = {
                try {
                    val actions = parent.preview.defaultPermission?.actions
                        ?: BrowserDefaultPermission.availableActions(parent.preview.grant.access)
                    plan = prepare(parent, prompt, actions, emptyList())
                    error = if (plan == null) "子任务准备被拒绝；未打开 host 或模型。" else null
                } catch (_: Exception) { error = "子任务准备失效；未自动创建目标或发送。" }
            }) { Text("添加子任务") }
        }
        plan?.let { selected ->
            Text(if (selected.confirmed) "子任务已就绪；仅在父消息持久接受后构造。" else "子任务需要受限策略审阅。")
            val owner = selected.scope.owner
            SelectionContainer { Text("parent run: ${owner.parentRunId}\nchild run: ${owner.childRunId}" +
                "\ngroup: ${owner.taskGroupId}\ngeneration: ${owner.generation}" +
                "\nowner: CHILD / ${selected.preview.grant.owner.sessionId}\n${selected.prompt}") }
            if (!selected.confirmed && parent.preview.defaultPermission == null) {
                TextButton(modifier = Modifier.testTag("child-browser-confirm"), onClick = {
                    if (!selected.confirm(selected.preview)) error = "子任务准备已失效。"
                }) { Text("批准受限子任务") }
            }
            Text("实际动作仍严格校验参数和精确效果。删除、付款、发布须保留业务确认。UNKNOWN 不自动重试。")
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
