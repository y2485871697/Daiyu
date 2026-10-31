package app.daiyu.android.storage

import app.daiyu.android.domain.MessageBlock
import app.daiyu.android.domain.MessageImageOwner
import app.daiyu.android.domain.MessageImageReference
import app.daiyu.android.media.MessageImageOwner as AssetOwner
import app.daiyu.android.media.MessageImageReference as AssetReference
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Closed v1 reference wire format, identical to the message asset store's toJson().
 * No original bytes, draft handle, URI or permission grant are serialized.
 *
 * This codec does not publish assets or prove their existence. Callers must validate the
 * reference owner against the persisted message identity and use the asset resolver to read.
 */
object MessageImageReferenceCodec {
    private val fields = setOf("version", "conversation", "message", "id", "mime", "bytes", "sha256", "width", "height")

    fun encode(reference: MessageImageReference): JSONObject = JSONObject()
        .put("version", 1).put("conversation", reference.owner.conversationId)
        .put("message", reference.owner.messageId).put("id", reference.id)
        .put("mime", reference.mime).put("bytes", reference.bytes).put("sha256", reference.sha256)
        .put("width", reference.width).put("height", reference.height)

    fun decode(value: JSONObject): MessageImageReference {
        require(value.keys().asSequence().toSet() == fields) { "Invalid image reference fields" }
        require(integer(value, "version") == 1) { "Unsupported image reference version" }
        return MessageImageReference(
            MessageImageOwner(text(value, "conversation"), text(value, "message")),
            text(value, "id"), text(value, "mime"), integer(value, "bytes"),
            text(value, "sha256"), integer(value, "width"), integer(value, "height")
        )
    }

    fun toJson(reference: MessageImageReference): String = encode(reference).toString()

    fun fromJson(encoded: String): MessageImageReference {
        require(encoded.length <= 4096) { "Image reference exceeds wire limit" }
        return decode(ReferenceJson(encoded).read())
    }

    /** Shared image block shape for ordinary rows and history segments. */
    fun encodeBlock(block: MessageBlock.Image): JSONObject = JSONObject()
        .put("type", "image").put("reference", encode(block.reference))

    fun decodeBlock(value: JSONObject): MessageBlock.Image {
        require(value.keys().asSequence().toSet() == setOf("type", "reference")) { "Invalid image block fields" }
        require(text(value, "type") == "image")
        return MessageBlock.Image(decode(value.getJSONObject("reference")))
    }

    /** Explicit copies keep core-domain free of Android/media dependencies. */
    fun fromAsset(reference: AssetReference): MessageImageReference = MessageImageReference(
        MessageImageOwner(reference.owner.conversationId, reference.owner.messageId),
        reference.id, reference.mime, reference.bytes, reference.sha256, reference.width, reference.height
    )

    fun toAsset(reference: MessageImageReference): AssetReference = AssetReference(
        AssetOwner(reference.owner.conversationId, reference.owner.messageId),
        reference.id, reference.mime, reference.bytes, reference.sha256, reference.width, reference.height
    )

    // Android's JSONTokener also accepts non-JSON syntax and overwrites duplicate keys.
    // References are flat and contain only strings/integers, so keep that wire boundary
    // strict on Android as well as in JVM tests instead of depending on parser quirks.
    private class ReferenceJson(private val source: String) {
        private var index = 0

        fun read(): JSONObject {
            val result = JSONObject()
            val keys = mutableSetOf<String>()
            take('{')
            if (peek() != '}') while (true) {
                val key = string()
                require(keys.add(key)) { "Duplicate image reference field" }
                take(':')
                val value: Any = if (peek() == '"') string() else integer()
                result.put(key, value)
                if (peek() != ',') break
                take(',')
            }
            take('}')
            whitespace()
            require(index == source.length) { "Trailing image reference data" }
            return result
        }

        private fun string(): String {
            whitespace()
            val start = index
            require(source.getOrNull(index++) == '"') { "Expected JSON string" }
            while (index < source.length) {
                val ch = source[index++]
                if (ch == '"') return JSONTokener(source.substring(start, index)).nextValue() as String
                require(ch >= ' ') { "Unescaped JSON control character" }
                if (ch == '\\') {
                    val escaped = source.getOrNull(index++) ?: error("Incomplete JSON escape")
                    if (escaped == 'u') {
                        repeat(4) {
                            val hex = source.getOrNull(index++) ?: error("Incomplete Unicode escape")
                            require(hex in '0'..'9' || hex in 'a'..'f' || hex in 'A'..'F')
                        }
                    } else require(escaped in "\"\\/bfnrt") { "Invalid JSON escape" }
                }
            }
            error("Incomplete JSON string")
        }

        private fun integer(): Long {
            whitespace()
            val start = index
            if (source.getOrNull(index) == '-') index++
            val first = source.getOrNull(index++)
            require(first == '0' || first != null && first in '1'..'9') { "Expected JSON integer" }
            if (first != '0') while (source.getOrNull(index)?.let { it in '0'..'9' } == true) index++
            return source.substring(start, index).toLongOrNull() ?: error("JSON integer overflow")
        }

        private fun peek(): Char? { whitespace(); return source.getOrNull(index) }
        private fun take(expected: Char) { require(peek() == expected) { "Invalid image reference JSON" }; index++ }
        private fun whitespace() {
            while (source.getOrNull(index)?.let { it in " \t\r\n" } == true) index++
        }
    }

    private fun text(value: JSONObject, key: String): String = (value.get(key) as? String)
        ?: error("Image reference field must be text: $key")

    private fun integer(value: JSONObject, key: String): Int {
        val raw = value.get(key)
        require(raw is Int || raw is Long) { "Image reference field must be an integer: $key" }
        val number = (raw as Number).toLong()
        require(number in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "Image reference integer overflow: $key" }
        return number.toInt()
    }
}
