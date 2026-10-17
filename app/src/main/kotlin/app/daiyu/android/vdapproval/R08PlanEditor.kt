package app.daiyu.android.vdapproval

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Independent embeddable editor: neither Activity/Manifest hookup nor a parallel runtime.
 * Parameters below really become frozen host inputs. Final authorization is ONLY the existing
 * VirtualDisplayApprovalScreen after durable acceptance/reservation with the actual run owner.
 */
@Composable
internal fun R08PlanEditor(
    host: R08PlanEditorHost,
    onFrozen: (R08FrozenPlan) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val submit = remember(host) { onFrozen }
    val cancel = remember(host) { onCancel }
    var rows by remember(host) { mutableStateOf(listOf(R08PlanEditorRow(R08PlanAction.START),
        R08PlanEditorRow(R08PlanAction.INITIALIZE))) }
    var answered by remember(host) { mutableStateOf(false) }
    var error by remember(host) { mutableStateOf<String?>(null) }
    fun deny() {
        if (!answered) {
            answered = true
            if (host.cancel()) cancel()
        }
    }
    BackHandler(enabled = !answered) { deny() }
    DisposableEffect(host) { onDispose { if (host.cancel()) cancel() } }
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("R08 副屏动作计划", style = MaterialTheme.typography.headlineSmall)
        Text("编辑仅生成有限计划，不启动 root、不接受消息、不授予权限。冻结后请在原运行审批中核对每项规范参数和 effect。" +
            "Launch 必填 package 和显式 component（包名/.Activity 或 包名/完整类名），不会在执行时解析或替换。" +
            "启动任务后的正常退出请在同一计划添加 HANDOFF → RELEASE；Handoff 明确不保留任务（taskIds=[]），由原 owner 验证并清理 owned 任务。" +
            "写步骤按下列顺序规划；执行仍经过现有中央目录，读取独立可用且绝不初始化。")
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("r08-plan-rows"),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(rows) { index, row ->
                Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${index + 1}. ${row.action.name}", style = MaterialTheme.typography.titleMedium)
                    row.action.fields.forEachIndexed { fieldIndex, name ->
                        OutlinedTextField(value = row.values[fieldIndex], onValueChange = { value ->
                            rows = rows.toMutableList().also { updated ->
                                updated[index] = row.copy(values = row.values.toMutableList().also { it[fieldIndex] = value })
                            }
                            error = null
                        }, enabled = !answered, label = { Text(name) }, singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("r08-plan-$index-$name"))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { rows = rows.filterIndexed { i, _ -> i != index } }, enabled = !answered,
                            modifier = Modifier.testTag("r08-remove-$index")) { Text("删除") }
                        TextButton(onClick = {
                            rows = rows.toMutableList().also { list ->
                                val previous = list[index - 1]; list[index - 1] = row; list[index] = previous
                            }
                        }, enabled = !answered && index > 0) { Text("上移") }
                        TextButton(onClick = {
                            rows = rows.toMutableList().also { list ->
                                val next = list[index + 1]; list[index + 1] = row; list[index] = next
                            }
                        }, enabled = !answered && index < rows.lastIndex) { Text("下移") }
                    }
                }
            }
            item {
                Text("添加有限动作 / 读取（最多 64 项）")
                R08PlanAction.entries.chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { action ->
                            OutlinedButton(onClick = { rows = rows + R08PlanEditorRow(action); error = null },
                                enabled = !answered && rows.size < 64,
                                modifier = Modifier.weight(1f).testTag("r08-add-${action.name}")) { Text(action.name) }
                        }
                    }
                }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("r08-plan-error")) }
        OutlinedButton(onClick = { deny() }, enabled = !answered, modifier = Modifier.fillMaxWidth()) { Text("取消计划") }
        Button(onClick = {
            val frozen = try { host.freeze(rows) } catch (_: IllegalArgumentException) {
                error = "计划无效：请检查顺序、包名与显式组件、整数范围、文本及 snapshot 参数。"; null
            } catch (_: IllegalStateException) {
                error = "计划参数无效或编辑器已失效。"; null
            }
            if (frozen != null) { answered = true; submit(frozen) }
        }, enabled = !answered, modifier = Modifier.fillMaxWidth().testTag("r08-plan-freeze")) { Text("冻结计划，继续原运行审批") }
    }
}
