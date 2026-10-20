package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Text
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.daiyu.android.ui.components.subagent.*

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
    val colors = conversationAgentColors()
    val maximumHeight = (LocalConfiguration.current.screenHeightDp - 48).coerceAtLeast(180).dp
    ConversationAgentSheet(onDismiss, radius = 26, modifier = Modifier.heightIn(max = maximumHeight)) {
        Column(Modifier.fillMaxWidth().heightIn(min = 180.dp).padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 12.dp).testTag("subagent-status")) {
            ConversationAgentHandle()
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp).heightIn(min = 28.dp), verticalAlignment = Alignment.Top) {
                Text("子代理状态", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = colors.text,
                    modifier = Modifier.weight(1f))
            }
            Text("当前会话", fontSize = 10.sp, color = colors.taskMuted, modifier = Modifier.padding(bottom = 10.dp))
            when {
                loading -> Text("正在读取任务状态…", color = colors.taskMuted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 32.dp))
                failed -> Text("任务状态读取失败；未改变任何任务", color = Color(if (colors.dark) 0xffe5a8b7 else 0xffa15468), fontSize = 13.sp, modifier = Modifier.padding(vertical = 32.dp))
                rows.isEmpty() -> Text("本会话暂无子代理任务", color = colors.taskMuted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 32.dp))
                else -> LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).testTag("subagent-status-list")) {
                    items(rows, key = { subAgentStatusItemKey(it.owner) }) { row ->
                        var expanded by remember(subAgentStatusItemKey(row.owner)) { mutableStateOf(false) }
                        Column(Modifier.fillMaxWidth()) {
                        val stateColor = when {
                            row.completed -> Color(if (colors.dark) 0xffa4c8b5 else 0xff527867)
                            row.status in setOf("执行中", "已暂停", "暂停中") -> Color(if (colors.dark) 0xffc4aad2 else 0xff786285)
                            else -> colors.taskMuted
                        }
                        Row(Modifier.fillMaxWidth().heightIn(min = 42.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { expanded = !expanded }
                            .padding(horizontal = 1.dp, vertical = 10.dp).testTag("subagent-status:${row.owner.childRunId}"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(ToolRichDetails.scrub(row.agent, limit = 100), Modifier.weight(1f),
                                fontSize = 13.sp, fontWeight = FontWeight.Medium, color = colors.text,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                Box(Modifier.size(5.dp).background(stateColor, CircleShape))
                                Text(row.status, fontSize = 10.sp, color = stateColor)
                            }
                        }
                        if (expanded) androidx.compose.foundation.text.selection.SelectionContainer {
                            Column(Modifier.padding(start = 1.dp, end = 1.dp, bottom = 10.dp)) {
                                Text(ToolRichDetails.scrub(row.model, limit = 160), fontSize = 10.sp, lineHeight = 17.sp, color = colors.taskMuted)
                                Text(row.owner.childRunId, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 17.sp, color = colors.taskMuted)
                            }
                        }
                        if (row != rows.lastOrNull()) HorizontalDivider(color = colors.taskLine)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
                Box(Modifier.testTag("subagent-status-close").heightIn(min = 36.dp).clip(RoundedCornerShape(12.dp))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
                    .padding(horizontal = 14.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                    Text("关闭", fontSize = 12.sp, color = colors.strong)
                }
            }
        }
    }
}
