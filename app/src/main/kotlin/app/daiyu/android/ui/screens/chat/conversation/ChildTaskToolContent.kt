package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import app.daiyu.android.ui.icons.DaiyuIcons
import app.daiyu.android.ui.screens.settings.settingsDisclosureRotation
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
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        rows.forEach { row -> key(row.owner) {
            val record = records.firstOrNull { it.owner == row.owner }
            val agentName = record?.display?.agentName?.takeIf { it.isNotBlank() }
                ?.let { ToolRichDetails.scrub(it, limit = 80) } ?: "子代理"
            val text = childTaskProgressText(record?.text, progress[row.owner])?.let { ToolRichDetails.scrub(it) }
            ChildTaskDisclosure(row, taskTitle = agentName, details = {
                ChildTaskProgressContent(text, childTaskStatusText(row.status), row.owner.childRunId, agentName, expanded = true)
            }, preview = {
                ChildTaskProgressContent(text, childTaskStatusText(row.status), row.owner.childRunId, agentName)
            }, controls = {
                val actions = when (row.status) {
                    "已暂停", "上下文超限" -> listOf(ToolCardAction.RESUME_TASK, ToolCardAction.STOP_TASK)
                    "执行中", "排队中" -> listOf(ToolCardAction.PAUSE_TASK, ToolCardAction.STOP_TASK)
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

@Composable
internal fun ChildTaskDisclosure(
    row: SubAgentStatusRow,
    details: @Composable () -> Unit,
    controls: @Composable () -> Unit,
    taskTitle: String = row.agent,
    preview: @Composable () -> Unit = {},
) {
    val colors = MiuixTheme.colorScheme
    var expanded by rememberSaveable(row.owner) { mutableStateOf(false) }
    ChildTaskFrame(Modifier.testTag("tool-child-frame:${row.owner.childRunId}")) {
        Row(Modifier.fillMaxWidth().testTag("tool-child-summary:${row.owner.childRunId}")
            .heightIn(min = 28.dp)
            .conversationClickable(onClickLabel = if (expanded) "收起子任务" else "展开子任务") { expanded = !expanded }
            .semantics { stateDescription = if (expanded) "已展开" else "已折叠" },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(taskTitle, Modifier.weight(1f).testTag("tool-child-name:${row.owner.childRunId}"), style = MiuixTheme.textStyles.footnote1,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(childTaskStatusText(row.status), Modifier.testTag("tool-child-status:${row.owner.childRunId}"), color = when {
                row.completed -> colors.primary
                row.status in setOf("失败", "未执行", "结果未知", "状态未确认", "上下文超限") -> colors.error
                else -> colors.onSurfaceVariantSummary
            },
                style = MiuixTheme.textStyles.footnote1)
            Icon(DaiyuIcons.ChevronDown, null, Modifier.size(14.dp)
                .settingsDisclosureRotation(expanded, expandedDegrees = 180f).testTag("tool-child-arrow:${row.owner.childRunId}"),
                tint = colors.onSurfaceVariantSummary)
        }
        if (expanded) details() else preview()
        controls()
    }
}
