package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.domain.*
import org.junit.Assert.*
import org.junit.Test

class ToolCardPresentationTest {
    @Test fun taxonomyIncludesAllReservedCategoriesWithoutInventingRecords() {
        assertEquals(21, ToolCardPresentations.categories.size)
        assertTrue(ToolCardPresentations.categories.containsAll(listOf("workspace", "image_read", "speech", "generic")))
        assertTrue(ChatTurnMapper.map(emptyList()).isEmpty())
    }
    @Test fun actualRegisteredToolsAndActionBranchesHaveChineseTitles() {
        val cases = listOf(
            Triple("assistant_memory_read", "{}", "memory"), Triple("skills_read", "{}", "skills"),
            Triple("start_child_tasks", "{}", "delegation"), Triple("direct_child_task", "{\"kind\":\"APPEND\"}", "child_control"),
            Triple("terminal_pty_step", "{}", "terminal"), Triple("terminal_job_read", "{}", "background_job"),
            Triple("list_files", "{}", "files"), Triple("browser_write", "{\"action\":\"navigate\"}", "browser"),
            Triple("screen", "{\"operation\":\"observe\"}", "gui"), Triple("search_apps", "{}", "apps"),
            Triple("device_status", "{}", "device_read"), Triple("notification_history", "{}", "personal_search"),
            Triple("platform_set_alarm", "{}", "system_action"), Triple("generate_images", "{}", "generation"),
            Triple("mcp_7_call", "{}", "mcp"), Triple("context_usage", "{}", "context"), Triple("ask_user", "{}", "user_question"),
        )
        cases.forEach { (name, args, category) ->
            val card = ToolCardPresentations.present(name, args, null)
            assertEquals(name, category, card.category)
            assertTrue(card.title.any { it.code > 127 })
        }
        assertEquals("打开网页", ToolCardPresentations.present("browser_write", "{\"action\":\"navigate\"}", null).title)
        assertEquals("替换子任务", ToolCardPresentations.present("direct_child_task", "{\"kind\":\"REPLACE\"}", null).title)
    }
    @Test fun futureToolsMalformedArgumentsAndUnknownActionsFallBackWithoutLeakingText() {
        listOf("vendor_future" to "{}", "browser_write" to "{\"action\":\"future\"}", "screen_action" to "not-json").forEach { (name, args) ->
            assertEquals("generic", ToolCardPresentations.present(name, args, "secret result").category)
        }
        assertTrue(ToolCardPresentations.present("vendor_future", "{}", null).title.contains("vendor_future"))
        assertNotEquals(ToolCardPresentations.present("vendor_future", "{}", null).title,
            ToolCardPresentations.present("another_tool", "{}", null).title)
        assertEquals("执行其他工具", ToolCardPresentations.present("private/credential?token=secret", "{}", null).title)
        val raw = "{\"cookie\":\"secret-cookie\",\"password\":\"secret-pass\",\"text\":\"123456\",\"count\":2,\"wait_status\":0}"
        listOf("browser_write", "terminal_pty_step", "current_notifications", "mcp_0_call").forEach { name ->
            val card = ToolCardPresentations.present(name, raw, raw)
            val visible = card.summary + card.sections.joinToString { it.text }
            listOf("secret-cookie", "secret-pass", "123456", "cookie", "password").forEach { assertFalse(visible.contains(it)) }
        }
        assertTrue(ToolCardPresentations.present("terminal_pty_read", "{}", "{\"wait_status\":7,\"stdout\":\"private\"}")
            .sections.any { it.text.contains("原始等待状态：7") && !it.text.contains("private") })
    }
    @Test fun receiptStateWinsOverTextAndOriginalOwnerAndToolIdentitySurvive() {
        val call = ToolInvocation(ToolCallId("call"), "browser_write", "{\"action\":\"navigate\",\"url\":\"https://private.invalid/?token=secret\"}")
        val source = Message(MessageId("message"), ConversationId("chat"), RunId("run"), Message.ASSISTANT_ROLE,
            listOf(MessageBlock.Tool(call, "error is ordinary text")), RunStatus.RUNNING)
        fun step(state: WorkStepState) = (ChatTurnMapper.map(listOf(source), toolState = { _, _ -> state }).single() as ChatTurnUi.Assistant)
            .workProcess!!.segments.flatMap { it.steps }.single()
        WorkStepState.entries.forEach { state -> assertEquals(state, step(state).state) }
        val card = step(WorkStepState.OK)
        assertEquals("call", card.toolCallId)
        assertEquals(call.name, card.toolName)
        assertEquals(WorkStepOwnerUi("chat", "run", "message"), card.owner)
        assertFalse(card.toString().contains("token=secret"))
        assertEquals(call, source.blocks.filterIsInstance<MessageBlock.Tool>().single().invocation)
    }
    @Test fun copyingReResolvesCurrentOwnerAndNeverUsesStaleOrForgedText() {
        val step = WorkStepUi("call", "读取终端输出", "current safe text", WorkStepState.UNKNOWN,
            WorkStepOwnerUi("chat", "run", "message"), "terminal_pty_read")
        val turns = listOf(ChatTurnUi.Assistant("message", content = listOf(MessageContentUi.Tool(step))))
        assertTrue(ToolCardPresentations.copySummary(turns, step.copy(detail = "forged secret"))!!.contains("current safe text"))
        assertFalse(ToolCardPresentations.copySummary(turns, step.copy(detail = "forged secret"))!!.contains("forged secret"))
        assertNull(ToolCardPresentations.copySummary(emptyList(), step))
        assertNull(ToolCardPresentations.copySummary(turns, step.copy(owner = step.owner!!.copy(runId = "other"))))
        assertNull(ToolCardPresentations.copySummary(turns, step.copy(owner = step.owner!!.copy(conversationId = "other"))))
        assertNull(ToolCardPresentations.copySummary(turns, step.copy(owner = null)))
        assertNull(ToolCardPresentations.copySummary(turns, step.copy(toolName = "other")))
        val summaryTurn = ChatTurnUi.Assistant("message", workProcess = WorkProcessUi(listOf(WorkSegmentUi(steps = listOf(step))), "结果未确认"))
        assertEquals(ToolCardPresentations.copySummary(turns, step), ToolCardPresentations.copySummary(listOf(summaryTurn), step))
    }

