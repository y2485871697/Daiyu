package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.daiyu.android.ui.haptics.clickable
import app.daiyu.android.ui.media.LocalOpenChatMediaPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** The list is authoritative: only adjacent media share a layout; no source crosses text/tools/thoughts. */
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
    val ranges = remember(content) { messageContentRanges(content.map { it.isInlineMedia() }) }
    val imageNumbers = remember(content, imageOffset) {
        var number = imageOffset
        content.map { if (it is MessageContentUi.Image) ++number else 0 }
    }
    // This column deliberately has no width derived from media. Long text measures against
    // the bubble's normal constraints, even when a narrow portrait is its only attachment.
    Column {
        var precedingReady = true
        ranges.forEach { range ->
            val index = range.first
            val source = content[index]
            val block = key(source.workflowKey(index)) { rememberWorkflowItem(source) }
            val visible = precedingReady
            key(block.workflowKey(index)) {
                if (block is MessageContentUi.Tool) {
                    key(block.step.owner, block.step.toolCallId) {
                        // Keep the dedicated interaction's callsite while a prior thought closes.
                        val dedicated = dedicatedTool(block.step)
                        if (visible && !dedicated) SpacedMessageEntry(index, content, connectors[index], entryBody) {
                            WorkStepRow(block.step, showConnector = connectors[index].after, toolContent = toolContent, flowRunning = flowRunning)
                        }
                    }
                } else if (visible && (block !is MessageContentUi.Text || block.text.isNotBlank())) {
                    SpacedMessageEntry(index, content, connectors[index], entryBody, lastIndex = range.last) {
                        when (block) {
                            is MessageContentUi.Text -> textBody(block, Modifier.testTag("message-content-text:$index")
                                .then(if (workflowTimeline) Modifier.padding(start = 18.dp) else Modifier))
                            is MessageContentUi.Reasoning -> reasoningBody(block.reported, connectors[index].after)
                            is MessageContentUi.Image, is MessageContentUi.Video -> MessageMediaGroup(
                                content, range, imageNumbers, imageLoader,
                                Modifier.then(if (workflowTimeline) Modifier.padding(start = 18.dp) else Modifier)
                                    .testTag("message-media-group:$index"),
                            )
                            is MessageContentUi.Tool -> Unit
                        }
                    }
                }
            }
            if (visible && block is MessageContentUi.Reasoning) precedingReady = reasoningReady(block.reported)
        }
    }
}

private fun MessageContentUi.isInlineMedia() = this is MessageContentUi.Image || this is MessageContentUi.Video

@Composable
private fun SpacedMessageEntry(
    index: Int, content: List<MessageContentUi>, connector: WorkflowConnector,
    entryBody: @Composable (String, @Composable () -> Unit) -> Unit,
    lastIndex: Int = index,
    body: @Composable () -> Unit,
) {
    val current = content[index]
    entryBody(current.workflowKey(index)) {
        WorkTimelineEntry(
            leadingConnector = connector.before,
            trailingConnector = connector.after,
            throughConnector = connector.through,
            spacingAfter = if (lastIndex < content.lastIndex) 10.dp else 0.dp,
            content = body,
        )
    }
}

@Composable
private fun MessageMediaGroup(
    content: List<MessageContentUi>, range: IntRange, imageNumbers: List<Int>, loader: MessageImageLoader?,
    modifier: Modifier,
) {
    val items = range.map { content[it] }
    // The load state lives under the same source key as the thumbnail, not its grid position.
    val results = items.mapIndexed { offset, block ->
        key(block.workflowKey(range.first + offset)) {
            if (block is MessageContentUi.Image) rememberMessageImageResult(block.image, loader).value else null
        }
    }
    val aspects = results.mapIndexed { offset, result ->
        if (items[offset] is MessageContentUi.Video) 16f / 9f else result.decodedAspectRatio()
    }
    MessageMediaGrid(aspects, items.mapIndexed { offset, block -> block.workflowKey(range.first + offset) }, modifier) { offset ->
        when (val block = items[offset]) {
            is MessageContentUi.Image -> MessageImageThumbnail(block.image, imageNumbers[range.first + offset],
                results[offset] ?: MessageImageLoadResult.Unavailable, compact = false)
            is MessageContentUi.Video -> MessageVideo(block.video, range.first + offset + 1)
            else -> Unit
        }
    }
}

