package app.daiyu.android.backup

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LargeChildTaskBackupTest {
    private fun task(index: Long) = JSONObject().apply {
        put("task_id", "child-$index"); put("conversation_id", "conversation"); put("parent_run_id", "run")
        put("parent_message_id", "parent"); put("parent_owner_id", "owner"); put("task_group_id", "group")
        put("generation", 1); put("task_index", index); put("assistant_message_id", "assistant-$index")
        put("profile_id", "review"); put("prompt", "task $index"); put("status", "COMPLETED"); put("text", "done")
        put("detail", JSONObject.NULL); put("control_revision", 0); put("source_task_id", JSONObject.NULL)
        put("command_kind", JSONObject.NULL)
        for (key in listOf("agent_name_snapshot", "model_id_snapshot", "model_name_snapshot")) put(key, JSONObject.NULL)
    }
    private fun validate(tasks: List<JSONObject>) = BackupChildTaskCodec.validate(JSONObject().put("version", 5)
        .put("tasks", JSONArray(tasks)).put("commands", JSONArray()).put("usage", JSONArray()),
        JSONArray().put(JSONObject().put("id", "parent").put("conversation_id", "conversation")
            .put("run_id", "run").put("role", "assistant")))
    @Test fun sixteenTaskHistoryKeepsEveryIndexInsteadOfFailingAtEight() {
        val result = validate((0L until 16).map(::task))
        assertEquals((0L until 16).toList(), result.tasks.map { it.getLong("task_index") })
    }
    @Test fun indexConversionStillRejectsNegativeAndOverflowingValues() {
        for (index in listOf(-1L, Int.MAX_VALUE.toLong() + 1))
            assertThrows(IllegalArgumentException::class.java) { validate(listOf(task(index))) }
    }
    @Test fun knownFailureKeepsItsStatusAndSafeReasonAcrossBackupValidationAndHistoricalRestore() {
        val failed = task(0).put("status", "FAILED").put("text", "").put("detail", "provider rejected")
        val validated = validate(listOf(failed)).tasks.single()
        val history = BackupChildTaskCodec.terminal(validated)
        assertEquals("FAILED", history.getString("status"))
        assertEquals("provider rejected", history.getString("detail"))
        assertEquals("", history.getString("text"))
        assertEquals(history.toString(), BackupChildTaskCodec.terminal(history).toString())
        assertEquals("UNKNOWN", BackupChildTaskCodec.terminal(task(1).put("status", "RUNNING").put("text", "")).getString("status"))
    }

    @Test fun requestRoutingAndUnreportedMainRequestSurviveVersionSevenValidation() {
        val task = task(0).put("tool_access_json", JSONObject.NULL).put("original_role", "REVIEW")
            .put("assigned_profile_id", "replacement")
        val usage = JSONObject().apply {
            put("task_id", "child-0"); put("request_id", "request"); put("request_owner", "owner")
            put("attempt_id", "attempt"); put("operation", "NEXT"); put("checkpoint_revision", 1)
            put("usage_source", "MAIN"); put("has_usage", 0)
            for (key in listOf("input_tokens", "output_tokens", "cached_tokens", "cache_creation_tokens")) put(key, JSONObject.NULL)
            put("provider_id_snapshot", "provider"); put("api_model_snapshot", "actual-model")
            put("requested_at_ms", 123); put("request_sequence", 1)
            put("assigned_profile_snapshot", "replacement"); put("original_role_snapshot", "REVIEW")
        }
        fun validateRouting() = BackupChildTaskCodec.validate(JSONObject().put("version", 7)
            .put("tasks", JSONArray().put(task)).put("commands", JSONArray()).put("usage", JSONArray().put(usage)),
            JSONArray().put(JSONObject().put("id", "parent").put("conversation_id", "conversation")
                .put("run_id", "run").put("role", "assistant")))
        val saved = validateRouting().usage.single()
        assertEquals("actual-model", saved.getString("api_model_snapshot"))
        assertEquals("replacement", saved.getString("assigned_profile_snapshot"))
        assertEquals(0, saved.getInt("has_usage"))
        usage.put("api_model_snapshot", JSONObject.NULL)
        assertThrows(IllegalArgumentException::class.java) { validateRouting() }
    }

}
