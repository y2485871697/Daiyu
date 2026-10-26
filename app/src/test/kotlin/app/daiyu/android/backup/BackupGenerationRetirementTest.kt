package app.daiyu.android.backup

import app.daiyu.android.domain.*
import app.daiyu.android.model.generation.GenerationProtocol
import app.daiyu.android.storage.GenerationAttemptBinding
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BackupGenerationRetirementTest {
    private val binding = GenerationAttemptBinding("conversation", "assistant", "run", "cut-call", "generate_images")
    private fun attempt() = JSONObject().put("operation_id", binding.operationId).put("item_id", "item")
        .put("conversation_id", binding.conversationId).put("assistant_id", binding.assistantMessageId)
        .put("run_id", binding.runId).put("tool_call_id", binding.toolCallId).put("tool_name", binding.toolName)
        .put("fingerprint", "a".repeat(64)).put("protocol", GenerationProtocol.OPENAI_IMAGES_JSON.name)
    private fun evidence(output: Boolean = false): JSONObject {
        val outputs = JSONArray()
        if (output) outputs.put(JSONObject(attempt().toString()).put("output_index", 0).put("account_scope", "local")
            .put("receipt", "genasset-v1-${"b".repeat(64)}-${"c".repeat(64)}").put("kind", "IMAGE")
            .put("mime_type", "image/png").put("byte_count", 4).put("sha256", "c".repeat(64))
            .put("width", 2).put("height", 2).put("duration_ms", 0))
        return JSONObject().put("version", 1).put("attempts", JSONArray().put(attempt())).put("outputs", outputs)
    }
    private fun owner(call: Boolean = false): Message = Message(MessageId("assistant"), ConversationId("conversation"),
        RunId("run"), Message.ASSISTANT_ROLE, if (!call) emptyList() else listOf(MessageBlock.Tool(
            ToolInvocation(ToolCallId("cut-call"), "generate_images", "{}"), "done")), RunStatus.COMPLETED)
    private fun reject(block: () -> Unit) {
        try { block(); fail("expected rejection") } catch (_: IllegalArgumentException) {} catch (_: IllegalStateException) {}
    }
    @Test fun retiredAttemptWithSurvivingOwnerStaysATombstone() {
        assertEquals(1, BackupGenerationCodec.validate(evidence(), staticMessageRows(listOf(owner()))).attempts.size)
        assertEquals(1, BackupGenerationCodec.validate(evidence(), JSONArray()).attempts.size)
    }
    @Test fun outputCannotOutliveExactHistoricalCall() {
        reject { BackupGenerationCodec.validate(evidence(true), staticMessageRows(listOf(owner()))) }
        assertEquals(1, BackupGenerationCodec.validate(evidence(true), staticMessageRows(listOf(owner(true)))).outputs.size)
        reject { BackupGenerationCodec.validate(evidence(true), staticMessageRows(listOf(owner(true).copy(runId = RunId("changed"))))) }
    }
    @Test fun retirementDoesNotRelaxIdentityOrFingerprint() {
        val bad = evidence().also { it.getJSONArray("attempts").getJSONObject(0).put("fingerprint", "bad") }
        reject { BackupGenerationCodec.validate(bad, staticMessageRows(listOf(owner()))) }
        val duplicate = evidence().also { it.getJSONArray("attempts").put(attempt()) }
        reject { BackupGenerationCodec.validate(duplicate, staticMessageRows(listOf(owner()))) }
    }
    @Test fun nonemptySegmentsRemainAuthorityOverLegacyBlocks() {
        val source = owner(true)
        val cut = source.copy(historySegments = listOf(MessageHistorySegment(source.id, Message.ASSISTANT_ROLE,
            listOf(MessageBlock.Text("answer", "kept")))))
        assertTrue(MessageGenerationCut.calls(listOf(cut)).isEmpty())
        reject { BackupGenerationCodec.validate(evidence(true), staticMessageRows(listOf(cut))) }
    }
}
