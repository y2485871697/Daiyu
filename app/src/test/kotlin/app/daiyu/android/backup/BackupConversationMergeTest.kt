package app.daiyu.android.backup

import app.daiyu.android.context.PrivateBlobs
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BackupConversationMergeTest {
    private fun text(id: String, messages: Int = 1, draft: String = ""): String {
        val payload = JSONObject().put("conversations", JSONArray().put(JSONObject().put("id", id)))
            .put("messages", JSONArray().apply { repeat(messages) { put(JSONObject().put("id", "$id-$it").put("conversation_id", id)) } })
            .put("draft", draft).toString()
        return JSONObject().put("payload", payload).toString()
    }
    @Test fun mergesOneTextEnvelopeWithCorrectCountsDigestAndNoGlobalDraft() {
        val result = JSONObject(BackupConversationMerge.text(listOf(text("A"), text("C", 2))))
        assertEquals("daiyu-neutral-text", result.getString("format")); assertEquals(3, result.getInt("version"))
        assertEquals(2, result.getInt("conversation_count")); assertEquals(3, result.getInt("message_count"))
        assertEquals(PrivateBlobs.digest(result.getString("payload").toByteArray(Charsets.UTF_8)), result.getString("sha256"))
        val payload = JSONObject(result.getString("payload"))
        assertEquals("", payload.getString("draft"))
        assertEquals(listOf("A", "C"), (0 until 2).map { payload.getJSONArray("conversations").getJSONObject(it).getString("id") })
        assertEquals(listOf("A", "C", "C"), (0 until 3).map { payload.getJSONArray("messages").getJSONObject(it).getString("conversation_id") })
    }
    @Test fun mergesSectionRowsButNeverAddsVersionsOrMutatesInputs() {
        val first = JSONObject().put("version", 5).put("tasks", JSONArray().put("A")).put("usage", JSONArray())
        val second = JSONObject().put("version", 5).put("tasks", JSONArray().put("C")).put("usage", JSONArray().put("receipt"))
        val result = BackupConversationMerge.sections(listOf(first, second))
        assertEquals(5, result.getInt("version")); assertEquals("[\"A\",\"C\"]", result.getJSONArray("tasks").toString())
        assertEquals(1, result.getJSONArray("usage").length()); assertEquals(1, first.getJSONArray("tasks").length())
    }
    @Test fun mergesUnversionedTables() {
        val first = JSONObject().put("context_archives", JSONArray().put("A"))
        val second = JSONObject().put("context_archives", JSONArray().put("C"))
        assertEquals(2, BackupConversationMerge.sections(listOf(first, second)).getJSONArray("context_archives").length())
    }
    @Test fun rejectsMixedSectionVersionsAndFields() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupConversationMerge.sections(listOf(JSONObject("{\"version\":1,\"rows\":[]}"), JSONObject("{\"version\":2,\"rows\":[]}")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupConversationMerge.sections(listOf(JSONObject("{\"rows\":[]}"), JSONObject("{\"other\":[]}")))
        }
    }
    @Test fun rejectsGlobalDraftAndCombinedMessageBudget() {
        assertThrows(IllegalArgumentException::class.java) { BackupConversationMerge.text(listOf(text("A", draft = "global"), text("B"))) }
        assertThrows(IllegalArgumentException::class.java) { BackupConversationMerge.text(listOf(text("A", 25001), text("B", 25000))) }
    }
    @Test fun rejectsCombinedConversationBudget() {
        assertThrows(IllegalArgumentException::class.java) { BackupConversationMerge.text(List(1001) { text("C-$it", 0) }) }
    }
    @Test fun aggregateHistoryRowsRejectBeforeCollectingAnotherCapture() {
        val budget = BackupConversationMerge.Budget()
        fun section(count: Int) = JSONObject().put("version", 1).put("requests", JSONArray().apply { repeat(count) { put(it) } })
        budget.include("statistics", section(25001))
        assertThrows(IllegalArgumentException::class.java) { budget.include("statistics", section(25000)) }
    }
    @Test fun aggregateHistoryBytesAndRuntimeSectionBudgetRejectIndividuallyValidParts() {
        val first = JSONObject().put("version", 1).put("requests", JSONArray().put("x".repeat(100)))
        val second = JSONObject().put("version", 1).put("requests", JSONArray().put("y".repeat(100)))
        val arrays = BackupConversationMerge.Budget()
        arrays.include("statistics", first, 150)
        assertThrows(IllegalArgumentException::class.java) { arrays.include("statistics", second, 150) }
        val runtime = BackupConversationMerge.Budget()
        runtime.include("runtime", first, sectionLimit = 200)
        assertThrows(IllegalArgumentException::class.java) { runtime.include("runtime", second, sectionLimit = 200) }
    }

    @Test fun aggregateContextUsageKeepsHistoryBudgetInsideLargerTableBudget() {
        val budget = BackupConversationMerge.Budget()
        fun tables() = JSONObject().put("context_usage", JSONArray().put("x".repeat(4 * 1024 * 1024)))
        budget.include("tables", tables(), NeutralBackupService.MAX_PACKAGE)
        assertThrows(IllegalArgumentException::class.java) {
            budget.include("tables", tables(), NeutralBackupService.MAX_PACKAGE)
        }
    }

}
