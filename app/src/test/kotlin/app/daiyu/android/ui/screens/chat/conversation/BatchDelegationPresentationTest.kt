package app.daiyu.android.ui.screens.chat.conversation

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BatchDelegationPresentationTest {
    private val arguments = """{"tasks":[{"profile_id":"INTERNAL_PROFILE","prompt":"检查布局"}]}"""
    private fun details(tool: String = "run_child_models", result: String?) =
        ToolCardPresentations.present(tool, arguments, result).details

    private fun visible(details: ToolCategoryDetailsUi): String {
        val body = toolBodyLayout("delegation", details)
        return (body.fields.map { it.label + it.value } + body.inputs.map { it.text } +
            body.documents.map { it.text } + listOfNotNull(body.prose?.text) +
            details.items.flatMap { it.fields }.map { it.value } +
            details.rich.entries.flatMap { entry -> listOf(entry.title) + entry.fields.map { it.value } + entry.documents.map { it.text } })
            .joinToString("\n")
    }

    @Test fun launchCardsKeepOneCountWithoutRawOrFallbackTaskLists() {
        for (tool in listOf("run_child_models", "start_child_tasks")) for (key in listOf("results", "tasks")) {
            val row = JSONObject().put("child_run_id", "INTERNAL_CHILD").put("profile_id", "INTERNAL_PROFILE")
                .put("task_id", "INTERNAL_TASK").put("result_id", "INTERNAL_RESULT").put("status", "dispatched")
                .put("description", "DEBUG_DESCRIPTION").put("text", "DEBUG_TEXT")
            val result = JSONObject().put(key, JSONArray().put(row))
                .put("message", "派发任务数 1 INTERNAL_CHILD DEBUG_MESSAGE").toString()
            val details = details(tool, result)
            assertEquals(listOf(ToolFieldUi("请求任务数", "1")), toolBodyLayout("delegation", details).fields)
            assertTrue(details.items.isEmpty())
            assertTrue(details.rich.entries.isEmpty())
            assertTrue(details.rich.documents.isEmpty())
            for (value in listOf("INTERNAL_", "DEBUG_", "dispatched", "已完成")) assertFalse(value, visible(details).contains(value))
            assertTrue(details.rich.technical.any { it.text.contains("INTERNAL_CHILD") })
        }
    }

    @Test fun acceptedReceiptDoesNotClaimExecutionOrCompletion() {
        val details = details("start_child_tasks", """{"accepted":true,"completed":false,"tasks":[{"expected_status":"QUEUED"}]}""")
        assertFalse(visible(details).contains("已完成"))
        assertFalse(visible(details).contains("已接纳"))
        assertNull(details.rich.prose)
    }

    @Test fun returnedTaskStatesNeverBecomeLiveProgress() {
        val details = details(result = """{"results":[{"status":"completed"},{"status":"unknown"}]}""")
        assertTrue(details.items.isEmpty())
        assertTrue(details.rich.entries.isEmpty())
        assertFalse(visible(details).contains("已完成"))
    }

    @Test fun waitingAndUnreadableReceiptsDoNotExposeTransportText() {
        assertEquals("等待执行回执", details(result = null).rich.prose!!.text)
        for (raw in listOf("{INTERNAL_BROKEN", "[\"INTERNAL_ARRAY\"]", "INTERNAL_PLAIN", "{" + "x".repeat(131_072))) {
            val details = details(result = raw)
            assertEquals("委派回执无法读取", details.rich.prose!!.text)
            assertTrue(details.rich.documents.isEmpty())
            assertFalse(visible(details).contains("INTERNAL_"))
        }
    }

    @Test fun failedAndPrivateLaunchesKeepTheirUserFacingReason() {
        val failed = details(result = """{"ok":false,"status":"permission_denied","error":"INTERNAL_ERROR"}""")
        assertTrue(failed.rich.failed)
        assertEquals("缺少本次操作所需权限", visible(failed))
        val private = details(result = """{"privacy_redacted":true,"message":"INTERNAL_PRIVATE"}""")
        assertEquals("结果未获展示授权", visible(private))
        assertFalse(private.rich.technical.any { it.text.contains("INTERNAL_PRIVATE") })
    }

    @Test fun closedFailureStatesAndErrorsRemainVisibleWithoutRawDebugText() {
        for ((state, expected) in listOf("failed" to "未能分配任务", "rejected" to "任务分配被拒绝", "not_executed" to "任务未执行")) {
            for (key in listOf("status", "state")) {
                val details = details(result = JSONObject().put(key, state).put("error", "INTERNAL_ERROR").toString())
                assertTrue(details.rich.failed)
                assertEquals(expected, visible(details))
            }
        }
        assertEquals("未能分配任务", visible(details(result = """{"error":"INTERNAL_ERROR"}""")))
        assertEquals("委派结果未确认", details(result = """{"status":"unknown"}""").rich.prose!!.text)
    }

    @Test fun launchSectionsNeverDeclareLifecycleCompletion() {
        for (tool in listOf("run_child_models", "start_child_tasks")) {
            val card = ToolCardPresentations.present(tool, arguments, """{"completed":true,"results":[{"status":"completed"}]}""")
            assertEquals(listOf(ToolDetailSectionUi("任务", "任务数：1")), card.sections)
            val failed = ToolCardPresentations.present(tool, arguments, """{"status":"failed"}""")
            assertTrue(failed.sections.any { it.text == "未能分配任务" })
        }
    }

    @Test fun resultReadsStillExposeTheActualResultAndProgress() {
        val details = details("get_child_task", """{"text":"USER_RESULT","progress":"USER_PROGRESS","partial_result":"USER_PARTIAL"}""")
        assertTrue(details.rich.documents.any { it.text == "USER_RESULT" })
        assertTrue(details.rich.documents.any { it.text == "USER_PROGRESS" })
        assertTrue(details.rich.documents.any { it.text == "USER_PARTIAL" })
    }

    @Test fun diagnosticsRemainScrubbedAndUnknownToolIsNotReclassified() {
        val details = details(result = """{"token":"PRIVATE_SECRET","message":"PRIVATE_SECRET"}""")
        assertFalse(details.rich.technical.any { it.text.contains("PRIVATE_SECRET") })
        val unknown = ToolCardPresentations.present("run_child_models_extra", arguments, """{"results":[]}""")
        assertEquals("generic", unknown.category)
    }
}
