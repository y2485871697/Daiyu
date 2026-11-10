package app.daiyu.android

import app.daiyu.android.domain.*
import app.daiyu.android.domain.context.ContextRevision
import org.junit.Assert.*
import org.junit.Test

class MessageActionRenderedRevisionTest {
    private val owner = ConversationId("conversation")
    private val reasoning = ReportedReasoning("reason", "public reasoning", elapsedMillis = 10, blockOffset = 0)
    private val image = MessageBlock.Image(MessageImageReference(
        MessageImageOwner(owner.value, "message"), "00000000-0000-0000-0000-000000000001",
        "image/png", 12, "a".repeat(64), 2, 3))
    private val tool = MessageBlock.Tool(ToolInvocation(ToolCallId("tool"), "lookup", "{\"q\":1}"), "result")
    private fun message(id: String = "message") = Message(
        MessageId(id), owner, RunId("run"), "assistant",
        listOf(MessageBlock.Text("text", "正文🙂"), tool, image), RunStatus.COMPLETED,
        protocolItems = listOf("authoritative protocol"),
        historySegments = listOf(MessageHistorySegment(MessageId(id), "assistant",
            listOf(MessageBlock.Text("segment-text", "segment body"), tool, image),
            listOf("segment protocol"), reportedReasoning = listOf(reasoning))),
        reportedReasoning = listOf(reasoning))

    private class Counting {
        var calls = 0
        val inputs = mutableListOf<List<Message>>()
        val cache = MessageActionRenderedRevision { rows ->
            calls++; inputs.add(rows); ContextRevision.digest(rows)
        }
    }

    @Test fun unchangedContentReusesOriginalDigestAcrossNewContainersAndValueCopies() {
        val f = Counting()
        val original = message()
        val expected = ContextRevision.digest(listOf(original))
        repeat(30) {
            val copied = original.copy(blocks = original.blocks.toMutableList(),
                protocolItems = original.protocolItems.toMutableList(),
                historySegments = original.historySegments.map { segment -> segment.copy(
                    blocks = segment.blocks.toMutableList(), protocolItems = segment.protocolItems.toMutableList(),
                    reportedReasoning = segment.reportedReasoning.map { it.copy() }) },
                reportedReasoning = original.reportedReasoning.map { it.copy() })
            assertEquals(expected, f.cache.revision(owner, mutableListOf(copied)))
        }
        assertEquals(1, f.calls)
    }

    @Test fun sameIdSameCountCorrectionsInvalidateAndAlwaysUseTheOriginalAlgorithm() {
        val original = message()
        val segment = original.historySegments.single()
        fun withSegment(value: MessageHistorySegment) = original.copy(historySegments = listOf(value))
        val corrections = listOf(
            original.copy(blocks = listOf(MessageBlock.Text("text", "corrected body"), tool, image)),
            original.copy(protocolItems = listOf("corrected protocol")),
            original.copy(blocks = listOf(original.blocks[0], tool.copy(result = "corrected result"), image)),
            original.copy(blocks = listOf(original.blocks[0], tool.copy(invocation = tool.invocation.copy(arguments = "{}")), image)),
            original.copy(blocks = listOf(original.blocks[0], tool, image.copy(reference = image.reference.copy(sha256 = "b".repeat(64))))),
            withSegment(segment.copy(blocks = listOf(MessageBlock.Text("segment-text", "corrected segment"), tool, image))),
            withSegment(segment.copy(protocolItems = listOf("corrected segment protocol"))),
            withSegment(segment.copy(blocks = listOf(segment.blocks[0], tool.copy(result = null), image))),
            withSegment(segment.copy(blocks = listOf(segment.blocks[0], tool, image.copy(reference = image.reference.copy(width = 4))))),
            withSegment(segment.copy(reportedReasoning = listOf(reasoning.copy(text = "corrected reasoning")))),
            withSegment(segment.copy(reportedReasoning = listOf(reasoning.copy(elapsedMillis = 20)))),
            original.copy(reportedReasoning = listOf(reasoning.copy(text = "corrected projected reasoning"))),
            original.copy(status = RunStatus.FAILED, error = "corrected failure"),
            original.copy(runId = RunId("other-run")),
            original.copy(conversationId = ConversationId("corrected-conversation")),
            original.copy(role = "user"),
        )
        corrections.forEach { corrected ->
            val f = Counting()
            val before = f.cache.revision(owner, listOf(original))
            val after = f.cache.revision(owner, listOf(corrected))
            assertEquals(original.id, corrected.id)
            assertEquals(original.blocks.size, corrected.blocks.size)
            assertEquals(ContextRevision.digest(listOf(corrected)), after)
            // Timing/expanded reasoning are deliberately not added to the persisted digest format.
            if (ContextRevision.digest(listOf(original)) != ContextRevision.digest(listOf(corrected))) assertNotEquals(before, after)
            assertEquals(after, f.cache.revision(owner, listOf(corrected.copy())))
            assertEquals(2, f.calls)
        }
    }

