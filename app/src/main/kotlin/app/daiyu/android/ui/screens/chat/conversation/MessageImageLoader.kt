package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.ui.graphics.ImageBitmap
import app.daiyu.android.domain.MessageImageOwner
import app.daiyu.android.domain.MessageImageReference
import kotlinx.coroutines.CancellationException

/**
 * Read-only host capability. Return a bounded decoded thumbnail, or null for unavailable assets.
 * UI never opens a URI/path, fetches a URL, or treats reference metadata as proof of ownership.
 *
 * The host MUST independently authorize the persisted conversation/message, resolve using
 * expectedOwner (not reference.owner alone), and verify manifest, digest and image limits.
 * expectedOwner is a lookup constraint, NOT authorization. Do not return unverified bytes.
 * Implementations must be cancellation-cooperative; work is invoked on Dispatchers.IO.
 */
fun interface MessageImageLoader {
    suspend fun load(expectedOwner: MessageImageOwner, reference: MessageImageReference, maxEdge: Int): ImageBitmap?
}

internal sealed interface MessageImageLoadResult {
    data object Loading : MessageImageLoadResult
    data object Unavailable : MessageImageLoadResult
    data class Ready(val bitmap: ImageBitmap) : MessageImageLoadResult
}

internal suspend fun loadMessageImage(
    image: MessageImageUi,
    loader: MessageImageLoader?,
    maxEdge: Int,
): MessageImageLoadResult {
    // Reject self-reported owners that disagree with the actual row/segment before any IO.
    if (loader == null || image.expectedOwner != image.reference.owner || maxEdge <= 0) {
        return MessageImageLoadResult.Unavailable
    }
    return try {
        val bitmap = loader.load(image.expectedOwner, image.reference, maxEdge)
        if (bitmap == null) MessageImageLoadResult.Unavailable else MessageImageLoadResult.Ready(bitmap)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // Never display exception text: resolver failures may contain local paths/URIs.
        MessageImageLoadResult.Unavailable
    }
}
