package app.daiyu.android.ui.screens.chat.conversation

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.semantics.Role
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

internal data class TrustedToolMediaUi(val key: String, val source: String,
    val metadata: ControllerGenerationOutputMetadata, val thumbnail: Bitmap?)

@Composable
internal fun GenerationToolContent(controller: ChatController, step: WorkStepUi, onOpenAssets: (() -> Unit)? = null) {
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
            } == true }?.map { TrustedToolMediaUi("live:${it.itemId}:${it.metadata.outputIndex}", "本次已登记产物", it.metadata, it.thumbnail) })
        }) complete(null)
        if (!controller.loadHistoricalGenerationOutputs(conversation, binding) { outputs ->
            complete(outputs?.map { display -> with(display.history) {
                TrustedToolMediaUi("history:${identity.itemId}:$outputIndex", "导入历史原件 · 只读",
                    ControllerGenerationOutputMetadata(outputIndex, kind, mimeType, bytes, width, height, durationMs), display.thumbnail)
            } })
        }) complete(null)
        if (!controller.loadStaticGenerationOutputs(conversation, binding) { outputs ->
            complete(outputs?.map { display -> with(display.output.source) {
                TrustedToolMediaUi("static:$itemId:$outputIndex", "分支静态原件 · 只读",
                    ControllerGenerationOutputMetadata(outputIndex, kind, mimeType, bytes, width, height, durationMs), display.thumbnail)
            } })
        }) complete(null)
        onDispose { visible = false }
    }
    Column(Modifier.fillMaxWidth().testTag("tool-media:${step.toolCallId}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            pending > 0 -> Text("正在校验该调用的已登记产物…", style = MiuixTheme.textStyles.footnote1)
            items.isEmpty() && failed -> Text("产物记录读取失败；不会重发生成请求。", color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.footnote1)
            items.isEmpty() -> Text("该调用暂无可读取的已登记产物。", style = MiuixTheme.textStyles.footnote1)
        }
        if (pending == 0) TextButton(text = "重新读取登记产物", onClick = { readRevision++ })
        items.forEach { item -> key(item.key) { TrustedMediaItem(item) } }
        if (items.isNotEmpty()) Text("仅加载本调用的本地登记原件；展开和放大不重试生成。", style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        onOpenAssets?.let { TextButton(text = "查看会话资产", onClick = it) }
    }
}

@Composable
private fun TrustedMediaItem(item: TrustedToolMediaUi) {
    var expanded by remember(item.key) { mutableStateOf(false) }
    val meta = item.metadata
    Text("${item.source} · ${meta.mimeType} · ${meta.width}×${meta.height} · ${meta.bytes}字节", style = MiuixTheme.textStyles.footnote1)
    val bitmap = item.thumbnail
    if (bitmap != null) {
        Image(bitmap.asImageBitmap(), "已校验的生成图片", Modifier.fillMaxWidth().heightIn(max = 220.dp)
            .clickable(role = Role.Button, onClickLabel = "放大已登记图片") { expanded = true }, contentScale = ContentScale.Fit)
        if (expanded) WindowDialog(show = true, title = "已登记图片", onDismissRequest = { expanded = false }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Image(bitmap.asImageBitmap(), "放大已登记图片", Modifier.fillMaxWidth().heightIn(max = 520.dp), contentScale = ContentScale.Fit)
                TextButton(text = "关闭", onClick = { expanded = false })
            }
        }
    } else if (meta.kind == GenerationKind.IMAGE) Text("图片预览不可用", style = MiuixTheme.textStyles.footnote1)
    else Text("视频原件 · ${meta.durationMs}毫秒；当前宿主未提供内联播放。", style = MiuixTheme.textStyles.footnote1)
}
