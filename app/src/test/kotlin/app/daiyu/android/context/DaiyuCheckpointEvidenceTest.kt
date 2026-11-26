package app.daiyu.android.context

import app.daiyu.android.domain.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DaiyuCheckpointEvidenceTest {
    private val row = Message(MessageId("message"), ConversationId("c"), RunId("r"), Message.ASSISTANT_ROLE,
        emptyList(), RunStatus.COMPLETED)
    private val cancellation = ContextPreparationCancellation()

    @Test fun confirmsOnlyIdentifiedCompleteCommandResults() {
        fun event(status: String, id: String = "12345", execution: String = "executed") = row.copy(blocks = listOf(
            MessageBlock.Tool(ToolInvocation(ToolCallId("call"), "terminal_exec", JSONObject()
                .put("command", "gh run view 12345 -R owner/repo --json status,conclusion").toString()),
                JSONObject().put("ok", true).put("execution", execution).put("output_complete", true).put("exit_code", 0)
                    .put("stdout", JSONObject().put("status", status).put("databaseId", id).toString()).toString())))
        val evidence = DaiyuCheckpointEvidence.collect(listOf(event("in_progress"), event("completed"),
            event("queued", execution = "unknown"), event("completed", "67890")), cancellation)
        assertTrue(evidence.prompt().contains("in_progress"))
        assertFalse(evidence.prompt().contains("67890"))
        assertFalse(evidence.prompt().contains("queued"))
        assertThrows(IllegalArgumentException::class.java) {
            evidence.finish("## Pending Jobs\n- 12345 仍在运行\n## Current Work\n- recorded")
        }
        val summary = evidence.finish("## Next Step\n- 下载 12345 的产物\n## Critical Context\n- facts")
        assertTrue(summary.contains("completed"))
        assertFalse(summary.contains("in_progress"))
        val framed = SummaryCheckpoint.frame(SummaryCheckpoint.withArchive(summary, "source"))
        assertTrue(SummaryCheckpoint.unframe(framed).orEmpty().contains("context-checkpoint:source"))
        assertFalse(SummaryCheckpoint.display(framed).contains("context-checkpoint:"))
    }

    @Test fun onlyMissingCallsBecomeReadOnlyEvidence() {
        val output = """{"type":"function_call_output","call_id":"call","output":"known"}"""
        val call = """{"type":"function_call","call_id":"call","name":"inspect","arguments":"{}"}"""
        val orphan = row.copy(protocolItems = listOf(output))
        val prepared = DaiyuSummaryEvidence.prepare(listOf(orphan), cancellation, null)
        assertFalse(prepared.replayable)
        assertEquals("historical_tool", prepared.messages.last().role)
        assertTrue(SummaryEvidence.messages(prepared.messages).last().contains("known"))
        assertTrue(prepared.messages.all { it.protocolItems.isEmpty() })
        assertEquals(listOf(output), orphan.protocolItems)
        for (invalid in listOf(listOf(call), listOf(output, call), listOf(call, output, output))) {
            assertThrows(IllegalArgumentException::class.java) {
                DaiyuSummaryEvidence.prepare(listOf(row.copy(protocolItems = invalid)), cancellation, null)
            }
        }
    }
}
