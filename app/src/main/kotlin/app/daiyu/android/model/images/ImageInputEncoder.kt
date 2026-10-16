package app.daiyu.android.model.images

import app.daiyu.android.media.AndroidImageInspector
import app.daiyu.android.media.FrozenImageAttachment
import app.daiyu.android.media.ImageAttachmentException
import app.daiyu.android.media.ImageAttachmentFailure
import app.daiyu.android.media.ImageAttachmentLimits
import app.daiyu.android.media.ImageInspector
import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.model.UnsupportedProviderException
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64

/** Encodes original frozen bytes, never a URI, preview or mutable draft. */
class ImageInputEncoder(
    private val inspector: ImageInspector = AndroidImageInspector(),
    private val limits: ImageAttachmentLimits = ImageAttachmentLimits(),
) {
    fun encode(
        protocol: ProviderProtocol,
        images: List<FrozenImageAttachment>,
        imageInputSupported: Boolean?,
        role: String = "user",
        isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
    ): List<JSONObject> {
        checkCancelled(isCancelled)
        if (protocol == ProviderProtocol.UNKNOWN) throw UnsupportedProviderException(protocol.wireName)
        val frozen = images.toList()
        requireSupported(frozen.isNotEmpty(), imageInputSupported)
        if (frozen.isEmpty()) return emptyList()
        require(role == "user") { "Image input requires user content" }
        checkMetadataLimits(frozen.map { it.attachment.bytes })
        if (frozen.map { it.attachment.owner to it.attachment.id }.toSet().size != frozen.size) {
            fail(ImageAttachmentFailure.CORRUPT)
        }
        val verified = frozen.map { image ->
            checkCancelled(isCancelled)
            val metadata = image.attachment
            if (metadata.mime !in SUPPORTED_MIMES) fail(ImageAttachmentFailure.UNSUPPORTED_IMAGE)
            val bytes = image.bytes()
            if (bytes.size != metadata.bytes || sha256(bytes) != metadata.sha256) fail(ImageAttachmentFailure.CORRUPT)
            val actual = inspector.inspect(bytes)
            if (actual.mime != metadata.mime || actual.width != metadata.width || actual.height != metadata.height) {
                fail(ImageAttachmentFailure.CORRUPT)
            }
            if (actual.width <= 0 || actual.height <= 0) fail(ImageAttachmentFailure.INVALID_IMAGE)
            if (actual.width.toLong() * actual.height > limits.maxPixels) fail(ImageAttachmentFailure.TOO_LARGE)
            if (protocol == ProviderProtocol.MESSAGES &&
                (actual.width > MESSAGES_MAX_EDGE || actual.height > MESSAGES_MAX_EDGE ||
                    base64Length(bytes.size) > MESSAGES_MAX_BASE64_BYTES)) fail(ImageAttachmentFailure.TOO_LARGE)
            Verified(metadata.mime, bytes)
        }
        val blocks = verified.map { image ->
            checkCancelled(isCancelled)
            val data = Base64.getEncoder().encodeToString(image.bytes)
            when (protocol) {
                ProviderProtocol.RESPONSES -> JSONObject().put("type", "input_image")
                    .put("image_url", "data:${image.mime};base64,$data")
                ProviderProtocol.MESSAGES -> JSONObject().put("type", "image")
                    .put("source", JSONObject().put("type", "base64").put("media_type", image.mime).put("data", data))
                ProviderProtocol.UNKNOWN -> throw UnsupportedProviderException(protocol.wireName)
            }
        }
        checkCancelled(isCancelled)
        return blocks
    }

    /** Shared by history preflight and post-read encoding; uses this encoder's exact limits. */
    internal fun checkMetadataLimits(imageBytes: List<Int>) {
        if (imageBytes.size > limits.maxImages) fail(ImageAttachmentFailure.TOO_MANY)
        var totalBytes = 0L
        imageBytes.forEach { bytes ->
            if (bytes <= 0) fail(ImageAttachmentFailure.CORRUPT)
            if (bytes > limits.maxImageBytes) fail(ImageAttachmentFailure.TOO_LARGE)
            totalBytes += bytes.toLong()
            if (totalBytes > limits.maxDraftBytes) fail(ImageAttachmentFailure.TOO_LARGE)
        }
    }

    companion object {
        private val SUPPORTED_MIMES = setOf("image/jpeg", "image/png", "image/webp")
        private const val MESSAGES_MAX_EDGE = 8_000
        private const val MESSAGES_MAX_BASE64_BYTES = 10_000_000L
        fun requireSupported(hasImages: Boolean, imageInputSupported: Boolean?) {
            if (hasImages && imageInputSupported != true) fail(ImageAttachmentFailure.MODEL_UNSUPPORTED)
        }
        private fun base64Length(size: Int): Long = ((size.toLong() + 2) / 3) * 4
        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        private fun checkCancelled(isCancelled: () -> Boolean) {
            if (isCancelled()) fail(ImageAttachmentFailure.CANCELLED)
        }
        private fun fail(failure: ImageAttachmentFailure): Nothing = throw ImageAttachmentException(failure)
    }
    private class Verified(val mime: String, val bytes: ByteArray)
}
