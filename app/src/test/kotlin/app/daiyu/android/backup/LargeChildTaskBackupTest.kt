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
}
