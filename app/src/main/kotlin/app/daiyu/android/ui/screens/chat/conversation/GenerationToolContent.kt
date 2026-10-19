package app.daiyu.android.ui.screens.chat.conversation

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.daiyu.android.ChatController
import app.daiyu.android.ControllerGenerationOutputMetadata
import app.daiyu.android.domain.ConversationId
import app.daiyu.android.model.generation.GenerationKind
import app.daiyu.android.storage.GenerationAttemptBinding
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

internal data class TrustedToolMediaUi(val key: String,
    val metadata: ControllerGenerationOutputMetadata, val thumbnail: Bitmap?)

@Composable
internal fun GenerationToolContent(controller: ChatController, step: WorkStepUi, onAction: (ToolCardActionRequest) -> Unit) {
    val owner = step.owner ?: return
    val binding = remember(owner, step.toolCallId, step.toolName) {
        GenerationAttemptBinding(owner.conversationId, owner.messageId, owner.runId, step.toolCallId, step.toolName)
    }
    var items by remember(binding) { mutableStateOf(emptyList<TrustedToolMediaUi>()) }
    var pending by remember(binding) { mutableStateOf(3) }
    var failed by remember(binding) { mutableStateOf(false) }
    var readRevision by remember(binding) { mutableStateOf(0) }
    DisposableEffect(controller, binding, step.activity, readRevision, controller.ready, controller.dataBusy) {
        var visible = true
        val gathered = linkedMapOf<String, TrustedToolMediaUi>()
        items = emptyList(); pending = 3; failed = false
        fun complete(values: List<TrustedToolMediaUi>?) {
            if (!visible) return
            if (values == null) failed = true else values.forEach { gathered[it.key] = it }
            items = gathered.values.toList()
            pending--
        }
        val conversation = ConversationId(owner.conversationId)
        if (!controller.loadGenerationOutputs(conversation, binding) { outputs ->
            complete(outputs?.filter { output -> output.binding?.let {
                it.conversationId == owner.conversationId && it.assistantMessageId == owner.messageId &&
                    it.runId == owner.runId && it.toolCallId == step.toolCallId && it.toolName == step.toolName
            } == true }?.map { TrustedToolMediaUi("live:${it.itemId}:${it.metadata.outputIndex}", it.metadata, it.thumbnail) })
        }) complete(null)
        if (!controller.loadHistoricalGenerationOutputs(conversation, binding) { outputs ->
            complete(outputs?.map { display -> with(display.history) {
                TrustedToolMediaUi("history:${identity.itemId}:$outputIndex",
                    ControllerGenerationOutputMetadata(outputIndex, kind, mimeType, bytes, width, height, durationMs), display.thumbnail)
            } })
        }) complete(null)
        if (!controller.loadStaticGenerationOutputs(conversation, binding) { outputs ->
            complete(outputs?.map { display -> with(display.output.source) {
                TrustedToolMediaUi("static:$itemId:$outputIndex",
                    ControllerGenerationOutputMetadata(outputIndex, kind, mimeType, bytes, width, height, durationMs), display.thumbnail)
            } })
        }) complete(null)
        onDispose { visible = false }
    }
    Column(Modifier.fillMaxWidth().testTag("tool-media:${step.toolCallId}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            pending > 0 -> Text("正在加载…", style = MiuixTheme.textStyles.footnote1)
            items.isEmpty() && failed -> Text("读取失败", color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.footnote1)
            items.isEmpty() -> Text("暂无产物", style = MiuixTheme.textStyles.footnote1)
        }
        if (pending == 0) TextButton(text = "刷新产物", onClick = { readRevision++ })
        if (items.isEmpty()) ToolMediaSurface(step, null, step.toolName == "generate_videos", null, null, onAction = onAction)
        items.forEach { item -> key(item.key) { TrustedMediaItem(item, step, onAction) } }
        TextButton(text = "查看全部产物", onClick = { onAction(step.actionRequest(ToolCardAction.VIEW_ASSETS)) })
    }
}

@Composable
private fun TrustedMediaItem(item: TrustedToolMediaUi, step: WorkStepUi, onAction: (ToolCardActionRequest) -> Unit) {
    var expanded by remember(item.key) { mutableStateOf(false) }
    val meta = item.metadata
    val video = meta.kind == GenerationKind.VIDEO
    Text("${meta.mimeType} · ${meta.width}×${meta.height} · ${meta.bytes} B", style = MiuixTheme.textStyles.footnote1)
    ToolMediaSurface(step, item.thumbnail, video, meta.durationMs, item.key, onAction = onAction,
        onPreview = if (!video && item.thumbnail != null) ({ expanded = true }) else null)
    if (expanded) WindowDialog(show = true, title = "图片", onDismissRequest = { expanded = false }) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item.thumbnail?.let { Image(it.asImageBitmap(), "图片", Modifier.fillMaxWidth().heightIn(max = 520.dp), contentScale = ContentScale.Fit) }
            TextButton(text = "保存原件", onClick = { onAction(step.actionRequest(ToolCardAction.SAVE_MEDIA, item.key)) })
            TextButton(text = "关闭", onClick = { expanded = false })
        }
    }
}
