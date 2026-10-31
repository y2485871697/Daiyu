package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.layout.ContentScale
import app.daiyu.android.ui.screens.chat.attachments.ComposerImageAttachmentChip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.daiyu.android.media.video.MessageVideoReference
import app.daiyu.android.ui.app.LocalAppearanceDark
import app.daiyu.android.ui.app.resolveAppearanceActions
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ComposerCancelEdit(enabled: Boolean, onCancel: () -> Unit) {
    val colors = resolveAppearanceActions(LocalAppearanceDark.current)
    Box(
        Modifier.widthIn(min = 72.dp).heightIn(min = 40.dp)
            .testTag("message-edit-cancel")
            .conversationClickable(enabled = enabled, onClickLabel = "取消编辑", onClick = onCancel)
            .padding(vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.widthIn(min = 72.dp).heightIn(min = 26.dp)
                .testTag("message-edit-cancel-background")
                .clip(RoundedCornerShape(12.dp)).background(colors.secondaryContainer)
                .padding(horizontal = 8.dp, vertical = 3.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("取消编辑", color = colors.onSecondary,
                style = MiuixTheme.textStyles.footnote1.copy(fontSize = 14.sp, lineHeight = 20.sp,
                    platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                    lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
                        androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
                        androidx.compose.ui.text.style.LineHeightStyle.Trim.None)),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A bounded, read-only historical thumbnail, not a draft asset or a message bubble. */
@Composable
private fun ComposerEditImageThumbnail(image: MessageImageUi, number: Int, loader: MessageImageLoader?) {
    val result by produceState<MessageImageLoadResult>(MessageImageLoadResult.Loading, image, loader) {
        value = MessageImageLoadResult.Loading
        value = withContext(Dispatchers.IO) { loadMessageImage(image, loader, 256) }
    }
    when (val current = result) {
        is MessageImageLoadResult.Ready -> Image(current.bitmap, "消息图片 $number",
            Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        MessageImageLoadResult.Loading -> Text("…", style = MiuixTheme.textStyles.footnote1,
            modifier = Modifier.testTag("message-edit-image-loading:$number").semantics { contentDescription = "图片加载中" })
        MessageImageLoadResult.Unavailable -> Text("!", style = MiuixTheme.textStyles.footnote1,
            modifier = Modifier.testTag("message-edit-image-unavailable:$number").semantics { contentDescription = "图片不可用" })
    }
}

/** Borrow message-owned references for display only; never hand them to deletable draft hosts. */
@Composable
internal fun ComposerEditAttachments(images: List<MessageContentUi.Image>, videos: List<MessageVideoReference>,
    loader: MessageImageLoader?, enabled: Boolean = false,
    onRemoveImage: (MessageContentUi.Image) -> Unit = {}) {
    if (images.isEmpty() && videos.isEmpty()) return
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
        .padding(horizontal = 14.dp, vertical = 8.dp).testTag("message-edit-attachments"),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        images.forEachIndexed { index, item ->
            key(item.image, loader) {
                val reference = item.image.reference
                val number = index + 1
                ComposerImageAttachmentChip(number,
                    "图片 $number · ${reference.width} × ${reference.height}",
                    removable = enabled, onRemove = { onRemoveImage(item) },
                    modifier = Modifier.testTag("message-edit-image:$index"),
                    thumbnailModifier = Modifier.testTag("message-edit-image-thumbnail:$index"),
                    removeModifier = Modifier.testTag("message-edit-image-remove:$index"),
                ) { ComposerEditImageThumbnail(item.image, number, loader) }
            }
        }
        videos.forEachIndexed { index, video ->
            Column(Modifier.widthIn(min = 96.dp, max = 144.dp).heightIn(min = 72.dp)
                .clip(RoundedCornerShape(12.dp)).background(MiuixTheme.colorScheme.surfaceVariant)
                .padding(10.dp).testTag("message-edit-video:$index"),
                verticalArrangement = Arrangement.Center) {
                Text("视频 ${index + 1}", style = MiuixTheme.textStyles.footnote1)
                Text("${video.width} × ${video.height} · ${video.durationMs / 1000}s",
                    style = MiuixTheme.textStyles.footnote1.copy(fontSize = 11.sp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
    }
}
