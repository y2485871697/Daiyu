package app.daiyu.android.backup

import app.daiyu.android.context.ArchiveCodec
import app.daiyu.android.context.PrivateBlobs
import app.daiyu.android.domain.Message
import app.daiyu.android.domain.MessageBlock
import app.daiyu.android.domain.MessageImageOwner
import app.daiyu.android.domain.MessageImageReference
import app.daiyu.android.domain.MessageImages
import app.daiyu.android.storage.MessageHistoryCodec
import app.daiyu.android.storage.MessageImageReferenceCodec
import org.json.JSONArray
import org.json.JSONObject

/** Closed, bounded manifest built from actual persisted row/segment identities, never from
 * reference.owner alone. ArchiveCodec remains the authoritative archive parser. */
internal object BackupImageManifest {
    const val MAX_ENTRIES = 50_000
    const val MAX_MANIFEST_BYTES = 4 * 1024 * 1024

    class References {
        private val owners = linkedMapOf<MessageImageOwner, LinkedHashMap<String, MessageImageReference>>()
        private var count = 0

        fun addRows(rows: JSONArray) {
            repeat(rows.length()) { index ->
                val row = rows.getJSONObject(index)
                val conversation = row.getString("conversation_id")
                // The text importer has already validated non-image fields. Do not impose
                // ArchiveCodec's tighter field limits on legacy ordinary text-only rows.
                val encoded = row.getJSONArray("blocks")
                val images = buildList {
                    repeat(encoded.length()) { block ->
                        val value = encoded.getJSONObject(block)
                        if (value.getString("type") == "image") add(MessageImageReferenceCodec.decodeBlock(value))
                    }
                }
                add(conversation, row.getString("id"), images)
                if (row.has("history_segments")) MessageHistoryCodec.decode(row.getJSONArray("history_segments")).forEach {
                    add(conversation, it.messageId.value, it.blocks)
                }
            }
        }

        fun addArchive(messages: List<Message>) {
            messages.forEach { message ->
                MessageImages.validate(message)
                add(message.conversationId.value, message.id.value, message.blocks)
                message.historySegments.forEach { add(message.conversationId.value, it.messageId.value, it.blocks) }
            }
        }

        private fun add(conversation: String, message: String, blocks: List<MessageBlock>) {
            val owner = MessageImageOwner(conversation, message)
            MessageImages.validateBlocks(blocks, owner)
            blocks.filterIsInstance<MessageBlock.Image>().forEach { block ->
                val images = owners.getOrPut(owner) { linkedMapOf() }
                val previous = images.put(block.reference.id, block.reference)
                require(previous == null || previous == block.reference) { "同一图片身份内容冲突" }
                if (previous == null) { count++; require(count <= MAX_ENTRIES) { "图片清单数量超限" } }
                require(images.size <= MessageImageReference.MAX_IMAGES)
                require(images.values.sumOf { it.bytes.toLong() } <= MessageImageReference.MAX_MESSAGE_BYTES)
            }
        }

        fun all(): List<MessageImageReference> = owners.values.flatMap { it.values }
            .sortedWith(compareBy({ it.owner.conversationId }, { it.owner.messageId }, { it.id }))
    }

    fun encode(references: List<MessageImageReference>): JSONArray = JSONArray().also { manifest ->
        require(references.size <= MAX_ENTRIES)
        references.forEach { manifest.put(MessageImageReferenceCodec.encode(it)) }
        require(manifest.toString().toByteArray(Charsets.UTF_8).size <= MAX_MANIFEST_BYTES) { "图片清单大小超限" }
    }

    /** Legacy text-only complete packages omit the manifest. Image reference-only packages do not
     * qualify as legacy-compatible: rejecting them prevents a successful import that silently loses images. */
    fun validate(payload: JSONObject, expected: References, bytes: Map<String, ByteArray>): List<MessageImageReference> {
        val wanted = expected.all()
        val manifest = if (payload.has("message_images")) payload.getJSONArray("message_images") else JSONArray()
        require(manifest.length() <= MAX_ENTRIES)
        require(manifest.toString().toByteArray(Charsets.UTF_8).size <= MAX_MANIFEST_BYTES)
        val declared = (0 until manifest.length()).map { MessageImageReferenceCodec.decode(manifest.getJSONObject(it)) }
        require(declared.size == declared.distinctBy { it.owner to it.id }.size) { "重复图片清单身份" }
        require(declared.toSet() == wanted.toSet()) { "图片引用与原图清单不一致" }
        wanted.forEach { reference ->
            val original = requireNotNull(bytes[reference.sha256]) { "备份缺少消息原图" }
            verify(reference, original)
        }
        return wanted
    }

    fun verify(reference: MessageImageReference, bytes: ByteArray) {
        require(bytes.size == reference.bytes && PrivateBlobs.digest(bytes) == reference.sha256) { "消息原图大小或摘要不匹配" }
    }
}
