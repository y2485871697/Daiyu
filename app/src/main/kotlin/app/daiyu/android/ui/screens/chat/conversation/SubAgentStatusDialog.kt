package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.daiyu.android.ChatController
import app.daiyu.android.domain.ConversationId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/** A visit owns observations only. Closing never stops, resumes or redispatches a child. */
@Composable
internal fun SubAgentStatusHost(controller: ChatController, conversationId: String?, onDismiss: () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var rows by remember(conversationId) { mutableStateOf(emptyList<SubAgentStatusRow>()) }
    var loading by remember(conversationId) { mutableStateOf(true) }
    var failed by remember(conversationId) { mutableStateOf(false) }
    LaunchedEffect(controller, conversationId, lifecycle) {
        if (conversationId == null) { loading = false; return@LaunchedEffect }
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val changed = Channel<Unit>(Channel.CONFLATED)
            val observer: () -> Unit = { changed.trySend(Unit); Unit }
            controller.observe(observer)
            try {
                changed.trySend(Unit)
                for (event in changed) {
                    val result = withContext(Dispatchers.IO) { runCatching {
                        val id = ConversationId(conversationId)
                        subAgentStatusRows(controller.childTasks(id, 20), controller.liveChildTasks(id))
                    } }
                    result.onSuccess { rows = it }.onFailure { rows = emptyList() }
                    failed = result.isFailure
                    loading = false
                }
            } finally { controller.unobserve(observer); changed.close() }
        }
    }
    SubAgentStatusDialog(rows, loading, failed, onDismiss)
}

@Composable
internal fun SubAgentStatusDialog(rows: List<SubAgentStatusRow>, loading: Boolean, failed: Boolean,
    onDismiss: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    WindowDialog(show = true, title = "子代理状态", onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().testTag("subagent-status"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                loading -> Text("正在读取任务状态…", color = colors.onSurfaceVariantSummary)
                failed -> Text("任务状态读取失败；未改变任何任务", color = colors.error)
                rows.isEmpty() -> Text("本会话暂无子代理任务", color = colors.onSurfaceVariantSummary)
                else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = minOf(480.dp,
                    (LocalConfiguration.current.screenHeightDp * 0.6f).dp)).testTag("subagent-status-list"),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(rows, key = { subAgentStatusItemKey(it.owner) }) { row ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp).testTag("subagent-status:${row.owner.childRunId}"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(Modifier.size(7.dp).background(if (row.completed) colors.primary else colors.onSurfaceVariantSummary, CircleShape))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(row.model, style = MiuixTheme.textStyles.body2, color = colors.onSurface)
                                Text("${row.agent} · ${row.owner.childRunId.take(6)}", maxLines = 2,
                                    overflow = TextOverflow.Ellipsis, style = MiuixTheme.textStyles.footnote1,
                                    color = colors.onSurfaceVariantSummary)
                            }
                            Text(row.status, style = MiuixTheme.textStyles.footnote1,
                                color = if (row.completed) colors.primary else colors.onSurfaceVariantSummary)
                        }
                    }
                }
            }
            TextButton(text = "关闭", onClick = onDismiss, modifier = Modifier.fillMaxWidth().testTag("subagent-status-close"))
        }
    }
}
