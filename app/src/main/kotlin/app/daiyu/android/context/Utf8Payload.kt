package app.daiyu.android.context

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/** Bounded encoding for backup limits/digests; preserves String.toByteArray's replacement policy. */
internal object Utf8Payload {
    private fun encode(text: String, sink: OutputStream) {
        OutputStreamWriter(sink, Charsets.UTF_8).use { writer ->
            var offset = 0
            while (offset < text.length) {
                val count = minOf(4096, text.length - offset)
                writer.write(text, offset, count)
                offset += count
            }
        }
    }

    fun byteCount(text: String, limit: Long): Long {
        require(limit >= 0)
        var count = 0L
        fun add(length: Int) {
            count += length
            require(count <= limit) { "备份超过字节上限" }
        }
        encode(text, object : OutputStream() {
            override fun write(value: Int) = add(1)
            override fun write(bytes: ByteArray, offset: Int, length: Int) = add(length)
        })
        return count
    }

    fun sha256(text: String): String {
        val hash = MessageDigest.getInstance("SHA-256")
        encode(text, object : OutputStream() {
            override fun write(value: Int) = hash.update(value.toByte())
            override fun write(bytes: ByteArray, offset: Int, length: Int) = hash.update(bytes, offset, length)
        })
        return hash.digest().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    }

    // Own only accumulated bytes, never the caller's stream. The accumulator is no longer
    // needed when JSON parsing starts; avoid a full-size byte copy and decoder CharBuffer.
    fun read(source: InputStream, limit: Int): String {
        require(limit >= 0)
        val bytes = BoundedBytes(limit)
        val block = ByteArray(8192)
        while (true) {
            val count = source.read(block)
            if (count < 0) break
            if (count == 0) {
                val value = source.read()
                if (value < 0) break
                block[0] = value.toByte()
                bytes.write(block, 0, 1)
            } else bytes.write(block, 0, count)
        }
        return bytes.strictText()
    }

    private class BoundedBytes(private val limit: Int) : ByteArrayOutputStream(minOf(8192, limit)) {
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            require(length <= limit - count) { "备份超过字节上限" }
            val needed = count + length
            if (needed > buf.size) {
                val capacity = minOf(limit.toLong(), maxOf(needed.toLong(), buf.size.toLong() * 2)).toInt()
                buf = buf.copyOf(capacity)
            }
            System.arraycopy(bytes, offset, buf, count, length)
            count = needed
        }

        fun strictText(): String {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val input = ByteBuffer.wrap(buf, 0, count)
            val output = CharBuffer.allocate(4096)
            while (true) {
                val result = decoder.decode(input, output, true)
                if (result.isError) result.throwException()
                output.clear()
                if (result.isUnderflow) break
            }
            while (true) {
                val result = decoder.flush(output)
                if (result.isError) result.throwException()
                output.clear()
                if (result.isUnderflow) break
            }
            return String(buf, 0, count, Charsets.UTF_8)
        }
    }
}
