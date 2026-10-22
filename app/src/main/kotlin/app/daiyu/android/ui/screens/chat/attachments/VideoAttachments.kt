package app.daiyu.android.ui.screens.chat.attachments

import android.graphics.BitmapFactory
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import app.daiyu.android.ui.haptics.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import app.daiyu.android.ui.icons.DaiyuIcons
import app.daiyu.android.ui.screens.chat.conversation.ChatIconButton
import app.daiyu.android.ui.screens.chat.conversation.chatChromeColors
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.daiyu.android.ui.components.DaiyuDialog as Dialog
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.daiyu.android.VideoChatHost
import app.daiyu.android.VideoDraftReceipt
import app.daiyu.android.VideoDraftRequest
import app.daiyu.android.media.video.VideoAttachmentFailure
import app.daiyu.android.videoReceiptMatches
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import app.daiyu.android.ui.haptics.miuix.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** The parent's editor identity, distinct from the host's video revision. Frozen at confirmation. */
internal data class VideoDraftText(val text: String, val editorGeneration: Long, val revision: Long)

/** The parent must deliver authoritative receipts on the host/main thread, even after route exit.
 * Returning true means this exact receipt settled, NOT that the outcome was ACCEPTED.
 * No editor mutation callback is supplied: only the parent may consume an accepted text revision.
 */
internal typealias VideoDraftHandoff = (VideoDraftRequest, VideoDraftText, (VideoDraftReceipt) -> Boolean) -> Unit

/**
 * Controlled P18 composer adjunct. Pass the ONE long-lived parent-owned VideoChatHost; never create
 * a host/journal writer in composition. Mount only for the active chat route (not merely hidden).
 * [viewLifecycleOwner] must be the route view lifecycle (Activity in a Compose-only route, Fragment
 * viewLifecycleOwner in a Fragment). Unless [manageLifecycle] is false, this component owns the
 * lifecycle binding. A parent-managed binding must live outside keyed composer subcompositions.
 *
 * [enabled] is the parent's media-route gate (e.g. no image draft); [parentBusy] gates actions without
 * touching normal text/image send routes. No network, DB, URI persistence, or editor clearing occurs.
 */
@Composable
internal fun VideoAttachments(
    host: VideoChatHost,
    conversation: String?,
    text: VideoDraftText,
    onHandoff: VideoDraftHandoff,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    parentBusy: Boolean = false,
    viewLifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
    compact: Boolean = false,
    manageLifecycle: Boolean = true,
    pickRequest: Any? = null,
    onControls: (VideoComposerControls?) -> Unit = {},
) {
    val registry = checkNotNull(LocalActivityResultRegistryOwner.current).activityResultRegistry
    val picker = remember(host, registry, conversation, viewLifecycleOwner) { VideoPickerBinding(host, registry) }
    DisposableEffect(host, picker, conversation, viewLifecycleOwner, manageLifecycle) {
        val binding = if (manageLifecycle) {
            host.enter(conversation)
            host.bindLifecycle(viewLifecycleOwner)
        } else null
        onDispose {
            binding?.close()
            picker.close()
        }
    }
    val state = host.state
    // Never display previous route's items before the lifecycle effect has entered the new slot.
    if (state.conversation != conversation) return
    key(host, state.owner, state.visit) {
        VideoAttachmentsContent(host, state, text, onHandoff, picker, modifier, enabled && !parentBusy, compact, pickRequest, onControls)
    }
}

internal data class VideoComposerControls(
    val canSubmit: Boolean,
    val blocked: Boolean,
    val select: () -> Unit,
    val send: () -> Unit,
)

private data class VideoConfirmation(val snapshot: VideoChatHost.Snapshot, val text: VideoDraftText)

