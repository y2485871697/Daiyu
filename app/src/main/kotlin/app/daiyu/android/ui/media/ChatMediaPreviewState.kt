package app.daiyu.android.ui.media

import androidx.compose.ui.geometry.Offset

internal const val CHAT_MEDIA_PREVIEW_MIN_ZOOM = 1f
internal const val CHAT_MEDIA_PREVIEW_MAX_ZOOM = 5f
internal const val CHAT_MEDIA_PREVIEW_DISPLAY_EDGE = 4096

/** Keys are opaque owner identifiers, not URLs or paths; never rewrite them. */
internal fun chatMediaPreviewGallery(gallery: List<ChatMediaPreviewItem>): List<ChatMediaPreviewItem> =
    gallery.filter { it.key.isNotBlank() }.distinctBy { it.key }

internal data class ChatMediaPreviewSelection(
    val gallery: List<ChatMediaPreviewItem>,
    val initialIndex: Int,
)

internal fun chatMediaPreviewSelection(
    gallery: List<ChatMediaPreviewItem>,
    key: String,
): ChatMediaPreviewSelection? {
    val items = chatMediaPreviewGallery(gallery)
    val index = items.indexOfFirst { it.key == key }
    // An unknown key must not acquire a different item's authorized callbacks.
    return if (index < 0) null else ChatMediaPreviewSelection(items, index)
}

internal data class ChatMediaPreviewTransform(
    val scale: Float = CHAT_MEDIA_PREVIEW_MIN_ZOOM,
    val offset: Offset = Offset.Zero,
)

internal fun chatMediaPreviewTransform(
    previous: ChatMediaPreviewTransform,
    zoom: Float,
    pan: Offset,
): ChatMediaPreviewTransform {
    val nextScale = (previous.scale * zoom).coerceIn(CHAT_MEDIA_PREVIEW_MIN_ZOOM, CHAT_MEDIA_PREVIEW_MAX_ZOOM)
    return ChatMediaPreviewTransform(
        scale = nextScale,
        offset = if (nextScale <= CHAT_MEDIA_PREVIEW_MIN_ZOOM) Offset.Zero else previous.offset + pan,
    )
}

internal fun chatMediaPreviewConsumesGesture(scale: Float, pointerCount: Int): Boolean =
    pointerCount >= 2 || scale > CHAT_MEDIA_PREVIEW_MIN_ZOOM

internal fun chatMediaPreviewPagerEnabled(scale: Float, transforming: Boolean, count: Int): Boolean =
    count > 1 && scale <= CHAT_MEDIA_PREVIEW_MIN_ZOOM && !transforming

internal fun chatMediaPreviewVideoShouldPlay(active: Boolean, resumed: Boolean): Boolean = active && resumed
