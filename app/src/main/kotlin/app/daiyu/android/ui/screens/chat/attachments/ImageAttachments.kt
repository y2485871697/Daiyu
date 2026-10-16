package app.daiyu.android.ui.screens.chat.attachments

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.daiyu.android.R
import app.daiyu.android.media.ImageAttachment
import app.daiyu.android.media.ImageAttachmentFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Existing host callback pattern: no business work or ActivityResultLauncher lives in this UI. */
data class ImageAttachmentsActions(
    val onSelect: (ImageAttachmentDraft) -> Unit,
    val onPreview: (ImageAttachmentItemKey) -> Unit,
    val onRemove: (ImageAttachmentItemKey) -> Unit,
    val onDismissPreview: (ImageAttachmentItemKey) -> Unit,
)

/**
 * Load app-owned image data, not the original picker URI. Called on Dispatchers.IO, with a bounded
 * edge (256 for thumbnails, 2048 for full preview); may throw. The host/cache owns the ImageBitmap
 * and must not recycle it while displayed. No destructive bitmap disposal is done by this UI.
 */
typealias ImageAttachmentPreviewLoader = suspend (ImageAttachment, Int) -> ImageBitmap

/**
 * Standalone P17 composer adjunct; host collects ImageAttachmentsState.state and supplies actions.
 * Does not send, freeze, import, remove files, launch SAF, or touch any other composer's controls.
 */
@Composable
fun ImageAttachments(
    state: ImageAttachmentsUiState,
    actions: ImageAttachmentsActions,
    loadPreview: ImageAttachmentPreviewLoader,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = MiuixTheme.colorScheme
    Column(modifier.fillMaxWidth().testTag("image_attachments"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.image_attachments_title, state.ready.size, state.maxImages),
                modifier = Modifier.weight(1f), style = MiuixTheme.textStyles.footnote1,
                color = colors.onSurfaceVariantSummary)
            TextButton(text = stringResource(R.string.image_attachments_select),
                onClick = { actions.onSelect(state.draft) }, enabled = enabled && state.canSelect,
                modifier = Modifier.heightIn(min = 48.dp).testTag("image_attachments_select"))
        }
        if (state.selection != null) {
            StatusText(stringResource(R.string.image_attachments_selecting), "image_attachments_selecting")
        }
        if (state.submission != null) {
            StatusText(stringResource(R.string.image_attachments_submitting), "image_attachments_submitting")
        }
        if (state.items.isEmpty()) {
            Text(stringResource(R.string.image_attachments_empty), style = MiuixTheme.textStyles.footnote1,
                color = colors.onSurfaceVariantSummary, modifier = Modifier.padding(bottom = 4.dp))
        } else {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.items.forEachIndexed { index, item ->
                    key(item.key) {
                        AttachmentTile(item, index + 1, enabled && state.canRemove(item.key), actions, loadPreview)
                    }
                }
            }
        }
    }
    state.ready.firstOrNull { it.key == state.preview }?.let { item ->
        key(item.key) { ImageAttachmentPreview(item, actions, loadPreview) }
    }
}

