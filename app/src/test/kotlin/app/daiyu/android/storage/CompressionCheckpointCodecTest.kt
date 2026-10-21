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
}
