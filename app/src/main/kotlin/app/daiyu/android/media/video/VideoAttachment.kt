package app.daiyu.android.media.video

import java.io.File
import java.security.MessageDigest

/** Host identities only. Neither a provider URI nor a file path is retained in a reference. */
sealed interface VideoOwner { val conversationId: String }

data class VideoAttachmentOwner(override val conversationId: String, val draftId: String) : VideoOwner {
    init { validateOwner(conversationId, draftId) }
}

data class MessageVideoOwner(override val conversationId: String, val messageId: String) : VideoOwner {
    init { validateOwner(conversationId, messageId) }
}

sealed interface VideoReference {
    val owner: VideoOwner
    val id: String
    val mime: String
    val bytes: Long
    val sha256: String
    val durationMs: Long
    val width: Int
    val height: Int
}

data class VideoAttachment(
    override val owner: VideoAttachmentOwner,
    override val id: String,
    override val mime: String,
    override val bytes: Long,
    override val sha256: String,
    override val durationMs: Long,
    override val width: Int,
    override val height: Int,
) : VideoReference

/** Persist with the accepted message in the host's transaction; not an image/model input. */
data class MessageVideoReference(
    override val owner: MessageVideoOwner,
    override val id: String,
    override val mime: String,
    override val bytes: Long,
    override val sha256: String,
    override val durationMs: Long,
    override val width: Int,
    override val height: Int,
) : VideoReference

/** The JPEG is a derivative, NEVER a replacement for the video's original bytes or MIME. */
class VideoPreview internal constructor(
    val original: VideoReference,
    val width: Int,
    val height: Int,
    jpeg: ByteArray,
) {
    private val content = jpeg.copyOf()
    val mime: String = "image/jpeg"
    val bytes: Int get() = content.size
    val sha256: String = videoDigest(content)
    fun bytes(): ByteArray = content.copyOf()
}

data class VideoAttachmentLimits(
    val maxVideoBytes: Long = 64L * 1024 * 1024,
    val maxOwnerBytes: Long = 128L * 1024 * 1024,
    val maxVideos: Int = 4,
    val maxPixels: Long = 40_000_000,
    val maxDurationMs: Long = 60L * 60 * 1000,
) {
    init {
        require(maxVideoBytes in 1..(256L * 1024 * 1024))
        require(maxOwnerBytes in 1..(512L * 1024 * 1024))
        require(maxVideos in 1..8)
        require(maxPixels in 1..40_000_000)
        require(maxDurationMs in 1..(24L * 60 * 60 * 1000))
    }
}

enum class VideoAttachmentFailure {
    SOURCE_UNAVAILABLE, UNSUPPORTED_VIDEO, INVALID_VIDEO, TOO_LARGE, TOO_MANY,
    MISSING, CORRUPT, OWNER_MISMATCH, STORAGE, CANCELLED, PREVIEW_UNAVAILABLE,
}

class VideoAttachmentException(val failure: VideoAttachmentFailure, cause: Throwable? = null) :
    RuntimeException("Video attachment: ${failure.name}", cause)

internal data class VideoMetadata(val mime: String, val durationMs: Long, val width: Int, val height: Int)
internal fun interface VideoInspector {
    fun inspect(file: File, limits: VideoAttachmentLimits, isCancelled: () -> Boolean): VideoMetadata
}

internal fun videoFail(failure: VideoAttachmentFailure): Nothing = throw VideoAttachmentException(failure)
internal fun checkVideoCancellation(cancel: () -> Boolean) {
    if (cancel()) videoFail(VideoAttachmentFailure.CANCELLED)
}
internal fun videoDigest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
internal fun validateVideo(value: VideoMetadata, limits: VideoAttachmentLimits) {
    if (value.mime !in VIDEO_MIMES) videoFail(VideoAttachmentFailure.UNSUPPORTED_VIDEO)
    if (value.durationMs <= 0 || value.width <= 0 || value.height <= 0) videoFail(VideoAttachmentFailure.INVALID_VIDEO)
    if (value.durationMs > limits.maxDurationMs || value.width.toLong() * value.height > limits.maxPixels) {
        videoFail(VideoAttachmentFailure.TOO_LARGE)
    }
}
internal val VIDEO_MIMES = setOf("video/mp4", "video/quicktime", "video/3gpp", "video/3gpp2", "video/webm", "video/x-matroska")
internal val VIDEO_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
internal val VIDEO_HASH = Regex("[0-9a-f]{64}")
internal fun VideoReference.metadata() = VideoMetadata(mime, durationMs, width, height)
internal fun VideoOwner.directoryName(): String {
    val (kind, local) = when (this) {
        is VideoAttachmentOwner -> "draft-video" to draftId
        is MessageVideoOwner -> "message-video" to messageId
    }
    return videoDigest("$kind:${conversationId.length}:$conversationId${local.length}:$local".toByteArray(Charsets.UTF_8))
}
private fun validateOwner(conversation: String, local: String) {
    require(conversation.isNotBlank() && conversation.length <= 200)
    require(local.isNotBlank() && local.length <= 200)
}
