package app.daiyu.android.ui.screens.chat.conversation

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChildTaskCardPresentationTest {
    private fun rich(payload: String, tool: String = "get_child_task") =
        ToolCategoryDetails.project("child_control", "{}", payload, tool).rich
    private fun visible(details: ToolRichDetailsUi) = (details.fields.map { it.label + it.value } +
        details.documents.map { it.text } + listOfNotNull(details.prose?.text) +
        details.entries.flatMap { listOf(it.title) + it.fields.map { field -> field.value } + it.documents.map { doc -> doc.text } }).joinToString("\n")

    @Test fun readsShowOneFrozenNameStatusAndResultWithoutDebugFields() {
        val payload = """{"agent_name":"审查","profile_id":"private-id","child_run_id":"internal-child","status":"COMPLETED","settled":true,"offset":42,"next_text_offset":128,"text":"已检查布局","progress":"已检查布局","detail":"internal-detail"}"""
        val value = rich(payload)
        assertEquals("审查", value.entries.single().title)
        assertEquals(listOf(ToolFieldUi("状态", "已完成")), value.entries.single().fields)
        assertEquals(listOf(ToolDisclosureUi("任务进度", "已检查布局")), value.entries.single().documents)
        assertTrue(value.fields.isEmpty()); assertTrue(value.documents.isEmpty()); assertNull(value.prose)
        for (debug in listOf("private-id", "internal-child", "internal-detail", "offset", "COMPLETED", "已读取结果"))
            assertFalse(debug, visible(value).contains(debug))
        assertTrue(value.technical.any { it.text.contains("internal-detail") && it.text.contains("offset") })
    }

    @Test fun sameTaskHasOneBodyWithResultBeforeProgressAndLegacyFallbacks() {
        assertEquals("final", childTaskProgressText("final", "different progress"))
        assertEquals("partial", childTaskProgressText(" \n", "partial"))
        assertNull(childTaskProgressText("", "\n"))
        for ((key, text) in listOf("text" to "final", "result" to "legacy result", "progress" to "progress", "partial_result" to "partial", "partial" to "older partial", "checkpoint" to "checkpoint summary")) {
            val entry = rich(JSONObject().put(key, text).toString()).entries.single()
            assertEquals(listOf(text), entry.documents.map { it.text })
        }
    }

    @Test fun actualTaskStatesNeverComeFromToolSuccessOrSettledAlone() {
        fun status(row: JSONObject) = rich(row.toString()).entries.single().fields.single().value
        for (row in listOf(
            JSONObject().put("status", "UNKNOWN").put("settled", true).put("completed", true),
            JSONObject().put("status", "COMPLETED").put("journal_unconfirmed", true),
            JSONObject().put("status", "COMPLETED").put("detail", "child_result_unconfirmed"),
            JSONObject().put("status", "RUNNING").put("live_state_available", false),
            JSONObject().put("status", "RUNNING"),
            JSONObject().put("child_run_id", "child").put("ok", true).put("outcome", "RESUMED"),
            JSONObject().put("status", "FUTURE").put("accepted", true))) {
            assertEquals(row.toString(), "状态未确认", status(row))
        }
        for ((recorded, expected) in listOf("COMPLETED" to "已完成", "FAILED" to "失败", "NOT_EXECUTED" to "未执行"))
            assertEquals(expected, status(JSONObject().put("status", recorded)))
        val running = JSONObject().put("status", "RUNNING").put("live_state_available", true).put("settled", false)
        assertEquals("进行中", status(running))
        assertEquals("已暂停", status(JSONObject(running.toString()).put("pause_state", "PAUSED")))
        assertEquals("暂停中", status(JSONObject(running.toString()).put("pause_state", "PENDING")))
        assertEquals("停止中", status(JSONObject(running.toString()).put("stop_requested", true)))
        assertEquals("排队中", status(JSONObject(running.toString()).put("queue_state", "PENDING")))
    }

    @Test fun separateTasksKeepEqualProgressWithoutCurrentConfigurationBackfill() {
        val tasks = JSONArray()
        repeat(2) { tasks.put(JSONObject().put("profile_id", "historical-$it").put("status", "UNKNOWN").put("progress", "同一段进度")) }
        val value = rich(JSONObject().put("tasks", tasks).put("available_profile_metadata",
            JSONArray().put(JSONObject().put("name", "当前改名"))).toString(), "inspect_child_tasks")
        assertEquals(2, value.entries.size)
        assertEquals(listOf("子代理", "子代理"), value.entries.map { it.title })
        assertEquals(listOf("同一段进度", "同一段进度"), value.entries.flatMap { it.documents }.map { it.text })
        assertFalse(visible(value).contains("historical-")); assertFalse(visible(value).contains("当前改名"))
    }

    @Test fun privacyAndSecretsStillApplyBeforeTaskDisplay() {
        val private = rich("""{"privacy_redacted":true,"agent_name":"private-name","status":"COMPLETED","text":"PRIVATE_BODY"}""")
        assertTrue(private.bodyRestricted); assertTrue(private.entries.isEmpty())
        assertFalse(visible(private).contains("PRIVATE_BODY"))
        val secret = rich("""{"agent_name":"审查","status":"UNKNOWN","token":"PRIVATE_SECRET","text":"进度 PRIVATE_SECRET token=OTHER_SECRET"}""")
        assertFalse(visible(secret).contains("PRIVATE_SECRET")); assertFalse(visible(secret).contains("OTHER_SECRET"))
        assertTrue(visible(secret).contains("[已隐藏]"))
    }

    @Test fun launchPayloadsNeverBecomeHostTasksAndMalformedReadsStayReadable() {
        val launch = ToolCategoryDetails.project("delegation", "{}", """{"accepted":true,"tasks":[{"agent_name":"伪造名称","status":"COMPLETED","text":"伪造结果"}]}""", "start_child_tasks")
        assertTrue(launch.rich.entries.isEmpty()); assertTrue(launch.fields.isEmpty())
        assertFalse(visible(launch.rich).contains("伪造"))
        assertEquals("任务状态暂不可用", rich("{broken raw debug").prose!!.text)
        assertEquals("暂无任务记录", rich("""{"tasks":[]}""", "inspect_child_tasks").prose!!.text)
    }
}
