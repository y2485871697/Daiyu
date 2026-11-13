package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.ui.screens.settings.settingsDisclosureRotation
import app.daiyu.android.ui.screens.settings.SettingsExpandableContent
import app.daiyu.android.ui.haptics.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import app.daiyu.android.ui.icons.DaiyuIcons
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.daiyu.android.ChatController
import app.daiyu.android.domain.ConversationId
import app.daiyu.android.domain.delegation.ChildRunOwner
import app.daiyu.android.domain.delegation.ChildTaskRecord
import app.daiyu.android.storage.SQLiteChildTaskStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ChildTaskToolContent(controller: ChatController, step: WorkStepUi, onAction: (ToolCardActionRequest) -> Unit) {
    val owner = step.owner ?: return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var records by remember(owner, step.toolCallId) { mutableStateOf(emptyList<ChildTaskRecord>()) }
    var rows by remember(owner, step.toolCallId) { mutableStateOf(emptyList<SubAgentStatusRow>()) }
    var progress by remember(owner, step.toolCallId) { mutableStateOf(emptyMap<ChildRunOwner, String>()) }
    var loading by remember(owner, step.toolCallId) { mutableStateOf(true) }
    var failed by remember(owner, step.toolCallId) { mutableStateOf(false) }
    LaunchedEffect(controller, owner, step.toolCallId, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val changed = Channel<Unit>(Channel.CONFLATED)
            val observer: () -> Unit = { changed.trySend(Unit); Unit }
            controller.observe(observer)
            try {
                changed.trySend(Unit)
                for (event in changed) {
                    val result = withContext(Dispatchers.IO) { runCatching {
                        val stored = SQLiteChildTaskStore(controller.database).forCall(owner.conversationId, owner.runId,
                            owner.messageId, step.toolName, step.toolCallId)
                        val owners = stored.map { it.owner }.toSet()
                        val live = controller.liveChildTasks(ConversationId(owner.conversationId)).filter { it.record.owner in owners }
                        val merged = stored.associateByTo(linkedMapOf()) { it.owner }
                        live.forEach { merged[it.record.owner] = it.record }
                        val rows = subAgentStatusRows(merged.values.toList(), live).map { row ->
                            if (row.status == "执行中" && live.none { it.record.owner == row.owner })
                                row.copy(status = "状态未确认") else row
                        }
                        Triple(merged.values.toList(), rows, owners.associateWith(controller::childTaskProgress))
                    } }
                    result.onSuccess { records = it.first; rows = it.second; progress = it.third }
                        .onFailure { records = emptyList(); rows = emptyList(); progress = emptyMap() }
                    failed = result.isFailure
                    loading = false
                }
            } finally { controller.unobserve(observer); changed.close() }
        }
    }
    Column(Modifier.fillMaxWidth().testTag("tool-children:${step.toolCallId}"), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        when {
            loading -> ChildTaskFrame { Text("正在读取任务…", style = MiuixTheme.textStyles.footnote1) }
            failed -> ChildTaskFrame { Text("任务暂时无法读取", color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.footnote1) }
            rows.isEmpty() -> ChildTaskFrame { Text("暂无任务记录", style = MiuixTheme.textStyles.footnote1) }
            else -> ChildTaskList(rows, records, { row, action ->
                onAction(step.actionRequest(action, subAgentStatusItemKey(row.owner)))
            }, progress)
        }
    }
}

@Composable
internal fun ChildTaskList(
    rows: List<SubAgentStatusRow>,
    records: List<ChildTaskRecord>,
    onAction: (SubAgentStatusRow, ToolCardAction) -> Unit,
    progress: Map<ChildRunOwner, String> = emptyMap(),
) {
    var showCompleted by remember(rows.map { it.owner }) { mutableStateOf(false) }
    val allCompleted = rows.size > 1 && childTaskBatchCompleted(rows)
    val colors = MiuixTheme.colorScheme
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        if (allCompleted) ChildTaskFrame {
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).testTag("tool-child-batch-summary")
                .semantics { stateDescription = if (showCompleted) "已展开" else "已折叠" }
                .clickable(role = Role.Button, onClickLabel = if (showCompleted) "收起任务" else "查看任务结果") {
                    showCompleted = !showCompleted
                }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${rows.size} 项任务已完成", Modifier.weight(1f), style = MiuixTheme.textStyles.footnote1)
                Icon(DaiyuIcons.ChevronDown, null, Modifier.size(14.dp).settingsDisclosureRotation(showCompleted, expandedDegrees = 180f),
                    tint = colors.onSurfaceVariantSummary)
            }
        }
        SettingsExpandableContent(!allCompleted || showCompleted) {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                rows.forEach { row -> key(row.owner) {
                    val record = records.firstOrNull { it.owner == row.owner }
                    ChildTaskDisclosure(row, taskTitle = record?.display?.agentName?.takeIf { it.isNotBlank() }?.let { ToolRichDetails.scrub(it, limit = 80) } ?: "子代理", details = {
                            val text = childTaskProgressText(record?.text, progress[row.owner])
                                ?.let { ToolRichDetails.scrub(it) }
                            ChildTaskProgressContent(text, childTaskStatusText(row.status), row.owner.childRunId)
                        }, controls = {
                            val actions = when (row.status) {
                                "已暂停", "上下文超限" -> listOf(ToolCardAction.RESUME_TASK, ToolCardAction.STOP_TASK)
                                "执行中", "排队中" -> listOf(ToolCardAction.PAUSE_TASK, ToolCardAction.STOP_TASK)
                                "暂停中", "停止中" -> emptyList()
                                else -> emptyList()
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                actions.forEach { action -> ToolMiniAction(
                                    text = if (row.contextLimited && action == ToolCardAction.RESUME_TASK) "压缩并继续" else action.label,
                                    onClick = { onAction(row, action) }) }
                            }
                        })
                } }
            }
        }
    }
}

@Composable
internal fun ChildTaskDisclosure(
    row: SubAgentStatusRow,
    details: @Composable () -> Unit,
    controls: @Composable () -> Unit,
    taskTitle: String = row.agent,
) {
    var expanded by remember(row.owner) { mutableStateOf(false) }
    val colors = MiuixTheme.colorScheme
    ChildTaskFrame(Modifier.testTag("tool-child-frame:${row.owner.childRunId}")) {
        Row(Modifier.fillMaxWidth().testTag("tool-child-summary:${row.owner.childRunId}")
            .heightIn(min = 44.dp).semantics { stateDescription = if (expanded) "已展开" else "已折叠" }
            .clickable(role = Role.Button, onClickLabel = if (expanded) "折叠子任务" else "展开子任务") { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(taskTitle, style = MiuixTheme.textStyles.footnote1, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(childTaskStatusText(row.status), color = when {
                row.completed -> colors.primary
                row.status in setOf("失败", "未执行", "结果未知", "状态未确认", "上下文超限") -> colors.error
                else -> colors.onSurfaceVariantSummary
            },
                style = MiuixTheme.textStyles.footnote1)
            Icon(DaiyuIcons.ChevronDown, null, Modifier.size(14.dp).settingsDisclosureRotation(expanded, expandedDegrees = 180f),
                tint = colors.onSurfaceVariantSummary)
        }
        SettingsExpandableContent(expanded) { details() }
        controls()
    }
}
