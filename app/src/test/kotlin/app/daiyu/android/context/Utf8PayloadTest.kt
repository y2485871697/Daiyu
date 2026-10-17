package app.daiyu.android.context

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.CharacterCodingException
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class Utf8PayloadTest {
    private fun refuse(action: () -> Unit) {
        try { action(); fail("expected byte budget refusal") } catch (_: IllegalArgumentException) { }
    }

    @Test fun chunkedCountAndDigestPreserveOriginalUtf8Bytes() {
        val samples = listOf("", "ASCII", "中😼", "a\uD800b", "\uDC00",
            "x".repeat(4095) + "😼" + "y".repeat(5000), "中".repeat(10000))
        samples.forEach { text ->
            val bytes = text.toByteArray(Charsets.UTF_8)
            val expected = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
            assertEquals(bytes.size.toLong(), Utf8Payload.byteCount(text, bytes.size.toLong()))
            assertEquals(expected, Utf8Payload.sha256(text))
            if (bytes.isNotEmpty()) refuse { Utf8Payload.byteCount(text, bytes.size.toLong() - 1) }
        }
    }

    @Test fun exactInputBudgetAndUnicodeArePreservedWithoutClosingSource() {
        val text = "x".repeat(8191) + "中😼" + "tail"
        val bytes = text.toByteArray(Charsets.UTF_8)
        val source = object : ByteArrayInputStream(bytes) {
            var closed = false
            override fun close() { closed = true; super.close() }
        }
        assertEquals(text, Utf8Payload.read(source, bytes.size))
        assertFalse(source.closed)
    }

    @Test fun oversizeInputIsRejectedAndDoesNotCloseSource() {
        val source = object : ByteArrayInputStream(ByteArray(8193) { 'a'.code.toByte() }) {
            var closed = false
            override fun close() { closed = true }
        }
        refuse { Utf8Payload.read(source, 8192) }
        assertFalse(source.closed)
    }

    @Test fun malformedAndTruncatedUtf8AreRejected() {
        val invalid = listOf(intArrayOf(0x80), intArrayOf(0xc0, 0xaf), intArrayOf(0xc1, 0xbf),
            intArrayOf(0xed, 0xa0, 0x80), intArrayOf(0xf4, 0x90, 0x80, 0x80),
            intArrayOf(0xff), intArrayOf(0xe4, 0xb8), intArrayOf(0xf0, 0x9f, 0x98))
        invalid.forEach { values ->
            val bytes = values.map { it.toByte() }.toByteArray()
            try { Utf8Payload.read(ByteArrayInputStream(bytes), bytes.size); fail("malformed UTF-8 accepted") }
            catch (_: CharacterCodingException) { }
        }
    }

    @Test fun splitInputAndDecoderOutputBoundaryKeepSupplementaryCharacters() {
        val text = "a".repeat(4095) + "😼中" + "b".repeat(8191) + "😼"
        val bytes = text.toByteArray(Charsets.UTF_8)
        val source = object : ByteArrayInputStream(bytes) {
            override fun read(target: ByteArray, offset: Int, length: Int): Int =
                super.read(target, offset, minOf(length, 1))
        }
        assertEquals(text, Utf8Payload.read(source, bytes.size))
    }

    @Test fun zeroLengthReadCannotLoseBytesOrCloseCaller() {
        val source = object : ByteArrayInputStream("中😼".toByteArray(Charsets.UTF_8)) {
            private var zero = true
            var closed = false
            override fun read(target: ByteArray, offset: Int, length: Int): Int {
                if (zero) { zero = false; return 0 }
                zero = true
                return super.read(target, offset, minOf(length, 1))
            }
            override fun close() { closed = true }
        }
        assertEquals("中😼", Utf8Payload.read(source, 7))
        assertFalse(source.closed)
    }

    @Test fun inputFailureIsPropagatedWithoutClosingCaller() {
        val expected = IOException("synthetic source failure")
        val source = object : InputStream() {
            var closed = false
            override fun read(): Int = throw expected
            override fun close() { closed = true }
        }
        try { Utf8Payload.read(source, 100); fail("expected source failure") }
        catch (actual: IOException) { assertSame(expected, actual) }
        assertFalse(source.closed)
    }

    @Test fun emptyAndNegativeBudgetsAreExplicit() {
        assertEquals("", Utf8Payload.read(ByteArrayInputStream(byteArrayOf()), 0))
        assertEquals(0L, Utf8Payload.byteCount("", 0))
        refuse { Utf8Payload.read(ByteArrayInputStream(byteArrayOf(1)), 0) }
        refuse { Utf8Payload.read(ByteArrayInputStream(byteArrayOf()), -1) }
        refuse { Utf8Payload.byteCount("", -1) }
    }
}
