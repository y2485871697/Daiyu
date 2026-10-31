package app.daiyu.android.storage

import app.daiyu.android.domain.*
import org.junit.Assert.*
import org.junit.Test

class CommitMessageDecodeReuseTest {
    private val raw = StoredMessageColumns("a", "c", "r", "assistant", "RUNNING", null,
        "[]", null, "[]", "[]")
    private fun message() = Message(MessageId("a"), ConversationId("c"), RunId("r"), "assistant",
        arrayListOf(MessageBlock.Text("t", "数🙂")), RunStatus.RUNNING,
        protocolItems = arrayListOf("protocol"), historySegments = arrayListOf(
            MessageHistorySegment(MessageId("a"), "assistant", arrayListOf(MessageBlock.Text("t", "数🙂")),
                arrayListOf("protocol"), reportedReasoning = arrayListOf(ReportedReasoning("thought", "reported")))))
    private fun rejected(work: () -> Unit) {
        assertNotNull("expected rejection", runCatching(work).exceptionOrNull())
    }

    @Test fun exactKeyIncludesEveryColumnAndDistinguishesNullUnicodeAndEncodedSpelling() {
        CommitMessageDecodeReuse().use { reuse ->
            var validations = 0
            fun read(value: StoredMessageColumns) = reuse.read(value, ::message) { validations++ }
            val first = read(raw)
            assertSame(first, read(raw.copy()))
            assertEquals(1, reuse.decodeCount)
            val changes = listOf(raw.copy(messageId = "b"), raw.copy(conversationId = "d"),
                raw.copy(runId = "s"), raw.copy(role = "user"), raw.copy(status = "COMPLETED"),
                raw.copy(createdAt = 0), raw.copy(blocks = "[ ]"), raw.copy(error = ""),
                raw.copy(protocol = "[\"数🙂\"]"), raw.copy(history = "[ ]"),
                raw.copy(error = "null"), raw.copy(error = "数🙂"), raw.copy(error = "数🙃"),
                raw.copy(protocol = "[\"\\u6570\\ud83d\\ude42\"]"))
            changes.forEachIndexed { index, columns ->
                read(columns)
                assertEquals(index + 2, reuse.decodeCount)
            }
            assertEquals(changes.size + 2, validations)
        }
    }

    @Test fun budgetAndEntryLimitBypassOrEvictWithoutSkippingValidation() {
        val size = raw.characterCount
        CommitMessageDecodeReuse(maxCharacters = size, maxEntries = 1).use { reuse ->
            var validations = 0
            fun read(value: StoredMessageColumns) = reuse.read(value, ::message) { validations++ }
            read(raw); read(raw)
            assertEquals(1, reuse.decodeCount)
            val other = raw.copy(messageId = "b")
            read(other); read(raw)
            assertEquals(3, reuse.decodeCount)
            repeat(2) { read(raw.copy(error = "too big")) }
            assertEquals(5, reuse.decodeCount)
            assertEquals(1, reuse.retainedEntries)
            assertEquals(size, reuse.retainedCharacters)
            assertEquals(6, validations)
        }
        CommitMessageDecodeReuse(maxCharacters = 0).use { reuse ->
            repeat(2) { reuse.read(raw, ::message) {} }
            assertEquals(2, reuse.decodeCount)
            assertEquals(0, reuse.retainedEntries)
        }
    }

    @Test fun failedDecodeOrValidationIsNeverRetainedAndHitValidationRunsAgain() {
        CommitMessageDecodeReuse().use { reuse ->
            rejected { reuse.read(raw, { error("bad JSON") }) {} }
            rejected { reuse.read(raw, ::message) { error("bad witness") } }
            assertEquals(0, reuse.retainedEntries)
            reuse.read(raw, ::message) {}
            rejected { reuse.read(raw, { error("must hit") }) { error("witness changed") } }
            assertEquals(3, reuse.decodeCount)
            assertEquals(0, reuse.retainedEntries)
            reuse.read(raw, ::message) {}
            assertEquals(4, reuse.decodeCount)
        }
    }

    @Test fun retainedGraphAndReturnedGraphCannotBePollutedThroughAnyListLevel() {
        val source = message()
        val reuse = CommitMessageDecodeReuse()
        val first = reuse.read(raw, { source }) {}
        (source.blocks as MutableList<*>).clear()
        (source.historySegments.single().blocks as MutableList<*>).clear()
        (source.historySegments.single().reportedReasoning as MutableList<*>).clear()
        fun immutable(values: List<*>) = rejected { (values as MutableList<*>).clear() }
        immutable(first.blocks); immutable(first.protocolItems); immutable(first.historySegments)
        immutable(first.reportedReasoning)
        first.historySegments.single().let {
            immutable(it.blocks); immutable(it.protocolItems); immutable(it.reportedReasoning)
        }
        assertEquals("数🙂", (first.blocks.single() as MessageBlock.Text).text)
        assertEquals(1, first.historySegments.single().blocks.size)
        assertEquals(1, first.historySegments.single().reportedReasoning.size)
        assertSame(first, reuse.read(raw, { error("must hit") }) {})
        reuse.close()
        assertEquals(0L, reuse.retainedCharacters)
        rejected { reuse.read(raw, ::message) {} }
        CommitMessageDecodeReuse().use { fresh ->
            fresh.read(raw, ::message) {}
            assertEquals(1, fresh.decodeCount)
        }
    }

    @Test fun historyUtf8LimitsMatchOriginalReplacementAndUnicodeByteSemantics() {
        fun segment(text: String) = MessageHistorySegment(MessageId("a"), "assistant",
            listOf(MessageBlock.Text("t", text)))
        val limit = 4 * 1024 * 1024
        // 4-byte astral text and malformed UTF-16 use the existing UTF-8 replacement policy.
        val accepted = listOf("a".repeat(limit), "🙂".repeat(limit / 4),
            "数".repeat(limit / 3), "\uD800".repeat(limit))
        accepted.forEach { text ->
            assertTrue(text.toByteArray(Charsets.UTF_8).size <= limit)
            MessageHistoryCodec.encode(listOf(segment(text)))
        }
        listOf("a".repeat(limit + 1), "🙂".repeat(limit / 4 + 1), "数".repeat(limit / 3 + 1)).forEach { text ->
            assertTrue(text.toByteArray(Charsets.UTF_8).size > limit)
            rejected { MessageHistoryCodec.encode(listOf(segment(text))) }
        }
    }
}
