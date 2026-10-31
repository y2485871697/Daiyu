package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.foundation.Image
import app.daiyu.android.ui.haptics.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.Role
import top.yukonga.miuix.kmp.window.WindowDialog
import app.daiyu.android.ui.haptics.miuix.TextButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** The list is authoritative: text, images and tools are rendered in source order, once. */
@Composable
internal fun MessageContent(
    content: List<MessageContentUi>,
    actions: ChatScreenActions,
    imageLoader: MessageImageLoader?,
    markdown: Boolean,
    dedicatedTool: @Composable (WorkStepUi) -> Boolean = { false },
    toolContent: @Composable (WorkStepUi) -> Unit = {},
    flowRunning: Boolean = false,
    workflowTimeline: Boolean = false,
    reasoningReady: (app.daiyu.android.domain.ReportedReasoning) -> Boolean = { true },
    reasoningBody: @Composable (app.daiyu.android.domain.ReportedReasoning, Boolean) -> Unit = { _, _ -> },
    imageOffset: Int = 0,
    entryBody: @Composable (String, @Composable () -> Unit) -> Unit = { _, body -> body() },
    textBody: @Composable (MessageContentUi.Text, Modifier) -> Unit = { text, modifier -> MessageMarkdown(text.text, modifier, markdown) },
) {
    val connectors = remember(content, workflowTimeline) { workflowConnectors(content, workflowTimeline) }
    Column {
        var imageNumber = imageOffset
        var precedingReady = true
        content.forEachIndexed { index, source ->
            val block = key(source.workflowKey(index)) { rememberWorkflowItem(source) }
            val visible = precedingReady
            val imageIndex = if (block is MessageContentUi.Image) ++imageNumber else 0
            key(block.workflowKey(index)) {
                if (block is MessageContentUi.Tool) {
                    key(block.step.owner, block.step.toolCallId) {
                        // The dedicated interaction keeps one callsite even while the prior thought closes.
                        val dedicated = dedicatedTool(block.step)
                        if (visible && !dedicated) SpacedMessageEntry(index, content, connectors[index], entryBody) {
                            WorkStepRow(block.step, showConnector = connectors[index].after, toolContent = toolContent, flowRunning = flowRunning)
                        }
                    }
                } else if (visible && (block !is MessageContentUi.Text || block.text.isNotBlank())) SpacedMessageEntry(index, content, connectors[index], entryBody) {
                    when (block) {
                        is MessageContentUi.Text -> if (block.text.isNotBlank())
                            textBody(block, Modifier.testTag("message-content-text:$index")
                                .then(if (workflowTimeline) Modifier.padding(start = 18.dp) else Modifier))
                        is MessageContentUi.Reasoning -> reasoningBody(block.reported,
                            connectors[index].after)
                        is MessageContentUi.Image -> Box(if (workflowTimeline) Modifier.padding(start = 18.dp) else Modifier) {
                            MessageImage(block.image, imageIndex, imageLoader)
                        }
                        is MessageContentUi.Tool -> Unit
                    }
                }
            }
            if (visible && block is MessageContentUi.Reasoning) precedingReady = reasoningReady(block.reported)
        }
    }
}

@Composable
private fun SpacedMessageEntry(index: Int, content: List<MessageContentUi>,
    connector: WorkflowConnector,
    entryBody: @Composable (String, @Composable () -> Unit) -> Unit, body: @Composable () -> Unit) {
    val current = content[index]
    entryBody(current.workflowKey(index)) {
        WorkTimelineEntry(
            leadingConnector = connector.before,
            trailingConnector = connector.after,
            throughConnector = connector.through,
            spacingAfter = if (index < content.lastIndex) 10.dp else 0.dp,
            content = body,
        )
    }
}

@Composable
internal fun MessageImage(image: MessageImageUi, number: Int, loader: MessageImageLoader?, compact: Boolean = false) = key(image, loader) {
    var expanded by remember(image, loader) { mutableStateOf(false) }
    val maxEdge = 1024
    val result by produceState<MessageImageLoadResult>(
        initialValue = if (loader == null || image.expectedOwner != image.reference.owner) {
            MessageImageLoadResult.Unavailable
        } else MessageImageLoadResult.Loading,
        image, loader,
    ) {
        value = if (loader == null || image.expectedOwner != image.reference.owner) {
            MessageImageLoadResult.Unavailable
        } else {
            MessageImageLoadResult.Loading
        }
        value = withContext(Dispatchers.IO) { loadMessageImage(image, loader, maxEdge) }
    }
    val ready = result as? MessageImageLoadResult.Ready
    if (expanded && ready != null) {
        WindowDialog(show = true, title = "消息图片 $number", onDismissRequest = { expanded = false }) {
            app.daiyu.android.diagnostics.ObserveDialogFrames("dialog")
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Image(ready.bitmap, contentDescription = "放大消息图片 $number",
                    modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp).testTag("message-image-expanded:$number"),
                    contentScale = ContentScale.Fit)
                Text("已校验的消息图片 · 放大不会重新读取或上传", style = MiuixTheme.textStyles.footnote1)
                TextButton(text = "关闭", onClick = { expanded = false }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(if (compact) 72.dp else 180.dp).clip(RoundedCornerShape(12.dp))
        .background(MiuixTheme.colorScheme.surfaceVariant).testTag("message-image:$number")
        .clickable(enabled = ready != null, role = Role.Button, onClickLabel = "放大图片") { expanded = true },
        contentAlignment = Alignment.Center) {
        when (val current = result) {
            MessageImageLoadResult.Loading -> Text(if (compact) "…" else "图片加载中…",
                modifier = Modifier.padding(if (compact) 4.dp else 12.dp).semantics { contentDescription = "图片加载中" },
                style = if (compact) MiuixTheme.textStyles.footnote1.copy(fontSize = 11.sp, lineHeight = 16.sp) else MiuixTheme.textStyles.body1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            MessageImageLoadResult.Unavailable -> Text(if (compact) "!" else "图片不可用",
                modifier = Modifier.padding(if (compact) 4.dp else 12.dp).semantics { contentDescription = "图片不可用" }
                    .testTag("message-image-unavailable:$number"),
                style = if (compact) MiuixTheme.textStyles.footnote1.copy(fontSize = 11.sp, lineHeight = 16.sp) else MiuixTheme.textStyles.body1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            is MessageImageLoadResult.Ready -> Image(current.bitmap, contentDescription = "消息图片 $number",
                modifier = Modifier.fillMaxWidth().height(180.dp), contentScale = ContentScale.Fit)
        }
    }
}

/** Mapper snapshots may allocate equal historical rows while only the active thought grows. */
@Composable
internal fun rememberWorkflowItem(item: MessageContentUi): MessageContentUi = remember(item) { item }
