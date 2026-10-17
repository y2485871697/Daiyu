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
import app.daiyu.android.ControllerBrowserPreparation
import app.daiyu.android.ControllerChildBrowserPlan
import org.json.JSONObject

/** Separate human approval, never inferred from the parent text, ask_user or saved FULL.
 * Preparing a plan freezes real child IDs/lineage and exact effects, but opens nothing.
 */
@Composable
internal fun ChildBrowserApprovalHost(
    parent: ControllerBrowserPreparation,
    prepare: (ControllerBrowserPreparation, String, Set<String>, List<String>) -> ControllerChildBrowserPlan?,
) {
    var enabled by remember(parent) { mutableStateOf(false) }
    var prompt by remember(parent) { mutableStateOf("") }
    var reads by remember(parent) { mutableStateOf("") }
    var writes by remember(parent) { mutableStateOf("") }
    var plan by remember(parent) { mutableStateOf(parent.childPlan) }
    var showing by remember(parent) { mutableStateOf(false) }
    var confirmed by remember(parent) { mutableStateOf(parent.childPlan?.confirmed == true) }
    var error by remember(parent) { mutableStateOf<String?>(null) }
    val editable = parent.state == ControllerBrowserPreparation.State.PREVIEW && plan == null
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row {
            Switch(enabled = editable, checked = enabled, modifier = Modifier.testTag("child-browser-enable"),
                onCheckedChange = { enabled = it })
            Text("另行批准一个工具型子任务（默认关闭）")
        }
        Text("普通 run_child_models 仍是 text-only。本入口仅批准以下一个预冻结子任务；标签隔离不等于 WebView 全局 cookie／账户隔离。cookie 和 history 始终拒绝。")
        if (enabled && plan == null) {
            OutlinedTextField(prompt, { prompt = it }, enabled = editable,
                label = { Text("独立子任务完整提示词") }, modifier = Modifier.testTag("child-browser-prompt"))
            OutlinedTextField(reads, { reads = it }, enabled = editable,
                label = { Text("明确选择子读取动作（如 list_tabs；英文逗号分隔）") })
            OutlinedTextField(writes, { writes = it }, enabled = editable,
                label = { Text("子写操作完整 JSON（每行一个；无默认写授权）") },
                supportingText = { Text("fetch 必须给完整 URL 和 tab_id；不允许 headers／cookie／destination。") })
            TextButton(enabled = editable && prompt.isNotBlank(), modifier = Modifier.testTag("child-browser-preview"), onClick = {
                try {
                    val raw = writes.lineSequence().filter { it.isNotBlank() }.toList()
                    val actions = reads.split(',').map { it.trim() }.filter { it.isNotBlank() }.toSet() +
                        raw.map { JSONObject(it).getString("action") }
                    plan = prepare(parent, prompt, actions, raw)
                    showing = plan != null
                    error = if (plan == null) "子任务预览被拒绝；未打开 host 或模型。" else null
                } catch (_: Exception) { error = "子操作必须是完整精确 JSON；未自动修复或授权。" }
            }) { Text("冻结独立子任务并审阅（尚未批准）") }
        }
        plan?.let {
            Text(if (confirmed) "子任务已独立批准；仅在父消息持久接受后构造。" else "子任务尚未批准，父发送被阻止。")
            TextButton(onClick = { showing = true }, modifier = Modifier.testTag("child-browser-review")) { Text("查看子任务完整批准页") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    val selected = plan
    if (showing && selected != null) AlertDialog(
        onDismissRequest = { showing = false },
        title = { Text("独立批准工具型 child 的精确浏览器效果") },
        text = { Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
            val owner = selected.scope.owner
            SelectionContainer { Text("conversation: ${owner.conversationId}\nparent run: ${owner.parentRunId}" +
                "\ngroup: ${owner.taskGroupId}\ngeneration: ${owner.generation}\nchild run: ${owner.childRunId}" +
                "\nassistant message: ${owner.assistantMessageId}\nprofile: ${parent.assistant.id}" +
                "\nmodel: ${parent.provider.model}\nowner: CHILD / ${selected.preview.grant.owner.sessionId}" +
                "\naccess ceiling: ${parent.settings.access}\n动作: ${selected.preview.allowedActions.sorted().joinToString(", ")}") }
            Text("完整子提示词及冻结的助手指令：")
            SelectionContainer { Text(selected.prompt + "\n\n" + parent.assistant.prompt) }
            selected.preview.writes.forEachIndexed { index, write ->
                Text("完整原始 JSON：")
                SelectionContainer { Text(write.arguments, Modifier.testTag("child-browser-write-${index + 1}")) }
                SelectionContainer { Text("namespace: ${write.effect.namespace}\noperation: ${write.effect.operation}" +
                    "\ntarget: ${write.effect.target}\nchange: ${write.effect.change}") }
            }
            Text("仅批准上述 child 的精确效果（包括列出的 exact fetch），不批准父／sibling／其他代次。Stop 不撤销已发生的效果；执行 UNKNOWN 由原 ledger 保护、不重 GET；清理 UNKNOWN 保留容量，不重开 host。")
        } },
        confirmButton = { Button(enabled = !confirmed, modifier = Modifier.testTag("child-browser-confirm"), onClick = {
            confirmed = selected.confirm(selected.preview)
            if (confirmed) showing = false else error = "子任务预览失效；未授权。"
        }) { Text(if (confirmed) "此子任务已独立批准" else "明确批准此 child 的精确访问") } },
        dismissButton = { TextButton(onClick = { showing = false }) { Text("返回（不增加批准）") } },
    )
}
