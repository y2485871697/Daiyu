package app.daiyu.android.ui.screens.chat.conversation

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import app.daiyu.android.ComposeActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.domain.*
import app.daiyu.android.ui.app.AgentAppTheme

class ToolOperationCardTest : ComposeActivityTestCase() {
    private lateinit var activity: ComponentActivity
    override fun setUp() {
        super.setUp()
        activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, ComponentActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
    }
    override fun tearDown() { try { mainThread { activity.finish() } } finally { super.tearDown() } }
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
            ui.onNodeWithText("原始等待状态：0").assertIsDisplayed()
            ui.onNodeWithText("secret-output", substring = true).assertDoesNotExist()
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
}
