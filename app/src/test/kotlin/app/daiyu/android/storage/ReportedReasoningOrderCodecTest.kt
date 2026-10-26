package app.daiyu.android.storage

import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.*
import app.daiyu.android.context.ArchiveCodec
import app.daiyu.android.ui.screens.chat.conversation.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReportedReasoningOrderCodecTest {
    @Test fun optionalPositionRoundTripsAndLegacyDoesNotInventAnOrder() {
        val reported = ReportedReasoning("r", "**summary**", elapsedMillis = 900, blockOffset = 1)
        assertEquals(reported, ReportedReasoningCodec.decode(ReportedReasoningCodec.encode(reported)))
        val legacy = ReportedReasoningCodec.encode(ReportedReasoning("old", "summary"))
        assertFalse(legacy.has("block_offset"))
        assertNull(ReportedReasoningCodec.decode(legacy).blockOffset)
    }
    @Test fun rejectsInvalidOffsetsAndUnknownKeys() {
        for (invalid in listOf(-1, Int.MAX_VALUE.toLong() + 1, 1.5, "1", true, JSONObject.NULL)) {
            try { ReportedReasoningCodec.decode(JSONObject().put("item_id", "r").put("text", "summary").put("block_offset", invalid)); fail("accepted $invalid") }
            catch (_: IllegalArgumentException) { }
        }
        try { ReportedReasoningCodec.decode(JSONObject().put("item_id", "r").put("text", "s").put("hidden", "x")); fail() }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun savedHistoryReopensWithTheSameWorkStepsAndMarkdownAndElapsedValues() {
        val blocks = listOf(MessageBlock.Tool(ToolInvocation(ToolCallId("t1"), "lookup", "{}"), "done"),
            MessageBlock.Tool(ToolInvocation(ToolCallId("t2"), "lookup", "{}"), "done"), MessageBlock.Text("a", "answer"))
        val summaries = listOf(ReportedReasoning("r1", "**first**", 100, blockOffset = 0),
            ReportedReasoning("r2", "`second`", 200, blockOffset = 1))
        val segment = MessageHistorySegment(MessageId("a"), "assistant", blocks, listOf("untouched protocol"), reportedReasoning = summaries)
        val message = Message(MessageId("a"), ConversationId("c"), RunId("r"), "assistant", blocks, RunStatus.COMPLETED, historySegments = listOf(segment))
        val restored = message.copy(historySegments = MessageHistoryCodec.decode(MessageHistoryCodec.encode(message.historySegments)))
        assertEquals(message, restored)
        assertEquals(ChatTurnMapper.map(listOf(message)), ChatTurnMapper.map(listOf(restored)))
    }
    @Test fun historyCodecRejectsPositionBeyondItsSegment() {
        val invalid = MessageHistorySegment(MessageId("a"), "assistant", emptyList(),
            reportedReasoning = listOf(ReportedReasoning("r", "summary", blockOffset = 1)))
        try { MessageHistoryCodec.encode(listOf(invalid)); fail() } catch (_: IllegalArgumentException) { }
    }
    private fun archivedOwner(): Message {
        val blocks = listOf(MessageBlock.Text("a", "answer"))
        val segment = MessageHistorySegment(MessageId("a"), "assistant", blocks,
            reportedReasoning = listOf(ReportedReasoning("r1", "**first**", 100, blockOffset = 0),
                ReportedReasoning("r2", "second", 200, blockOffset = 1)))
        return Message(MessageId("a"), ConversationId("c"), RunId("r"), "assistant", blocks,
            RunStatus.RUNNING, historySegments = listOf(segment))
    }
    @Test fun physicalArchiveAndVersionTwoEnvelopeKeepReasoningPositions() {
        val original = listOf(archivedOwner())
        assertEquals(original, ArchiveCodec.decode(ArchiveCodec.encode(original)))
        val document = ArchiveCodec.decodeDocument(ArchiveCodec.encodeDocument(original, 1))
        assertEquals(original, document.messages)
        assertEquals(1, document.logicalPrefixCount)
    }
    @Test fun normalAndContextCheckpointsKeepOffsetsThroughDecodeAndRecovery() {
        val physical = archivedOwner()
        val normal = RunCheckpoint(physical, emptyList(), ExecutionPhase.BOUNDARY, owner = "owner", revision = 1)
        val logical = normal.copy(history = MessageHistory.expand(listOf(physical.copy(status = RunStatus.COMPLETED))),
            context = RunContextCheckpoint())
        for (value in listOf(normal, logical)) {
            val decoded = RunCheckpointCodec.decode(RunCheckpointCodec.encode(value))
            assertEquals(value, decoded)
            assertEquals(listOf(0, 1), decoded.message.historySegments.single().reportedReasoning.map { it.blockOffset })
            assertEquals(decoded.history, ProcessRecovery.interrupt(decoded).history)
        }
    }
}
