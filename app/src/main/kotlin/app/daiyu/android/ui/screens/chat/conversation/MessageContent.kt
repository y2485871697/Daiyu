package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.semantics.Role
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.basic.TextButton
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
    textBody: @Composable (String, Modifier) -> Unit = { text, modifier -> MessageMarkdown(text, modifier, markdown) },
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        var imageNumber = 0
        content.forEachIndexed { index, block ->
            val itemKey = if (block is MessageContentUi.Text) block.itemId else index
            key(itemKey) {
                when (block) {
                    is MessageContentUi.Text -> {
                        if (block.text.isNotBlank()) textBody(block.text, Modifier.testTag("message-content-text:$index"))
                    }
                    is MessageContentUi.Image -> MessageImage(block.image, ++imageNumber, imageLoader)
                    is MessageContentUi.Tool -> {
                        key(block.step.owner, block.step.toolCallId) {
                            if (!dedicatedTool(block.step)) WorkStepRow(block.step, toolContent = toolContent)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun MessageImage(image: MessageImageUi, number: Int, loader: MessageImageLoader?) = key(image, loader) {
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
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Image(ready.bitmap, contentDescription = "放大消息图片 $number",
                    modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp).testTag("message-image-expanded:$number"),
                    contentScale = ContentScale.Fit)
                Text("已校验的消息图片 · 放大不会重新读取或上传", style = MiuixTheme.textStyles.footnote1)
                TextButton(text = "关闭", onClick = { expanded = false }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(12.dp))
        .background(MiuixTheme.colorScheme.surfaceVariant).testTag("message-image:$number")
        .clickable(enabled = ready != null, role = Role.Button, onClickLabel = "放大图片") { expanded = true },
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
