package app.daiyu.android.storage

import app.daiyu.android.domain.Message
import app.daiyu.android.domain.MessageHistory
import app.daiyu.android.media.video.MessageVideoOwner
import app.daiyu.android.media.video.MessageVideoReference
import app.daiyu.android.media.video.VIDEO_HASH
import app.daiyu.android.media.video.VIDEO_ID
import app.daiyu.android.media.video.VIDEO_MIMES
import app.daiyu.android.model.videos.MessageVideoInput

/** Stable, redacted failures: never attach parser exceptions, identities, paths or payloads. */
internal enum class MessageVideoCodecFailure {
    TOO_LARGE, INVALID_JSON, INVALID_FIELDS, UNSUPPORTED_VERSION, INVALID_IDENTITY,
    INVALID_MESSAGE, OWNER_MISMATCH, DUPLICATE_OWNER, DUPLICATE_REFERENCE, INVALID_REFERENCE,
}

internal class MessageVideoCodecException(val failure: MessageVideoCodecFailure) :
    IllegalArgumentException("Message video sidecar: ${failure.name}")

/** Metadata only. This is not an asset resolver, authorization grant or provider input codec. */
internal object MessageVideoInputCodec {
    internal const val MAX_UTF8_BYTES = 1024 * 1024
    internal const val MAX_INPUTS = 256
    internal const val MAX_REFERENCES = 1024
    internal const val MAX_REFERENCES_PER_OWNER = 8
    // Absolute supported VideoAttachmentLimits maxima, not the default import/model policy.
    private const val MAX_VIDEO_BYTES = 256L * 1024 * 1024
    private const val MAX_OWNER_BYTES = 512L * 1024 * 1024
    private const val MAX_DURATION_MS = 24L * 60 * 60 * 1000
    private const val MAX_PIXELS = 40_000_000L
    private val rootFields = setOf("version", "owner", "inputs")
    private val ownerFields = setOf("conversation", "message", "run")
    private val inputFields = setOf("owner", "references")
    private val referenceFields = setOf("id", "mime", "bytes", "sha256", "duration_ms", "width", "height")

    fun encode(message: Message, inputs: List<MessageVideoInput>): String {
        val rows = userRows(message)
        bound(inputs.size <= MAX_INPUTS)
        val source = inputs.toList()
        validateInputs(rows, source)
        val wire = linkedMapOf<String, Any>(
            "version" to 1L,
            "owner" to ownerWire(message),
            "inputs" to source.map { input ->
                linkedMapOf("owner" to ownerWire(rowFor(rows, input.owner)),
                    "references" to input.references.map { ref ->
                        linkedMapOf<String, Any>("id" to ref.id, "mime" to ref.mime,
                            "bytes" to ref.bytes, "sha256" to ref.sha256,
                            "duration_ms" to ref.durationMs, "width" to ref.width.toLong(),
                            "height" to ref.height.toLong())
                    })
            }
        )
        return buildString { writeJson(wire) }.also { boundedUtf8(it, MAX_UTF8_BYTES) }
    }

    fun decode(message: Message, encoded: String): List<MessageVideoInput> {
        boundedUtf8(encoded, MAX_UTF8_BYTES)
        val root = obj(Json(encoded).read(), rootFields)
        if (number(root, "version") != 1L) fail(MessageVideoCodecFailure.UNSUPPORTED_VERSION)
        val rows = userRows(message)
        checkOwner(obj(root.getValue("owner"), ownerFields), message)
        val rawInputs = array(root.getValue("inputs"))
        bound(rawInputs.size <= MAX_INPUTS)
        var count = 0
        val inputs = rawInputs.map { raw ->
            val input = obj(raw, inputFields)
            val owner = obj(input.getValue("owner"), ownerFields)
            val conversation = identity(text(owner, "conversation"))
            val id = identity(text(owner, "message"))
            val row = rowFor(rows, MessageVideoOwner(conversation, id))
            checkOwner(owner, row)
            val refs = array(input.getValue("references"))
            bound(refs.size <= MAX_REFERENCES_PER_OWNER)
            count += refs.size
            bound(count <= MAX_REFERENCES)
            MessageVideoInput(MessageVideoOwner(conversation, id), refs.map { rawRef ->
                val ref = obj(rawRef, referenceFields)
                MessageVideoReference(MessageVideoOwner(conversation, id), text(ref, "id"),
                    text(ref, "mime"), number(ref, "bytes"), text(ref, "sha256"),
                    number(ref, "duration_ms"), dimension(ref, "width"), dimension(ref, "height"))
            })
        }
        validateInputs(rows, inputs)
        return inputs
    }

