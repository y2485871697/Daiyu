package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
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
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        var imageNumber = 0
        content.forEachIndexed { index, block ->
            when (block) {
                is MessageContentUi.Text -> {
                    if (block.text.isNotBlank()) MessageMarkdown(block.text,
                        modifier = Modifier.testTag("message-content-text:$index"), markdown = markdown)
                }
                is MessageContentUi.Image -> MessageImage(block.image, ++imageNumber, imageLoader)
                is MessageContentUi.Tool -> {
                    val detail = if (!block.result.isNullOrBlank() && block.arguments.isNotBlank()) {
                        "参数：${block.arguments}\n结果：${block.result}"
                    } else block.step.detail
                    WorkStepRow(block.step.copy(detail = detail), actions.onRetryStep)
                }
            }
        }
    }
}

@Composable
internal fun MessageImage(image: MessageImageUi, number: Int, loader: MessageImageLoader?) = key(image, loader) {
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
    Box(Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(12.dp))
        .background(MiuixTheme.colorScheme.surfaceVariant).testTag("message-image:$number"),
        contentAlignment = Alignment.Center) {
        when (val current = result) {
            MessageImageLoadResult.Loading -> Text("图片加载中…", modifier = Modifier.padding(12.dp),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            MessageImageLoadResult.Unavailable -> Text("图片不可用", modifier = Modifier.padding(12.dp)
                .testTag("message-image-unavailable:$number"), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            is MessageImageLoadResult.Ready -> Image(current.bitmap, contentDescription = "消息图片 $number",
                modifier = Modifier.fillMaxWidth().height(180.dp), contentScale = ContentScale.Fit)
        }
    }
}
