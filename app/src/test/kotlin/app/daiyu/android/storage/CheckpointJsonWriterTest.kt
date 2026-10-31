package app.daiyu.android.storage

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.math.BigInteger

open class CheckpointJsonWriterTest {
    private fun assertLegacy(root: JSONObject, writer: CheckpointJsonWriter): String {
        val legacy = root.toString()
        val actual = writer.write(root)
        assertEquals(legacy, actual)
        assertArrayEquals(legacy.toByteArray(Charsets.UTF_8), actual.toByteArray(Charsets.UTF_8))
        return actual
    }

    @Test fun platformEscapesNullNumbersAndKeyOrderOnTheSameTree() {
        val strings = listOf("", "中文🙂", "\u0000\u0001\b\t\n\u000c\r\u001f", "\"\\/</script>",
            "\u2028\u2029", "\ud800", "\udfff", "a\ud800z\udfff", "\ud83d\ude42")
        val numbers = listOf<Number>(0, -1, Long.MIN_VALUE, Long.MAX_VALUE, -0.0, -0.0f,
            0.0, 1.0, 1.25, Double.MIN_VALUE, Double.MAX_VALUE, 1e-7, 1e20,
            Float.MIN_VALUE, Float.MAX_VALUE, BigDecimal("123.4500"), BigInteger("1234567890123456789012345"))
        val array = JSONArray().put(JSONObject.NULL).put(null as Any?).put(true).put(false)
        strings.forEach { array.put(it) }
        numbers.forEach { array.put(it) }
        val root = JSONObject().put("z", array).put("a", JSONObject.NULL)
            .put("null", "null").put("boolean", "true")
        strings.forEachIndexed { index, key -> root.put("$index$key", key) }
        root.put("nested", JSONObject().put("last", JSONArray().put(JSONObject()).put(JSONArray())))
        for (writer in listOf(CheckpointJsonWriter(minCacheChars = 1),
            CheckpointJsonWriter(maxEntries = 0), CheckpointJsonWriter(maxRetainedChars = 0))) {
            repeat(2) { assertLegacy(root, writer) }
        }
    }

    @Test fun thirtyKResultQuotedOnceAcrossFourPlacesAndOneHundredFreshStreamingTrees() {
        val result = ("screen 中文🙂\n\"\\/\u0000".repeat(2500)).take(30_000)
        val writer = CheckpointJsonWriter()
        repeat(100) { tick ->
            // Equal content, different String instances, and no retained outer tree.
            fun copy() = String(result.toCharArray())
            val root = JSONObject().put("message", JSONArray().put(copy()))
                .put("history", JSONArray().put(copy()))
                .put("tools", JSONArray().put(JSONObject().put("id", "same-id").put("result", copy())))
                .put("outputs", JSONArray().put(JSONObject().put("id", "same-id").put("output", copy())))
                .put("text", "stream-" + "x".repeat(tick))
            assertLegacy(root, writer)
        }
        assertEquals(1L, writer.largeStringQuoteCalls)
        assertEquals(1, writer.cachedEntryCount)
        assertEquals(result.length.toLong() + JSONObject.quote(result).length, writer.retainedChars)
        writer.clear()
        assertEquals(0, writer.cachedEntryCount)
        assertEquals(0L, writer.retainedChars)
        assertEquals(0L, writer.quoteCalls)
        assertLegacy(JSONObject().put("result", result), writer)
        assertEquals(1L, writer.largeStringQuoteCalls)
    }

    @Test fun contentNotIdOrMutableGraphDeterminesReuse() {
        val first = "a".repeat(30_000)
        val second = "b".repeat(30_000)
        val writer = CheckpointJsonWriter()
        val row = JSONObject().put("id", "unchanged-call-id").put("result", first)
        val root = JSONObject().put("tools", JSONArray().put(row))
        assertLegacy(root, writer)
        row.put("result", second)
        assertTrue(assertLegacy(root, writer).contains(second))
        row.put("result", String(first.toCharArray()))
        assertLegacy(root, writer)
        assertEquals(2L, writer.largeStringQuoteCalls)
        assertEquals(2, writer.cachedEntryCount)
    }

