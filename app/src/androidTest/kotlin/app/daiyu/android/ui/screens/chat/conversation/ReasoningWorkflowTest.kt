package app.daiyu.android.ui.screens.chat.conversation

import androidx.activity.compose.setContent
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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

    fun testFinalAnswerAppendsLeaveTheOpenedWorkflowIdleButUpdateTextLeaves() {
        prepare()
        val owner = WorkStepOwnerUi("c", "r", "a")
        val step = WorkStepUi("probe", "probe tool", "result", WorkStepState.OK, owner = owner, dedicatedKey = "probe")
        val thought = ReportedReasoning("r1", "first summary", 100)
        val work = WorkProcessUi(listOf(WorkSegmentUi(steps = listOf(step), reasoning = thought)), "进行中", true)
        var turn by mutableStateOf(ChatTurnUi.Assistant("a", finalText = "answer", status = RunStatus.RUNNING,
            isStreaming = true, reportedReasoning = listOf(thought), workProcess = work,
            content = listOf(MessageContentUi.Reasoning(thought), MessageContentUi.Tool(step), MessageContentUi.Text("final", "answer"))))
        val toolCommits = java.util.concurrent.atomic.AtomicInteger()
        val actions = ChatScreenActions()
        val dedicated: @androidx.compose.runtime.Composable (WorkStepUi) -> Boolean = {
            androidx.compose.runtime.SideEffect { toolCommits.incrementAndGet() }
            Text("stable dedicated probe")
            true
        }
        mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
            AssistantTurn(turn, actions, dedicatedTool = dedicated)
        } } }
        awaitHeader()
        ui.waitForIdle()
        ui.onNodeWithTag("work-process-header").performClick()
        ui.onNodeWithText("stable dedicated probe").assertIsDisplayed()
        ui.waitForIdle()
        val before = toolCommits.get()
        repeat(8) { index ->
            val answer = "answer update $index"
            runBlocking { projectMarkdown(listOf(answer), parser = streamingMarkdownParser) }
            mainThread { turn = turn.copy(finalText = answer, content = turn.content.dropLast(1) + MessageContentUi.Text("final", answer)) }
            ui.waitForIdle()
            ui.onNodeWithText(answer).assertIsDisplayed()
            assertEquals("Final answer updates must not recompose the unchanged dedicated workflow", before, toolCommits.get())
        }
        mainThread { turn = turn.copy(content = turn.content.map {
            if (it is MessageContentUi.Tool) it.copy(step = step.copy(detail = "changed result")) else it
        }) }
        ui.waitForIdle()
        assertTrue("Changed workflow data must still reach its dedicated content", toolCommits.get() > before)
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
        awaitHeader()
        ui.onNodeWithTag("work-process-header").performClick()
        ui.waitUntil(5_000) { ui.onAllNodesWithText("direct question").fetchSemanticsNodes().size == 1 }
        ui.onNodeWithText("direct question").assertIsDisplayed()
        ui.onNodeWithTag("reported-reasoning:${turn.listKey}").assertDoesNotExist()
        val r1 = ui.onNodeWithTag("work-reasoning:r1").fetchSemanticsNode().boundsInRoot.top
        val q = ui.onNodeWithText("direct question").fetchSemanticsNode().boundsInRoot.top
        val r2 = ui.onNodeWithTag("work-reasoning:r2").fetchSemanticsNode().boundsInRoot.top
        assertTrue(r1 < q && q < r2)
    }

    fun testPureAnswerKeepsItsSingleThoughtInsideTheWorkflow() {
        prepare()
        val turn = mapped(message(listOf(MessageBlock.Text("a", "answer")), listOf(ReportedReasoning("r1", "first summary"))))
        mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) { AssistantTurn(turn, ChatScreenActions()) } } }
        awaitHeader()
        ui.onNodeWithTag("reported-reasoning:${turn.listKey}").assertDoesNotExist()
        assertDisclosure("work-process-header", expanded = false)
        ui.onNodeWithTag("work-process-header").performClick()
        ui.onNodeWithTag("reported-reasoning-header:${turn.listKey}:reasoning:r1").performClick()
        ui.onNodeWithText("first summary").assertIsDisplayed()
        ui.onNodeWithText("answer").assertIsDisplayed()
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
            assertDisclosure("work-process-header", expanded = true)
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
                tail.copy(reportedReasoning = tail.reportedReasoning.map { it.frozen() }))) }
            awaitFrame {
                ui.onAllNodesWithText("second summary", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
            }
            ui.onNodeWithText("answer", useUnmergedTree = true).assertDoesNotExist()
            ui.onAllNodesWithTag("work-process-header").assertCountEquals(1)
            ui.onNodeWithText("first summary", useUnmergedTree = true).assertIsDisplayed()
            ui.onNodeWithText("second summary", useUnmergedTree = true).assertDoesNotExist()
            ui.onAllNodesWithTag("markdown-pending", useUnmergedTree = true).assertCountEquals(0)
            mainThread { source = source.copy(status = RunStatus.COMPLETED,
                historySegments = source.historySegments.mapIndexed { index, part ->
                    part.copy(blocks = if (index == source.historySegments.lastIndex)
                        listOf(MessageBlock.Text("answer", "answer")) else part.blocks,
                        reportedReasoning = part.reportedReasoning.map { it.frozen() })
                }) }
            awaitFrame { ui.onNodeWithTag("work-process-header").fetchSemanticsNode().config[
                androidx.compose.ui.semantics.SemanticsProperties.StateDescription] == "已折叠" }
            ui.mainClock.advanceTimeBy(300)
            ui.onNodeWithText("answer", useUnmergedTree = true).assertIsDisplayed()
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

    fun testRunningFlowStartsExpandedAndTerminalResetsOnlyTheOuterDisclosure() {
        var source by mutableStateOf(message(listOf(tool("t1")), emptyList()).copy(status = RunStatus.RUNNING))
        mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
            AssistantTurn(mapped(source), ChatScreenActions(), toolContent = { Text("unique tool detail") })
        } } }
        awaitHeader()
        assertDisclosure("work-process-header", expanded = true)
        ui.onNodeWithTag("tool-step:t1").assertIsDisplayed()
        ui.onNodeWithText("unique tool detail").assertDoesNotExist()
        ui.onNodeWithTag("tool-step-header:t1").performClick()
        ui.onNodeWithText("unique tool detail").assertIsDisplayed()
        ui.onNodeWithTag("tool-step-header:t1").performClick()
        ui.onNodeWithTag("tool-step-header:t1").performClick()
        ui.onNodeWithTag("work-process-header").performClick()
        ui.onNodeWithTag("work-process-header").performClick()
        assertDisclosure("work-process-header", expanded = true)
        mainThread { source = source.copy(status = RunStatus.COMPLETED) }
        ui.waitUntil(5_000) { ui.onNodeWithTag("work-process-header").fetchSemanticsNode().config[
            androidx.compose.ui.semantics.SemanticsProperties.StateDescription] == "已折叠" }
        ui.onNodeWithTag("work-process-header").performClick()
        ui.onNodeWithTag("tool-step-header:t1").assert(SemanticsMatcher("terminal tool details stay collapsed") {
            it.config[SemanticsProperties.StateDescription].endsWith("已折叠")
        })
        ui.waitUntil(5_000) { ui.onAllNodesWithText("unique tool detail").fetchSemanticsNodes().isEmpty() }
        mainThread { source = source.copy(error = "updated terminal receipt") }
        assertDisclosure("work-process-header", expanded = true)
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
    fun testToolOnlyRunningWorkflowStartsExpanded() {
        val turn = mapped(message(listOf(tool("t1")), emptyList()).copy(status = RunStatus.RUNNING)).copy(isStreaming = true)
        mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
            AssistantTurn(turn, ChatScreenActions())
        } } }
        awaitHeader()
        ui.onNodeWithTag("work-process-header").assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "已展开"))
        ui.onNodeWithTag("tool-step:t1").assertIsDisplayed()
        ui.onNodeWithTag("tool-step-header:t1").assert(SemanticsMatcher("leaf remains collapsed") {
            it.config[SemanticsProperties.StateDescription].endsWith("已折叠")
        })
    }

    fun testStoppingBeforeAnyToolKeepsReasoningInsideTheCollapsedWorkflow() {
        prepare()
        var source by mutableStateOf(message(emptyList(), listOf(
            ReportedReasoning("r1", "first summary", 0L, System.nanoTime(), blockOffset = 0))).copy(status = RunStatus.RUNNING))
        var requested by mutableStateOf(false)
        mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
            val turn = ChatTurnMapper.withStreamingOwner(ChatTurnMapper.map(listOf(source)), source,
                stopRequested = requested).single() as ChatTurnUi.Assistant
            AssistantTurn(turn, ChatScreenActions())
        } } }
        val owner = mapped(source).listKey
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("reported-reasoning-content:$owner:reasoning:r1").fetchSemanticsNodes().isNotEmpty() }
        mainThread { requested = true }
        ui.onNodeWithTag("reported-reasoning-content:$owner:reasoning:r1").assertDoesNotExist()
        mainThread { source = source.copy(status = RunStatus.STOPPED,
            reportedReasoning = source.reportedReasoning.map { it.frozen() }) }
        awaitHeader()
        assertDisclosure("work-process-header", expanded = false)
        ui.onNodeWithTag("reported-reasoning:$owner").assertDoesNotExist()
        ui.onNodeWithText("first summary").assertDoesNotExist()
        ui.onNodeWithTag("work-process-header").performClick()
        ui.onNodeWithTag("work-reasoning:r1").assertIsDisplayed()
        ui.onNodeWithTag("reported-reasoning-header:$owner:reasoning:r1").performClick()
        ui.onNodeWithText("first summary").assertIsDisplayed()
    }

    fun testStoppedReasoningNeverRestoresAPreStopExpandedOverride() {
        prepare()
        var mounted by mutableStateOf(true)
        var stopped by mutableStateOf(false)
        mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
            val holder = rememberSaveableStateHolder()
            if (mounted) holder.SaveableStateProvider("reasoning-state") {
                ReportedReasoningCard("restored-stop", listOf(ReportedReasoning("r1", "first summary", 0L, 1L)),
                    allowLiveTiming = true, stopped = stopped)
            }
        } } }
        val header = "reported-reasoning-header:restored-stop"
        ui.onNodeWithTag(header).performClick()
        ui.onNodeWithTag(header).performClick()
        assertDisclosure(header, expanded = true)
        mainThread { mounted = false }
        ui.onNodeWithTag(header).assertDoesNotExist()
        mainThread { stopped = true; mounted = true }
        assertDisclosure(header, expanded = false)
        ui.onNodeWithTag(header).performClick()
        assertDisclosure(header, expanded = true)
        mainThread { mounted = false }
        ui.onNodeWithTag(header).assertDoesNotExist()
        mainThread { mounted = true }
        assertDisclosure(header, expanded = true)
    }

    fun testTerminalWorkflowRestoresOnlyAnOverrideFromItsCurrentPhase() {
        var mounted by mutableStateOf(true)
        var running by mutableStateOf(true)
        val step = mapped(message(listOf(tool("t1")), emptyList())).workProcess!!.segments.single().steps.single()
        mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
            val holder = rememberSaveableStateHolder()
            if (mounted) holder.SaveableStateProvider("workflow-state") {
                WorkProcessCard(WorkProcessUi(listOf(WorkSegmentUi(steps = listOf(step))), "已完成", running), "restored-flow")
            }
        } } }
        awaitHeader()
        ui.onNodeWithTag("work-process-header").performClick()
        ui.onNodeWithTag("work-process-header").performClick()
        assertDisclosure("work-process-header", expanded = true)
        mainThread { mounted = false }
        ui.onNodeWithTag("work-process-header").assertDoesNotExist()
        mainThread { running = false; mounted = true }
        assertDisclosure("work-process-header", expanded = false)
        ui.onNodeWithTag("work-process-header").performClick()
        mainThread { mounted = false }
        ui.onNodeWithTag("work-process-header").assertDoesNotExist()
        mainThread { mounted = true }
        assertDisclosure("work-process-header", expanded = true)
    }

    fun testLiveAnswerWaitsForOuterWorkflowExitWithoutWaitingForTurnEnd() {
        prepare()
        var source by mutableStateOf(message(listOf(tool("t1")), listOf(
            ReportedReasoning("r1", "first summary", 0L, System.nanoTime(), blockOffset = 0))).copy(status = RunStatus.RUNNING))
        ui.mainClock.autoAdvance = false
        try {
            mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                AssistantTurn(mapped(source).copy(isStreaming = true), ChatScreenActions())
            } } }
            ui.mainClock.advanceTimeBy(300)
            ui.onNodeWithText("first summary").assertIsDisplayed()
            assertDisclosure("work-process-header", expanded = true)
            mainThread { source = source.copy(blocks = source.blocks + MessageBlock.Text("answer", "answer"),
                reportedReasoning = source.reportedReasoning.map { it.frozen() }) }
            ui.mainClock.advanceTimeByFrame()
            ui.onNodeWithText("answer").assertDoesNotExist()
            ui.waitUntil(5_000) {
                ui.mainClock.advanceTimeByFrame()
                ui.onNodeWithText("answer").assertDoesNotExist()
                ui.onNodeWithTag("work-process-header").fetchSemanticsNode().config[
                    SemanticsProperties.StateDescription] == "已折叠"
            }
            ui.mainClock.advanceTimeBy(48)
            ui.onNodeWithTag("tool-step:t1").assertExists()
            ui.onNodeWithText("answer").assertDoesNotExist()
            ui.waitUntil(5_000) {
                ui.mainClock.advanceTimeByFrame()
                if (ui.onAllNodesWithTag("tool-step:t1").fetchSemanticsNodes().isNotEmpty()) {
                    ui.onNodeWithText("answer").assertDoesNotExist()
                    false
                } else ui.onNodeWithText("answer").isDisplayed()
            }
            assertEquals(RunStatus.RUNNING, source.status)
            ui.onNodeWithText("first summary").assertDoesNotExist()
            ui.onNodeWithTag("work-process-header").performClick()
            ui.mainClock.advanceTimeBy(300)
            assertDisclosure("work-process-header", expanded = true)
            ui.onNodeWithText("answer").assertIsDisplayed()
        } finally { ui.mainClock.autoAdvance = true }
    }

    fun testToolOnlyAnswerWaitsForOuterExitInBothContentPaths() {
        prepare()
        ui.mainClock.autoAdvance = false
        try {
            for (mixed in listOf(false, true)) {
                var answer by mutableStateOf(false)
                val id = if (mixed) "mixed" else "legacy"
                mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    val base = mapped(message(listOf(tool(id)), emptyList()).copy(status = RunStatus.RUNNING))
                    val step = base.workProcess!!.segments.flatMap { it.steps }.single()
                    val body = if (answer) "answer" else ""
                    AssistantTurn(base.copy(finalText = body, isStreaming = true,
                        content = if (mixed) listOf(MessageContentUi.Tool(step)) +
                            if (answer) listOf(MessageContentUi.Text("a", body)) else emptyList() else emptyList()),
                        ChatScreenActions())
                } } }
                ui.mainClock.advanceTimeBy(300)
                ui.onNodeWithTag("tool-step:$id").assertIsDisplayed()
                assertDisclosure("work-process-header", expanded = true)
                mainThread { answer = true }
                ui.mainClock.advanceTimeByFrame()
                assertDisclosure("work-process-header", expanded = false)
                ui.onNodeWithTag("tool-step:$id").assertExists()
                ui.onNodeWithText("answer").assertDoesNotExist()
                ui.mainClock.advanceTimeBy(48)
                ui.onNodeWithTag("tool-step:$id").assertExists()
                ui.onNodeWithText("answer").assertDoesNotExist()
                ui.waitUntil(5_000) {
                    ui.mainClock.advanceTimeByFrame()
                    if (ui.onAllNodesWithTag("tool-step:$id").fetchSemanticsNodes().isNotEmpty()) {
                        ui.onNodeWithText("answer").assertDoesNotExist()
                        false
                    } else ui.onNodeWithText("answer").isDisplayed()
                }
                ui.onNodeWithTag("work-process-header").performClick()
                ui.mainClock.advanceTimeBy(300)
                ui.onNodeWithTag("tool-step:$id").assertIsDisplayed()
                ui.onNodeWithText("answer").assertIsDisplayed()
            }
        } finally { ui.mainClock.autoAdvance = true }
    }

    fun testStopImmediatelyRemovesAnUnfinishedReasoningRevealAndAllowsManualReopen() {
        prepare()
        var stopped by mutableStateOf(false)
        var text by mutableStateOf("first summary")
        var advances = 0
        var ready = false
        ui.mainClock.autoAdvance = false
        try {
            mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                ReportedReasoningCard("stop-reveal", listOf(ReportedReasoning("r1", text, 0L, 1L)),
                    allowLiveTiming = true, stopped = stopped, onVisibleAdvance = { advances++ },
                    onHandoffReady = { ready = it })
            } } }
            ui.mainClock.advanceTimeBy(300)
            mainThread { text += " still revealing".repeat(200) }
            ui.mainClock.advanceTimeBy(32)
            mainThread { stopped = true }
            ui.mainClock.advanceTimeByFrame()
            ui.onNodeWithTag("reported-reasoning-content:stop-reveal").assertDoesNotExist()
            val stoppedAdvances = advances
            ui.mainClock.advanceTimeBy(1000)
            assertEquals(stoppedAdvances, advances)
            assertTrue(ready)
            ui.onNodeWithTag("reported-reasoning-header:stop-reveal").performClick()
            ui.mainClock.advanceTimeBy(300)
            ui.waitUntil(5_000) { ui.onAllNodesWithTag("reported-reasoning-content:stop-reveal").fetchSemanticsNodes().size == 1 }
            mainThread { text += " terminal update" }
            ui.mainClock.advanceTimeBy(300)
            assertDisclosure("reported-reasoning-header:stop-reveal", expanded = true)
        } finally { ui.mainClock.autoAdvance = true }
    }

    fun testSingleThoughtWorkflowWaitsForTheLiveReasoningToFinishClosing() {
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

    fun testTerminalAutoCollapseAndManualReopenDoNotBlockTheAnswer() {
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
            ui.waitUntil(5_000) {
                ui.mainClock.advanceTimeBy(32)
                ui.onNodeWithTag("work-process-header").fetchSemanticsNode().config[SemanticsProperties.StateDescription] == "已折叠"
            }
            ui.onNodeWithTag("work-process-header").performClick()
            ui.mainClock.advanceTimeBy(300)
            assertDisclosure("work-process-header", expanded = true)
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
        ui.onNodeWithTag("tool-step:t1").assertIsDisplayed()
        mainThread { summaries = listOf(ReportedReasoning("later", "first summary", elapsedMillis = 0L, activeSinceNanos = System.nanoTime(), blockOffset = 1)) }
        ui.waitUntil(5_000) { ui.onAllNodesWithText("first summary").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("first summary").assertIsDisplayed()
        mainThread { summaries = summaries.map { it.copy(activeSinceNanos = null) } +
            ReportedReasoning("empty", "...", elapsedMillis = 0L, activeSinceNanos = System.nanoTime(), blockOffset = 1) }
        mainThread { summaries = summaries.map { it.copy(activeSinceNanos = null) }; done = true }
        ui.waitUntil(5_000) { ui.onAllNodesWithText("answer").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("answer").assertIsDisplayed()
    }

    fun testFirstThoughtIsAlreadyInTheWorkflowAndKeepsItsBodyWhenToolsAndMixedContentArrive() {
        prepare()
        var source by mutableStateOf(message(emptyList(), listOf(
            ReportedReasoning("r1", "first summary", 0L, System.nanoTime(), blockOffset = 0))).copy(status = RunStatus.RUNNING))
        var dedicated by mutableStateOf(false)
        mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
            val turn = ChatTurnMapper.map(listOf(source), dedicatedTool = { _, _ -> if (dedicated) "verified" else null })
                .single() as ChatTurnUi.Assistant
            AssistantTurn(turn.copy(isStreaming = true), ChatScreenActions())
        } } }
        awaitHeader()
        assertDisclosure("work-process-header", expanded = true)
        val owner = mapped(source).listKey
        val body = "reported-reasoning-content:$owner:reasoning:r1"
        ui.waitUntil(5_000) { ui.onNodeWithText("first summary").isDisplayed() }
        val original = ui.onNodeWithTag(body).fetchSemanticsNode().id
        ui.onNodeWithTag("reported-reasoning:$owner").assertDoesNotExist()
        mainThread { source = source.copy(blocks = listOf(tool("t1"))) }
        ui.onNodeWithTag("work-process-header").assertExists()
        assertEquals(original, ui.onNodeWithTag(body).fetchSemanticsNode().id)
        ui.onNodeWithTag("tool-step:t1").assertDoesNotExist()
        mainThread { dedicated = true }
        assertEquals(original, ui.onNodeWithTag(body).fetchSemanticsNode().id)
        ui.onAllNodesWithTag("work-process-header").assertCountEquals(1)
        ui.onAllNodesWithText("first summary").assertCountEquals(1)
    }

    fun testAReleasedToolExpandsIntoPlaceInsteadOfMountingAtFullHeight() {
        prepare()
        var reported by mutableStateOf(ReportedReasoning("r1", "first summary", 0L, System.nanoTime(), blockOffset = 0))
        ui.mainClock.autoAdvance = false
        try {
            mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                val source = message(listOf(tool("t1")), listOf(reported)).copy(status = RunStatus.RUNNING)
                AssistantTurn(mapped(source).copy(isStreaming = true), ChatScreenActions())
            } } }
            ui.mainClock.advanceTimeBy(300)
            ui.onNodeWithText("first summary").assertIsDisplayed()
            ui.onNodeWithTag("workflow-entry:tool:t1").assertDoesNotExist()
            mainThread { reported = reported.frozen() }
            ui.mainClock.advanceTimeByFrame()
            ui.onNodeWithTag("workflow-entry:tool:t1").assertDoesNotExist()
            ui.waitUntil(5_000) {
                ui.mainClock.advanceTimeByFrame()
                ui.onAllNodesWithTag("workflow-entry:tool:t1").fetchSemanticsNodes().isNotEmpty()
            }
            ui.onNodeWithText("first summary").assertDoesNotExist()
            ui.mainClock.advanceTimeBy(48)
            val intermediate = ui.onNodeWithTag("workflow-entry:tool:t1").fetchSemanticsNode().boundsInRoot.height
            ui.mainClock.advanceTimeBy(300)
            val complete = ui.onNodeWithTag("workflow-entry:tool:t1").fetchSemanticsNode().boundsInRoot.height
            assertTrue("entry should have a real intermediate height: $intermediate < $complete", intermediate > 0f && intermediate < complete)
            ui.onNodeWithTag("tool-step:t1").assertIsDisplayed()
            assertDisclosure("work-process-header", expanded = true)
        } finally { ui.mainClock.autoAdvance = true }
    }

    fun testEntranceRegistrySurvivesContentUnmountAndDoesNotReplayOnReopen() {
        var mounted by mutableStateOf(true)
        ui.mainClock.autoAdvance = false
        try {
            mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                val entrances = remember { WorkflowEntrances() }
                if (mounted) WorkflowEntry("fixture", entrances, animate = true) { Box(Modifier.size(100.dp)) }
            } } }
            ui.mainClock.advanceTimeBy(64)
            val intermediate = ui.onNodeWithTag("workflow-entry:fixture").fetchSemanticsNode().boundsInRoot.height
            ui.mainClock.advanceTimeBy(300)
            val complete = ui.onNodeWithTag("workflow-entry:fixture").fetchSemanticsNode().boundsInRoot.height
            assertTrue(intermediate > 0f && intermediate < complete)
            mainThread { mounted = false }
            ui.mainClock.advanceTimeByFrame()
            ui.onNodeWithTag("workflow-entry:fixture").assertDoesNotExist()
            mainThread { mounted = true }
            ui.mainClock.advanceTimeByFrame()
            assertEquals(complete, ui.onNodeWithTag("workflow-entry:fixture").fetchSemanticsNode().boundsInRoot.height, 1f)
        } finally { ui.mainClock.autoAdvance = true }
    }

}