@Composable
private fun VideoAttachmentsContent(
    host: VideoChatHost,
    state: VideoChatHost.Snapshot,
    text: VideoDraftText,
    onHandoff: VideoDraftHandoff,
    picker: VideoPickerBinding,
    modifier: Modifier,
    enabled: Boolean,
    compact: Boolean,
    pickRequest: Any?,
    onControls: (VideoComposerControls?) -> Unit,
) {
    var confirmation by remember { mutableStateOf<VideoConfirmation?>(null) }
    var cover by remember { mutableStateOf<VideoChatHost.Item.Ready?>(null) }
    val canEdit = enabled && state.visible && !state.fenced && !state.storageFailed && !state.recovering
    val controls by rememberUpdatedState(onControls)
    val canSelect = canEdit && !picker.selecting && state.remaining > 0 && state.items.none { it is VideoChatHost.Item.Importing }
    val canSubmit = enabled && state.canSubmit && !picker.selecting
    DisposableEffect(host, picker, state, text, enabled, picker.selecting, pickRequest) {
        var live = true
        controls(VideoComposerControls(canSubmit,
            picker.selecting || state.fenced || state.storageFailed || state.recovering ||
                state.items.isNotEmpty() && !canSubmit,
            select = { if (live && canSelect && sameVideoVisit(state, host.state)) picker.launch(state) },
            send = { if (live && canSubmit && sameVideoVisit(state, host.state)) confirmation = VideoConfirmation(state, text) }))
        onDispose { live = false; controls(null) }
    }
    val colors = MiuixTheme.colorScheme
    val hasStatus = picker.selecting || picker.notice != null || state.storageFailed || state.fenced
    if (!compact || state.items.isNotEmpty() || hasStatus) Column(modifier.fillMaxWidth().then(if (compact) Modifier.padding(horizontal = 13.dp, vertical = 6.dp) else Modifier).testTag("video_attachments"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!compact) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("原视频附件（${state.items.size}/4）", modifier = Modifier.weight(1f),
                style = MiuixTheme.textStyles.footnote1, color = colors.onSurfaceVariantSummary)
            TextButton(text = "选择视频", onClick = { picker.launch(state) },
                enabled = canEdit && !picker.selecting && state.remaining > 0 && state.items.none { it is VideoChatHost.Item.Importing },
                modifier = Modifier.heightIn(min = 48.dp).testTag("video_attachments_select"))
        }
        if (!compact) Text("发送的是原视频，不是封面图片；仅上传确认后交给父级处理。",
            style = MiuixTheme.textStyles.footnote1, color = colors.onSurfaceVariantSummary)
        if (picker.selecting) VideoStatus("正在选择视频…", "video_attachments_selecting")
        picker.notice?.let { VideoStatus(it, "video_attachments_notice") }
        if (state.storageFailed) VideoStatus("视频草稿保存失败，已停止操作；请勿重复发送", "video_attachments_storage_failed")
        if (state.fenced) VideoStatus("正在确认视频发送结果，请勿重复发送", "video_attachments_fenced")
        if (!compact && state.items.isEmpty()) Text("尚未选择视频", style = MiuixTheme.textStyles.footnote1)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.items.forEachIndexed { index, item ->
                key(state.owner, state.visit, state.revision, item.key) {
                    if (compact) CompactVideoAttachment(host, state, item, index, canEdit && !picker.selecting,
                        onPreview = { if (sameVideoVisit(state, host.state)) cover = item as? VideoChatHost.Item.Ready })
                    else Column(Modifier.width(168.dp).clip(RoundedCornerShape(12.dp))
                        .background(colors.surfaceContainerHigh).testTag("video_attachments_item:${item.key}")) {
                        Box(Modifier.fillMaxWidth().height(104.dp).then(
                            if (item is VideoChatHost.Item.Ready && state.visible) Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() }, indication = null,
                                role = Role.Button, onClickLabel = "查看第 ${index + 1} 个视频的封面",
                                onClick = { if (sameVideoVisit(state, host.state)) cover = item },
                            ) else Modifier,
                        ).testTag("video_attachments_thumbnail:${item.key}"), contentAlignment = Alignment.Center) {
                            when (item) {
                                is VideoChatHost.Item.Importing -> VideoStatus("正在导入原视频…", "video_attachments_importing:${item.key}")
                                is VideoChatHost.Item.Failed -> VideoStatus(videoFailureText(item.failure), "video_attachments_failed:${item.key}")
                                is VideoChatHost.Item.Ready -> VideoCover(host, state, item, 256)
                            }
                        }
                        if (item is VideoChatHost.Item.Ready) {
                            Text("原视频 · ${item.video.mime}\n${item.video.width} × ${item.video.height} · ${item.video.durationMs / 1000} 秒\n${item.video.bytes} 字节",
                                modifier = Modifier.padding(8.dp), style = MiuixTheme.textStyles.footnote1)
                            VideoStatus("已就绪", "video_attachments_ready:${item.key}")
                        }
                        TextButton(text = "移除第 ${index + 1} 项", enabled = canEdit && !picker.selecting,
                            onClick = { host.remove(item.key, state) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .testTag("video_attachments_remove:${item.key}"))
                    }
                }
            }
        }
        if (!compact) TextButton(text = "发送原视频…", enabled = enabled && state.canSubmit && !picker.selecting,
            onClick = { confirmation = VideoConfirmation(state, text) },
            modifier = Modifier.heightIn(min = 48.dp).testTag("video_attachments_send"))
    }
    cover?.takeIf { state.visible && state.items.contains(it) }?.let { item ->
        Dialog(onDismissRequest = { cover = null }) {
            Column(Modifier.fillMaxWidth().background(colors.background).padding(16.dp).testTag("video_attachments_preview")) {
                Text("封面预览（非视频播放）", style = MiuixTheme.textStyles.title3)
                Box(Modifier.fillMaxWidth().height(280.dp), contentAlignment = Alignment.Center) {
                    key(state.owner, state.visit, state.revision, item.key) { VideoCover(host, state, item, 1024) }
                }
                TextButton(text = "关闭", onClick = { cover = null }, modifier = Modifier.heightIn(min = 48.dp)
                    .testTag("video_attachments_close_preview"))
            }
        }
    }
    confirmation?.takeIf { sameVideoVisit(it.snapshot, state) && state.canSubmit }?.let { frozen ->
        Dialog(onDismissRequest = { confirmation = null }) {
            Column(Modifier.fillMaxWidth().background(colors.background).padding(16.dp).testTag("video_attachments_confirmation")) {
                Text("确认上传原视频？", style = MiuixTheme.textStyles.title3)
                Text("将上传 ${frozen.snapshot.items.size} 个原视频及以下内容，可能产生流量。")
                // Bound the viewport before scrolling so long frozen captions remain fully readable.
                Text(frozen.text.text, modifier = Modifier.heightIn(max = 120.dp)
                    .verticalScroll(rememberScrollState()).testTag("video_attachments_confirm_text"))
                TextButton(text = "取消", onClick = { confirmation = null }, modifier = Modifier.heightIn(min = 48.dp)
                    .testTag("video_attachments_cancel_confirmation"))
                TextButton(text = "确认上传原视频", enabled = enabled && !picker.selecting,
                    onClick = {
                        confirmation = null
                        host.submit(frozen.text.text, frozen.snapshot) { request ->
                            // This closure retains only the long-lived host and immutable request.
                            // It remains valid after disposal; parent owns async work and main dispatch.
                            onHandoff(request, frozen.text) { receipt ->
                                videoReceiptMatches(request, receipt) && host.settle(receipt)
                            }
                        }
                    }, modifier = Modifier.heightIn(min = 48.dp).testTag("video_attachments_confirm"))
            }
        }
    }
}