    private fun userRows(message: Message): List<Message> {
        identity(message.id.value); identity(message.conversationId.value); identity(message.runId.value)
        if (message.role != Message.USER_ROLE && message.role != Message.ASSISTANT_ROLE) {
            fail(MessageVideoCodecFailure.INVALID_MESSAGE)
        }
        // expand() validates histories, but the legacy/no-segment path does not validate role/IDs.
        val rows = try {
            MessageHistory.validateOwner(message)
            MessageHistory.expand(listOf(message))
        } catch (_: IllegalArgumentException) {
            fail(MessageVideoCodecFailure.INVALID_MESSAGE)
        } catch (_: IllegalStateException) {
            fail(MessageVideoCodecFailure.INVALID_MESSAGE)
        }
        rows.forEach { row ->
            identity(row.id.value); identity(row.conversationId.value); identity(row.runId.value)
            if (row.conversationId != message.conversationId || row.runId != message.runId) {
                fail(MessageVideoCodecFailure.INVALID_MESSAGE)
            }
        }
        // Keep ALL rows: a matching assistant row must not be hidden when testing uniqueness.
        return rows
    }

    private fun rowFor(rows: List<Message>, owner: MessageVideoOwner): Message {
        identity(owner.conversationId); identity(owner.messageId)
        val matches = rows.filter { it.conversationId.value == owner.conversationId && it.id.value == owner.messageId }
        if (matches.size != 1 || matches.single().role != Message.USER_ROLE || matches.single().protocolItems.isNotEmpty()) {
            fail(MessageVideoCodecFailure.OWNER_MISMATCH)
        }
        return matches.single()
    }

    private fun validateInputs(rows: List<Message>, inputs: List<MessageVideoInput>) {
        bound(inputs.size <= MAX_INPUTS)
        val owners = mutableSetOf<MessageVideoOwner>()
        var count = 0
        inputs.forEach { input ->
            rowFor(rows, input.owner)
            if (!owners.add(input.owner)) fail(MessageVideoCodecFailure.DUPLICATE_OWNER)
            if (input.references.isEmpty()) fail(MessageVideoCodecFailure.INVALID_REFERENCE)
            bound(input.references.size <= MAX_REFERENCES_PER_OWNER)
            count += input.references.size
            bound(count <= MAX_REFERENCES)
            val ids = mutableSetOf<String>()
            var ownerBytes = 0L
            input.references.forEach { ref ->
                if (ref.owner != input.owner) fail(MessageVideoCodecFailure.OWNER_MISMATCH)
                if (!ids.add(ref.id)) fail(MessageVideoCodecFailure.DUPLICATE_REFERENCE)
                if (!VIDEO_ID.matches(ref.id) || !VIDEO_HASH.matches(ref.sha256) || ref.mime !in VIDEO_MIMES ||
                    ref.bytes !in 1..MAX_VIDEO_BYTES || ref.durationMs !in 1..MAX_DURATION_MS ||
                    ref.width <= 0 || ref.height <= 0 || ref.width.toLong() * ref.height > MAX_PIXELS) {
                    fail(MessageVideoCodecFailure.INVALID_REFERENCE)
                }
                ownerBytes += ref.bytes // At most eight already-bounded positive values; cannot overflow.
                bound(ownerBytes <= MAX_OWNER_BYTES)
            }
        }
    }

    private fun ownerWire(row: Message): Map<String, Any> = linkedMapOf(
        "conversation" to row.conversationId.value, "message" to row.id.value, "run" to row.runId.value)

    private fun checkOwner(owner: Map<String, Any>, row: Message) {
        val conversation = identity(text(owner, "conversation"))
        val message = identity(text(owner, "message"))
        val run = identity(text(owner, "run"))
        if (conversation != row.conversationId.value || message != row.id.value || run != row.runId.value) {
            fail(MessageVideoCodecFailure.OWNER_MISMATCH)
        }
    }

    private fun identity(value: String): String {
        if (value.isBlank() || value.length > 200 || value.trim() != value || value == "." || value == ".." ||
            value.any { it == '/' || it == '\\' || it == ':' || it.isISOControl() }) {
            fail(MessageVideoCodecFailure.INVALID_IDENTITY)
        }
        boundedUtf8(value, 800)
        return value // Exact identity; no normalization, URI decoding or path resolution.
    }

    private fun dimension(value: Map<String, Any>, key: String): Int {
        val number = number(value, key)
        if (number !in 1..Int.MAX_VALUE.toLong()) fail(MessageVideoCodecFailure.INVALID_REFERENCE)
        return number.toInt()
    }

    private fun number(value: Map<String, Any>, key: String): Long = value[key] as? Long
        ?: fail(MessageVideoCodecFailure.INVALID_FIELDS)
    private fun text(value: Map<String, Any>, key: String): String = value[key] as? String
        ?: fail(MessageVideoCodecFailure.INVALID_FIELDS)

    @Suppress("UNCHECKED_CAST")
    private fun obj(value: Any, fields: Set<String>): Map<String, Any> {
        val result = value as? Map<String, Any> ?: fail(MessageVideoCodecFailure.INVALID_FIELDS)
        if (result.keys != fields) fail(MessageVideoCodecFailure.INVALID_FIELDS)
        return result
    }

    @Suppress("UNCHECKED_CAST")
    private fun array(value: Any): List<Any> = value as? List<Any> ?: fail(MessageVideoCodecFailure.INVALID_FIELDS)

