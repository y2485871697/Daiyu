package app.daiyu.android.vdapproval

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import app.daiyu.android.ui.haptics.material.Button
import app.daiyu.android.ui.haptics.material.OutlinedButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Bind onDecision to the ORIGINAL retained session.answerCleanupFromUi. Confirmation grants
 * only; the host must explicitly schedule releaseInitializedOnceOnWorker on its worker. Removal,
 * Back and denial neither release nor cancel any newer request/run. No automatic observation.
 */
@Composable
internal fun R08CleanupApprovalScreen(
    request: R08CleanupApprovalRequest,
    onDecision: (R08CleanupApprovalRequest, Boolean) -> Boolean,
    modifier: Modifier = Modifier
) {
    val decide = remember(request) { onDecision }
    var answered by remember(request) { mutableStateOf(false) }
    var obsolete by remember(request) { mutableStateOf(false) }
    fun answer(accepted: Boolean) {
        if (!answered) { answered = true; obsolete = !decide(request, accepted) }
    }
    BackHandler(enabled = !answered) { answer(false) }
    DisposableEffect(request) { onDispose { decide(request, false) } }
    val plan = request.review.plan
    val binding = remember(request) { plan.binding.json() }
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (request.deliversTasks) "已停止运行：所选任务交付审批" else if (request.cleanupOnly) "已停止运行：不交付任务的清理审批" else "已停止运行：额外释放审批", style = MaterialTheme.typography.headlineSmall)
        Text(request.warning)
        SelectionContainer(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("session", "task", "conversation", "run", "assistantMessage", "runOwner").forEach {
                    Text("$it: ${binding.getString(it)}")
                }
                Text("bindingKey: ${plan.bindingKey}")
                Text("generation: ${plan.generation}")
                request.review.rows.forEach { row ->
                    Text("WRITE ${row.stepId} · ${row.operation}")
                    Text("规范生效参数: ${row.effectiveParameters}")
                    Text("namespace: ${row.exactEffect.namespace}")
                    Text("operation: ${row.exactEffect.operation}")
                    Text("target: ${row.exactEffect.target}")
                    Text("change: ${row.exactEffect.change}")
                }
            }
        }
        if (obsolete) Text("收尾请求已过期；未操作其他运行。")
        OutlinedButton(onClick = { answer(false) }, enabled = !answered,
            modifier = Modifier.fillMaxWidth().testTag("r08-cleanup-reject")) { Text("拒绝，保留资源") }
        Button(onClick = { answer(true) }, enabled = !answered,
            modifier = Modifier.fillMaxWidth().testTag("r08-cleanup-confirm")) { Text(request.confirmText) }
    }
}
