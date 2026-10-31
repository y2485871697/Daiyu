package app.daiyu.android.ui.media

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import java.io.Closeable
import java.io.InputStream

/** Only the authorized owner supplies image decoding and access to original bytes. */
data class ChatMediaPreviewItem(
    val key: String,
    val mimeType: String,
    val displayName: String,
    val loadImage: suspend (Int) -> ImageBitmap?,
    val openOriginal: suspend () -> ChatMediaPreviewOriginal?,
)

class ChatMediaPreviewOriginal(
    val stream: InputStream,
    val mimeType: String,
    val displayName: String,
) : Closeable {
    override fun close() = stream.close()
}

internal val LocalOpenChatMediaPreview = staticCompositionLocalOf<(String) -> Unit> { {} }