    @Test fun inPlaceMutableAliasesCannotPolluteThePreviousFrozenProjection() {
        val original = message()
        val blocks = original.blocks.toMutableList()
        val protocol = original.protocolItems.toMutableList()
        val segment = original.historySegments.single()
        val segmentBlocks = segment.blocks.toMutableList()
        val segmentProtocol = segment.protocolItems.toMutableList()
        val segmentReasoning = segment.reportedReasoning.toMutableList()
        val segments = mutableListOf(segment.copy(blocks = segmentBlocks, protocolItems = segmentProtocol,
            reportedReasoning = segmentReasoning))
        val projectedReasoning = original.reportedReasoning.toMutableList()
        val rows = mutableListOf(original.copy(blocks = blocks, protocolItems = protocol,
            historySegments = segments, reportedReasoning = projectedReasoning))
        val f = Counting()
        val first = f.cache.revision(owner, rows)
        val frozen = f.inputs.single()
        val changes: List<() -> Unit> = listOf(
            { blocks[0] = MessageBlock.Text("text", "mutated body") },
            { protocol[0] = "mutated protocol" },
            { segmentBlocks[1] = tool.copy(result = "mutated nested result") },
            { segmentProtocol[0] = "mutated nested protocol" },
            { segmentReasoning[0] = reasoning.copy(text = "mutated nested reasoning") },
            { projectedReasoning[0] = reasoning.copy(text = "mutated projected reasoning") },
            { segments[0] = segments[0].copy(role = "user") },
            { rows[0] = rows[0].copy(error = "mutated outer list") },
        )
        changes.forEachIndexed { index, mutate ->
            mutate()
            assertEquals(ContextRevision.digest(rows), f.cache.revision(owner, rows))
            assertEquals(index + 2, f.calls)
            assertEquals(listOf(original), frozen)
            assertEquals(first, ContextRevision.digest(frozen))
        }
    }

    @Test fun reorderInsertRemoveAndEmptyHistoryReplaceTheSingleEntry() {
        val f = Counting()
        val first = message()
        val second = message("second")
        val versions = listOf(listOf(first, second), listOf(second, first), listOf(first),
            emptyList(), listOf(first), listOf(first, second))
        versions.forEachIndexed { index, rows ->
            assertEquals(ContextRevision.digest(rows), f.cache.revision(owner, rows))
            assertEquals(ContextRevision.digest(rows), f.cache.revision(owner, rows.toMutableList()))
            assertEquals(index + 1, f.calls)
        }
    }

    @Test fun conversationSwitchEvictsAndActivityInstancesNeverShareEntries() {
        val f = Counting()
        val rows = listOf(message())
        val expected = ContextRevision.digest(rows)
        assertEquals(expected, f.cache.revision(owner, rows))
        assertEquals(expected, f.cache.revision(ConversationId("other"), rows))
        assertEquals(expected, f.cache.revision(owner, rows))
        assertEquals(3, f.calls) // A -> B -> A is not a conversation-indexed cache.
        val otherActivity = Counting()
        assertEquals(expected, otherActivity.cache.revision(owner, rows))
        assertEquals(1, otherActivity.calls)
        assertEquals(expected, f.cache.revision(owner, rows))
        assertEquals(3, f.calls)
    }
}
