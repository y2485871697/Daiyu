package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.daiyu.android.ChatController
import app.daiyu.android.data.repository.RecordedUsageProjection
import app.daiyu.android.domain.ConversationId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.window.WindowDialog

/** Reuses the retained usage presentation; missing provider reports remain unknown. */
@Composable
internal fun ChatUsageDialog(controller: ChatController, conversationId: String?, selectScope: Boolean,
    onDismiss: () -> Unit) {
    var all by remember(conversationId) { mutableStateOf(conversationId == null) }
    var scopePicker by remember(conversationId) { mutableStateOf(selectScope) }
    var projection by remember(all, conversationId) { mutableStateOf<RecordedUsageProjection?>(null) }
    var loading by remember(all, conversationId) { mutableStateOf(true) }
    var refresh by remember { mutableStateOf(0) }
    LaunchedEffect(controller, conversationId, all, refresh) {
        loading = true
        projection = withContext(Dispatchers.IO) { runCatching {
            RecordedUsageProjection(controller.recordedRequestUsage(if (all) null else conversationId?.let(::ConversationId)))
        }.getOrNull() }
        loading = false
    }
    WindowDialog(show = true, title = if (scopePicker) "统计对象" else "Token 用量", onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = minOf(540.dp, (LocalConfiguration.current.screenHeightDp * 0.65f).dp)).verticalScroll(rememberScrollState())
            .testTag("chat-usage-dialog"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (scopePicker) {
                TextButton(text = "本会话", enabled = conversationId != null,
                    onClick = { all = false; scopePicker = false }, modifier = Modifier.testTag("usage-scope-conversation"))
                TextButton(text = "全部会话", onClick = { all = true; scopePicker = false },
                    modifier = Modifier.testTag("usage-scope-all"))
            } else {
                TextButton(text = if (all) "统计对象：全部会话" else "统计对象：本会话",
                    onClick = { scopePicker = true })
                if (loading) Text("正在读取已记录用量…")
                else app.daiyu.android.ui.screens.stats.RecordedUsagePane(projection, modelView = false)
                Text("累计用量不是当前上下文窗口占用；缺报字段不按 0 处理。", Modifier.padding(horizontal = 4.dp))
                TextButton(text = "刷新", onClick = { refresh++ }, enabled = !loading)
            }
            TextButton(text = "关闭", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
        }
    }
}
