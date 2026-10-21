package app.daiyu.android.vdapproval

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import app.daiyu.android.ui.haptics.material.Button
import androidx.compose.material3.MaterialTheme
import app.daiyu.android.ui.haptics.material.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Presentation only: no Context, host, process, journal, transport or authorization factory.
 * The parent must use ControllerVirtualDisplaySession.answerFromUi as onDecision. Returning false
 * means a stale/already-answered request; the screen never substitutes the current request.
 * Back, removal and explicit rejection all deny the ORIGINAL request through its original
 * session callback, never a newer pending request or another session's callback.
 */
@Composable
internal fun VirtualDisplayApprovalScreen(
    request: VirtualDisplayApprovalRequest,
    onDecision: (VirtualDisplayApprovalRequest, Boolean) -> Boolean,
    modifier: Modifier = Modifier
) {
    // Keep request and owning session callback together, including during old effect disposal.
    val decide = remember(request) { onDecision }
    var answered by remember(request) { mutableStateOf(false) }
    var obsolete by remember(request) { mutableStateOf(false) }
    fun answer(accepted: Boolean) {
        if (!answered) {
            answered = true
            obsolete = !decide(request, accepted)
        }
    }
    BackHandler(enabled = !answered) { answer(false) }
    DisposableEffect(request) {
        onDispose {
            // Intentional even after confirmation: the parent's single-use identity check ignores it.
            decide(request, false)
        }
    }
    val plan = request.plan
    val binding = remember(request) { plan.binding.json() }
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("虚拟显示：本次运行审批", style = MaterialTheme.typography.headlineSmall)
        Text("消息已被接受。拒绝或离开只停止这次运行，不会撤销消息、自动重发或启动 root。")
        Text(request.warning, style = MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("vd-approval-details"),
            contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "binding") {
                SelectionContainer {
                    Column {
                        Text("完整绑定 / generation", style = MaterialTheme.typography.titleMedium)
                        listOf("session", "task", "conversation", "run", "assistantMessage", "runOwner").forEach { name ->
                            Text("$name: ${binding.getString(name)}")
                        }
                        Text("bindingKey: ${plan.bindingKey}")
                        Text("generation: ${plan.generation}")
                    }
                }
            }
            items(request.rows, key = { "write:${it.stepId}" }) { row ->
                SelectionContainer {
                    Column {
                        Text("WRITE ${row.stepId} · ${row.operation}", style = MaterialTheme.typography.titleMedium)
                        Text("规范生效参数: ${row.effectiveParameters}")
                        Text("namespace: ${row.exactEffect.namespace}")
                        Text("operation: ${row.exactEffect.operation}")
                        Text("target: ${row.exactEffect.target}")
                        Text("change: ${row.exactEffect.change}")
                    }
                }
            }
            items(plan.reads, key = { "read:${it.id}" }) { read ->
                SelectionContainer {
                    Column {
                        Text("READ ${read.id} · ${read.operation}", style = MaterialTheme.typography.titleMedium)
                        Text("规范生效参数: ${read.canonicalParameters}")
                        Text("只读；不会启动或初始化显示。")
                    }
                }
            }
            item(key = "warning") {
                Text("确认只授予上述有限操作，不执行它们。启动 root owner、初始化显示和 GUI 写入分别授权。" +
                    "关闭仅撤销访问；不隐式 release / shutdown。未知结果继续保留 owner 和 lease。")
            }
        }
        if (obsolete) Text("此审批已过期；未操作任何较新的请求。", Modifier.testTag("vd-approval-obsolete"))
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { answer(false) }, enabled = !answered,
                modifier = Modifier.fillMaxWidth().testTag("vd-approval-reject")) { Text("拒绝并停止本次运行") }
            Button(onClick = { answer(true) }, enabled = !answered,
                modifier = Modifier.fillMaxWidth().testTag("vd-approval-confirm")) { Text("确认上述操作") }
        }
    }
}
