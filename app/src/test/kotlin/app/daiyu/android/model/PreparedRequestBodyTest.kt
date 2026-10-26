package app.daiyu.android.model

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64

class PreparedRequestBodyTest {
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    @Test fun base64TailsCrossChunksAndUnicodeRemainExactAndRepeatable() {
        for (size in listOf(1, 2, 3, 49151, 49152, 49153, 65537)) {
            val original = ByteArray(size) { (it * 17).toByte() }
            val source = StreamingMedia(size.toLong(), hash(original)) { original.inputStream() }
            val request = JSONObject().put("type", "input_image")
                .put("image_url", "data:image/png;base64,${source.token}").put("text", "中文😀\n\"\\")
            PreparedRequestBody.prepare(request, listOf(source)) { false }.use { body ->
                repeat(2) {
                    val sent = ByteArrayOutputStream(); body.writeTo(sent) { false }
                    val bytes = sent.toByteArray(); val result = JSONObject(bytes.toString(Charsets.UTF_8))
                    assertArrayEquals(original, Base64.getDecoder().decode(result.getString("image_url").substringAfter("base64,")))
                    assertEquals("中文😀\n\"\\", result.getString("text"))
                    assertEquals(bytes.size, body.size); assertEquals(hash(bytes), body.sha256)
                }
            }
        }
    }
    @Test fun sourceCorruptionMissingMarkersAndCancellationFailBeforeNetwork() {
        val bytes = byteArrayOf(1, 2)
        val source = StreamingMedia(3, hash(bytes)) { bytes.inputStream() }
        val request = JSONObject().put("type", "input_image").put("image_url", "data:image/png;base64,${source.token}")
        assertThrows(app.daiyu.android.media.ImageAttachmentException::class.java) { PreparedRequestBody.prepare(request, listOf(source)) { false } }
        assertThrows(IllegalArgumentException::class.java) { PreparedRequestBody.prepare(JSONObject(), listOf(source)) { false } }
        assertThrows(InterruptedException::class.java) { PreparedRequestBody.prepare(request, listOf(source)) { true } }
    }
    @Test fun encoderForwardsBulkWritesNotPerByteCalls() {
        val bytes = ByteArray(1024 * 1024) { 1 }
        val source = StreamingMedia(bytes.size.toLong(), hash(bytes)) { bytes.inputStream() }
        var singles = 0; var blocks = 0
        source.writeBase64(object : java.io.OutputStream() {
            override fun write(value: Int) { singles++ }
            override fun write(buffer: ByteArray, offset: Int, length: Int) { blocks++ }
        }) { false }
        assertTrue(singles < 10); assertTrue(blocks in 1..1000)
    }

    @Test fun largePayloadUsesBoundedReadsAndDiskNotABase64String() {
        val length = 12 * 1024 * 1024
        val digest = MessageDigest.getInstance("SHA-256")
        val block = ByteArray(48 * 1024) { 5 }
        repeat(length / block.size) { digest.update(block) }
        val sha = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        val source = StreamingMedia(length.toLong(), sha) {
            object : java.io.InputStream() {
                var remaining = length
                override fun read(): Int = if (remaining-- > 0) 5 else -1
                override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
                    assertTrue(count <= 64 * 1024)
                    if (remaining == 0) return -1
                    val n = minOf(count, remaining); bytes.fill(5, offset, offset + n); remaining -= n; return n
                }
            }
        }
        val request = JSONObject().put("type", "input_image").put("image_url", "data:image/png;base64,${source.token}")
        assertTrue(request.toString().length < 256)
        PreparedRequestBody.prepare(request, listOf(source)) { false }.use { body ->
            var count = 0L
            body.writeTo(object : java.io.OutputStream() {
                override fun write(value: Int) { count++ }
                override fun write(buffer: ByteArray, offset: Int, length: Int) { count += length }
            }) { false }
            assertEquals(body.size.toLong(), count)
        }
    }
}
