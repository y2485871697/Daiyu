package app.daiyu.android.media

import org.json.JSONObject

/** Host-assigned persisted message identity, never a URI, filename or active-conversation lookup. */
data class MessageImageOwner(val conversationId: String, val messageId: String) {
    init {
        require(conversationId.isNotBlank() && conversationId.length <= 200)
        require(messageId.isNotBlank() && messageId.length <= 200)
    }
    internal fun directoryName(): String = imageDigest(
        "message:${conversationId.length}:$conversationId${messageId.length}:$messageId".toByteArray(Charsets.UTF_8)
    )
}

/** Immutable value to persist in the SAME DB transaction as the accepted message/receipt.
 * No draft path, provider grant, bitmap or mutable bytes escape into history.
 */
data class MessageImageReference(
    val owner: MessageImageOwner,
    val id: String,
    val mime: String,
    val bytes: Int,
    val sha256: String,
    val width: Int,
    val height: Int,
) {
    fun toJson(): String {
        validateImageMetadata(asAttachment(), CODEC_LIMITS)
        return json().toString()
    }

    internal fun json(): JSONObject = JSONObject().put("version", 1)
        .put("conversation", owner.conversationId).put("message", owner.messageId)
        .put("id", id).put("mime", mime).put("bytes", bytes).put("sha256", sha256)
        .put("width", width).put("height", height)

    // Model input retains the existing frozen-byte API, but uses a message-derived synthetic
    // owner, never a live draft reference. This value must not be passed to the draft store.
    internal fun asAttachment(): ImageAttachment = ImageAttachment(
        ImageAttachmentOwner(owner.conversationId, owner.directoryName()), id, mime, bytes, sha256, width, height
    )

    companion object {
        private val CODEC_LIMITS = ImageAttachmentLimits(maxImageBytes = 20 * 1024 * 1024)
        fun fromJson(encoded: String): MessageImageReference {
            try {
                if (encoded.length > 4096) throw ImageAttachmentException(ImageAttachmentFailure.CORRUPT)
                return decode(JSONObject(encoded))
            } catch (failure: Exception) {
                if (failure is ImageAttachmentException) throw failure
                throw ImageAttachmentException(ImageAttachmentFailure.CORRUPT, failure)
            }
        }
        internal fun decode(json: JSONObject): MessageImageReference {
            if (json.getInt("version") != 1) throw ImageAttachmentException(ImageAttachmentFailure.CORRUPT)
            return MessageImageReference(
                MessageImageOwner(json.getString("conversation"), json.getString("message")),
                json.getString("id"), json.getString("mime"), json.getInt("bytes"),
                json.getString("sha256"), json.getInt("width"), json.getInt("height")
            ).also { validateImageMetadata(it.asAttachment(), CODEC_LIMITS) }
        }
    }
}

/** Capability exposed to history/preview/model consumers: no import, publish or deletion API. */
interface MessageImageResolver {
    fun read(owner: MessageImageOwner, reference: MessageImageReference): ByteArray
    fun freeze(
        owner: MessageImageOwner,
        references: List<MessageImageReference>,
        imageInputSupported: Boolean,
        isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
    ): List<FrozenImageAttachment>
}
