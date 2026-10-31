package app.daiyu.android

import app.daiyu.android.ui.media.ChatMediaPreviewItem
import app.daiyu.android.ui.screens.chat.conversation.ChatTurnUi
import app.daiyu.android.ui.screens.chat.conversation.MessageContentUi
import app.daiyu.android.ui.screens.chat.conversation.messageImagePreviewKey
import app.daiyu.android.ui.screens.chat.conversation.messageVideoPreviewKey
import java.io.Closeable
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/** Route-scoped original streams. Closing the route revokes existing reads as well as new opens. */
internal class MessageMediaOriginals : Closeable {
    private val gate = Any()
    private var open = true
    private val streams = mutableSetOf<InputStream>()
    fun isOpen(): Boolean = synchronized(gate) { open }
    fun retain(source: InputStream): InputStream = synchronized(gate) {
        if (!open) {
            source.close()
            throw IOException("Media page left")
        }
        val wrapped = object : FilterInputStream(source) {
            private var released = false
            private fun authorize() { if (!isOpen()) throw IOException("Media page left") }
            override fun read(): Int { authorize(); return super.read() }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                authorize(); return `in`.read(buffer, offset, length)
            }
            override fun skip(count: Long): Long { authorize(); return super.skip(count) }
            override fun close() {
                synchronized(gate) {
                    if (released) return
                    released = true
                    streams.remove(this)
                }
                super.close()
            }
        }
        streams += wrapped
        wrapped
    }
    override fun close() {
        val closing = synchronized(gate) {
            if (!open) return
            open = false
            streams.toList().also { streams.clear() }
        }
        closing.forEach { try { it.close() } catch (_: IOException) { } }
    }
}

/** Display order, not physical row order or image-only offsets, defines viewer navigation. */
internal fun messageMediaSequence(turns: List<ChatTurnUi>): List<MessageContentUi> = turns.flatMap { turn ->
    when (turn) {
        is ChatTurnUi.User -> turn.content
        is ChatTurnUi.Assistant -> turn.content
    }
}.filter { it is MessageContentUi.Image || it is MessageContentUi.Video }

internal fun chatMediaPreviewGallery(turns: List<ChatTurnUi>, lease: PreviewLease?,
    snapshot: ControllerMessageMediaSnapshot?): List<ChatMediaPreviewItem> {
    if (lease == null || !lease.isOpen()) return emptyList()
    return messageMediaSequence(turns).mapNotNull { item ->
        when (item) {
            is MessageContentUi.Image -> {
                val image = item.image
                if (image.expectedOwner != image.reference.owner ||
                    image.expectedOwner.conversationId != lease.conversation ||
                    snapshot?.containsImage(image) != true) null
                else ChatMediaPreviewItem(messageImagePreviewKey(image), image.reference.mime,
                    messageMediaDisplayName("image", image.reference.mime),
                    loadImage = { edge -> lease.loadDisplay(image, edge.coerceIn(1, 4096)) },
                    openOriginal = { lease.openImageOriginal(image) })
            }
            is MessageContentUi.Video -> {
                val video = item.video
                val binding = snapshot?.takeIf { it.conversation.value == lease.conversation }?.binding(video)
                if (binding == null || video.expectedOwner != video.reference.owner) null
                else ChatMediaPreviewItem(messageVideoPreviewKey(video), video.reference.mime,
                    messageMediaDisplayName("video", video.reference.mime), loadImage = { null },
                    openOriginal = { lease.openVideoOriginal(binding) })
            }
            else -> null
        }
    }.distinctBy { it.key }
}

internal fun messageMediaDisplayName(kind: String, mime: String): String = "$kind." + when (mime) {
    "image/jpeg" -> "jpg"
    "image/png" -> "png"
    "image/webp" -> "webp"
    "image/gif" -> "gif"
    "video/mp4" -> "mp4"
    "video/webm" -> "webm"
    "video/quicktime" -> "mov"
    else -> "bin"
}
