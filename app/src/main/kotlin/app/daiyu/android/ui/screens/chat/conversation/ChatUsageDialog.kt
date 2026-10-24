package app.daiyu.android.ui.screens.chat.conversation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.daiyu.android.ChatController
import app.daiyu.android.data.repository.RecordedUsageProjection
import app.daiyu.android.domain.ConversationId
import app.daiyu.android.ui.app.ConversationTokenUsageContent
import app.daiyu.android.ui.components.UsageGlassDialog
import app.daiyu.android.ui.components.usagePalette
import app.daiyu.android.ui.layout.appWindowSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** This surface only reads its conversation. An unattached chat never queries global usage. */
@Composable
internal fun ChatUsageDialog(
    controller: ChatController,
    conversationId: ConversationId?,
    readUsage: ((ConversationId) -> RecordedUsageProjection)? = null,
    onDismiss: () -> Unit,
    usageRevision: Long = 0L,
) {
    var projection by remember(controller, conversationId) { mutableStateOf<app.daiyu.android.ui.model.ConversationTokenUsageUi?>(null) }
    var loading by remember(controller, conversationId) { mutableStateOf(conversationId != null) }
    LaunchedEffect(controller, conversationId, usageRevision) {
        val owner = conversationId ?: return@LaunchedEffect
        projection = withContext(Dispatchers.IO) {
            try { if (readUsage != null) readUsage(owner).conversationUsage else controller.conversationTokenUsage(owner) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        }
        loading = false
    }
    if (loading) {
        BackHandler(onBack = onDismiss)
        return
    }
    val colors = usagePalette()
    UsageGlassDialog(title = "Token 用量", onDismiss = onDismiss, compact = true, adaptiveWidth = true) {
        Column(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = minOf(540.dp, appWindowSize().height * 0.65f))
            .verticalScroll(rememberScrollState()).testTag("chat-usage-dialog"),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val usage = projection
            when {
                conversationId == null || usage?.hasUsage == false -> {
                    Box(Modifier.fillMaxWidth().heightIn(min = 100.dp).testTag("chat-usage-empty"),
                        contentAlignment = Alignment.Center) {
                        Text("暂无用量", fontSize = 13.sp, color = colors.muted, textAlign = TextAlign.Center)
                    }
                }
                usage == null -> Text("用量读取失败", fontSize = 10.sp, color = MiuixTheme.colorScheme.error)
                else -> key(controller, conversationId) { ConversationTokenUsageContent(usage) }
            }
        }
    }
}
