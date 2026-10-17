package app.daiyu.android.backup

import app.daiyu.android.model.generation.GenerationKind
import app.daiyu.android.model.generation.GenerationProtocol
import app.daiyu.android.storage.GenerationAttemptBinding
import app.daiyu.android.storage.StaticGenerationDestination
import app.daiyu.android.storage.StaticGenerationOutput
import app.daiyu.android.storage.StaticGenerationProvenance
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Codec/model tests only. SQLite and real PNG inspection are covered by new device sources. */
class StaticGenerationCodecTest {
    private val hash = "a".repeat(64)
    private fun output(assistant: String = "destination-assistant"): StaticGenerationOutput {
        val binding = GenerationAttemptBinding("source-conversation", "source-assistant", "source-run", "source-call", "generate_images")
        val source = StaticGenerationProvenance("source-account", binding.operationId, "item", 3,
            binding.conversationId, binding.assistantMessageId, binding.runId, binding.toolCallId, binding.toolName,
            "b".repeat(64), GenerationProtocol.OPENAI_IMAGES_JSON, "genasset-v1-${"c".repeat(64)}-$hash",
            GenerationKind.IMAGE, "image/png", 4, hash, 2, 2, 0)
        return StaticGenerationOutput(source, StaticGenerationDestination("destination-account", "destination-conversation",
            assistant, "destination-run", "destination-call", source.toolName))
    }
    private fun messages(value: StaticGenerationOutput): JSONArray = with(value.destination) {
        JSONArray().put(JSONObject().put("id", assistantMessageId).put("conversation_id", conversationId)
            .put("run_id", runId).put("role", "assistant").put("blocks", JSONArray().put(JSONObject()
                .put("type", "tool").put("id", toolCallId).put("name", toolName).put("result", "UNKNOWN history"))))
    }
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected static contract rejection") }
        catch (_: IllegalArgumentException) { }
        catch (_: IllegalStateException) { }
        catch (_: org.json.JSONException) { }
    }

    @Test fun allSourceScalarsRoundTripAndDestinationIsIndependentOfOrigin() {
        val first = output(); val second = output("another-assistant")
        assertEquals(first.source, second.source)
        assertNotEquals(first.identity, second.identity)
        assertTrue(first.identity.matches(Regex("genstatic-v1-[0-9a-f]{64}")))
        assertEquals(first, StaticGenerationCodec.decode(StaticGenerationCodec.encode(first)))
        assertEquals(StaticGenerationCodec.KIND, JSONObject(StaticGenerationCodec.encode(first)).getString("kind"))
        assertEquals(4L, StaticGenerationCodec.validateBatch(listOf(first, second))) // hash dedup BEFORE source read
        val again = first.copy(destination = second.destination.copy(conversationId = "third-branch"))
        assertEquals(first.source, again.source)
        assertNotEquals(first.identity, again.identity)
        assertTrue(again.historicalOnly)
        assertFalse(first.toString().contains(first.source.receipt))
        assertFalse(first.source.toString().contains(first.source.accountScope))
    }

    @Test fun strictFieldsTypesIdentitiesMediaAndCanonicalInput() {
        val value = output()
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.put("endpoint", "https://not-allowed.invalid") },
            { it.put("version", 2) }, { it.put("kind", "generation_output") },
            { it.put("identity", output("foreign-owner").identity) },
            { it.getJSONObject("source").put("headers", JSONObject()) },
            { it.getJSONObject("destination").put("account_scope", "https://remote.invalid") },
            { it.getJSONObject("destination").put("tool_name", "renamed") },
            { it.getJSONObject("source").put("output_index", "3") },
            { it.getJSONObject("source").put("output_index", 32) },
            { it.getJSONObject("source").put("output_index", 3.0) },
            { it.getJSONObject("source").put("byte_count", 0) },
            { it.getJSONObject("source").put("width", Long.MAX_VALUE) },
            { it.getJSONObject("source").put("duration_ms", 1) },
            { it.getJSONObject("source").put("mime_type", "image/png; charset=utf-8") },
            { it.getJSONObject("source").put("fingerprint", "private arbitrary token") },
            { it.getJSONObject("source").put("sha256", "A".repeat(64)) },
            { it.getJSONObject("source").put("receipt", "genasset-v1-${"c".repeat(64)}-${"d".repeat(64)}") },
            { it.getJSONObject("source").put("protocol", "OPENAI_VIDEO_MULTIPART") },
            { it.getJSONObject("source").put("conversation_id", "forged-source") },
            { it.getJSONObject("source").put("assistant_id", "forged-source") },
            { it.getJSONObject("source").put("run_id", "forged-source") },
            { it.getJSONObject("source").put("tool_call_id", "forged-source") },
            { it.getJSONObject("source").put("item_id", "../secret") },
            { it.getJSONObject("destination").put("assistant_id", "owner\n") },
            { it.getJSONObject("source").remove("receipt") },
        )
        mutations.forEach { mutate -> rejects {
            StaticGenerationCodec.decode(JSONObject(StaticGenerationCodec.encode(value)).also(mutate))
        } }
        val raw = StaticGenerationCodec.encode(value)
        rejects { StaticGenerationCodec.decode(" $raw") }
        rejects { StaticGenerationCodec.decode(raw + "{}") }
        rejects { StaticGenerationCodec.decode(raw.replace("\"version\":1", "\"version\":1,\"version\":1")) }
        rejects { StaticGenerationCodec.decode(raw.replace("\"byte_count\":4", "\"byte_count\":4.0")) }
        rejects { StaticGenerationCodec.decode(" ".repeat(StaticGenerationCodec.MAX_RECORD_BYTES + 1)) }
    }

    @Test fun exactDestinationCallIsRequiredButDeletedSourceIsNot() {
        val value = output()
        val section = StaticGenerationCodec.encodeSection(listOf(value))
        // Only destination is present. No source message/tombstone/live output is required.
        assertEquals(listOf(value), StaticGenerationCodec.decodeSection(section, messages(value)))
        val missingCall = messages(value).also { it.getJSONObject(0).put("blocks", JSONArray()) }
        rejects { StaticGenerationCodec.decodeSection(section, missingCall) }
        val wrongCall = messages(value).also { it.getJSONObject(0).getJSONArray("blocks").getJSONObject(0).put("name", "renamed") }
        rejects { StaticGenerationCodec.decodeSection(section, wrongCall) }
        val duplicate = messages(value).also { it.put(it.getJSONObject(0)) }
        rejects { StaticGenerationCodec.decodeSection(section, duplicate) }
        rejects { StaticGenerationCodec.decodeSection(section, messages(value).also { it.getJSONObject(0).put("run_id", "wrong-run") }) }
        rejects { StaticGenerationCodec.decodeSection(JSONObject(section.toString()).put("unknown", 1), messages(value)) }
    }

    @Test fun batchRejectsDuplicateSlotsRowLimitAndTotalOriginalBudget() {
        val value = output()
        rejects { StaticGenerationCodec.validateBatch(listOf(value, value)) }
        rejects { StaticGenerationCodec.validateBatch(List(BackupHistoryRows.MAX_ROWS + 1) { value }) }
        fun video(id: String, sha: String) = value.copy(source = value.source.copy(itemId = id,
            kind = GenerationKind.VIDEO, protocol = GenerationProtocol.OPENAI_VIDEO_MULTIPART,
            mimeType = "video/mp4", bytes = 40L * 1024 * 1024, sha256 = sha,
            receipt = "genasset-v1-${"c".repeat(64)}-$sha", durationMs = 1000))
        rejects { StaticGenerationCodec.validateBatch(listOf(video("one", "e".repeat(64)), video("two", "f".repeat(64)))) }
        val inconsistentSize = value.copy(source = value.source.copy(itemId = "different", bytes = 5))
        rejects { StaticGenerationCodec.validateBatch(listOf(value, inconsistentSize)) }
        // Fingerprint is immutable record evidence; it does not allocate a second slot.
        val changed = value.copy(source = value.source.copy(fingerprint = "d".repeat(64)))
        assertEquals(value.identity, changed.identity)
        assertNotEquals(value, changed)
        rejects { StaticGenerationCodec.validateBatch(listOf(value, changed)) }
    }
}