@Composable
private fun rememberMessageImageResult(image: MessageImageUi, loader: MessageImageLoader?): State<MessageImageLoadResult> {
    val initial = if (loader == null || image.expectedOwner != image.reference.owner)
        MessageImageLoadResult.Unavailable else MessageImageLoadResult.Loading
    // Key the producer itself: replacing an image must never briefly reuse the old ready bitmap
    // (and accidentally enable a click for an asset which has not yet passed validation).
    return key(image, loader) {
        produceState<MessageImageLoadResult>(initialValue = initial, image, loader) {
            value = withContext(Dispatchers.IO) { loadMessageImage(image, loader, 1024) }
        }
    }
}

private fun MessageImageLoadResult?.decodedAspectRatio(): Float? =
    (this as? MessageImageLoadResult.Ready)?.bitmap?.let { bitmap ->
        if (bitmap.width > 0 && bitmap.height > 0) bitmap.width.toFloat() / bitmap.height else null
    }

/** The editor's compact 72dp call remains independent from message media geometry. */
@Composable
internal fun MessageImage(image: MessageImageUi, number: Int, loader: MessageImageLoader?, compact: Boolean = false) {
    val result by rememberMessageImageResult(image, loader)
    if (compact) {
        MessageImageThumbnail(image, number, result, compact = true,
            modifier = Modifier.fillMaxWidth().height(72.dp))
    } else {
        MessageMediaGrid(listOf(result.decodedAspectRatio()), listOf(messageImagePreviewKey(image))) {
            MessageImageThumbnail(image, number, result, compact = false)
        }
    }
}

@Composable
private fun MessageImageThumbnail(
    image: MessageImageUi, number: Int, result: MessageImageLoadResult, compact: Boolean, modifier: Modifier = Modifier,
) {
    val openPreview = LocalOpenChatMediaPreview.current
    val ready = result is MessageImageLoadResult.Ready && image.expectedOwner == image.reference.owner
    Box(modifier.clip(RoundedCornerShape(8.dp))
        // A ready image has no full-width pale backing plate: its box follows decoded geometry.
        .then(if (ready) Modifier else Modifier.background(MiuixTheme.colorScheme.surfaceVariant))
        .testTag("message-image:$number")
        .clickable(enabled = ready, role = Role.Button, onClickLabel = "查看原始媒体") {
            if (ready) openPreview(messageImagePreviewKey(image))
        }, contentAlignment = Alignment.Center) {
        when (result) {
            MessageImageLoadResult.Loading -> MessageImageStatus(if (compact) "…" else "图片加载中…", "图片加载中", compact)
            MessageImageLoadResult.Unavailable -> MessageImageStatus(if (compact) "!" else "图片不可用", "图片不可用", compact,
                Modifier.testTag("message-image-unavailable:$number"))
            is MessageImageLoadResult.Ready -> Image(result.bitmap, contentDescription = "消息图片 $number",
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
    }
}

@Composable
private fun MessageImageStatus(label: String, description: String, compact: Boolean, modifier: Modifier = Modifier) {
    Text(label, modifier = modifier.padding(if (compact) 4.dp else 12.dp)
        .semantics { contentDescription = description },
        style = if (compact) MiuixTheme.textStyles.footnote1.copy(fontSize = 11.sp, lineHeight = 16.sp) else MiuixTheme.textStyles.body1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
}

/**
 * No UI file/URI construction. Until the host exposes an authorized cover loader, show an
 * explicit 16:9 playback block; the mixed viewer resolves the original through its owner.
 */
@Composable
internal fun MessageVideo(video: MessageVideoUi, number: Int, modifier: Modifier = Modifier) {
    val openPreview = LocalOpenChatMediaPreview.current
    val ownerMatches = video.expectedOwner == video.reference.owner
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(MiuixTheme.colorScheme.surfaceVariant)
        .testTag("message-video:$number")
        .clickable(enabled = ownerMatches, role = Role.Button, onClickLabel = "播放视频") {
            if (ownerMatches) openPreview(messageVideoPreviewKey(video))
        }, contentAlignment = Alignment.Center) {
        Text(if (ownerMatches) "▶ 播放视频" else "视频不可用",
            modifier = Modifier.padding(12.dp).semantics {
                contentDescription = if (ownerMatches) "消息视频 $number，播放视频" else "视频不可用"
            }, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

/** Mapper snapshots may allocate equal historical rows while only the active thought grows. */
@Composable
internal fun rememberWorkflowItem(item: MessageContentUi): MessageContentUi = remember(item) { item }
