package app.daiyu.android.model

import app.daiyu.android.domain.ModelFailure
import app.daiyu.android.domain.ModelRequestException
import java.io.BufferedReader
import java.io.Reader
import java.io.StringReader
import org.junit.Assert.*
import org.junit.Test

class SseReaderTest {
    private fun events(text: String): List<String> = mutableListOf<String>().also { result ->
        SseReader.read(StringReader(text).buffered()) { result.add(it); true }
    }
    private fun failure(expected: ModelFailure, read: () -> Unit) {
        try { read(); fail("expected $expected") }
        catch (error: ModelRequestException) { assertEquals(expected, error.failure) }
    }
    @Test fun lineEndingsAndMultilineDataArePreserved() {
        for (ending in listOf("\n", "\r", "\r\n")) {
            assertEquals(listOf("one\ntwo", "three"), events(
                "data: one${ending}data: two$ending$ending: comment${ending}data: three$ending$ending"))
        }
    }
    @Test fun onlyTheFirstStreamCharacterMayBeABom() {
        assertEquals(listOf("first"), events("\uFEFFdata: first\n\n"))
        assertEquals(listOf("\uFEFFbody"), events("data: \uFEFFbody\n\n"))
        assertEquals(listOf("second"), events("\uFEFFdata: second\n\n\uFEFFdata: ignored\n\n"))
        assertEquals(emptyList<String>(), events("\uFEFF"))
    }
    @Test fun unterminatedDataRemainsIncomplete() {
        for (text in listOf("data: x", "data: x\n", "\uFEFFdata: x\r\n")) {
            failure(ModelFailure.INCOMPLETE) { events(text) }
        }
    }
    @Test fun rejectsWhileReadingEveryKindOfUnterminatedLine() {
        for (prefix in listOf("data: ", ":", "unknown: ")) {
            val source = object : Reader() {
                var delivered = 0
                override fun read(buffer: CharArray, offset: Int, length: Int): Int {
                    check(delivered < 1_100_000) { "unbounded_read_reached_sentinel" }
                    val count = minOf(length, 1_100_000 - delivered)
                    repeat(count) { index ->
                        val position = delivered + index
                        buffer[offset + index] = if (position < prefix.length) prefix[position] else 'x'
                    }
                    delivered += count
                    return count
                }
                override fun close() = Unit
            }
            failure(ModelFailure.PROTOCOL) { SseReader.read(BufferedReader(source, 128)) { true } }
            assertTrue(source.delivered <= 1_048_576 + 128)
        }
    }
    @Test fun exactLineLimitIsAcceptedAndNextCharacterRejected() {
        val accepted = "x".repeat(1_048_576 - 6)
        assertEquals(listOf(accepted), events("data: $accepted\n\n"))
        failure(ModelFailure.PROTOCOL) { events("data: ${accepted}x\n\n") }
    }
    @Test fun accumulatedDataLimitIsStillEnforced() {
        val half = "x".repeat(524_288)
        failure(ModelFailure.PROTOCOL) { events("data: $half\ndata: $half\n\n") }
    }
    @Test fun bufferedReadAheadRetainsTheUnconsumedTail() {
        for (ending in listOf("\n", "\r", "\r\n")) {
            val reader = BufferedReader(StringReader("data: first$ending${ending}data: second\n\n"), 128)
            val actual = mutableListOf<String>()
            SseReader.read(reader) { actual.add(it); false }
            assertEquals(listOf("first"), actual)
            assertEquals((if (ending == "\r\n") "\n" else "") + "data: second\n\n", reader.readText())
        }
    }
    @Test fun eofDoesNotDispatchTheUnterminatedLastFrame() {
        val actual = mutableListOf<String>()
        failure(ModelFailure.INCOMPLETE) {
            SseReader.read(StringReader("data: confirmed\n\ndata: pending\n").buffered()) { actual.add(it); true }
        }
        assertEquals(listOf("confirmed"), actual)
    }
    @Test fun eofWithoutPendingDataIsNotIncompleteAtSseLayer() {
        for (text in listOf("", "\uFEFF", ": comment", "unknown: value\n")) {
            assertEquals(emptyList<String>(), events(text))
        }
        assertEquals(listOf(""), events("data:\n\n"))
    }
    @Test fun consumerStopDoesNotReadTheNextLine() {
        for (ending in listOf("\n", "\r", "\r\n")) {
            val prefix = "data: first$ending$ending"
            val expectedReads = prefix.length - if (ending == "\r\n") 1 else 0
            val source = object : BufferedReader(StringReader("")) {
                var delivered = 0
                override fun read(): Int {
                    check(delivered < expectedReads) { "read_after_consumer_stopped" }
                    return prefix[delivered++].code
                }
            }
            val actual = mutableListOf<String>()
            SseReader.read(source) { actual.add(it); false }
            assertEquals(listOf("first"), actual)
            assertEquals(expectedReads, source.delivered)
        }
    }
}
