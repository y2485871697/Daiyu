package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.animation.core.MutableTransitionState
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
internal fun ChildTaskToolContent(controller: ChatController, step: WorkStepUi, active: Boolean = true) {
    val owner = step.owner ?: return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var records by remember(owner, step.toolCallId) { mutableStateOf(emptyList<ChildTaskRecord>()) }
    var rows by remember(owner, step.toolCallId) { mutableStateOf(emptyList<SubAgentStatusRow>()) }
    var progress by remember(owner, step.toolCallId) { mutableStateOf(emptyMap<ChildRunOwner, String>()) }
    var loading by remember(owner, step.toolCallId) { mutableStateOf(true) }
    var failed by remember(owner, step.toolCallId) { mutableStateOf(false) }
    LaunchedEffect(controller, owner, step.toolCallId, lifecycle, active) {
        if (!active) return@LaunchedEffect
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
                        val rows = subAgentStatusRows(merged.values.toList(), live)
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
            else -> ChildTaskList(rows, records, progress, showPrompt = true)
        }
    }
}

@Composable
internal fun ChildTaskList(
    rows: List<SubAgentStatusRow>,
    records: List<ChildTaskRecord>,
    progress: Map<ChildRunOwner, String> = emptyMap(),
    showPrompt: Boolean = false,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        rows.forEach { row -> key(row.owner) {
            val record = records.firstOrNull { it.owner == row.owner }
            val agentName = record?.display?.agentName?.takeIf { it.isNotBlank() }
                ?.let { ToolRichDetails.scrub(it, limit = 80) } ?: "子代理"
            val text = (if (showPrompt) record?.prompt else childTaskProgressText(record?.text, progress[row.owner]))
                ?.takeIf { it.isNotBlank() }?.let { ToolRichDetails.scrub(it) }
            ChildTaskDisclosure(row, taskTitle = agentName, details = {
                val notice = record?.let(::childTaskUnconfirmedNetworkNotice)
                ChildTaskProgressContent(listOfNotNull(notice, text).joinToString("\n\n").takeIf { it.isNotEmpty() },
                    childTaskStatusText(row.status), row.owner.childRunId, agentName)
            })
        } }
    }
}

@Composable
internal fun ChildTaskDisclosure(
    row: SubAgentStatusRow,
    details: @Composable () -> Unit,
    taskTitle: String = row.agent,
) {
    val colors = MiuixTheme.colorScheme
    var expanded by rememberSaveable(row.owner) { mutableStateOf(false) }
    val visibility = remember(row.owner) { MutableTransitionState(expanded) }
    visibility.targetState = expanded
    val disclosure = rememberWorkflowDisclosureMotion(subAgentStatusItemKey(row.owner), visibility)
    ChildTaskFrame(Modifier.testTag("tool-child-frame:${row.owner.childRunId}").then(disclosure.containerModifier)) {
        Row(Modifier.fillMaxWidth().testTag("tool-child-summary:${row.owner.childRunId}")
            .then(disclosure.headerModifier).heightIn(min = 28.dp)
            .conversationClickable(onClickLabel = if (expanded) "收起子任务" else "展开子任务") {
                disclosure.toggle(opening = !expanded); expanded = !expanded
            }
            .semantics { stateDescription = if (expanded) "已展开" else "已折叠" },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(taskTitle, Modifier.weight(1f).testTag("tool-child-name:${row.owner.childRunId}"), style = MiuixTheme.textStyles.footnote1,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(childTaskStatusText(row.status), Modifier.testTag("tool-child-status:${row.owner.childRunId}"), color = when {
                row.completed -> conversationColors().muted
                row.status in setOf("失败", "未执行", "结果未知", "状态未确认", "上下文超限") -> colors.error
                else -> colors.onSurfaceVariantSummary
            },
                style = MiuixTheme.textStyles.footnote1)
            Icon(DaiyuIcons.ChevronDown, null, Modifier.size(14.dp)
                .settingsDisclosureRotation(expanded, expandedDegrees = 180f).testTag("tool-child-arrow:${row.owner.childRunId}"),
                tint = colors.onSurfaceVariantSummary)
        }
        WorkflowExpandableContent(visibility, disclosure) {
            details()
        }
    }
}
