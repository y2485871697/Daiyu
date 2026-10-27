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
            Triple("terminal_exec", "{}", "terminal"), Triple("terminal_read", "{}", "terminal"),
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
        assertEquals("打开页面", ToolCardPresentations.present("browser_write", "{\"action\":\"navigate\"}", null).title)
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

    @Test fun processTitlesMatchCategoriesAndMixedCallsRemainAChain() {
        assertEquals("设备查询", ToolCardPresentations.processTitle(listOf("device_read")))
        assertEquals("终端命令", ToolCardPresentations.processTitle(listOf("terminal", "terminal")))
        assertEquals("工具链", ToolCardPresentations.processTitle(listOf("terminal", "device_read")))
        assertEquals("工具链", ToolCardPresentations.processTitle(emptyList()))
        assertEquals("工具链", ToolCardPresentations.processTitle(listOf("future_category")))
        ToolCardPresentations.categories.forEach { assertNotEquals("工具链", ToolCardPresentations.processTitle(listOf(it))) }
    }

    @Test fun allRegisteredNamesAndClosedActionBranchesHaveSpecificPresentation() {
        val groups = mapOf(
            "memory" to listOf("assistant_memory_search", "assistant_memory_read"),
            "skills" to listOf("skills_list", "skills_read", "skills_read_resource"),
            "delegation" to listOf("run_child_models", "start_child_tasks"),
            "terminal" to listOf("terminal_exec", "terminal_read", "terminal_pty_step", "terminal_pty_read", "shizuku_execute"),
            "background_job" to listOf("terminal_job_start", "terminal_job_read"),
            "files" to listOf("list_files", "file_metadata", "read_text_file"),
            "gui" to listOf("r08_virtual_display_step", "r08_virtual_display_read"),
            "apps" to listOf("search_apps"),
            "device_read" to listOf("get_battery_status", "device_status", "network_status", "device_time", "device_audio", "device_environment", "platform_permissions", "last_known_location"),
            "personal_search" to listOf("search_media", "calendar_events", "search_contacts", "app_notifications", "current_notifications", "notification_history"),
            "system_action" to listOf("platform_set_alarm", "platform_set_timer", "platform_set_volume", "platform_media_key", "platform_set_wifi"),
            "generation" to listOf("generate_images", "generate_videos"),
            "context" to listOf("context_archive_page", "context_usage"),
            "user_question" to listOf("ask_user"),
            "mcp" to (0..7).map { "mcp_${it}_call" },
        )
        groups.forEach { (category, names) -> names.forEach { name ->
            assertEquals(name, category, ToolCardPresentations.present(name, "{}", null).category)
            assertNotEquals(name, "generic", toolOperationGlyph(name, "{}"))
        } }
        val branches = listOf(
            Triple("browser_write", "action", listOf("list_tabs", "get_readable", "get_text", "find_elements", "get_page_info", "get_backbone", "screenshot", "new_tab", "close_tab", "navigate", "click", "type", "hover", "scroll", "execute_js", "fetch", "get_cookies", "set_cookies", "scroll_and_collect", "set_user_agent", "set_viewport", "wait_for_selector", "wait_for_dom_stable", "reload", "go_back", "go_forward")),
            Triple("screen_action", "operation", listOf("observe", "screenshot", "tap", "long_press", "scroll_forward", "scroll_backward", "input", "back", "home", "recents", "notifications")),
            Triple("direct_child_task", "kind", listOf("APPEND", "REPLACE", "REDISPATCH")),
        )
        branches.forEach { (name, key, actions) -> actions.forEach { action ->
            val args = org.json.JSONObject().put(key, action).toString()
            val actualName = when {
                name == "browser_write" && action in setOf("list_tabs", "get_readable", "get_text", "find_elements", "get_page_info", "get_backbone", "screenshot") -> "browser_read"
                name == "screen_action" && action in setOf("observe", "screenshot") -> "screen"
                else -> name
            }
            assertNotEquals(action, "generic", ToolCardPresentations.present(actualName, args, null).category)
            assertNotEquals(action, "generic", toolOperationGlyph(actualName, args))
        } }
        assertEquals("generic", ToolCardPresentations.present("mcp_8_call", "{}", null).category)
        assertEquals("music", toolOperationGlyph("search_media", """{"kind":"audio"}"""))
        assertEquals("检索音乐", ToolCardPresentations.present("search_media", """{"kind":"audio"}""", null).title)
    }

    @Test fun presentationAcceptsSchemaSizedArgumentsWithoutDroppingBrowserCategory() {
        for (length in listOf(32768, 32769, 65536, 65537)) {
            val seed = org.json.JSONObject().put("action", "execute_js").put("script", "").toString()
            val args = org.json.JSONObject().put("action", "execute_js")
                .put("script", "x".repeat(length - seed.length)).toString()
            assertEquals(length, args.length)
            assertEquals(if (length <= 65536) "browser" else "generic",
                ToolCardPresentations.present("browser_write", args, null).category)
        }
        mapOf("skills" to "Skills", "delegation" to "子代理委派与结果", "terminal" to "终端命令",
            "background_job" to "后台与守护任务", "files" to "文件操作", "image_read" to "图片与视频封面")
            .forEach { (category, title) -> assertEquals(title, ToolCardPresentations.processTitle(listOf(category))) }
    }

    @Test fun ordinaryCommandToolsUseTerminalPresentationWithoutChangingReceiptOrIdentity() {
        val cases = listOf("terminal_exec" to "执行命令", "terminal_read" to "读取命令输出")
        cases.forEach { (name, title) ->
            val args = if (name == "terminal_exec")
                """{"command":"printf token=PRIVATE_VALUE","environment":"android","timeout_ms":30000}"""
            else """{"job_id":"00000000-0000-0000-0000-000000000001","offset":0,"limit":16384}"""
            val invocation = ToolInvocation(ToolCallId("call-$name"), name, args)
            val payload = """{"ok":true,"execution":"executed","state":"exited","exit_code":0,"stdout":"actual output"}"""
            val message = Message(MessageId("message-$name"), ConversationId("chat"), RunId("run"),
                Message.ASSISTANT_ROLE, listOf(MessageBlock.Tool(invocation, payload)), RunStatus.COMPLETED)
            val mappedStates = WorkStepState.entries.map { state ->
                val turn = ChatTurnMapper.map(listOf(message), toolState = { _, _ -> state }).single() as ChatTurnUi.Assistant
                val process = turn.workProcess!!
                assertEquals("终端命令", ToolCardPresentations.processTitle(process.segments.flatMap { it.steps }.map { it.category }))
                val step = process.segments.flatMap { it.steps }.single()
                assertEquals("terminal", step.category)
                assertEquals(title, step.name)
                assertEquals(if (name == "terminal_exec") "terminal" else "output", step.operationGlyph)
                assertEquals(invocation.id.value, step.toolCallId)
                assertEquals(name, step.toolName)
                assertEquals(WorkStepOwnerUi("chat", "run", message.id.value), step.owner)
                assertEquals("运行与输出", step.categoryDetails!!.heading)
                assertTrue(step.sections.any { it.title == "输出摘要" && it.text.contains("退出码：0") })
                assertFalse(step.toString().contains("PRIVATE_VALUE"))
                step.state
            }
            assertEquals(WorkStepState.entries.toList(), mappedStates)
            val withoutReceipt = ChatTurnMapper.map(listOf(message)).single() as ChatTurnUi.Assistant
            assertEquals(WorkStepState.UNKNOWN, withoutReceipt.workProcess!!.segments.flatMap { it.steps }.single().state)
            assertEquals(invocation, message.blocks.filterIsInstance<MessageBlock.Tool>().single().invocation)
        }
        assertEquals("工具链", ToolCardPresentations.processTitle(listOf("terminal", "device_read")))
        for (unknown in listOf("future_terminal_exec", "functions.terminal_exec", "terminal.exec")) {
            assertEquals("generic", ToolCardPresentations.present(unknown, "{}", null).category)
            assertEquals("generic", toolOperationGlyph(unknown, "{}"))
        }
    }

}
