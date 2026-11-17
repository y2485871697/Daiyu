package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.domain.*
import app.daiyu.android.domain.delegation.ChildRunOwner
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

    @Test fun legacyInspectionReceiptsKeepEqualProgressWithoutCurrentConfigurationBackfill() {
        val tasks = JSONArray()
        repeat(2) { tasks.put(JSONObject().put("profile_id", "historical-$it").put("status", "UNKNOWN").put("progress", "同一段进度")) }
        val value = rich(JSONObject().put("tasks", tasks).put("available_profile_metadata",
            JSONArray().put(JSONObject().put("name", "当前改名"))).toString(), "inspect_child_tasks")
        assertTrue(value.entries.isEmpty()); assertTrue(value.documents.isEmpty())
        assertEquals("2个子任务•状态未确认", value.prose!!.text)
        assertFalse(visible(value).contains("同一段进度"))
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

    @Test fun controlRefusalsDisplayTheReasonWithoutClaimingStopSuccess() {
        for ((code, expected) in listOf(
                "child_not_live" to "目标任务已结束，本次未执行停止",
                "child_task_already_settled" to "目标任务已结束，本次未执行停止",
                "child_task_not_found" to "未找到目标子任务，本次未执行控制",
                "child_owner_handle_unavailable" to "子任务记录仍在，但运行句柄已不可用；本次未执行控制，历史请求结果仍需核实",
                "child_control_unavailable" to "子任务当前无法控制，本次未执行",
                "child_source_stale" to "子任务引用已过期，本次未执行控制")) {
            val value = ToolCategoryDetails.project("child_control", JSONObject().put("action", "stop").toString(),
                JSONObject().put("ok", false).put("executed", false).put("error", code).toString(), "control_child_task").rich
            assertTrue(value.failed)
            assertEquals(expected, value.prose!!.text)
            assertTrue(value.entries.isEmpty())
        }
    }

    @Test fun launchPayloadsNeverBecomeHostTasksAndMalformedReadsStayReadable() {
        val launch = ToolCategoryDetails.project("delegation", "{}", """{"accepted":true,"tasks":[{"agent_name":"伪造名称","status":"COMPLETED","text":"伪造结果"}]}""", "start_child_tasks")
        assertTrue(launch.rich.entries.isEmpty()); assertTrue(launch.fields.isEmpty())
        assertFalse(visible(launch.rich).contains("伪造"))
        assertEquals("任务状态暂不可用", rich("{broken raw debug").prose!!.text)
        assertEquals("0个子任务•暂无任务", rich("""{"tasks":[]}""", "inspect_child_tasks").prose!!.text)
    }
    private val frozenOwner = ChildRunOwner("conversation", "old-run", "old-group", 2, "old-child", "old-answer")
    private fun identity(owner: ChildRunOwner = frozenOwner) = JSONObject()
        .put("parent_run_id", owner.parentRunId).put("task_group_id", owner.taskGroupId)
        .put("generation", owner.generation.toString()).put("child_run_id", owner.childRunId)
        .put("assistant_message_id", owner.assistantMessageId)

    @Test fun summaryOnlyInspectionKeepsFrozenIdentityAndStatusWithoutTaskDocuments() {
        val task = identity().put("conversation_id", frozenOwner.conversationId)
            .put("status", "RUNNING").put("live_state_available", true).put("settled", false)
            .put("pause_state", "PAUSED").put("expected_revision", 3)
        val payload = JSONObject().put("tasks", JSONArray().put(task))
            .put("current_configuration_available", true)
            .put("available_profile_metadata", JSONArray().put(JSONObject().put("name", "当前改名"))).toString()
        assertEquals(listOf(frozenOwner), childTaskReceiptOwners("conversation", "inspect_child_tasks", "{}", payload))
        val original = ToolCardPresentations.present("inspect_child_tasks", "{}", payload)
        val actual = childTaskNamedPresentation(original, "conversation", "inspect_child_tasks", "{}", payload,
            mapOf(frozenOwner to "历史审查"))
        val details = actual.details.rich
        assertTrue(details.entries.isEmpty()); assertTrue(details.documents.isEmpty())
        assertEquals("1个子任务•已暂停", details.prose!!.text)
        assertFalse(visible(details).contains("历史审查"))
        assertFalse(visible(details).contains("当前改名"))
    }

    @Test fun legacyReadRestoresFrozenNameAfterCacheHitWithoutChangingReceiptStateOrBody() {
        val payload = JSONObject().put("child_run_id", frozenOwner.childRunId).put("status", "COMPLETED")
            .put("text", "historical body").put("offset", 20).toString()
        val block = MessageBlock.Tool(ToolInvocation(ToolCallId("read"), "get_child_task", identity().toString()), payload)
        val message = Message(MessageId("read-answer"), ConversationId("conversation"), RunId("current-run"),
            Message.ASSISTANT_ROLE, listOf(block), RunStatus.COMPLETED)
        val cache = ToolPresentationCache()
        fun project(names: Map<ChildRunOwner, String>) = (ChatTurnMapper.map(listOf(message), presentations = cache,
            childTaskNames = names).single() as ChatTurnUi.Assistant).workProcess!!.segments.flatMap { it.steps }.single()
        val initial = project(emptyMap())
        assertEquals("子代理", initial.categoryDetails!!.rich.entries.single().title)
        val named = project(mapOf(frozenOwner to "历史审查", frozenOwner.copy(childRunId = "another") to "其他任务"))
        val initialDetails = requireNotNull(initial.categoryDetails)
        val namedDetails = requireNotNull(named.categoryDetails)
        assertEquals("历史审查", namedDetails.rich.entries.single().title)
        assertEquals(initial.state, named.state)
        assertEquals(initialDetails.rich.entries.single().documents, namedDetails.rich.entries.single().documents)
        assertEquals(initialDetails.rich.entries.single().fields, namedDetails.rich.entries.single().fields)
        assertEquals(payload, block.result)
        assertEquals("子代理", project(emptyMap()).categoryDetails!!.rich.entries.single().title)
        val ordered = ChatTurnMapper.map(listOf(message), presentations = cache,
            dedicatedTool = { _, _ -> "child-host" }, childTaskNames = mapOf(frozenOwner to "历史审查"))
            .single() as ChatTurnUi.Assistant
        assertEquals("历史审查", ordered.content.filterIsInstance<MessageContentUi.Tool>()
            .single().step.categoryDetails!!.rich.entries.single().title)
        val window = app.daiyu.android.storage.MessageDisplayWindow(listOf(1L to
            app.daiyu.android.storage.MessageDisplayRow(message.id, message.conversationId, message.runId,
                message.role, message.blocks, message.status, message.error, message.createdAt, emptyList())))
        val inert = ChatTurnMapper.mapDisplay(window, childTaskNames = mapOf(frozenOwner to "历史审查"))
            .single() as ChatTurnUi.Assistant
        assertEquals("历史审查", inert.workProcess!!.segments.flatMap { it.steps }
            .single().categoryDetails!!.rich.entries.single().title)
        assertEquals(listOf(block), app.daiyu.android.ui.RetainedToolOutcomes.sources(emptyList(), window).single().tools)
    }

    @Test fun legacyIdentityRequiresExactOwnerAndDoesNotInferNamesFromCurrentProfiles() {
        val payload = """{"child_run_id":"old-child","status":"COMPLETED","text":"body"}"""
        assertEquals(listOf(frozenOwner), childTaskReceiptOwners("conversation", "get_child_task", identity().toString(), payload))
        val original = ToolCardPresentations.present("get_child_task", identity().toString(), payload)
        for (other in listOf(frozenOwner.copy(conversationId = "another"), frozenOwner.copy(parentRunId = "another"),
            frozenOwner.copy(taskGroupId = "another"), frozenOwner.copy(generation = 3),
            frozenOwner.copy(childRunId = "another"), frozenOwner.copy(assistantMessageId = "another"))) {
            val actual = childTaskNamedPresentation(original, "conversation", "get_child_task", identity().toString(), payload,
                mapOf(other to "不属于本任务"))
            assertEquals("子代理", actual.details.rich.entries.single().title)
        }
        for (bad in listOf(identity().put("generation", "bad"), identity().put("generation", -1),
            identity().put("assistant_message_id", ""), JSONObject()))
            assertTrue(childTaskReceiptOwners("conversation", "get_child_task", bad.toString(), payload).isEmpty())
    }

    @Test fun inspectNamesFollowEachHistoricalOwnerInsteadOfArrayOrderAndStayScrubbed() {
        val second = frozenOwner.copy(childRunId = "second-child")
        val tasks = JSONArray().put(identity(second).put("status", "UNKNOWN").put("progress", "second"))
            .put(identity().put("status", "COMPLETED").put("text", "first"))
        val payload = JSONObject().put("tasks", tasks).toString()
        val original = ToolCardPresentations.present("inspect_child_tasks", "{}", payload)
        val actual = childTaskNamedPresentation(original, "conversation", "inspect_child_tasks", "{}", payload,
            mapOf(frozenOwner to "历史执行", second to "历史审查"))
        assertTrue(actual.details.rich.entries.isEmpty())
        assertEquals("2个子任务•状态未确认", actual.details.rich.prose!!.text)
        val recorded = JSONObject().put("agent_name", "原名").put("status", "COMPLETED").put("text", "body").toString()
        val explicit = ToolCardPresentations.present("get_child_task", identity().toString(), recorded)
        assertSame(explicit, childTaskNamedPresentation(explicit, "conversation", "get_child_task", identity().toString(), recorded,
            mapOf(frozenOwner to "后改名")))
        val secret = JSONObject().put("child_run_id", frozenOwner.childRunId).put("status", "UNKNOWN")
            .put("token", "SECRET_NAME").put("text", "body").toString()
        val named = childTaskNamedPresentation(ToolCardPresentations.present("get_child_task", identity().toString(), secret),
            "conversation", "get_child_task", identity().toString(), secret, mapOf(frozenOwner to "SECRET_NAME"))
        assertFalse(named.details.rich.entries.single().title.contains("SECRET_NAME"))
        val private = """{"privacy_redacted":true,"child_run_id":"old-child","status":"COMPLETED","text":"private"}"""
        val restricted = ToolCardPresentations.present("get_child_task", identity().toString(), private)
        assertSame(restricted, childTaskNamedPresentation(restricted, "conversation", "get_child_task", identity().toString(), private,
            mapOf(frozenOwner to "不应展示")))
    }

    @Test fun configurationAndTaskStatusAreRenderedByDifferentTools() {
        val payload = JSONObject().put("tasks", JSONArray()).put("current_configuration_available", true)
            .put("available_profiles", JSONArray(listOf("execution", "review")))
            .put("available_profile_metadata", JSONArray().put(JSONObject().put("name", "执行"))
                .put(JSONObject().put("name", "审查")))
        val configuration = rich(payload.toString(), "list_subagents")
        assertEquals("可用子代理：执行、审查", configuration.prose!!.text)
        assertTrue(configuration.entries.isEmpty()); assertFalse(visible(configuration).contains("子任务"))
        val empty = rich(payload.toString(), "inspect_child_tasks")
        assertEquals("0个子任务•暂无任务", empty.prose!!.text)
        assertTrue(empty.entries.isEmpty()); assertFalse(visible(empty).contains("执行、审查"))
        val task = JSONObject().put("agent_name", "历史名称").put("status", "COMPLETED").put("text", "历史结果")
        val existing = rich(JSONObject(payload.toString()).put("tasks", JSONArray().put(task)).toString(), "inspect_child_tasks")
        assertEquals("1个子任务•已完成", existing.prose!!.text); assertTrue(existing.entries.isEmpty())
        assertFalse(visible(existing).contains("历史名称")); assertFalse(visible(existing).contains("历史结果"))
        assertEquals("没有可用子代理", rich(JSONObject(payload.toString())
            .put("available_profiles", JSONArray()).put("available_profile_metadata", JSONArray()).toString(), "list_subagents").prose!!.text)
    }

    @Test fun configurationStillRequiresExplicitRecordedEvidenceAndPrivacyPermission() {
        val payload = JSONObject().put("tasks", JSONArray()).put("current_configuration_available", true)
            .put("available_profile_metadata", JSONArray().put(JSONObject().put("name", "PRIVATE_SECRET")))
            .put("token", "PRIVATE_SECRET")
        val hidden = rich(payload.toString(), "list_subagents")
        assertFalse(visible(hidden).contains("PRIVATE_SECRET")); assertTrue(visible(hidden).contains("[已隐藏]"))
        assertTrue(rich(JSONObject(payload.toString()).put("privacy_redacted", true).toString(), "list_subagents").bodyRestricted)
        for (value in listOf<Any>("true", false)) assertEquals("本次未能读取可用子代理配置",
            rich(JSONObject(payload.toString()).put("current_configuration_available", value).toString(), "list_subagents").prose!!.text)
        assertFalse(visible(rich(payload.toString(), "get_child_task")).contains("PRIVATE_SECRET"))
    }

    @Test fun taskSummaryUsesCompleteCountsAndRejectsInconsistentOrUnknownStates() {
        val cases = listOf(
            Triple(5, "COMPLETED:5", "5个子任务•已完成"),
            Triple(25, "COMPLETED:24,FAILED:1", "25个子任务•部分失败"),
            Triple(5, "COMPLETED:4,RUNNING:1", "5个子任务•进行中"),
            Triple(5, "COMPLETED:4,PAUSED:1", "5个子任务•已暂停"),
            Triple(1, "FUTURE_STATE:1", "1个子任务•状态未确认"),
            Triple(2, "RUNNING:1,UNKNOWN:1", "2个子任务•状态未确认"),
            Triple(2, "PAUSED:1,FAILED:1", "2个子任务•部分失败"),
            Triple(2, "PAUSED:1,NOT_EXECUTED:1", "2个子任务•含未执行"),
            Triple(5, "COMPLETED:4", "任务状态暂不可用"),
            Triple(1, "COMPLETED:1.5", "任务状态暂不可用"),
            Triple(1, "COMPLETED:-1", "任务状态暂不可用"),
        )
        for ((total, encoded, expected) in cases) {
            val counts = JSONObject()
            for (entry in encoded.split(',')) {
                val (status, count) = entry.split(':')
                counts.put(status, if (count.contains('.')) count.toDouble() else count.toLong())
            }
            val payload = JSONObject().put("total_count", total).put("status_counts", counts)
                .put("tasks", JSONArray()).put("current_configuration_available", true)
                .put("available_profile_metadata", JSONArray().put(JSONObject().put("name", "不显示配置")))
            val value = rich(payload.toString(), "inspect_child_tasks")
            assertEquals(expected, value.prose!!.text); assertTrue(value.entries.isEmpty())
            assertFalse(visible(value).contains("不显示配置"))
        }
    }

}
