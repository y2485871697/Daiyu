package app.daiyu.android.storage

import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class SQLiteTextChunksTest {
    private fun decode(bytes: ByteArray, checks: () -> Unit = {}): Pair<String, List<Long>> {
        val offsets = mutableListOf<Long>()
        val first = bytes.copyOfRange(0, minOf(bytes.size, 65536))
        val text = SQLiteText.decodeChunks(bytes.size.toLong(), first, checks) { consumed ->
            offsets += consumed
            bytes.copyOfRange(consumed.toInt(), minOf(bytes.size, consumed.toInt() + 65536))
        }
        return text to offsets
    }

    @Test fun smallValuesUseOnlyTheAlreadyReadFirstChunk() {
        for (size in listOf(0, 1, 65535, 65536)) {
            val text = "a".repeat(size)
            val (actual, offsets) = decode(text.toByteArray())
            assertEquals(text, actual)
            assertEquals(emptyList<Long>(), offsets)
        }
    }

    @Test fun largeValuesNeverReadTheFirstChunkTwiceAndKeepCrossChunkUnicode() {
        for (prefix in listOf(65533, 65534, 65535, 65536, 65537)) {
            for (suffix in listOf("数🙂e\u0301\u0000", "🙂数e\u0301\u0000")) {
                val text = "x".repeat(prefix) + suffix + "中".repeat(50_000)
                val (actual, offsets) = decode(text.toByteArray(Charsets.UTF_8))
                assertEquals(text, actual)
                assertEquals((65536L until text.toByteArray().size.toLong() step 65536L).toList(), offsets)
            }
        }
    }

    @Test fun shortAndChunkedInputsRejectMalformedUtf8() {
        val malformed = listOf(byteArrayOf(0x80.toByte()), byteArrayOf(0xc0.toByte(), 0xaf.toByte()),
            byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()),
            byteArrayOf(0xf0.toByte(), 0x9f.toByte(), 0x99.toByte()))
        for (bytes in malformed) for (prefix in listOf(0, 65535, 65536)) {
            val failure = runCatching { decode(ByteArray(prefix) { 0x61 } + bytes) }.exceptionOrNull()
            assertTrue(failure.toString(), failure is java.nio.charset.CharacterCodingException)
        }
    }

    @Test fun truncatedFirstAndLaterChunksCannotReturnPartialText() {
        assertTrue(runCatching { SQLiteText.decodeChunks(3, byteArrayOf(1), {}, { error("not read") }) }.isFailure)
        assertTrue(runCatching { SQLiteText.decodeChunks(65537, ByteArray(65536), {}, { byteArrayOf() }) }.isFailure)
        assertTrue(runCatching { SQLiteText.decodeChunks(65537, ByteArray(65536), {}, { byteArrayOf(1, 2) }) }.isFailure)
    }

    @Test fun cancellationIsCheckedBeforeDecodeDuringStreamingAndBeforeReturn() {
        for (size in listOf(1, 65536, 65537, 140_000)) {
            var checks = 0
            decode(ByteArray(size) { 0x61 }) { checks++ }
            for (cancelAt in setOf(1, checks / 2 + 1, checks)) {
                var current = 0
                val failure = runCatching { decode(ByteArray(size) { 0x61 }) {
                    if (++current == cancelAt) throw CancellationException("cancelled")
                } }.exceptionOrNull()
                assertTrue("size=$size check=$cancelAt", failure is CancellationException)
            }
        }
    }
}
