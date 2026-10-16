package app.daiyu.android.storage

import app.daiyu.android.model.generation.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Private versioned binary payload. No base64 expansion, Java serialization, paths or debug dumps. */
internal object GenerationAttemptCodec {
    const val MAX_ENCODED_BYTES = 32 * 1024 * 1024
    private const val MAX_INLINE_BYTES = 20 * 1024 * 1024
    private const val MAX_OUTPUTS = 32
    private const val MAX_URL_BYTES = 16 * 1024

    fun encode(value: Submission): ByteArray {
        val bytes = ByteArrayOutputStream()
        val bounded = object : OutputStream() {
            private var count = 0
            override fun write(b: Int) {
                check(count < MAX_ENCODED_BYTES); bytes.write(b); count++
            }
            override fun write(b: ByteArray, off: Int, len: Int) {
                check(len >= 0 && count <= MAX_ENCODED_BYTES - len)
                bytes.write(b, off, len); count += len
            }
        }
        DataOutputStream(bounded).use { out ->
            out.writeInt(1)
            when (value) {
                is Submission.Completed -> {
                    out.writeByte(1)
                    require(value.outputs.size <= MAX_OUTPUTS)
                    require(value.outputs.map { it.index }.distinct().size == value.outputs.size)
                    out.writeInt(value.outputs.size)
                    value.outputs.sortedBy { it.index }.forEach { item ->
                        require(item.index in 0 until MAX_OUTPUTS)
                        require((item.source == null) != (item.failure == null))
                        out.writeInt(item.index)
                        when (val source = item.source) {
                            is GenerationSource.Inline -> {
                                out.writeByte(1); out.text(source.mimeType, 256)
                                val data = source.bytes
                                require(data.isNotEmpty() && data.size <= MAX_INLINE_BYTES)
                                out.writeInt(data.size); out.write(data)
                            }
                            is GenerationSource.Remote -> {
                                out.writeByte(2); out.text(source.url, MAX_URL_BYTES); out.writeBoolean(source.authenticated)
                            }
                            null -> { out.writeByte(3); out.text(checkNotNull(item.failure).name, 128) }
                        }
                    }
                }
                is Submission.Pending -> { out.writeByte(2); out.handle(value.handle) }
                is Submission.Unknown -> {
                    out.writeByte(3); out.text(value.reason.name, 128)
                    out.writeBoolean(value.statusCode != null); value.statusCode?.let(out::writeInt)
                    out.writeBoolean(value.handle != null); value.handle?.let { out.handle(it) }
                }
                is Submission.NotExecuted -> { out.writeByte(4); out.text(value.reason.name, 128) }
            }
        }
        return bytes.toByteArray()
    }

    fun decode(bytes: ByteArray): Submission {
        require(bytes.size in 1..MAX_ENCODED_BYTES)
        val input = DataInputStream(ByteArrayInputStream(bytes))
        check(input.readInt() == 1)
        val value = when (input.readUnsignedByte()) {
            1 -> {
                val count = input.readInt(); check(count in 0..MAX_OUTPUTS)
                val outputs = (0 until count).map {
                    val index = input.readInt(); check(index in 0 until MAX_OUTPUTS)
                    when (input.readUnsignedByte()) {
                        1 -> {
                            val mime = input.text(256)
                            val length = input.readInt(); check(length in 1..MAX_INLINE_BYTES && length <= input.available())
                            val data = ByteArray(length); input.readFully(data)
                            GenerationOutput(index, GenerationSource.Inline(data, mime))
                        }
                        2 -> GenerationOutput(index, GenerationSource.Remote(input.text(MAX_URL_BYTES), input.flag()))
                        3 -> GenerationOutput(index, failure = GenerationFailure.valueOf(input.text(128)))
                        else -> error("generation_outcome_invalid")
                    }
                }
                check(outputs.map { it.index }.distinct().size == outputs.size)
                Submission.Completed(outputs.sortedBy { it.index })
            }
            2 -> Submission.Pending(input.handle())
            3 -> {
                val reason = GenerationFailure.valueOf(input.text(128))
                val status = if (input.flag()) input.readInt() else null
                val handle = if (input.flag()) input.handle() else null
                Submission.Unknown(reason, status, handle)
            }
            4 -> Submission.NotExecuted(GenerationFailure.valueOf(input.text(128)))
            else -> error("generation_outcome_invalid")
        }
        check(input.available() == 0)
        return value
    }

