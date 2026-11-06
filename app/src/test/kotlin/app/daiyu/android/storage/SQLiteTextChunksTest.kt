package app.daiyu.android.storage

import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class SQLiteTextChunksTest {
    private fun decode(bytes: ByteArray, checks: () -> Unit = {}): Pair<String, List<Long>> {
        val offsets = mutableListOf<Long>()
        val first = bytes.copyOfRange(0, minOf(bytes.size, SQLiteText.CHUNK_BYTES))
        val text = SQLiteText.decodeChunks(bytes.size.toLong(), first, checks) { consumed ->
            offsets += consumed
            bytes.copyOfRange(consumed.toInt(), minOf(bytes.size, consumed.toInt() + SQLiteText.CHUNK_BYTES))
        }
        return text to offsets
    }

    @Test fun smallValuesUseOnlyTheAlreadyReadFirstChunk() {
        for (size in listOf(0, 1, 65535, 65536, 65537, SQLiteText.CHUNK_BYTES - 1, SQLiteText.CHUNK_BYTES)) {
            val text = "a".repeat(size)
            val (actual, offsets) = decode(text.toByteArray())
            assertEquals(text, actual)
            assertEquals(emptyList<Long>(), offsets)
        }
    }

    @Test fun largeValuesNeverReadTheFirstChunkTwiceAndKeepCrossChunkUnicode() {
        val boundaries = listOf(65536, SQLiteText.CHUNK_BYTES)
        for (boundary in boundaries) for (prefix in boundary - 3..boundary + 1) {
            for (suffix in listOf("数🙂e\u0301\u0000", "🙂数e\u0301\u0000")) {
                val text = "x".repeat(prefix) + suffix + "中".repeat(50_000)
                val bytes = text.toByteArray(Charsets.UTF_8)
                val (actual, offsets) = decode(bytes)
                assertEquals(text, actual)
                assertArrayEquals(bytes, actual.toByteArray(Charsets.UTF_8))
                val chunk = SQLiteText.CHUNK_BYTES.toLong()
                assertEquals((chunk until bytes.size.toLong() step chunk).toList(), offsets)
            }
        }
    }

    @Test fun multiMiBValuesRoundTripAllBytesWithOneLoadPerContinuationChunk() {
        val text = "a".repeat(SQLiteText.CHUNK_BYTES - 1) + "🙂数\u0000" + "数🙂".repeat(340_000) + " \n"
        val bytes = text.toByteArray(Charsets.UTF_8)
        val (actual, offsets) = decode(bytes)
        assertEquals(text, actual)
        assertArrayEquals(bytes, actual.toByteArray(Charsets.UTF_8))
        assertEquals(listOf(1L, 2L, 3L).map { it * SQLiteText.CHUNK_BYTES }, offsets)
        assertEquals(4, 1 + offsets.size)
        val legacyReads = (bytes.size + 65535) / 65536
        assertTrue("loads=${1 + offsets.size}, legacy=$legacyReads", (1 + offsets.size) * 8 <= legacyReads)
    }

    @Test fun shortAndChunkedInputsRejectMalformedUtf8() {
        val malformed = listOf(byteArrayOf(0x80.toByte()), byteArrayOf(0xc0.toByte(), 0xaf.toByte()),
            byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()),
            byteArrayOf(0xf0.toByte(), 0x9f.toByte(), 0x99.toByte()))
        for (bytes in malformed) for (prefix in listOf(0, 65535, 65536,
            SQLiteText.CHUNK_BYTES - 1, SQLiteText.CHUNK_BYTES)) {
            val failure = runCatching { decode(ByteArray(prefix) { 0x61 } + bytes) }.exceptionOrNull()
            assertTrue("prefix=$prefix: $failure", failure is java.nio.charset.CharacterCodingException)
        }
    }

    @Test fun truncatedFirstAndLaterChunksCannotReturnPartialText() {
        val chunk = SQLiteText.CHUNK_BYTES
        assertTrue(runCatching { SQLiteText.decodeChunks(3, byteArrayOf(1), {}, { error("not read") }) }.isFailure)
        for (last in listOf(byteArrayOf(), byteArrayOf(1, 2))) {
            assertTrue(runCatching { SQLiteText.decodeChunks(chunk + 1L, ByteArray(chunk), {}, { last }) }.isFailure)
        }
        for (size in listOf(chunk - 1, chunk + 1)) {
            assertTrue(runCatching {
                SQLiteText.decodeChunks(3L * chunk, ByteArray(chunk), {}, { ByteArray(size) })
            }.exceptionOrNull() is IllegalStateException)
        }
    }

    @Test fun cancellationIsCheckedBeforeDecodeDuringStreamingAndBeforeReturn() {
        for (size in listOf(1, 65536, 65537, SQLiteText.CHUNK_BYTES,
            SQLiteText.CHUNK_BYTES + 1, 2 * SQLiteText.CHUNK_BYTES + 17)) {
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
