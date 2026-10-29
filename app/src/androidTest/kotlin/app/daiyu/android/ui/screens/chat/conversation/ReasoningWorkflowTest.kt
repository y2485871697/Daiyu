package app.daiyu.android.ui.screens.chat.conversation

import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.domain.*
import app.daiyu.android.ui.app.AgentAppTheme
import kotlinx.coroutines.runBlocking
import top.yukonga.miuix.kmp.basic.Text

/** Production mapper and AssistantTurn; offline component regression, no tool execution. */
class ReasoningWorkflowTest : WiredActivityTestCase() {
    private fun tool(id: String) = MessageBlock.Tool(ToolInvocation(ToolCallId(id), "lookup", "{}"), "done")
    private fun message(blocks: List<MessageBlock>, summaries: List<ReportedReasoning>) = Message(
        MessageId("a"), ConversationId("c"), RunId("r"), "assistant", blocks, RunStatus.COMPLETED, reportedReasoning = summaries)
    private fun mapped(message: Message) = ChatTurnMapper.map(listOf(message)).single() as ChatTurnUi.Assistant
    private fun prepare() = runBlocking {
        val sources = listOf("first summary", "second summary", "answer")
        projectMarkdown(sources)
        projectMarkdown(sources, parser = streamingMarkdownParser)
    }
    private fun awaitHeader() = ui.waitUntil(5_000) { ui.onAllNodesWithTag("work-process-header").fetchSemanticsNodes().size == 1 }
    private fun assertDisclosure(tag: String, expanded: Boolean) {
        ui.onNodeWithTag(tag).assert(SemanticsMatcher.expectValue(
            SemanticsProperties.StateDescription, if (expanded) "已展开" else "已折叠"))
    }