    @Test fun actualTaskArraysAndTerminalFlagsAreBoundedMetadataNotSuccessInference() {
        val queued = ToolCardPresentations.present("start_child_tasks", "{\"tasks\":[{\"prompt\":\"private\"}]}",
            "{\"accepted\":true,\"completed\":false,\"tasks\":[{\"child_run_id\":\"secret\"}]}")
        assertTrue(queued.summary.contains("生命周期"))
        assertTrue(queued.sections.any { it.text.contains("入队任务数：1") && it.text.contains("子任务已完成：否") })
        assertFalse(queued.toString().contains("private"))
        assertFalse(queued.toString().contains("secret"))
        val pty = ToolCardPresentations.present("terminal_pty_read", "{}", "{\"wait_status\":7,\"state\":\"exited\",\"has_more\":false,\"total_bytes\":32}")
        assertTrue(pty.sections.any { it.text.contains("原始等待状态：7") && it.text.contains("总字节数：32") && it.text.contains("进程已退出") })
        assertFalse(pty.toString().contains("退出码"))
    }

    @Test fun typedDetailsKeepSafeCategoryFieldsAndNeverEchoFreeText() {
        val payload = """{"wait_status":0,"next_offset":256,"stdout":"token=secret","password":"private","state":"running"}"""
        val detail = ToolCardPresentations.present("terminal_pty_read", "{}", payload).details
        assertEquals("运行与输出", detail.heading)
        assertTrue(detail.fields.contains(ToolFieldUi("原始等待状态", "0")))
        assertTrue(detail.fields.contains(ToolFieldUi("下次偏移", "256")))
        assertFalse(detail.toString().contains("secret"))
        assertFalse(detail.toString().contains("private"))
        ToolCardPresentations.categories.forEach { category ->
            val typed = ToolCategoryDetails.project(category, payload, payload)
            assertTrue(typed.heading.isNotBlank())
            assertTrue(typed.notice.isNotBlank())
            assertFalse(typed.toString().contains("token=secret"))
        }
    }
    @Test fun taskDetailsUseReturnedStatesWithoutPromotingAdmissionToCompletion() {
        val detail = ToolCategoryDetails.project("delegation", "{\"tasks\":[{}]}",
            """{"accepted":true,"tasks":[{"state":"queued"},{"state":"running","text":"private-output"}]}""")
        assertEquals(listOf("排队中", "执行中"), detail.items.map { it.fields.single().value })
        assertFalse(detail.fields.any { it.label == "任务已完成" })
        assertFalse(detail.toString().contains("private-output"))
        assertTrue(ToolCategoryDetails.project("system_action", "{\"percent\":0,\"enabled\":false}", null)
            .fields.containsAll(listOf(ToolFieldUi("百分比", "0"), ToolFieldUi("已启用", "否"))))
    }

    @Test fun copyIncludesTypedZeroCountsAndRevalidatesCurrentIdentity() {
        val display = ToolCardPresentations.present("skills_list", "{}", "{\"skills\":[]}")
        val step = WorkStepUi("call", display.title, display.summary, WorkStepState.OK,
            WorkStepOwnerUi("conversation", "run", "message"), "skills_list", display.category,
            display.sections, categoryDetails = display.details)
        val turn = ChatTurnUi.Assistant("message", content = listOf(MessageContentUi.Tool(step)))
        assertTrue(ToolCardPresentations.copySummary(listOf(turn), step)!!.contains("技能数：0"))
        assertNull(ToolCardPresentations.copySummary(listOf(turn), step.copy(owner = WorkStepOwnerUi("other", "run", "message"))))
    }

}