    fun validateHandle(attempt: GenerationAttempt, value: Submission) {
        val handle = handleOf(value) ?: return
        check(attempt.protocol == GenerationProtocol.OPENAI_VIDEO_MULTIPART)
        check(handle.identity == attempt.identity && handle.planFingerprint == attempt.fingerprint)
        check(handle.taskId.matches(Regex("[A-Za-z0-9_-]{1,256}")))
        check(handle.pollUrl.endsWith("/" + handle.taskId) && handle.contentUrl == handle.pollUrl + "/content")
    }

    /**
     * First known source wins at each index; a source can upgrade a failed sibling. A sparse late
     * completion never deletes earlier indices. Remote-failed is terminal but retains its handle.
     * No arrival order can demote Completed, even if the later result is empty or NotExecuted.
     */
    fun merge(prior: Submission?, incoming: Submission): Submission {
        if (prior == null) return incoming
        val oldHandle = handleOf(prior)
        val newHandle = handleOf(incoming)
        check(oldHandle == null || newHandle == null || oldHandle == newHandle)
        if (prior is Submission.Completed && incoming is Submission.Completed) {
            val outputs = prior.outputs.associateBy { it.index }.toMutableMap()
            incoming.outputs.forEach { next ->
                val old = outputs[next.index]
                if (old == null || (old.source == null && next.source != null)) outputs[next.index] = next
            }
            return Submission.Completed(outputs.values.sortedBy { it.index })
        }
        if (prior is Submission.Completed) return prior
        if (incoming is Submission.Completed) return incoming
        val kept = if (rank(incoming) > rank(prior)) incoming else prior
        return if (kept is Submission.Unknown && kept.handle == null)
            kept.copy(handle = oldHandle ?: newHandle) else kept
    }

    private fun rank(value: Submission): Int = when (value) {
        is Submission.Completed -> 4
        is Submission.Unknown -> if (value.reason == GenerationFailure.REMOTE_FAILED) 3 else 1
        is Submission.Pending -> 2
        is Submission.NotExecuted -> 0
    }

    private fun handleOf(value: Submission): GenerationHandle? = when (value) {
        is Submission.Pending -> value.handle
        is Submission.Unknown -> value.handle
        else -> null
    }

    private fun DataOutputStream.text(value: String, max: Int) {
        require(value.isNotEmpty() && value.length <= max)
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.isNotEmpty() && bytes.size <= max)
        writeInt(bytes.size); write(bytes)
    }
    private fun DataInputStream.text(max: Int): String {
        val size = readInt(); check(size in 1..max && size <= available())
        val bytes = ByteArray(size); readFully(bytes)
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }
    private fun DataInputStream.flag(): Boolean = when (readUnsignedByte()) {
        0 -> false
        1 -> true
        else -> error("generation_outcome_invalid")
    }
    private fun DataOutputStream.handle(value: GenerationHandle) {
        text(value.identity.operationId, 128); text(value.identity.itemId, 128)
        text(value.planFingerprint, 512); text(value.taskId, 256)
        text(value.pollUrl, MAX_URL_BYTES); text(value.contentUrl, MAX_URL_BYTES)
    }
    private fun DataInputStream.handle() = GenerationHandle(GenerationIdentity(text(128), text(128)),
        text(512), text(256), text(MAX_URL_BYTES), text(MAX_URL_BYTES))
}