    fun testWorkflowOrderIndependentDisclosureAndNoDuplicateTopCard() {
        prepare()
        val turn = mapped(message(listOf(tool("t1"), tool("t2"), MessageBlock.Text("a", "answer")),
            listOf(ReportedReasoning("r1", "first summary", 100, blockOffset = 0), ReportedReasoning("r2", "second summary", 200, blockOffset = 1))))
        mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) { AssistantTurn(turn, ChatScreenActions()) } } }
        awaitHeader()
        ui.onNodeWithTag("reported-reasoning:${turn.listKey}").assertDoesNotExist()
        ui.onNodeWithTag("work-process-header").performClick()
        val tags = listOf("work-reasoning:r1", "tool-step:t1", "work-reasoning:r2", "tool-step:t2")
        val tops = tags.map { ui.onNodeWithTag(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top }
        assertTrue(tops.zipWithNext().all { (a, b) -> a < b })
        val first = "reported-reasoning-header:${turn.listKey}:reasoning:r1"
        val second = "reported-reasoning-header:${turn.listKey}:reasoning:r2"
        ui.onNodeWithTag(first).performClick()
        ui.onNodeWithText("first summary").assertIsDisplayed()
        ui.onNodeWithText("second summary").assertDoesNotExist()
        ui.onNodeWithTag(second).performClick()
        ui.onNodeWithTag(first).performClick()
        ui.onNodeWithText("first summary").assertDoesNotExist()
        ui.onNodeWithText("second summary").assertIsDisplayed()
        ui.onNodeWithText("answer").assertIsDisplayed()
        assertDisclosure(first, expanded = false)
        assertDisclosure(second, expanded = true)
        ui.onNodeWithTag("work-process-header").performClick()
        assertDisclosure("work-process-header", expanded = false)
        ui.onNodeWithText("second summary").assertDoesNotExist()
        ui.onNodeWithText("answer").assertIsDisplayed()
        ui.onNodeWithTag("work-process-header").performClick()
        assertDisclosure("work-process-header", expanded = true)
        assertDisclosure(first, expanded = false)
        assertDisclosure(second, expanded = true)
        ui.onNodeWithText("second summary").assertExists().assertIsDisplayed()
        ui.onNodeWithText("first summary").assertDoesNotExist()
        ui.onNodeWithText("answer").assertIsDisplayed()
    }

    fun testAppendingStepsKeepsTheExistingDisclosureIdentity() {
        prepare()
        val first = message(listOf(tool("t1")), listOf(ReportedReasoning("r1", "first summary", blockOffset = 0)))
        var turn by mutableStateOf(mapped(first))
        mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) { AssistantTurn(turn, ChatScreenActions()) } } }
        awaitHeader()
        ui.onNodeWithTag("work-process-header").performClick()
        ui.onNodeWithTag("reported-reasoning-header:${turn.listKey}:reasoning:r1").performClick()
        mainThread { turn = mapped(first.copy(blocks = first.blocks + tool("t2"), reportedReasoning = first.reportedReasoning +
            ReportedReasoning("r2", "second summary", blockOffset = 1))) }
        ui.onNodeWithText("first summary").assertIsDisplayed()
        ui.onNodeWithText("second summary").assertDoesNotExist()
        ui.onNodeWithTag("work-reasoning:r2").assertIsDisplayed()
    }

    fun testDedicatedInteractionStaysDirectlyAvailableInMixedOrder() {
        prepare()
        val source = message(listOf(tool("question"), MessageBlock.Text("a", "answer")),
            listOf(ReportedReasoning("r1", "first summary", blockOffset = 0), ReportedReasoning("r2", "second summary", blockOffset = 1)))
        val turn = ChatTurnMapper.map(listOf(source), dedicatedTool = { _, _ -> "verified" }).single() as ChatTurnUi.Assistant
        mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
            AssistantTurn(turn, ChatScreenActions(), dedicatedTool = { Text("direct question"); true })
        } } }
        ui.waitUntil(5_000) { ui.onAllNodesWithText("direct question").fetchSemanticsNodes().size == 1 }
        ui.onNodeWithText("direct question").assertIsDisplayed()
        ui.onNodeWithTag("reported-reasoning:${turn.listKey}").assertDoesNotExist()
        val r1 = ui.onNodeWithTag("work-reasoning:r1").fetchSemanticsNode().boundsInRoot.top
        val q = ui.onNodeWithText("direct question").fetchSemanticsNode().boundsInRoot.top
        val r2 = ui.onNodeWithTag("work-reasoning:r2").fetchSemanticsNode().boundsInRoot.top
        assertTrue(r1 < q && q < r2)
    }

    fun testPureAnswerKeepsStandaloneReasoning() {
        prepare()
        val turn = mapped(message(listOf(MessageBlock.Text("a", "answer")), listOf(ReportedReasoning("r1", "first summary"))))
        mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) { AssistantTurn(turn, ChatScreenActions()) } } }
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("reported-reasoning:${turn.listKey}").fetchSemanticsNodes().size == 1 }
        ui.onNodeWithTag("work-process-header").assertDoesNotExist()
        ui.onNodeWithTag("reported-reasoning-header:${turn.listKey}").performClick()
        ui.onNodeWithText("first summary").assertIsDisplayed()
    }

    fun testLiveReasoningOpensItsWorkflowAndCollapsesAtCompletion() {
        prepare()
        var reported by mutableStateOf(ReportedReasoning("r1", "first summary", 0L, System.nanoTime(), blockOffset = 1))
        var running by mutableStateOf(true)
        ui.mainClock.autoAdvance = false
        try {
            mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                val turn = mapped(message(listOf(tool("t1")), listOf(reported)).copy(status = if (running) RunStatus.RUNNING else RunStatus.COMPLETED))
                    .copy(isStreaming = running)
                AssistantTurn(turn, ChatScreenActions())
            } } }
            ui.mainClock.advanceTimeBy(300)
            ui.onNodeWithTag("work-process-header").assert(SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "已展开"))
            ui.onNodeWithText("first summary").assertIsDisplayed()
            ui.onNodeWithText("first summary").performTouchInput { click() }
            ui.mainClock.advanceTimeBy(300)
            ui.onNodeWithText("first summary").assertDoesNotExist()
            ui.onNodeWithTag("tool-step:t1").assertIsDisplayed()
            mainThread { reported = reported.copy(elapsedMillis = 5_400L, activeSinceNanos = null) }
            ui.mainClock.advanceTimeBy(300)
            assertDisclosure("work-process-header", expanded = false)
            ui.onNodeWithTag("work-process-header").performClick()
            ui.mainClock.advanceTimeBy(300)
            ui.onNodeWithTag("reasoning-step-state:r1", useUnmergedTree = true).assert(SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "已思考"))
            ui.onNodeWithContentDescription("思考完成", useUnmergedTree = true).assertExists()
            ui.onNodeWithTag("work-process-header").performClick()
            ui.mainClock.advanceTimeBy(300)
            mainThread { running = false }
            ui.mainClock.advanceTimeBy(300)
            ui.onNodeWithTag("work-process-header").assert(SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "已折叠"))
            ui.onNodeWithTag("work-process-header").performClick()
            ui.mainClock.advanceTimeBy(300)
            ui.onNodeWithText("已思考").assertExists()
            ui.onNodeWithText("5.4s").assertExists()
            ui.onNodeWithText("first summary").assertDoesNotExist()
        } finally { ui.mainClock.autoAdvance = true }
    }
    fun testProtocolTailAndThoughtCompletionKeepOneExpandedWorkflow() {
        prepare()
        val first = MessageHistorySegment(MessageId("a"), "assistant", listOf(tool("t1")),
            reportedReasoning = listOf(ReportedReasoning("r1", "first summary", 100, blockOffset = 0)))
        val tail = MessageHistorySegment(MessageId("a"), "assistant", emptyList(),
            reportedReasoning = listOf(ReportedReasoning("r2", "second summary", 0, 0, blockOffset = 0)))
        var source by mutableStateOf(message(emptyList(), emptyList()).copy(status = RunStatus.RUNNING,
            historySegments = listOf(first, tail)))
        fun awaitFrame(condition: () -> Boolean) {
            ui.waitUntil(5_000) {
                ui.mainClock.advanceTimeBy(32)
                condition()
            }
        }
        ui.mainClock.autoAdvance = false
        try {
            mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                val turn = ChatTurnMapper.withStreamingOwner(ChatTurnMapper.map(listOf(source)), source).single() as ChatTurnUi.Assistant
                AssistantTurn(turn, ChatScreenActions())
            } } }
            awaitFrame {
                ui.onAllNodesWithTag("work-process-header").fetchSemanticsNodes().size == 1 &&
                    ui.onAllNodesWithText("second summary", useUnmergedTree = true).fetchSemanticsNodes().size == 1
            }
            ui.onAllNodesWithTag("work-process-header").assertCountEquals(1)
            val owner = mapped(source).listKey
            ui.onNodeWithTag("reported-reasoning-header:$owner:reasoning:r1").performClick()
            ui.mainClock.advanceTimeBy(300)
            ui.onAllNodesWithTag("markdown-pending", useUnmergedTree = true).assertCountEquals(0)
            ui.onNodeWithText("first summary", useUnmergedTree = true).assertIsDisplayed()
            mainThread { source = source.copy(historySegments = listOf(first,
                tail.copy(blocks = listOf(MessageBlock.Text("answer", "answer")),
                    reportedReasoning = tail.reportedReasoning.map { it.frozen() }))) }
            awaitFrame {
                ui.onNodeWithText("answer", useUnmergedTree = true).isDisplayed() &&
                    ui.onAllNodesWithText("first summary", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
            }
            ui.onAllNodesWithTag("work-process-header").assertCountEquals(1)
            ui.onNodeWithText("first summary", useUnmergedTree = true).assertDoesNotExist()
            ui.onNodeWithText("second summary", useUnmergedTree = true).assertDoesNotExist()
            ui.onAllNodesWithTag("markdown-pending", useUnmergedTree = true).assertCountEquals(0)
            mainThread { source = source.copy(status = RunStatus.COMPLETED,
                historySegments = source.historySegments.map { part ->
                    part.copy(reportedReasoning = part.reportedReasoning.map { it.frozen() })
                }) }
            awaitFrame { ui.onNodeWithTag("work-process-header").fetchSemanticsNode().config[
                androidx.compose.ui.semantics.SemanticsProperties.StateDescription] == "已折叠" }
            ui.mainClock.advanceTimeBy(300)
            ui.onNodeWithTag("work-process-header").performClick()
            ui.mainClock.advanceTimeBy(300)
            val key = mapped(source).listKey
            assertDisclosure("reported-reasoning-header:$key:reasoning:r1", expanded = true)
            ui.onNodeWithTag("reported-reasoning-header:$key:reasoning:r2").performClick()
            ui.mainClock.advanceTimeBy(300)
            awaitFrame { ui.onAllNodesWithText("second summary", useUnmergedTree = true).fetchSemanticsNodes().size == 1 }
            ui.onNodeWithText("first summary", useUnmergedTree = true).assertIsDisplayed()
            ui.onAllNodesWithTag("markdown-pending", useUnmergedTree = true).assertCountEquals(0)
        } finally { ui.mainClock.autoAdvance = true }
    }

    fun testToolOnlyRunningFlowStartsCollapsedAndManualStatePersists() {
        var source by mutableStateOf(message(listOf(tool("t1")), emptyList()).copy(status = RunStatus.RUNNING))
        mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
            AssistantTurn(mapped(source), ChatScreenActions(), toolContent = { Text("unique tool detail") })
        } } }
        awaitHeader()
        ui.onNodeWithTag("tool-step:t1").assertDoesNotExist()
        ui.onNodeWithTag("work-process-header").performClick()
        ui.onNodeWithText("unique tool detail").assertDoesNotExist()
        ui.onNodeWithTag("tool-step-header:t1").performClick()
        ui.onNodeWithText("unique tool detail").assertIsDisplayed()
        ui.onNodeWithTag("tool-step-header:t1").performClick()
        ui.onNodeWithTag("tool-step-header:t1").performClick()
        ui.onNodeWithTag("work-process-header").performClick()
        mainThread { source = source.copy(status = RunStatus.COMPLETED) }
        ui.waitUntil(5_000) { ui.onNodeWithTag("work-process-header").fetchSemanticsNode().config[
            androidx.compose.ui.semantics.SemanticsProperties.StateDescription] == "已折叠" }
        ui.onNodeWithTag("work-process-header").performClick()
        ui.onNodeWithTag("tool-step-header:t1").assert(SemanticsMatcher("terminal tool details stay collapsed") {
            it.config[SemanticsProperties.StateDescription].endsWith("已折叠")
        })
        ui.waitUntil(5_000) { ui.onAllNodesWithText("unique tool detail").fetchSemanticsNodes().isEmpty() }
    }

    fun testMixedContentKeepsDisclosureAcrossPrefixChangesAndCollapsesOnlyAtTerminal() {
        prepare()
        val step = mapped(message(listOf(tool("t1")), emptyList())).workProcess!!.segments.single().steps.single()
        val first = MessageContentUi.Reasoning(ReportedReasoning("r1", "first summary"))
        val second = MessageContentUi.Reasoning(ReportedReasoning("r2", "second summary"))
        val initial = listOf(first, MessageContentUi.Tool(step), second)
        var content by mutableStateOf(initial)
        var running by mutableStateOf(true)
        mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
            MessageContent(content, ChatScreenActions(), null, markdown = true, flowRunning = running,
                toolContent = { Text("mixed tool detail") },
                reasoningBody = { reasoning, connector -> ReasoningStepRow("mixed", reasoning, connector,
                    generationComplete = !running) })
        } } }
        val firstHeader = "reported-reasoning-header:mixed:reasoning:r1"
        val secondHeader = "reported-reasoning-header:mixed:reasoning:r2"
        ui.waitUntil(5_000) { ui.onAllNodesWithTag(secondHeader).fetchSemanticsNodes().size == 1 }
        ui.onNodeWithTag(firstHeader).performClick()
        ui.onNodeWithTag(secondHeader).performClick()
        ui.onNodeWithText("first summary").assertIsDisplayed()
        ui.onNodeWithText("second summary").assertIsDisplayed()
        ui.onNodeWithTag("tool-step-header:t1").performClick()
        ui.onNodeWithText("mixed tool detail").assertIsDisplayed()
        ui.onNodeWithTag(firstHeader).performClick()
        ui.onNodeWithTag("tool-step-header:t1").performClick()
        mainThread { content = listOf(MessageContentUi.Text("prefix", "prefix")) + initial }
        assertDisclosure(firstHeader, false)
        assertDisclosure(secondHeader, true)
        ui.onNodeWithTag("tool-step-header:t1").assert(SemanticsMatcher("same tool stays collapsed") {
            it.config[SemanticsProperties.StateDescription].endsWith("已折叠")
        })
        mainThread { content = initial }
        assertDisclosure(firstHeader, false)
        assertDisclosure(secondHeader, true)
        ui.onNodeWithTag("tool-step-header:t1").performClick()
        mainThread { running = false }
        assertDisclosure(firstHeader, false)
        assertDisclosure(secondHeader, true)
        ui.onNodeWithTag("tool-step-header:t1").assert(SemanticsMatcher("terminal retains explicit tool disclosure") {
            it.config[SemanticsProperties.StateDescription].endsWith("已展开")
        })
        ui.onNodeWithTag(secondHeader).performClick()
        ui.onNodeWithTag("tool-step-header:t1").performClick()
        assertDisclosure(secondHeader, false)
    }

    fun testGroupedReplyActionBarAddressesTheExactFinalSource() {
        prepare()
        val source = message(emptyList(), emptyList()).copy(historySegments = listOf(
            MessageHistorySegment(MessageId("a"), "assistant", listOf(tool("t1"))),
            MessageHistorySegment(MessageId("a"), "assistant", listOf(MessageBlock.Text("answer", "answer")))))
        val turn = mapped(source)
        val received = mutableListOf<String>()
        mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
            AssistantTurn(turn, ChatScreenActions(onBranch = { received += it }))
        } } }
        ui.waitUntil(5_000) { ui.onAllNodesWithContentDescription("分支").fetchSemanticsNodes().size == 1 }
        ui.onNodeWithContentDescription("分支").performClick()
        assertEquals(listOf(turn.sourceListKeys.last()), received)
        assertTrue(received.single() != turn.listKey)
    }

    fun testUnifiedHeaderTracksCompletedStepsAndCurrentPhaseWithoutReopening() {
        prepare()
        val before = ReportedReasoning("before", "first summary", blockOffset = 0)
        val after = ReportedReasoning("after", "second summary", 0, 1, blockOffset = 1)
        val source = message(listOf(tool("t1")), listOf(before)).copy(status = RunStatus.RUNNING)
        fun project(message: Message, receipt: ToolReceiptUi) =
            ChatTurnMapper.map(listOf(message), toolReceipt = { _, _ -> receipt }).single() as ChatTurnUi.Assistant
        var turn by mutableStateOf(project(source, ToolReceiptUi(WorkStepState.PENDING, ToolActivityUi.RUNNING)))
        mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
            AssistantTurn(turn, ChatScreenActions())
        } } }
        awaitHeader()
        ui.onNodeWithText("工作流程").assertIsDisplayed()
        ui.onNodeWithText("已完成1步 · 执行中").assertIsDisplayed()
        assertDisclosure("work-process-header", expanded = false)
        ui.onNodeWithTag("work-process-header").performClick()
        assertDisclosure("work-process-header", expanded = true)
        ui.onNodeWithTag("work-process-header").performClick()
        assertDisclosure("work-process-header", expanded = false)
        val thinking = source.copy(reportedReasoning = listOf(before, after))
        mainThread { turn = project(thinking, ToolReceiptUi(WorkStepState.OK)) }
        ui.onNodeWithText("已完成2步 · 思考中").assertIsDisplayed()
        assertDisclosure("work-process-header", expanded = false)
        mainThread { turn = project(thinking.copy(status = RunStatus.COMPLETED,
            reportedReasoning = listOf(before, after.copy(activeSinceNanos = null))), ToolReceiptUi(WorkStepState.OK)) }
        ui.onNodeWithText("工作流程").assertIsDisplayed()
        ui.onNodeWithText("已完成3步").assertIsDisplayed()
        ui.onNodeWithText("已完成2步 · 思考中").assertDoesNotExist()
        assertDisclosure("work-process-header", expanded = false)
    }

    fun testUnifiedHeaderKeepsFailureStopAndUnknownVisible() {
        prepare()
        for ((status, state, label) in listOf(
            Triple(RunStatus.COMPLETED, WorkStepState.UNKNOWN, "1步待确认"),
            Triple(RunStatus.COMPLETED, WorkStepState.FAILED, "1步失败"),
            Triple(RunStatus.STOPPED, WorkStepState.UNKNOWN, "1步待确认 · 已停止"),
            Triple(RunStatus.INTERRUPTED, WorkStepState.UNKNOWN, "1步待确认 · 已中断"))) {
            val source = message(listOf(tool("t1")), listOf(ReportedReasoning("r1", "first summary"))).copy(status = status)
            val turn = ChatTurnMapper.map(listOf(source), toolState = { _, _ -> state }).single() as ChatTurnUi.Assistant
            mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                AssistantTurn(turn, ChatScreenActions())
            } } }
            awaitHeader()
            ui.onNodeWithText("工作流程").assertIsDisplayed()
            ui.onNodeWithText("已完成1步 · $label").assertIsDisplayed()
            ui.onNodeWithText("已完成2步").assertDoesNotExist()
        }
    }


    fun testMixedResultCountsUpdateWithoutReopeningOrDuplicatingRows() {
        prepare()
        val source = message(listOf(tool("ok-1"), tool("ok-2"), tool("unknown"), tool("failed")),
            listOf(ReportedReasoning("r1", "first summary")))
        fun project(unknown: WorkStepState, failed: WorkStepState) = ChatTurnMapper.map(listOf(source), toolState = { _, block ->
            when (block.invocation.id.value) {
                "unknown" -> unknown
                "failed" -> failed
                else -> WorkStepState.OK
            }
        }).single() as ChatTurnUi.Assistant
        var turn by mutableStateOf(project(WorkStepState.UNKNOWN, WorkStepState.NOT_EXECUTED))
        mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
            AssistantTurn(turn, ChatScreenActions())
        } } }
        awaitHeader()
        ui.onNodeWithText("已完成3步 · 1步待确认").assertIsDisplayed()
        assertDisclosure("work-process-header", expanded = false)
        mainThread { turn = project(WorkStepState.UNKNOWN, WorkStepState.FAILED) }
        ui.onNodeWithText("已完成3步 · 1步失败 · 1步待确认").assertIsDisplayed()
        assertDisclosure("work-process-header", expanded = false)
        ui.onNodeWithTag("work-process-header").performClick()
        ui.onNodeWithTag("tool-step-header:unknown").assertIsDisplayed()
        ui.onNodeWithTag("tool-step-header:failed").assertIsDisplayed()
        ui.onNodeWithTag("work-process-header").performClick()
        mainThread { turn = project(WorkStepState.NOT_EXECUTED, WorkStepState.FAILED) }
        ui.onNodeWithText("已完成3步 · 1步失败").assertIsDisplayed()
        ui.onNodeWithText("已完成3步 · 1步失败 · 1步待确认").assertDoesNotExist()
        assertDisclosure("work-process-header", expanded = false)
    }
    fun testToolOnlyRunningWorkflowStartsCollapsed() {
        val turn = mapped(message(listOf(tool("t1")), emptyList()).copy(status = RunStatus.RUNNING)).copy(isStreaming = true)
        mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
            AssistantTurn(turn, ChatScreenActions())
        } } }
        awaitHeader()
        ui.onNodeWithTag("work-process-header").assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "已折叠"))
        ui.onNodeWithTag("tool-step:t1").assertDoesNotExist()
    }

    fun testStandaloneAnswerWaitsForTheLiveReasoningToFinishClosing() {
        prepare()
        var reported by mutableStateOf(ReportedReasoning("r1", "first summary", elapsedMillis = 0L, activeSinceNanos = System.nanoTime()))
        var done by mutableStateOf(false)
        ui.mainClock.autoAdvance = false
        try {
            mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                val source = message(if (done) listOf(MessageBlock.Text("a", "answer")) else emptyList(), listOf(reported))
                    .copy(status = if (done) RunStatus.COMPLETED else RunStatus.RUNNING)
                AssistantTurn(mapped(source).copy(isStreaming = !done), ChatScreenActions())
            } } }
            ui.mainClock.advanceTimeBy(300)
            ui.onNodeWithText("first summary").assertIsDisplayed()
            mainThread { reported = reported.copy(activeSinceNanos = null, elapsedMillis = 100L); done = true }
            ui.mainClock.advanceTimeByFrame()
            ui.onNodeWithText("answer").assertDoesNotExist()
            ui.waitUntil(5_000) {
                ui.mainClock.advanceTimeBy(32)
                ui.onAllNodesWithText("first summary").fetchSemanticsNodes().isEmpty() &&
                    ui.onNodeWithText("answer").isDisplayed()
            }
            ui.onNodeWithText("first summary").assertDoesNotExist()
            ui.onNodeWithText("answer").assertIsDisplayed()
        } finally { ui.mainClock.autoAdvance = true }
    }


    fun testRevealEpochDoesNotReleaseAClosingReasoningHandoff() {
        prepare()
        var reported by mutableStateOf(ReportedReasoning("r1", "first summary", elapsedMillis = 0L,
            activeSinceNanos = System.nanoTime()))
        var done by mutableStateOf(false)
        var epoch by mutableStateOf(0L)
        ui.mainClock.autoAdvance = false
        try {
            mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                val source = message(if (done) listOf(MessageBlock.Text("a", "answer")) else emptyList(), listOf(reported))
                    .copy(status = if (done) RunStatus.COMPLETED else RunStatus.RUNNING)
                AssistantTurn(mapped(source).copy(isStreaming = !done), ChatScreenActions(), revealResetEpoch = epoch)
            } } }
            ui.mainClock.advanceTimeBy(300)
            ui.onNodeWithText("first summary").assertIsDisplayed()
            mainThread { reported = reported.copy(activeSinceNanos = null, elapsedMillis = 100L); done = true; epoch++ }
            ui.mainClock.advanceTimeByFrame()
            ui.onNodeWithText("answer").assertDoesNotExist()
            ui.waitUntil(5_000) {
                ui.mainClock.advanceTimeBy(32)
                ui.onAllNodesWithText("first summary").fetchSemanticsNodes().isEmpty() &&
                    ui.onNodeWithText("answer").isDisplayed()
            }
            ui.onNodeWithText("first summary").assertDoesNotExist()
            ui.onNodeWithText("answer").assertIsDisplayed()
        } finally { ui.mainClock.autoAdvance = true }
    }

    fun testManualWorkflowCollapseFinishesBeforeTheLegacyAnswerMounts() {
        prepare()
        var reported by mutableStateOf(ReportedReasoning("r1", "first summary", elapsedMillis = 0L,
            activeSinceNanos = System.nanoTime(), blockOffset = 0))
        var done by mutableStateOf(false)
        ui.mainClock.autoAdvance = false
        try {
            mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                val source = message(listOf(tool("t1")) + if (done) listOf(MessageBlock.Text("a", "answer")) else emptyList(),
                    listOf(reported)).copy(status = if (done) RunStatus.COMPLETED else RunStatus.RUNNING)
                AssistantTurn(mapped(source).copy(content = emptyList(), isStreaming = !done), ChatScreenActions())
            } } }
            ui.mainClock.advanceTimeBy(300)
            ui.onNodeWithText("first summary").assertIsDisplayed()
            ui.onNodeWithTag("work-process-header").performClick()
            mainThread { reported = reported.copy(activeSinceNanos = null, elapsedMillis = 100L); done = true }
            ui.mainClock.advanceTimeByFrame()
            ui.onNodeWithText("answer").assertDoesNotExist()
            ui.waitUntil(5_000) {
                ui.mainClock.advanceTimeBy(32)
                ui.onAllNodesWithText("first summary").fetchSemanticsNodes().isEmpty() &&
                    ui.onNodeWithText("answer").isDisplayed()
            }
            ui.onNodeWithText("first summary").assertDoesNotExist()
            ui.onNodeWithText("answer").assertIsDisplayed()
        } finally { ui.mainClock.autoAdvance = true }
    }

    fun testExplicitlyOpenLegacyWorkflowDoesNotBlockItsNewAnswer() {
        prepare()
        var reported by mutableStateOf(ReportedReasoning("r1", "first summary", 0L,
            System.nanoTime(), blockOffset = 0))
        var done by mutableStateOf(false)
        ui.mainClock.autoAdvance = false
        try {
            mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                val source = message(listOf(tool("t1")) + if (done) listOf(MessageBlock.Text("a", "answer")) else emptyList(),
                    listOf(reported)).copy(status = if (done) RunStatus.COMPLETED else RunStatus.RUNNING)
                AssistantTurn(mapped(source).copy(content = emptyList(), isStreaming = !done), ChatScreenActions())
            } } }
            ui.mainClock.advanceTimeBy(300)
            assertDisclosure("work-process-header", expanded = true)
            ui.onNodeWithTag("work-process-header").performClick()
            ui.mainClock.advanceTimeBy(300)
            ui.onNodeWithTag("work-process-header").performClick()
            ui.mainClock.advanceTimeBy(300)
            assertDisclosure("work-process-header", expanded = true)
            mainThread { reported = reported.copy(activeSinceNanos = null); done = true }
            ui.waitUntil(5_000) {
                ui.mainClock.advanceTimeBy(32)
                ui.onNodeWithText("answer").isDisplayed()
            }
            assertDisclosure("work-process-header", expanded = true)
            ui.onNodeWithTag("work-process-header").performClick()
            ui.mainClock.advanceTimeBy(300)
            ui.onNodeWithText("answer").assertIsDisplayed()
            ui.onNodeWithTag("work-process-header").performClick()
            ui.mainClock.advanceTimeBy(300)
            ui.onNodeWithText("answer").assertIsDisplayed()
        } finally { ui.mainClock.autoAdvance = true }
    }

    fun testLaterReasoningOpensAfterAToolOnlyStartAndEmptySummaryDoesNotLockTheAnswer() {
        prepare()
        var summaries by mutableStateOf<List<ReportedReasoning>>(emptyList())
        var done by mutableStateOf(false)
        mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
            val source = message(listOf(tool("t1")) + if (done) listOf(MessageBlock.Text("a", "answer")) else emptyList(), summaries)
                .copy(status = if (done) RunStatus.COMPLETED else RunStatus.RUNNING)
            AssistantTurn(mapped(source).copy(isStreaming = !done), ChatScreenActions())
        } } }
        awaitHeader()
        ui.onNodeWithTag("tool-step:t1").assertDoesNotExist()
        mainThread { summaries = listOf(ReportedReasoning("later", "first summary", elapsedMillis = 0L, activeSinceNanos = System.nanoTime(), blockOffset = 1)) }
        ui.waitUntil(5_000) { ui.onAllNodesWithText("first summary").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("first summary").assertIsDisplayed()
        mainThread { summaries = summaries.map { it.copy(activeSinceNanos = null) } +
            ReportedReasoning("empty", "...", elapsedMillis = 0L, activeSinceNanos = System.nanoTime(), blockOffset = 1) }
        mainThread { summaries = summaries.map { it.copy(activeSinceNanos = null) }; done = true }
        ui.waitUntil(5_000) { ui.onAllNodesWithText("answer").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("answer").assertIsDisplayed()
    }

}