@Composable
private fun AttachmentTile(
    item: ImageAttachmentItem,
    number: Int,
    removable: Boolean,
    actions: ImageAttachmentsActions,
    loadPreview: ImageAttachmentPreviewLoader,
) {
    val colors = MiuixTheme.colorScheme
    val previewLabel = stringResource(R.string.image_attachments_preview_number, number)
    Column(Modifier.width(144.dp).clip(RoundedCornerShape(12.dp)).background(colors.surfaceContainerHigh)
        .testTag("image_attachments_item:${item.key.id}")) {
        val previewModifier = if (item is ImageAttachmentItem.Ready) Modifier.clickable(
            role = Role.Button, onClickLabel = previewLabel, onClick = { actions.onPreview(item.key) },
        ) else Modifier
        Box(Modifier.fillMaxWidth().height(96.dp).then(previewModifier)
            .testTag("image_attachments_thumbnail:${item.key.id}"), contentAlignment = Alignment.Center) {
            when (item) {
                is ImageAttachmentItem.Importing -> StatusText(stringResource(R.string.image_attachments_importing), "image_attachments_importing:${item.key.id}")
                is ImageAttachmentItem.Failed -> StatusText(failureText(item.failure), "image_attachments_failed:${item.key.id}")
                is ImageAttachmentItem.Ready -> AttachmentImage(item, loadPreview, 256, previewLabel, ContentScale.Crop)
            }
        }
        if (item is ImageAttachmentItem.Ready) {
            Text("${item.attachment.width} × ${item.attachment.height}", style = MiuixTheme.textStyles.footnote1,
                color = colors.onSurfaceVariantSummary, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        TextButton(text = stringResource(R.string.image_attachments_remove_number, number),
            onClick = { actions.onRemove(item.key) }, enabled = removable,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("image_attachments_remove:${item.key.id}"))
    }
}

private sealed interface PreviewResult {
    data object Loading : PreviewResult
    data class Loaded(val bitmap: ImageBitmap) : PreviewResult
    data object Failed : PreviewResult
}

@Composable
private fun AttachmentImage(
    item: ImageAttachmentItem.Ready,
    loadPreview: ImageAttachmentPreviewLoader,
    maxEdge: Int,
    description: String,
    contentScale: ContentScale,
    modifier: Modifier = Modifier,
) {
    val loader by rememberUpdatedState(loadPreview)
    var attempt by remember(item.key) { mutableStateOf(0) }
    val result by produceState<PreviewResult>(PreviewResult.Loading, item.key, maxEdge, attempt) {
        value = PreviewResult.Loading
        value = try {
            PreviewResult.Loaded(withContext(Dispatchers.IO) { loader(item.attachment, maxEdge) })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            PreviewResult.Failed
        }
    }
    when (val current = result) {
        PreviewResult.Loading -> StatusText(stringResource(R.string.image_attachments_preview_loading), "image_attachments_preview_loading:${item.key.id}")
        PreviewResult.Failed -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            StatusText(stringResource(R.string.image_attachments_preview_failed), "image_attachments_preview_failed:${item.key.id}")
            TextButton(text = stringResource(R.string.image_attachments_retry_preview),
                onClick = { attempt++ }, modifier = Modifier.heightIn(min = 48.dp))
        }
        is PreviewResult.Loaded -> Image(current.bitmap, contentDescription = description,
            modifier = modifier.fillMaxSize(), contentScale = contentScale)
    }
}

/** Full-screen fitted preview with bounded zoom/pan. Original persisted image is never altered. */
@Composable
private fun ImageAttachmentPreview(
    item: ImageAttachmentItem.Ready,
    actions: ImageAttachmentsActions,
    loadPreview: ImageAttachmentPreviewLoader,
) {
    val colors = MiuixTheme.colorScheme
    var zoom by remember(item.key) { mutableStateOf(1f) }
    var pan by remember(item.key) { mutableStateOf(Offset.Zero) }
    Dialog(onDismissRequest = { actions.onDismissPreview(item.key) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(colors.background).testTag("image_attachments_preview")) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.image_attachments_preview), modifier = Modifier.weight(1f), style = MiuixTheme.textStyles.title3)
                TextButton(text = stringResource(R.string.image_attachments_close),
                    onClick = { actions.onDismissPreview(item.key) }, modifier = Modifier.heightIn(min = 48.dp)
                        .testTag("image_attachments_close_preview"))
            }
            Text(stringResource(R.string.image_attachments_preview_size, item.attachment.width, item.attachment.height),
                modifier = Modifier.padding(horizontal = 16.dp), color = colors.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.footnote1)
            Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .pointerInput(item.key) {
                    detectTransformGestures { _, delta, scale, _ ->
                        zoom = (zoom * scale).coerceIn(1f, 5f)
                        val maxX = size.width * (zoom - 1f) / 2f
                        val maxY = size.height * (zoom - 1f) / 2f
                        pan = Offset((pan.x + delta.x).coerceIn(-maxX, maxX), (pan.y + delta.y).coerceIn(-maxY, maxY))
                    }
                }, contentAlignment = Alignment.Center) {
                AttachmentImage(item, loadPreview, 2048, stringResource(R.string.image_attachments_preview), ContentScale.Fit,
                    Modifier.graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y })
            }
            TextButton(text = stringResource(R.string.image_attachments_reset_zoom),
                onClick = { zoom = 1f; pan = Offset.Zero }, modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp))
        }
    }
}

@Composable
private fun StatusText(text: String, tag: String) {
    Text(text, modifier = Modifier.padding(8.dp).testTag(tag).semantics { liveRegion = LiveRegionMode.Polite },
        style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
}

@Composable
private fun failureText(failure: ImageAttachmentFailure): String = stringResource(when (failure) {
    ImageAttachmentFailure.SOURCE_UNAVAILABLE -> R.string.image_attachments_error_source
    ImageAttachmentFailure.UNSUPPORTED_IMAGE -> R.string.image_attachments_error_type
    ImageAttachmentFailure.INVALID_IMAGE, ImageAttachmentFailure.CORRUPT -> R.string.image_attachments_error_invalid
    ImageAttachmentFailure.TOO_LARGE -> R.string.image_attachments_error_size
    ImageAttachmentFailure.TOO_MANY -> R.string.image_attachments_error_count
    ImageAttachmentFailure.MODEL_UNSUPPORTED -> R.string.image_attachments_error_model
    ImageAttachmentFailure.CANCELLED -> R.string.image_attachments_error_cancelled
    ImageAttachmentFailure.MISSING -> R.string.image_attachments_error_missing
    ImageAttachmentFailure.OWNER_MISMATCH, ImageAttachmentFailure.STORAGE -> R.string.image_attachments_error_storage
})
