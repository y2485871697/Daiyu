package app.daiyu.android.media

import java.security.MessageDigest

/** A draft identity is supplied by the host, never taken from a picked URI. */
data class ImageAttachmentOwner(val conversationId: String, val draftId: String) {
    init {
        require(conversationId.isNotBlank() && conversationId.length <= 200)
        require(draftId.isNotBlank() && draftId.length <= 200)
    }
    internal fun directoryName(): String = imageDigest(
        "${conversationId.length}:$conversationId${draftId.length}:$draftId".toByteArray(Charsets.UTF_8)
    )
}

data class ImageAttachment(
    val owner: ImageAttachmentOwner,
    val id: String,
    val mime: String,
    val bytes: Int,
    val sha256: String,
    val width: Int,
    val height: Int,
)

data class ImageDimensions(val mime: String, val width: Int, val height: Int)

fun interface ImageInspector {
    fun inspect(bytes: ByteArray): ImageDimensions
}

/** All bytes are frozen before the host starts a request; consumers cannot mutate the snapshot. */
class FrozenImageAttachment internal constructor(val attachment: ImageAttachment, bytes: ByteArray) {
    private val content = bytes.copyOf()
    fun bytes(): ByteArray = content.copyOf()
}

data class ImageAttachmentLimits(
    val maxImageBytes: Int = 10 * 1024 * 1024,
    val maxPixels: Long = 40_000_000,
    val maxImages: Int = 8,
    val maxDraftBytes: Long = 24L * 1024 * 1024,
) {
    init {
        require(maxImageBytes in 1..(20 * 1024 * 1024))
        require(maxPixels in 1..40_000_000)
        require(maxImages in 1..8)
        require(maxDraftBytes in 1..(64L * 1024 * 1024))
    }
}

enum class ImageAttachmentFailure {
    SOURCE_UNAVAILABLE, UNSUPPORTED_IMAGE, INVALID_IMAGE, TOO_LARGE, TOO_MANY,
    MISSING, CORRUPT, OWNER_MISMATCH, STORAGE, CANCELLED, MODEL_UNSUPPORTED,
}

class ImageAttachmentException(val failure: ImageAttachmentFailure, cause: Throwable? = null) :
    RuntimeException("Image attachment: ${failure.name}", cause)

internal fun imageDigest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

internal fun checkImageCancellation(isCancelled: () -> Boolean) {
    if (isCancelled()) throw ImageAttachmentException(ImageAttachmentFailure.CANCELLED)
}