@Composable
private fun CompactVideoAttachment(
    host: VideoChatHost,
    state: VideoChatHost.Snapshot,
    item: VideoChatHost.Item,
    index: Int,
    canRemove: Boolean,
    onPreview: () -> Unit,
) {
    val colors = chatChromeColors()
    Row(Modifier.width(188.dp).clip(RoundedCornerShape(12.dp))
        .background(colors.accent.copy(alpha = .08f))
        .testTag("video_attachments_item:${item.key}").padding(4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).then(
            if (item is VideoChatHost.Item.Ready && state.visible) Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null,
                role = Role.Button, onClickLabel = "查看第 ${index + 1} 个视频的封面", onClick = onPreview,
            ) else Modifier,
        ).testTag("video_attachments_thumbnail:${item.key}"), contentAlignment = Alignment.Center) {
            if (item is VideoChatHost.Item.Ready) VideoCover(host, state, item, 256, compact = true)
            else androidx.compose.material3.Icon(DaiyuIcons.Video, null, Modifier.size(18.dp), tint = colors.accent)
        }
        Text(when (item) {
            is VideoChatHost.Item.Ready -> "视频 ${index + 1} · ${item.video.durationMs / 1000} 秒"
            is VideoChatHost.Item.Importing -> "正在导入原视频…"
            is VideoChatHost.Item.Failed -> videoFailureText(item.failure)
        }, Modifier.weight(1f).testTag(when (item) {
            is VideoChatHost.Item.Ready -> "video_attachments_ready:${item.key}"
            is VideoChatHost.Item.Importing -> "video_attachments_importing:${item.key}"
            is VideoChatHost.Item.Failed -> "video_attachments_failed:${item.key}"
        }), fontSize = 10.sp, lineHeight = 14.sp, color = colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        ChatIconButton(DaiyuIcons.Close, "移除第 ${index + 1} 个视频", { host.remove(item.key, state) },
            Modifier.size(24.dp).testTag("video_attachments_remove:${item.key}"),
            enabled = canRemove, iconSize = 12.dp, tint = colors.muted)
    }
}

