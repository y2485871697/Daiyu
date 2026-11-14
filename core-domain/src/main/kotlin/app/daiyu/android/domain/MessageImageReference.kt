package app.daiyu.android.domain

/** Persisted message identity, not a draft identity, URI, path or permission grant. */
data class MessageImageOwner(val conversationId: String, val messageId: String) {
    init {
        require(conversationId.isNotBlank() && conversationId.length <= 200)
        require(messageId.isNotBlank() && messageId.length <= 200)
    }
}

/** 消息原图的固定引用，与 media.MessageImageReference 共用 v1 字段和硬上限，资产库可采用更低限制。
 * 这里只存元数据，原件、清单、所有者和摘要由资产解析器核验。
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
    init {
        require(ID.matches(id))
        require(mime in SUPPORTED_MIMES)
        require(bytes in 1..MAX_IMAGE_BYTES)
        require(SHA256.matches(sha256))
        require(width > 0 && height > 0 && width.toLong() * height <= MAX_PIXELS)
    }

    companion object {
        const val MAX_IMAGE_BYTES = 20 * 1024 * 1024
        const val MAX_PIXELS = 40_000_000L
        const val MAX_IMAGES = 8
        const val MAX_MESSAGE_BYTES = 64L * 1024 * 1024
        private val ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val SHA256 = Regex("[0-9a-f]{64}")
        private val SUPPORTED_MIMES = setOf("image/jpeg", "image/png", "image/webp")
    }
}

/** Ownership checks must use the persisted row/segment identity, never the active chat. */
object MessageImages {
    fun validate(message: Message) {
        if (message.blocks.any { it is MessageBlock.Image }) {
            validateBlocks(message.blocks, MessageImageOwner(message.conversationId.value, message.id.value))
        }
        message.historySegments.forEach { segment ->
            if (segment.blocks.any { it is MessageBlock.Image }) {
                validateBlocks(segment.blocks, MessageImageOwner(message.conversationId.value, segment.messageId.value))
            }
        }
    }

    /** A standalone segment has a message identity but not yet its enclosing conversation. */
    fun validateSegment(segment: MessageHistorySegment) {
        val first = segment.blocks.filterIsInstance<MessageBlock.Image>().firstOrNull() ?: return
        validateBlocks(segment.blocks, MessageImageOwner(first.reference.owner.conversationId, segment.messageId.value))
    }

    fun validateBlocks(blocks: List<MessageBlock>, owner: MessageImageOwner) {
        val references = blocks.filterIsInstance<MessageBlock.Image>().map { it.reference }
        require(references.size <= MessageImageReference.MAX_IMAGES)
        require(references.all { it.owner == owner }) { "Image reference belongs to another message" }
        require(references.map { it.id }.distinct().size == references.size) { "Duplicate message image" }
        require(references.sumOf { it.bytes.toLong() } <= MessageImageReference.MAX_MESSAGE_BYTES)
    }
}