    @Test fun entryLimitUsesAccessOrderAndSmallStringsDoNotDisplaceHotResults() {
        val writer = CheckpointJsonWriter(maxEntries = 2)
        val a = "a".repeat(2048)
        val b = "b".repeat(2048)
        val c = "c".repeat(2048)
        fun write(text: String) = assertLegacy(JSONObject().put("result", text), writer)
        write(a); write(b); write(String(a.toCharArray()))
        repeat(100) { write("short streaming $it") }
        write(c) // b, not the recently hit a, must be evicted.
        write(a)
        assertEquals(3L, writer.largeStringQuoteCalls)
        write(b)
        assertEquals(4L, writer.largeStringQuoteCalls)
        assertEquals(2, writer.cachedEntryCount)
        assertTrue(writer.retainedChars <= 4L * 1024 * 1024)
    }

    @Test fun freshLongStreamingStringsDoNotThrashARepeatedResult() {
        val result = "screen-result".repeat(2500)
        val writer = CheckpointJsonWriter(maxEntries = 2)
        repeat(100) { tick ->
            val stream = "new long streaming text $tick " + "x".repeat(2048 + tick)
            val root = JSONObject().put("events", JSONArray().put(result).put(stream).put(fresh(result)))
            assertLegacy(root, writer)
        }
        // One result plus one genuinely new long string per checkpoint. The previous
        // text, not the repeatedly hit result, is evicted when admitting the next text.
        assertEquals(101L, writer.largeStringQuoteCalls)
        assertEquals(2, writer.cachedEntryCount)
    }

    @Test fun equalHashCodesStillRequireExactStringContent() {
        val a = "Aa".repeat(1024)
        val b = "BB".repeat(1024)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, b)
        val writer = CheckpointJsonWriter()
        repeat(2) { assertLegacy(JSONObject().put("values", JSONArray().put(a).put(b)), writer) }
        assertEquals(2L, writer.largeStringQuoteCalls)
        assertEquals(2, writer.cachedEntryCount)
    }

    private fun fresh(value: String) = String(value.toCharArray())

    @Test fun exactCombinedCharacterBudgetZeroBudgetAndOversizedBypass() {
        val source = "\u0000".repeat(1024)
        val cost = source.length + JSONObject.quote(source).length
        val exact = CheckpointJsonWriter(maxRetainedChars = cost)
        val root = JSONObject().put("result", source)
        repeat(2) { assertLegacy(root, exact) }
        assertEquals(cost.toLong(), exact.retainedChars)
        assertEquals(1L, exact.largeStringQuoteCalls)
        for (disabled in listOf(CheckpointJsonWriter(maxEntries = 0),
            CheckpointJsonWriter(maxRetainedChars = 0), CheckpointJsonWriter(maxRetainedChars = cost - 1))) {
            repeat(2) { assertLegacy(root, disabled) }
            assertEquals(0, disabled.cachedEntryCount)
            assertEquals(0L, disabled.retainedChars)
            assertEquals(2L, disabled.largeStringQuoteCalls)
        }
        val writer = CheckpointJsonWriter(maxEntries = 8, maxRetainedChars = cost)
        val small = JSONObject().put("result", "a".repeat(1024))
        assertLegacy(small, writer)
        val retained = writer.retainedChars
        val tooLarge = JSONObject().put("result", "\u0000".repeat(1025))
        repeat(2) { assertLegacy(tooLarge, writer) }
        assertEquals(retained, writer.retainedChars)
        assertLegacy(small, writer)
        assertEquals(3L, writer.largeStringQuoteCalls)
        // Character budget independently forces eviction even below the entry cap.
        assertLegacy(root, writer)
        assertEquals(1, writer.cachedEntryCount)
        assertEquals(cost.toLong(), writer.retainedChars)
        assertLegacy(small, writer)
        assertEquals(5L, writer.largeStringQuoteCalls)
        assertTrue(writer.retainedChars <= cost)
    }
}

// Exercise Android's own org.json rules too, not just the host org.json dependency.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 35], manifest = Config.NONE)
class AndroidCheckpointJsonWriterTest : CheckpointJsonWriterTest()
