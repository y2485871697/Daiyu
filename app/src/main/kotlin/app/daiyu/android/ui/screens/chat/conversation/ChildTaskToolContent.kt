package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.daiyu.android.ChatController
import app.daiyu.android.domain.ConversationId
import app.daiyu.android.domain.delegation.ChildTaskRecord
import app.daiyu.android.storage.SQLiteChildTaskStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ChildTaskToolContent(controller: ChatController, step: WorkStepUi, onOpenRecords: (() -> Unit)? = null) {
    val owner = step.owner ?: return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var records by remember(owner, step.toolCallId) { mutableStateOf(emptyList<ChildTaskRecord>()) }
    var rows by remember(owner, step.toolCallId) { mutableStateOf(emptyList<SubAgentStatusRow>()) }
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
                        stored to subAgentStatusRows(stored, live)
                    } }
                    result.onSuccess { records = it.first; rows = it.second }
                        .onFailure { records = emptyList(); rows = emptyList() }
                    failed = result.isFailure
                    loading = false
                }
            } finally { controller.unobserve(observer); changed.close() }
        }
    }
    Column(Modifier.fillMaxWidth().testTag("tool-children:${step.toolCallId}"), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text("子任务生命周期", style = MiuixTheme.textStyles.footnote1)
        when {
            loading -> Text("正在读取已登记子任务…", style = MiuixTheme.textStyles.footnote1)
            failed -> Text("子任务记录读取失败", color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.footnote1)
            rows.isEmpty() -> Text("未记录该调用的精确子任务关联；旧记录可在任务记录中查看。", style = MiuixTheme.textStyles.footnote1)
            else -> rows.forEach { row -> key(row.owner) {
                Column(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(ToolRichDetails.scrub(row.agent, limit = 80), Modifier.weight(1f), style = MiuixTheme.textStyles.footnote1)
                        Text(row.status, color = if (row.completed) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            style = MiuixTheme.textStyles.footnote1)
                    }
                    Text(ToolRichDetails.scrub(row.model, limit = 160) + " · " + row.owner.childRunId.take(8), style = MiuixTheme.textStyles.footnote1)
                    records.firstOrNull { it.owner == row.owner }?.text?.takeIf { it.isNotBlank() }?.let {
                        ToolDocument(ToolDisclosureUi("子任务输出", ToolRichDetails.scrub(it)))
                    }
                }
            } }
        }
        Text("父调用回执与子任务状态分别记录；查看不会恢复或重新派发。", style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        onOpenRecords?.let { TextButton(text = "查看任务记录", onClick = it) }
    }
}