private fun sameVideoVisit(a: VideoChatHost.Snapshot, b: VideoChatHost.Snapshot): Boolean =
    a.owner == b.owner && a.conversation == b.conversation && a.visit == b.visit && a.revision == b.revision && b.visible

private sealed interface VideoCoverResult {
    data object Loading : VideoCoverResult
    data object Failed : VideoCoverResult
    data class Loaded(val image: ImageBitmap) : VideoCoverResult
}

@Composable
private fun VideoCover(host: VideoChatHost, state: VideoChatHost.Snapshot, item: VideoChatHost.Item.Ready, edge: Int, compact: Boolean = false) {
    var retry by remember { mutableStateOf(0) }
    val result by produceState<VideoCoverResult>(VideoCoverResult.Loading, host, state.owner, state.visit, state.revision, item, edge, retry, state.visible) {
        value = VideoCoverResult.Loading
        // host.preview is a MAIN-thread entry point; it owns the IO/cancellation/identity fence.
        val preview = host.preview(item.video, edge)
        val image = if (preview != null && preview.original == item.video && preview.width in 1..edge && preview.height in 1..edge) {
            withContext(Dispatchers.IO) {
                val bytes = preview.bytes()
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth !in 1..edge || bounds.outHeight !in 1..edge) null
                else BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }
        } else null
        value = if (sameVideoVisit(state, host.state) && host.state.items.contains(item) && image != null)
            VideoCoverResult.Loaded(image) else VideoCoverResult.Failed
    }
    when (val current = result) {
        VideoCoverResult.Loading -> if (compact) androidx.compose.material3.Icon(DaiyuIcons.Video, "正在读取封面…",
            Modifier.size(18.dp).testTag("video_attachments_cover_loading:${item.key}"), tint = chatChromeColors().muted)
        else VideoStatus("正在读取封面…", "video_attachments_cover_loading:${item.key}")
        VideoCoverResult.Failed -> if (compact) ChatIconButton(DaiyuIcons.Retry, "重试封面", { retry++ },
            Modifier.size(24.dp).testTag("video_attachments_cover_failed:${item.key}"), iconSize = 14.dp,
            tint = chatChromeColors().muted)
        else Column(horizontalAlignment = Alignment.CenterHorizontally) {
            VideoStatus("封面不可用，原视频仍保留", "video_attachments_cover_failed:${item.key}")
            TextButton(text = "重试封面", onClick = { retry++ }, modifier = Modifier.heightIn(min = 48.dp))
        }
        is VideoCoverResult.Loaded -> Image(current.image, contentDescription = "原视频的封面（不是视频）",
            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
    }
}

@Composable
private fun VideoStatus(text: String, tag: String) {
    Text(text, modifier = Modifier.padding(4.dp).semantics { liveRegion = LiveRegionMode.Polite }.testTag(tag),
        style = MiuixTheme.textStyles.footnote1.copy(fontSize = 10.sp, lineHeight = 15.sp), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
}

/** Limits are deliberately described as a category: TOO_LARGE also covers duration, dimensions,
 * total owner quota, and the stricter API-26 decode bound, not only the single-file byte count. */
internal fun videoFailureText(failure: VideoAttachmentFailure): String = when (failure) {
    VideoAttachmentFailure.SOURCE_UNAVAILABLE -> "无法读取原视频，请重新选择"
    VideoAttachmentFailure.UNSUPPORTED_VIDEO -> "暂不支持此视频类型、编码或多视频轨道，请选择其他视频"
    VideoAttachmentFailure.INVALID_VIDEO -> "视频无效、损坏或没有可解码的视频轨道"
    VideoAttachmentFailure.TOO_LARGE -> "视频超出大小、总容量、时长或分辨率限制（旧设备限制可能更严格）"
    VideoAttachmentFailure.TOO_MANY -> "视频数量超限，最多 4 个"
    VideoAttachmentFailure.MISSING -> "原视频已丢失，请移除后重新选择"
    VideoAttachmentFailure.CORRUPT -> "原视频校验失败，请移除后重新选择"
    VideoAttachmentFailure.OWNER_MISMATCH -> "视频不属于当前草稿，无法使用"
    VideoAttachmentFailure.STORAGE -> "原视频保存失败，请检查存储空间"
    VideoAttachmentFailure.CANCELLED -> "视频导入已取消，请移除后重新选择"
    VideoAttachmentFailure.PREVIEW_UNAVAILABLE -> "封面不可用，原视频仍保留"
}
