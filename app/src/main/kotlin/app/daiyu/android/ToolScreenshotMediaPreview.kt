package app.daiyu.android

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asImageBitmap
import app.daiyu.android.ui.media.ChatMediaPreviewItem
import app.daiyu.android.ui.media.ChatMediaPreviewOriginal
import app.daiyu.android.ui.screens.chat.conversation.WorkStepOwnerUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.Closeable
import kotlin.coroutines.resume

internal data class ToolScreenshotOriginal(val bytes: ByteArray, val mimeType: String)

/** The ordinary viewer consumes exact receipt bytes; it never owns the card's cached bitmap. */
internal class ToolScreenshotMediaPreview(
    key: String,
    private val mimeType: String,
    private val isCurrent: () -> Boolean,
    private val readOriginal: suspend () -> ToolScreenshotOriginal?,
) : Closeable {
    private val originals = MessageMediaOriginals()
    private val displayName = if (mimeType == "image/png") "屏幕截图.png" else "浏览器截图.jpg"
    private fun current() = originals.isOpen() && isCurrent()
    private suspend fun original(): ToolScreenshotOriginal? {
        currentCoroutineContext().ensureActive()
        if (!current()) return null
        val source = readOriginal() ?: return null
        currentCoroutineContext().ensureActive()
        return source.takeIf { current() && it.mimeType == mimeType }
    }

    val item = ChatMediaPreviewItem(key, mimeType, displayName,
        loadImage = { edge ->
            original()?.let { source ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(source.bytes, 0, source.bytes.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null else {
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > edge.coerceIn(1, 4096)) sample *= 2
                    val decoded = BitmapFactory.decodeByteArray(source.bytes, 0, source.bytes.size,
                        BitmapFactory.Options().apply { inSampleSize = sample })
                    if (!current() || !currentCoroutineContext().isActive) { decoded?.recycle(); null }
                    else decoded?.asImageBitmap()
                }
            }
        },
        openOriginal = {
            original()?.let { source ->
                ChatMediaPreviewOriginal(originals.retain(source.bytes.inputStream()), mimeType, displayName)
            }
        },
    )
    override fun close() = originals.close()
}

internal suspend fun ChatController.readToolScreenshotOriginal(owner: WorkStepOwnerUi, callId: String,
    dataRevision: Long, screenshotRevision: Long): ToolScreenshotOriginal? = withContext(Dispatchers.Main.immediate) {
    suspendCancellableCoroutine { continuation ->
        if (!loadToolScreenshotOriginal(owner, callId, dataRevision, screenshotRevision) { source ->
            if (continuation.isActive) continuation.resume(source)
        }) continuation.resume(null)
    }
}
