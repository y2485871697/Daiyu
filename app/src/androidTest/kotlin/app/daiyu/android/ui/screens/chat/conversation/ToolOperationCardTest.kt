package app.daiyu.android.ui.screens.chat.conversation

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toPixelMap
import app.daiyu.android.data.model.AppearanceThemeMode
import androidx.compose.ui.unit.sp
import androidx.compose.ui.test.*
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.domain.*
import app.daiyu.android.ui.app.AgentAppTheme

class ToolOperationCardTest : WiredActivityTestCase() {

    private fun show(turn: ChatTurnUi.Assistant, actions: ChatScreenActions) {
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                Column(Modifier.verticalScroll(rememberScrollState())) { AssistantTurn(turn, actions) }
            }
        } }
        ui.waitForIdle()
    }
    private fun source(mixed: Boolean, fixtureKey: String = ""): Message {
        val messageId = "message$fixtureKey"
        val owner = MessageImageOwner("conversation", messageId)
        val image = MessageBlock.Image(MessageImageReference(owner, "00000000-0000-0000-0000-000000000001", "image/png", 10, "b".repeat(64), 2, 2))
        val blocks = buildList {
            add(MessageBlock.Text("before", "before operation"))
            if (mixed) add(image)
            add(MessageBlock.Tool(ToolInvocation(ToolCallId("card-call$fixtureKey"), "terminal_pty_read", "{}"), "{\"wait_status\":0,\"stdout\":\"secret-output\"}"))
            add(MessageBlock.Text("after", "final answer"))
        }
        return Message(MessageId(messageId), ConversationId("conversation"), RunId("run"), Message.ASSISTANT_ROLE, blocks, RunStatus.COMPLETED)
    }
    fun testBothProductionPathsRenderCallOnceAndDisclosureNeverRetries() {
        var executions = 0
        for (mixed in listOf(false, true)) {
            val turn = ChatTurnMapper.map(listOf(source(mixed)), toolState = { _, _ -> WorkStepState.FAILED }).single() as ChatTurnUi.Assistant
            show(turn, ChatScreenActions(onRetryStep = { executions++ }))
            if (!mixed) ui.onNodeWithTag("work-process-header").performClick()
            ui.onAllNodesWithTag("tool-step:card-call").assertCountEquals(1)
            ui.onNodeWithText("读取终端输出").scrollToVisible().assertIsDisplayed().performClick()
            ui.onNodeWithText("原始等待状态").assertDoesNotExist()
            ui.onNodeWithTag("tool-runtime").scrollToVisible().performClick()
            ui.onNodeWithText("原始等待状态").assertIsDisplayed()
            ui.onNodeWithText("secret-output", substring = true).assertIsDisplayed()
            ui.onNodeWithText("技术详情").scrollToVisible().assertIsDisplayed().performClick()
            ui.onNodeWithText("收起技术详情").scrollToVisible().assertIsDisplayed().performClick()
            ui.onNodeWithContentDescription("复制安全摘要").assertDoesNotExist()
            ui.onNodeWithContentDescription("重试 读取终端输出").assertDoesNotExist()
            ui.onNodeWithText("读取终端输出").scrollToVisible().assertIsDisplayed().performClick()
            assertEquals(0, executions)
            ui.onNodeWithText("final answer").assertIsDisplayed()
        }
    }
    fun testHistoricalUnknownAndEveryReservedCategoryHaveOnlyReadOnlyDisclosure() {
        var executions = 0
        ToolCardPresentations.categories.forEach { category ->
            val step = WorkStepUi("reserved", "fixture-$category", "安全摘要", WorkStepState.UNKNOWN,
                WorkStepOwnerUi("old", "old-run", category), "fixture-only", category,
                listOf(ToolDetailSectionUi("结果摘要", "结果未确认，不恢复执行权")))
            show(ChatTurnUi.Assistant("historical-$category", content = listOf(MessageContentUi.Tool(step))),
                ChatScreenActions(onRetryStep = { executions++ }))
            ui.onNodeWithText("fixture-$category").performClick()
            ui.onNodeWithText("结果未确认，不恢复执行权").assertIsDisplayed()
            ui.onNodeWithContentDescription("重试 fixture-$category").assertDoesNotExist()
        }
        assertEquals(0, executions)
    }
    fun testTypedCategoryBodiesUseTheSharedReadOnlyDisclosure() {
        ToolCardPresentations.categories.forEach { category ->
            val details = ToolCategoryDetails.project(category, "{}", "{\"count\":0,\"state\":\"unknown\"}")
            val step = WorkStepUi("typed", "typed-$category", "安全摘要", WorkStepState.UNKNOWN,
                WorkStepOwnerUi("chat", "run", category), "fixture-only", category,
                categoryDetails = details)
            show(ChatTurnUi.Assistant("typed-$category", content = listOf(MessageContentUi.Tool(step))), ChatScreenActions())
            ui.onNodeWithTag("tool-category:$category").assertDoesNotExist()
            ui.onNodeWithText("typed-$category").performClick()
            ui.onNodeWithTag("tool-category:$category").assertIsDisplayed()
            ui.onNodeWithText(details.heading).assertDoesNotExist()
            ui.onNodeWithText(details.notice).assertDoesNotExist()
            ui.onNodeWithText(details.rich.source).assertDoesNotExist()
            ui.onNodeWithContentDescription("重试 typed-$category").assertDoesNotExist()
        }
    }

    fun testCompactDeviceAndActionBodiesHideTechnicalFieldsInBothProductionPaths() {
        var executions = 0
        for (mixed in listOf(false, true)) for (device in listOf(false, true)) {
            val name = if (device) "device_time" else "platform_set_wifi"
            val result = if (device) """{"ok":true,"data":{"local_iso":"2026-04-18T21:22:00+08:00","timezone":"Asia/Shanghai","epoch_ms":1}}"""
                else """{"ok":true,"message":"系统已记录请求"}"""
            val original = source(mixed, "-compact-$mixed-$device")
            val message = original.copy(blocks = original.blocks.map { block ->
                if (block is MessageBlock.Tool) block.copy(invocation = block.invocation.copy(name = name,
                    arguments = """{"enabled":true}"""), result = result) else block
            })
            val turn = ChatTurnMapper.map(listOf(message), toolState = { _, _ -> WorkStepState.OK }).single() as ChatTurnUi.Assistant
            show(turn, ChatScreenActions(onRetryStep = { executions++ }))
            if (!mixed) ui.onNodeWithTag("work-process-header").performClick()
            ui.onNodeWithText(if (device) "查询时间" else "切换 Wi-Fi").scrollToVisible().performClick()
            ui.onNodeWithText(if (device) "2026-04-18 21:22:00\nAsia/Shanghai · UTC+08:00" else "系统已记录请求").assertIsDisplayed()
            ui.onNodeWithText("时间戳").assertDoesNotExist()
            ui.onNodeWithText("请求启用").assertDoesNotExist()
            ui.onNodeWithText("已记录返回").assertDoesNotExist()
            ui.onNodeWithText("调用参数").assertDoesNotExist()
            ui.onNodeWithText("技术详情").scrollToVisible().performClick()
            ui.onNodeWithText("调用参数").scrollToVisible().assertIsDisplayed()
            ui.onNodeWithText("调用回执").scrollToVisible().assertIsDisplayed()
        }
        assertEquals(0, executions)
    }

    fun testCommandAndShortLogKeepLavenderFramesAndMonospace() {
        for (dark in listOf(false, true)) {
            val backdrop = if (dark) Color(0xFF101218) else Color.White
            mainThread { activity.setContent {
                AgentAppTheme(AppearanceSettings(themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT),
                    applyInterfaceScale = false) {
                    Column(Modifier.background(backdrop).verticalScroll(rememberScrollState())) {
                        ToolCategoryContent("terminal", ToolCategoryDetails.project("terminal",
                            """{"command":"printf hello"}""", """{"stdout":"hello","exit_code":0}"""))
                    }
                }
            } }
            ui.waitForIdle()
            for (tag in listOf("tool-document:命令", "tool-document:标准输出")) {
                val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                ui.onNodeWithTag(tag, useUnmergedTree = true).performSemanticsAction(
                    androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertEquals(androidx.compose.ui.text.font.FontFamily.Monospace, layouts.single().layoutInput.style.fontFamily)
                assertEquals(13.sp, layouts.single().layoutInput.style.fontSize)
            }
            val pixels = captureScreenNode(ui.onNodeWithTag("tool-document:命令", useUnmergedTree = true)).toPixelMap()
            val actual = pixels[pixels.width / 2, (pixels.height * .15f).toInt()]
            val expected = Color(if (dark) 0x13BEA8D7 else 0x108A779A).compositeOver(backdrop)
            assertEquals(expected.red, actual.red, .025f)
            assertEquals(expected.green, actual.green, .025f)
            assertEquals(expected.blue, actual.blue, .025f)
            ui.onNodeWithTag("tool-output-hint:标准输出").assertDoesNotExist()
            ui.onNodeWithTag("tool-document:标准输出", useUnmergedTree = true).assertTextEquals("hello")
            ui.onNodeWithText("运行回执").assertDoesNotExist()
        }
    }

    fun testLongOutputDisclosureAndHostSlotNeverReplayTools() {
        var executions = 0
        val body = "long output " + "x".repeat(600)
        for (mixed in listOf(false, true)) {
            val message = source(mixed).copy(blocks = source(mixed).blocks.map { block ->
                if (block is MessageBlock.Tool) block.copy(result = org.json.JSONObject().put("stdout", body).toString()) else block
            })
            val turn = ChatTurnMapper.map(listOf(message), toolState = { _, _ -> WorkStepState.OK }).single() as ChatTurnUi.Assistant
            mainThread { activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        AssistantTurn(turn, ChatScreenActions(onRetryStep = { executions++ }), toolContent = {
                            top.yukonga.miuix.kmp.basic.Text("trusted-host-slot")
                        })
                    }
                }
            } }
            ui.waitForIdle()
            if (!mixed) ui.onNodeWithTag("work-process-header").performClick()
            ui.onNodeWithText("读取终端输出").scrollToVisible().assertIsDisplayed().performClick()
            ui.onAllNodesWithText("trusted-host-slot").assertCountEquals(1)
            ui.onNodeWithText(body).assertDoesNotExist()
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            ui.onNodeWithTag("tool-output-preview:标准输出", useUnmergedTree = true)
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue(layouts.single().lineCount <= 3)
            ui.onNodeWithTag("tool-document:标准输出").performClick()
            ui.onNodeWithText(body).assertExists()
            ui.onNodeWithTag("tool-output-copy").performClick()
            ui.onNodeWithText("已复制").assertExists()
            mainThread {
                val manager = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                assertEquals(body, manager.primaryClip!!.getItemAt(0).text.toString())
            }
            ui.onNodeWithTag("tool-output-close").performClick()
            ui.onNodeWithText(body).assertDoesNotExist()
            assertEquals(0, executions)
        }
    }

    fun testRestrictedReceiptsNeverComposeAssetsOrControlsInEitherProductionPath() {
        var hostCompositions = 0
        for (mixed in listOf(false, true)) for (private in listOf(false, true)) {
            val key = "restricted-$mixed-$private"
            val state = if (private) "\"privacy_redacted\":true" else "\"ok\":false"
            val payload = "{\"data\":{" + state + ",\"text\":\"RESTRICTED_PAYLOAD\"}}"
            val original = source(mixed, key)
            val message = original.copy(blocks = original.blocks.map { block ->
                if (block is MessageBlock.Tool) block.copy(invocation = block.invocation.copy(name = "read_text_file"), result = payload) else block
            })
            val turn = ChatTurnMapper.map(listOf(message), toolState = { _, _ -> WorkStepState.OK }).single() as ChatTurnUi.Assistant
            mainThread { activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        AssistantTurn(turn, ChatScreenActions(), toolContent = {
                            hostCompositions++
                            top.yukonga.miuix.kmp.basic.Text("restricted-host-content")
                        })
                    }
                }
            } }
            ui.waitForIdle()
            if (!mixed) ui.onNodeWithTag("work-process-header").performClick()
            ui.onNodeWithTag("tool-step-header:card-call$key").scrollToVisible().performClick()
            ui.onNodeWithText(if (private) "结果未获展示授权" else "本次调用未完成，详情见技术详情").assertIsDisplayed()
            ui.onNodeWithText("restricted-host-content").assertDoesNotExist()
            ui.onNodeWithText("RESTRICTED_PAYLOAD", substring = true).assertDoesNotExist()
            if (private) {
                ui.onNodeWithTag("tool-step-technical:card-call$key").scrollToVisible().performClick()
                ui.onNodeWithText("RESTRICTED_PAYLOAD", substring = true).assertDoesNotExist()
            }
            assertEquals(0, hostCompositions)
        }
    }

    fun testDedicatedQuestionIsVisibleAtCallPositionInBothPathsAndMissingHostFallsBack() {
        for (mixed in listOf(false, true)) {
            val original = source(mixed).copy(blocks = source(mixed).blocks.map { block ->
                if (block is MessageBlock.Tool) block.copy(invocation = block.invocation.copy(name = "ask_user")) else block
            })
            val turn = ChatTurnMapper.map(listOf(original), dedicatedTool = { _, _ -> "validated-question" })
                .single() as ChatTurnUi.Assistant
            mainThread { activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        AssistantTurn(turn, ChatScreenActions(), dedicatedTool = { step ->
                            if (step.dedicatedKey == "validated-question") {
                                top.yukonga.miuix.kmp.basic.Text("Dedicated question")
                                true
                            } else false
                        })
                    }
                }
            } }
            ui.waitForIdle()
            ui.onAllNodesWithText("Dedicated question").assertCountEquals(1)
            ui.onNodeWithText("Dedicated question").assertIsDisplayed()
            ui.onNodeWithTag("work-process-header").assertDoesNotExist()
            ui.onAllNodesWithTag("tool-step:card-call").assertCountEquals(0)
            show(turn, ChatScreenActions())
            ui.onAllNodesWithTag("tool-step:card-call").assertCountEquals(1)
        }
    }

    fun testPublicListShowsFirstResultsAndPrivateListRemainsExplicit() {
        for (category in listOf("files", "personal_search")) {
            val result = "{\"items\":[" + (1..5).joinToString { "{\"name\":\"file$it\",\"content\":\"body$it\"}" } + "]}"
            mainThread { activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        ToolCategoryContent(category, ToolCategoryDetails.project(category, "{}", result))
                    }
                }
            } }
            ui.waitForIdle()
            if (category == "files") {
                ui.onNodeWithText("body1").assertIsDisplayed()
                ui.onNodeWithText("body4").assertDoesNotExist()
                ui.onNodeWithText("查看其余 2 项").scrollToVisible().performClick()
                ui.onNodeWithText("body4").scrollToVisible().assertIsDisplayed()
            } else {
                ui.onNodeWithText("body1").assertDoesNotExist()
                ui.onNodeWithTag("tool-entries").performClick()
                ui.onNodeWithText("body1").assertIsDisplayed()
            }
        }
    }
    fun testEmptySkillsListShowsZeroUnlessBodyRestricted() {
        for (restricted in listOf(false, true)) {
            val payload = if (restricted) """{"privacy_redacted":true,"skills":[]}"""
                else """{"skills":[]}"""
            val details = ToolCardPresentations.present("skills_list", "{}", payload).details
            mainThread { activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    ToolCategoryContent("skills", details)
                }
            } }
            ui.waitForIdle()
            if (restricted) {
                ui.onNodeWithText("结果未获展示授权").assertIsDisplayed()
                ui.onNodeWithText("技能数").assertDoesNotExist()
                ui.onNodeWithText("0").assertDoesNotExist()
            } else {
                ui.onNodeWithText("技能数").assertIsDisplayed()
                ui.onNodeWithText("0").assertIsDisplayed()
            }
        }
    }

    fun testPrivacyWithheldHidesAllCategoryDataAndUnknownCategoryIsNotBlank() {
        for (category in listOf("files", "apps", "browser", "personal_search", "future_category")) {
            val payload = if (category == "future_category") """{"new_result":"unknown visible receipt"}"""
                else """{"privacy_redacted":true,"status":"private_result_withheld","data":{"text":"PRIVATE_BODY","items":[{"content":"PRIVATE_ITEM"}]}}"""
            mainThread { activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    ToolCategoryContent(category, ToolCategoryDetails.project(category, "{}", payload))
                }
            } }
            ui.waitForIdle()
            if (category == "future_category") ui.onNodeWithText("unknown visible receipt", substring = true).assertExists()
            else {
                ui.onNodeWithText("结果未获展示授权").assertIsDisplayed()
                ui.onNodeWithText("PRIVATE_BODY", substring = true).assertDoesNotExist()
                ui.onNodeWithText("PRIVATE_ITEM", substring = true).assertDoesNotExist()
            }
        }
    }
    fun testSameCategoryAndMixedToolChainsUseCategoryDrivenTitles() {
        for ((names, heading) in listOf(
            listOf("device_time", "network_status") to "设备查询",
            listOf("terminal_pty_read", "terminal_pty_read") to "终端",
            listOf("device_time", "terminal_pty_read", "future_tool") to "工具链")) {
            val key = names.joinToString("-")
            val message = Message(MessageId(key), ConversationId("conversation"), RunId("run"), Message.ASSISTANT_ROLE,
                names.mapIndexed { index, name -> MessageBlock.Tool(ToolInvocation(ToolCallId("chain-$index"), name, "{}"), "unique receipt $index") }, RunStatus.COMPLETED)
            val turn = ChatTurnMapper.map(listOf(message), toolState = { _, _ -> WorkStepState.OK }).single() as ChatTurnUi.Assistant
            show(turn, ChatScreenActions())
            ui.onNodeWithText(heading).assertIsDisplayed()
            ui.onNodeWithTag("work-process-header").performClick()
            names.indices.forEach { ui.onAllNodesWithTag("tool-step:chain-$it").assertCountEquals(1) }
        }
    }

    fun testFinalControlsStayEnabledAndDispatchOnlyBoundIntent() = finalControlsStayEnabledAndDispatchOnlyBoundIntent()

    private fun finalControlsStayEnabledAndDispatchOnlyBoundIntent() {
        val step = WorkStepUi("final-call", "工作区", "", WorkStepState.UNKNOWN,
            WorkStepOwnerUi("chat", "old-run", "message"), "workspace-inspect", "workspace")
        val requests = mutableListOf<ToolCardActionRequest>()
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ToolMediaSurface(step, null, true, 12000L, "asset") { requests += it }
                }
            }
        } }
        ui.waitForIdle()
        ui.onNodeWithTag("tool-action:MERGE_WORKSPACE").assertDoesNotExist()
        ui.onNodeWithTag("tool-action:DISCARD_WORKSPACE").assertDoesNotExist()
        ui.onNodeWithTag("tool-action:PLAY_VIDEO").scrollToVisible().assertIsEnabled().performClick()
        assertEquals(step.actionRequest(ToolCardAction.PLAY_VIDEO, "asset"), requests.last())
        assertEquals(1, requests.size)
        ui.onNodeWithText("暂未接入", substring = true).assertDoesNotExist()
        ui.onNodeWithText("宿主未提供", substring = true).assertDoesNotExist()
    }

}
