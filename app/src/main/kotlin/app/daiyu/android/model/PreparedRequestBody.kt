package app.daiyu.android.model

import app.daiyu.android.media.ImageAttachmentException
import app.daiyu.android.media.ImageAttachmentFailure
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/** A typed, run-local placeholder. Only the exact plan may resolve it; it is never persisted. */
internal class StreamingMedia(val bytes: Long, val sha256: String, val open: () -> InputStream) {
    val token = "daiyu-media-${UUID.randomUUID()}"
    fun writeBase64(output: OutputStream, cancelled: () -> Boolean) {
        val digest = MessageDigest.getInstance("SHA-256")
        var copied = 0L
        val encoder = Base64.getEncoder().wrap(object : FilterOutputStream(output) {
            override fun write(buffer: ByteArray, offset: Int, length: Int) { out.write(buffer, offset, length) }
            override fun close() { flush() }
        })
        encoder.use { encoded -> open().use { input ->
            val buffer = ByteArray(48 * 1024)
            while (true) {
                checkRequestCancellation(cancelled)
                var count = input.read(buffer)
                if (count < 0) break
                if (count == 0) {
                    val next = input.read()
                    if (next < 0) break
                    buffer[0] = next.toByte(); count = 1
                }
                copied = Math.addExact(copied, count.toLong())
                if (copied > bytes) throw ImageAttachmentException(ImageAttachmentFailure.CORRUPT)
                digest.update(buffer, 0, count)
                encoded.write(buffer, 0, count)
            }
        } }
        if (copied != bytes || requestHex(digest.digest()) != sha256) throw ImageAttachmentException(ImageAttachmentFailure.CORRUPT)
        checkRequestCancellation(cancelled)
    }
}

/** The exact bounded POST is frozen on disk before connection/approval, not as a giant heap array.
 * Android/POSIX unlinks its pathname before writing; Windows uses delete-on-close.
 * close releases the only retained descriptor. */
internal class PreparedRequestBody private constructor(
    private val source: FileChannel,
    val size: Int,
    val sha256: String,
) : Closeable {
    @Synchronized fun writeTo(output: OutputStream, cancelled: () -> Boolean) {
        source.position(0)
        val input = Channels.newInputStream(source)
        val buffer = ByteArray(64 * 1024)
        var copied = 0
        while (true) {
            checkRequestCancellation(cancelled)
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count); copied += count
        }
        check(copied == size)
    }
    override fun close() = source.close()
    override fun toString() = "PreparedRequestBody(<payload redacted>)"

    companion object {
        fun prepare(request: JSONObject, media: Collection<StreamingMedia>, cancelled: () -> Boolean): PreparedRequestBody {
            checkRequestCancellation(cancelled)
            val tokens = media.associateBy { it.token }
            require(tokens.size == media.size)
            val used = mutableSetOf<String>()
            val path = File.createTempFile("daiyu-request-", ".pending")
            var retained: FileChannel? = null
            var transferred = false
            try {
                val input = FileChannel.open(path.toPath(), StandardOpenOption.READ,
                    StandardOpenOption.WRITE, StandardOpenOption.DELETE_ON_CLOSE).also { retained = it }
                Channels.newOutputStream(input).let { destination ->
                    if (File.separatorChar != '\\') {
                        check(!path.exists() || path.delete()) { "Cannot unlink private request spool" }
                    }
                    val digest = MessageDigest.getInstance("SHA-256")
                    var count = 0L
                    val output = object : OutputStream() {
                        override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
                        override fun write(buffer: ByteArray, offset: Int, length: Int) {
                            checkRequestCancellation(cancelled)
                            count += length
                            if (count > ImageRequestPreflight.MAX_REQUEST_BYTES) {
                                throw ImageAttachmentException(ImageAttachmentFailure.TOO_LARGE)
                            }
                            digest.update(buffer, offset, length)
                            destination.write(buffer, offset, length)
                        }
                    }
                    fun text(value: String) = output.write(value.toByteArray(Charsets.UTF_8))
                    fun value(node: Any?, mediaField: Boolean = false) {
                        checkRequestCancellation(cancelled)
                        when (node) {
                            is JSONObject -> {
                                text("{")
                                node.keys().asSequence().forEachIndexed { index, key ->
                                    if (index > 0) text(",")
                                    text(JSONObject.quote(key)); text(":")
                                    val typed = when (node.optString("type")) {
                                        "input_image" -> key == "image_url"
                                        "input_video" -> key == "video_url"
                                        "base64" -> key == "data" && node.optString("media_type").startsWith("image/")
                                        else -> key == "url" && node.length() == 1
                                    }
                                    value(node.get(key), typed)
                                }
                                text("}")
                            }
                            is JSONArray -> {
                                text("[")
                                for (index in 0 until node.length()) {
                                    if (index > 0) text(",")
                                    value(node.get(index))
                                }
                                text("]")
                            }
                            is String -> {
                                // Data URLs contain a MIME prefix; the marker itself is opaque.
                                val token = node.substringAfter("base64,", node)
                                val attachment = if (mediaField) tokens[token] else null
                                if (attachment == null) text(JSONObject.quote(node))
                                else {
                                    require(used.add(token)) { "Duplicate attachment placeholder" }
                                    text("\"")
                                    if (token != node) text(node.substringBefore(token))
                                    attachment.writeBase64(output, cancelled)
                                    text("\"")
                                }
                            }
                            null, JSONObject.NULL -> text("null")
                            is Number -> text(JSONObject.numberToString(node))
                            is Boolean -> text(node.toString())
                            else -> error("Unsupported JSON value")
                        }
                    }
                    value(request)
                    require(used == tokens.keys) { "Frozen attachments were removed from the request" }
                    input.force(true)
                    checkRequestCancellation(cancelled)
                    return PreparedRequestBody(input, count.toInt(), requestHex(digest.digest())).also { transferred = true }
                }
            } finally {
                if (!transferred) {
                    try { retained?.close() }
                    finally { if (path.exists()) path.delete() }
                }
            }
        }
    }
}

private fun checkRequestCancellation(cancelled: () -> Boolean) {
    if (cancelled() || Thread.currentThread().isInterrupted) throw InterruptedException()
}
private fun requestHex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
