package app.daiyu.android.ui.screens.chat.conversation

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
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
    private fun source(mixed: Boolean): Message {
        val owner = MessageImageOwner("conversation", "message")
        val image = MessageBlock.Image(MessageImageReference(owner, "00000000-0000-0000-0000-000000000001", "image/png", 10, "b".repeat(64), 2, 2))
        val blocks = buildList {
            add(MessageBlock.Text("before", "before operation"))
            if (mixed) add(image)
            add(MessageBlock.Tool(ToolInvocation(ToolCallId("card-call"), "terminal_pty_read", "{}"), "{\"wait_status\":0,\"stdout\":\"secret-output\"}"))
            add(MessageBlock.Text("after", "final answer"))
        }
        return Message(MessageId("message"), ConversationId("conversation"), RunId("run"), Message.ASSISTANT_ROLE, blocks, RunStatus.COMPLETED)
    }
    fun testBothProductionPathsRenderCallOnceAndDisclosureNeverRetries() {
        var executions = 0
        var copies = 0
        for (mixed in listOf(false, true)) {
            val turn = ChatTurnMapper.map(listOf(source(mixed)), toolState = { _, _ -> WorkStepState.FAILED }).single() as ChatTurnUi.Assistant
            show(turn, ChatScreenActions(onRetryStep = { executions++ }, onCopyToolSummary = { copies++ }))
            if (!mixed) ui.onNodeWithText("工作过程").performClick()
            ui.onAllNodesWithTag("tool-step:card-call").assertCountEquals(1)
            ui.onNodeWithText("读取终端输出").performClick()
            ui.onNodeWithText("原始等待状态").assertIsDisplayed()
            ui.onNodeWithText("secret-output", substring = true).assertIsDisplayed()
            ui.onNodeWithText("技术详情").performClick()
            ui.onNodeWithText("收起技术详情").performClick()
            ui.onNodeWithContentDescription("复制安全摘要").performClick()
            assertEquals(if (mixed) 2 else 1, copies)
            ui.onNodeWithContentDescription("重试 读取终端输出").assertDoesNotExist()
            ui.onNodeWithText("读取终端输出").performClick()
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
            if (!mixed) ui.onNodeWithText("工作过程").performClick()
            ui.onNodeWithText("读取终端输出").performClick()
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
            ui.onNodeWithText("工作过程").assertDoesNotExist()
            ui.onAllNodesWithTag("tool-step:card-call").assertCountEquals(0)
            show(turn, ChatScreenActions())
            ui.onAllNodesWithTag("tool-step:card-call").assertCountEquals(1)
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
        ui.onNodeWithTag("tool-action:PLAY_VIDEO").performScrollTo().assertIsEnabled().performClick()
        assertEquals(step.actionRequest(ToolCardAction.PLAY_VIDEO, "asset"), requests.last())
        assertEquals(1, requests.size)
        ui.onNodeWithText("暂未接入", substring = true).assertDoesNotExist()
        ui.onNodeWithText("宿主未提供", substring = true).assertDoesNotExist()
    }

}