    private fun bound(valid: Boolean) { if (!valid) fail(MessageVideoCodecFailure.TOO_LARGE) }
    private fun fail(code: MessageVideoCodecFailure): Nothing = throw MessageVideoCodecException(code)

    /** Count real UTF-8 bytes without a large temporary allocation or replacement of broken UTF-16. */
    private fun boundedUtf8(value: String, maximum: Int) {
        bound(value.length <= maximum)
        var bytes = 0
        var index = 0
        while (index < value.length) {
            val c = value[index++]
            bytes += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                c.isHighSurrogate() -> {
                    if (index == value.length || !value[index++].isLowSurrogate()) fail(MessageVideoCodecFailure.INVALID_JSON)
                    4
                }
                c.isLowSurrogate() -> fail(MessageVideoCodecFailure.INVALID_JSON)
                else -> 3
            }
            bound(bytes <= maximum)
        }
    }

    private fun StringBuilder.writeJson(value: Any) {
        when (value) {
            is String -> {
                append('"')
                value.forEach { c -> when (c) {
                    '"', '\\' -> { append('\\'); append(c) }
                    else -> append(c) // All persisted strings have been validated: no controls.
                } }
                append('"')
            }
            is Long -> append(value)
            is Map<*, *> -> {
                append('{')
                value.entries.forEachIndexed { index, entry ->
                    if (index != 0) append(',')
                    writeJson(entry.key as String); append(':'); writeJson(entry.value!!)
                }
                append('}')
            }
            is List<*> -> {
                append('[')
                value.forEachIndexed { index, item -> if (index != 0) append(','); writeJson(item!!) }
                append(']')
            }
            else -> fail(MessageVideoCodecFailure.INVALID_FIELDS)
        }
    }

    /** Restricted strict JSON, identical on JVM and Android (no lenient JSONTokener coercion).
     * Only objects, arrays, strings and signed decimal Long integers occur in this schema.
     * Reject duplicate decoded keys, decimals/exponents, null, booleans, comments and trailing data.
     */
    private class Json(private val source: String) {
        private var index = 0
        private var nodes = 0
        fun read(): Any {
            val value = value(0)
            if (peek() != null) bad()
            return value
        }
        private fun value(depth: Int): Any {
            bound(depth <= 6 && ++nodes <= 16_000)
            val c = peek() ?: bad()
            return when (c) {
                '{' -> objectValue(depth)
                '[' -> arrayValue(depth)
                '"' -> string()
                '-', in '0'..'9' -> integer()
                else -> bad()
            }
        }
        private fun objectValue(depth: Int): Map<String, Any> {
            take('{')
            val result = linkedMapOf<String, Any>()
            if (peek() != '}') while (true) {
                bound(result.size < 8)
                val key = string()
                if (key in result) bad()
                take(':')
                result[key] = value(depth + 1)
                if (peek() != ',') break
                take(',')
            }
            take('}')
            return result
        }
        private fun arrayValue(depth: Int): List<Any> {
            take('[')
            val result = mutableListOf<Any>()
            if (peek() != ']') while (true) {
                bound(result.size < MAX_REFERENCES)
                result.add(value(depth + 1))
                if (peek() != ',') break
                take(',')
            }
            take(']')
            return result
        }
        private fun string(): String {
            take('"')
            val result = StringBuilder()
            while (index < source.length) {
                val c = source[index++]
                if (c == '"') return result.toString().also { boundedUtf8(it, 800) }
                if (c < ' ') bad()
                if (c != '\\') result.append(c) else {
                    result.append(when (source.getOrNull(index++)) {
                        '"' -> '"'; '\\' -> '\\'; '/' -> '/'; 'b' -> '\b'; 'f' -> '\u000c'
                        'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'
                        'u' -> {
                            var code = 0
                            repeat(4) {
                                val hex = source.getOrNull(index++) ?: bad()
                                val digit = when (hex) {
                                    in '0'..'9' -> hex - '0'
                                    in 'a'..'f' -> hex - 'a' + 10
                                    in 'A'..'F' -> hex - 'A' + 10
                                    else -> bad()
                                }
                                code = code * 16 + digit
                            }
                            code.toChar()
                        }
                        else -> bad()
                    })
                }
                bound(result.length <= 200)
            }
            bad()
        }
        private fun integer(): Long {
            val start = index
            if (source.getOrNull(index) == '-') index++
            when (source.getOrNull(index++) ?: bad()) {
                '0' -> Unit
                in '1'..'9' -> while (source.getOrNull(index)?.let { it in '0'..'9' } == true) index++
                else -> bad()
            }
            // Subsequent punctuation is consumed by the enclosing grammar; 01/1.0/1e0/+1 fail.
            return source.substring(start, index).toLongOrNull() ?: bad()
        }
        private fun peek(): Char? {
            while (source.getOrNull(index)?.let { it in " \t\r\n" } == true) index++
            return source.getOrNull(index)
        }
        private fun take(c: Char) { if (peek() != c) bad(); index++ }
        private fun bad(): Nothing = fail(MessageVideoCodecFailure.INVALID_JSON)
    }
}
