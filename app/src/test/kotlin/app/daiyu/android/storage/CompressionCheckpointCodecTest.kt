package app.daiyu.android.storage

import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CompressionCheckpointCodecTest {
    private fun checkpoint(): RunCheckpoint {
        val request = RequestCheckpoint("main", "a", "owner", RequestOperation.NEXT)
        val summary = RequestCheckpoint("summary", "s", "owner", RequestOperation.NEXT)
        return RunCheckpoint(Message(MessageId("m"), ConversationId("c"), RunId("r"), "assistant",
            emptyList(), RunStatus.RUNNING), emptyList(), ExecutionPhase.BOUNDARY, owner = "owner", revision = 1,
            request = request, usageRequests = listOf(request), context = RunContextCheckpoint(
                RequestInputCheckpoint(request, 123, null, 100), RequestInputCheckpoint(request, 123, 80, 100),
                request, listOf(summary)))
    }
    @Test fun v9KeepsSameRequestAnchorsAndNoUsageSummaryAdmission() {
        val checkpoint = checkpoint()
        val encoded = RunCheckpointCodec.encode(checkpoint)
        assertEquals(9, JSONObject(encoded).getInt("version"))
        assertEquals(checkpoint, RunCheckpointCodec.decode(encoded))
        assertEquals(checkpoint.context, ProcessRecovery.interrupt(RunCheckpointCodec.decode(encoded)).context)
        val legacy = checkpoint.copy(context = null)
        assertEquals(6, JSONObject(RunCheckpointCodec.encode(legacy)).getInt("version"))
        assertEquals(legacy, RunCheckpointCodec.decode(RunCheckpointCodec.encode(legacy)))
    }
    @Test fun v9LogicalSegmentsRoundTripWithoutWeakeningPhysicalArchives() {
        val base = checkpoint()
        val call = ToolInvocation(ToolCallId("read"), "read", "{}")
        val toolSegment = base.message.copy(status = RunStatus.COMPLETED,
            blocks = listOf(MessageBlock.Tool(call, "done")))
        val textSegment = toolSegment.copy(blocks = listOf(MessageBlock.Text("answer", "finished")),
            reportedReasoning = listOf(ReportedReasoning("reason", "reported")))
        val value = base.copy(history = listOf(toolSegment, textSegment),
            context = base.context!!.copy(compactions = 1))
        val encoded = RunCheckpointCodec.encode(value)
        assertEquals(9, JSONObject(encoded).getInt("version"))
        assertEquals(value, RunCheckpointCodec.decode(encoded))
        assertEquals(value.context, ProcessRecovery.interrupt(RunCheckpointCodec.decode(encoded)).context)
        assertThrows(IllegalArgumentException::class.java) {
            app.daiyu.android.context.ArchiveCodec.encode(value.history)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RunCheckpointCodec.encode(value.copy(context = null))
        }
        assertThrows(IllegalArgumentException::class.java) {
            app.daiyu.android.context.ArchiveCodec.decode(JSONObject(encoded).getJSONArray("history")
                .apply { getJSONObject(1).remove("reported_reasoning") }.toString().toByteArray())
        }
        val legacyEncoded = JSONObject(RunCheckpointCodec.encode(base.copy(context = null)))
            .put("history", JSONObject(encoded).getJSONArray("history")
                .apply { getJSONObject(1).remove("reported_reasoning") })
        assertThrows(IllegalArgumentException::class.java) { RunCheckpointCodec.decode(legacyEncoded.toString()) }
        val duplicateReasoning = JSONObject(encoded)
        val parts = duplicateReasoning.getJSONArray("history").getJSONObject(1).getJSONArray("reported_reasoning")
        parts.put(parts.getJSONObject(0))
        assertThrows(IllegalArgumentException::class.java) { RunCheckpointCodec.decode(duplicateReasoning.toString()) }
        assertThrows(IllegalArgumentException::class.java) {
            RunCheckpointCodec.encode(value.copy(history = listOf(textSegment.copy(role = Message.USER_ROLE))))
        }
        val invalid = JSONObject(encoded)
        invalid.getJSONArray("history").getJSONObject(1).put("role", "not-a-role")
        assertThrows(IllegalArgumentException::class.java) { RunCheckpointCodec.decode(invalid.toString()) }
    }

    @Test fun rejectsForeignAnchorsAndStrictNumberAndDuplicateSummary() {
        val value = checkpoint()
        assertThrows(IllegalArgumentException::class.java) { RunCheckpointCodec.encode(value.copy(context =
            value.context!!.copy(input = value.context!!.input!!.copy(request = value.request!!.copy(requestId = "foreign"))))) }
        val json = JSONObject(RunCheckpointCodec.encode(value))
        json.getJSONObject("context").getJSONObject("input").put("local", "123")
        assertThrows(IllegalArgumentException::class.java) { RunCheckpointCodec.decode(json.toString()) }
        assertThrows(IllegalArgumentException::class.java) { RunCheckpointCodec.encode(value.copy(context =
            value.context!!.copy(summaries = value.context!!.summaries + value.context!!.summaries))) }
    }
    @Test fun v10CapacityStopSurvivesCodecAndProcessInterruptionWithoutResumeAdmission() {
        val base = checkpoint()
        val message = base.message.copy(blocks = listOf(MessageBlock.Text("text", "stopped here")))
        val capacity = ContextCapacityCheckpoint("limit", 123L, 280_000L, 272_000L, 270_000L, 0)
        val value = base.copy(message = message, phase = ExecutionPhase.PAUSED,
            context = base.context!!.copy(capacity = capacity))
        val json = RunCheckpointCodec.encode(value)
        assertEquals(10, JSONObject(json).getInt("version"))
        assertEquals(value, RunCheckpointCodec.decode(json))
        assertEquals(capacity, ProcessRecovery.interrupt(RunCheckpointCodec.decode(json)).context!!.capacity)
        val cleared = value.copy(context = value.context!!.copy(capacity = null))
        assertEquals(9, JSONObject(RunCheckpointCodec.encode(cleared)).getInt("version"))
        assertThrows(IllegalArgumentException::class.java) {
            RunCheckpointCodec.encode(value.copy(context = value.context!!.copy(capacity = capacity.copy(segment = 2))))
        }
        val negativeSegment = JSONObject(json)
        negativeSegment.getJSONObject("context").getJSONObject("capacity").put("segment", -4294967296L)
        assertThrows(IllegalArgumentException::class.java) { RunCheckpointCodec.decode(negativeSegment.toString()) }
        val invalid = JSONObject(json)
        invalid.getJSONObject("context").getJSONObject("capacity").put("estimated", 270_000L)
        assertThrows(IllegalArgumentException::class.java) { RunCheckpointCodec.decode(invalid.toString()) }
    }

    @Test fun capacityAnchorCountsOnlyItsAssistantSegmentsNotSupplementRows() {
        val base = checkpoint()
        val block = MessageBlock.Text("answer", "before supplement")
        val message = base.message.copy(blocks = listOf(block), historySegments = listOf(
            MessageHistorySegment(base.message.id, Message.ASSISTANT_ROLE, listOf(block)),
            MessageHistorySegment(MessageId("supplement"), Message.USER_ROLE,
                listOf(MessageBlock.Text("supplement", "extra instruction")))))
        val capacity = ContextCapacityCheckpoint("limit", 123L, 280_000L, 272_000L, 270_000L, 0)
        val value = base.copy(message = message, phase = ExecutionPhase.PAUSED,
            context = base.context!!.copy(capacity = capacity))
        assertEquals(value, RunCheckpointCodec.decode(RunCheckpointCodec.encode(value)))
        assertThrows(IllegalArgumentException::class.java) {
            RunCheckpointCodec.encode(value.copy(context = value.context!!.copy(capacity = capacity.copy(segment = 1))))
        }
        val invalid = JSONObject(RunCheckpointCodec.encode(value))
        invalid.getJSONObject("context").getJSONObject("capacity").put("segment", 1)
        assertThrows(IllegalArgumentException::class.java) { RunCheckpointCodec.decode(invalid.toString()) }
    }

}
