package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.ExecutionPhase
import app.daiyu.android.domain.runtime.ToolDisposition
import org.junit.Assert.*
import org.junit.Test

class WorkProcessProgressTest {
    private val before = ReportedReasoning("before", "checked", blockOffset = 0)
    private val active = ReportedReasoning("after", "checking", 0, 1, blockOffset = 1)
    private fun source(status: RunStatus = RunStatus.RUNNING, reasoning: List<ReportedReasoning> = listOf(before)) =
        Message(MessageId("message"), ConversationId("conversation"), RunId("run"), Message.ASSISTANT_ROLE,
            listOf(MessageBlock.Tool(ToolInvocation(ToolCallId("call"), "browser_read", "{\"action\":\"list_tabs\"}"), "done")),
            status, reportedReasoning = reasoning)
    private fun process(message: Message, receipt: ToolReceiptUi? = null): WorkProcessUi =
        (ChatTurnMapper.map(listOf(message), toolReceipt = { _, _ -> receipt }).single() as ChatTurnUi.Assistant).workProcess!!
    private fun step(state: WorkStepState, activity: ToolActivityUi = ToolActivityUi.from(state)) =
        WorkStepUi("call-$state", "operation", "", state, activity = activity)

    @Test fun allCategoriesTargetsAndEmptyProcessesUseTheSameTitle() {
        for (category in ToolCardPresentations.categories + "future_category") {
            for (target in listOf(null, "Android • user", "Debian • root")) {
                val work = WorkProcessUi(listOf(WorkSegmentUi(steps = listOf(
                    step(WorkStepState.OK).copy(category = category, executionTargetLabel = target)))), "已完成")
                assertEquals("工作流程", work.title)
            }
        }
        assertEquals("工作流程", WorkProcessUi(emptyList(), "已完成").title)
    }

    @Test fun onlySuccessfulToolReceiptsContributeToCompletedSteps() {
        val work = WorkProcessUi(listOf(WorkSegmentUi(narration = "not a step",
            steps = WorkStepState.entries.map { step(it) }, reasoning = before)), "失败")
        assertEquals(2, work.completedStepCount)
        assertEquals("已完成2步 · 1步失败 · 1步待确认", work.progressSummary)
        assertEquals(1, work.failedStepCount)
        assertEquals(1, work.unconfirmedStepCount)
    }

    @Test fun activeAndHiddenPlaceholderReasoningAreNotCompleted() {
        val reports = listOf(before, active, ReportedReasoning("blank", ""), ReportedReasoning("dots", "... …"),
            ReportedReasoning("empty-live", "", 0, 1))
        val work = WorkProcessUi(reports.map { WorkSegmentUi(reasoning = it) }, "思考中", true)
        assertEquals(1, work.completedStepCount)
        assertEquals("已完成1步 · 思考中", work.progressSummary)
        assertEquals(2, work.copy(segments = listOf(WorkSegmentUi(reasoning = before),
            WorkSegmentUi(reasoning = active.copy(activeSinceNanos = null)))).completedStepCount)
    }

    @Test fun finishedReasoningDoesNotRequireKnownDuration() {
        for (elapsed in listOf(null, 0L, 5_400L)) {
            val work = WorkProcessUi(listOf(WorkSegmentUi(reasoning = before.copy(elapsedMillis = elapsed))), "已完成")
            assertEquals("已完成1步", work.progressSummary)
        }
    }

    @Test fun returnedTextWithoutTrustedReceiptDoesNotBecomeSuccessful() {
        val work = process(source(RunStatus.COMPLETED))
        assertEquals(WorkStepState.UNKNOWN, work.segments.flatMap { it.steps }.single().state)
        assertEquals("已完成1步 · 1步待确认", work.progressSummary)
    }

    @Test fun liveSequenceCountsOnlyClosedStepsAndSwitchesCurrentPhase() {
        val executing = process(source(), ToolReceiptUi(WorkStepState.PENDING, ToolActivityUi.RUNNING))
        assertEquals("已完成1步 · 执行中", executing.progressSummary)
        val thinking = process(source(reasoning = listOf(before, active)), ToolReceiptUi(WorkStepState.OK))
        assertEquals("已完成2步 · 思考中", thinking.progressSummary)
        val finished = process(source(RunStatus.COMPLETED, listOf(before, active.copy(activeSinceNanos = null))),
            ToolReceiptUi(WorkStepState.OK))
        assertEquals("已完成3步", finished.progressSummary)
        assertEquals("工作流程", executing.title)
        assertEquals(executing.title, thinking.title)
        assertEquals(thinking.title, finished.title)
    }


    @Test fun activeUnknownReceiptDoesNotFlickerInTheWorkflowSummary() {
        for ((phase, pause, label) in listOf(
            Triple(ExecutionPhase.TOOL_EXECUTING, false, "执行中"),
            Triple(ExecutionPhase.WAITING_USER, false, "等待回答"),
            Triple(ExecutionPhase.TOOL_EXECUTING, true, "暂停中"))) {
            val receipt = toolReceiptPresentation(ToolDisposition.UNKNOWN, true, phase,
                pauseRequested = pause, activeCall = true)
            val work = process(source(), receipt)
            assertEquals(WorkStepState.UNKNOWN, work.segments.flatMap { it.steps }.single().state)
            assertEquals(0, work.unconfirmedStepCount)
            assertEquals("已完成1步 · $label", work.progressSummary)
        }
        val unknown = toolReceiptPresentation(ToolDisposition.UNKNOWN, true, ExecutionPhase.TOOL_EXECUTING,
            activeCall = false)
        assertEquals("已完成1步 · 1步待确认 · 进行中", process(source(), unknown).progressSummary)
        val stoppedUnknown = toolReceiptPresentation(ToolDisposition.UNKNOWN, true, ExecutionPhase.TOOL_EXECUTING,
            stopRequested = true, activeCall = false)
        assertEquals(ToolActivityUi.UNKNOWN, stoppedUnknown.activity)
        assertEquals(1, process(source(), stoppedUnknown).unconfirmedStepCount)
        val stoppingActive = toolReceiptPresentation(ToolDisposition.UNKNOWN, true, ExecutionPhase.TOOL_EXECUTING,
            stopRequested = true, activeCall = true)
        assertEquals("已完成1步 · 停止中", process(source(), stoppingActive).progressSummary)
        val stillMarkedActive = toolReceiptPresentation(ToolDisposition.UNKNOWN, true, ExecutionPhase.TOOL_EXECUTING,
            activeCall = true)
        assertEquals(1, process(source(RunStatus.COMPLETED), stillMarkedActive).unconfirmedStepCount)
        assertEquals("已完成2步 · 进行中", process(source(), ToolReceiptUi(WorkStepState.OK)).progressSummary)
    }

    @Test fun runningStepCountIsNotTotalMinusOneForParallelCalls() {
        val work = WorkProcessUi(listOf(WorkSegmentUi(steps = listOf(step(WorkStepState.OK),
            step(WorkStepState.PENDING), step(WorkStepState.PENDING).copy(id = "other")), reasoning = before)), "执行中", true)
        assertEquals("已完成2步 · 执行中", work.progressSummary)
    }

    @Test fun completedToolDoesNotLeaveAStaleExecutingPhase() {
        assertEquals("已完成2步 · 进行中", process(source(), ToolReceiptUi(WorkStepState.OK)).progressSummary)
    }

    @Test fun pendingControlsPreserveTheirActualActivity() {
        for (activity in listOf(ToolActivityUi.QUEUED, ToolActivityUi.PREPARING, ToolActivityUi.WAITING,
            ToolActivityUi.PAUSING, ToolActivityUi.PAUSED, ToolActivityUi.STOPPING)) {
            assertEquals("已完成1步 · ${activity.label}",
                process(source(), ToolReceiptUi(WorkStepState.PENDING, activity)).progressSummary)
        }
    }

    @Test fun abnormalEndStatesKeepTheirLabelsWithoutFinishingCurrentTool() {
        for ((status, label) in listOf(RunStatus.FAILED to "流程失败", RunStatus.STOPPED to "已停止",
            RunStatus.INTERRUPTED to "已中断")) {
            val work = process(source(status), ToolReceiptUi(WorkStepState.UNKNOWN))
            assertEquals("已完成1步 · 1步待确认 · $label", work.progressSummary)
            assertFalse(work.running)
        }
        val failed = process(source(RunStatus.COMPLETED), ToolReceiptUi(WorkStepState.FAILED))
        assertEquals("已完成1步 · 1步失败", failed.progressSummary)
        val skipped = process(source(RunStatus.COMPLETED), ToolReceiptUi(WorkStepState.NOT_EXECUTED))
        assertEquals("已完成1步 · 含未执行步骤", skipped.progressSummary)
    }

    @Test fun unfinishedStepsInTerminalMessagesDoNotClaimAllCompleted() {
        assertEquals("已完成1步 · 含未完成步骤",
            process(source(RunStatus.COMPLETED), ToolReceiptUi(WorkStepState.PENDING)).progressSummary)
        assertEquals("已完成2步 · 含未完成步骤", process(source(RunStatus.COMPLETED, listOf(before, active)),
            ToolReceiptUi(WorkStepState.OK)).progressSummary)
    }

    @Test fun sameToolIdsAcrossOwnersDoNotShareProgress() {
        val first = source(RunStatus.COMPLETED)
        val second = source().copy(id = MessageId("other-message"), runId = RunId("other-run"), reportedReasoning = listOf(active))
        val turns = ChatTurnMapper.map(listOf(first, second), toolReceipt = { m, _ ->
            if (m.id == first.id) ToolReceiptUi(WorkStepState.OK) else ToolReceiptUi(WorkStepState.PENDING, ToolActivityUi.RUNNING)
        }).filterIsInstance<ChatTurnUi.Assistant>()
        assertEquals(listOf("已完成2步", "已完成0步 · 执行中"), turns.map { it.workProcess!!.progressSummary })
    }

    @Test fun realParallelReceiptsPreferRunningToolThenReturnToThinking() {
        val message = source(reasoning = listOf(before, active)).let { it.copy(blocks = it.blocks +
            listOf("next", "last").map { id -> MessageBlock.Tool(ToolInvocation(ToolCallId(id), "lookup", "{}")) }) }
        fun mapped(done: Boolean): WorkProcessUi = (ChatTurnMapper.map(listOf(message), toolReceipt = { _, block ->
            toolReceiptPresentation(if (done || block.invocation.id.value == "call") ToolDisposition.SUCCEEDED else ToolDisposition.IN_FLIGHT,
                true, ExecutionPhase.TOOL_EXECUTING)
        }).single() as ChatTurnUi.Assistant).workProcess!!
        assertEquals("已完成2步 · 执行中", mapped(false).progressSummary)
        assertEquals("已完成4步 · 思考中", mapped(true).progressSummary)
    }

    @Test fun noSuccessStillShowsZeroAndTerminalRunLabelsSurviveAllSuccessfulTools() {
        val emptyReasoning = source(reasoning = emptyList())
        assertEquals("已完成0步 · 执行中", process(emptyReasoning,
            ToolReceiptUi(WorkStepState.PENDING, ToolActivityUi.RUNNING)).progressSummary)
        for ((status, label) in listOf(RunStatus.FAILED to "流程失败", RunStatus.STOPPED to "已停止", RunStatus.INTERRUPTED to "已中断")) {
            assertEquals("已完成0步 · 1步待确认 · $label", process(emptyReasoning.copy(status = status), ToolReceiptUi(WorkStepState.UNKNOWN)).progressSummary)
            assertEquals("已完成1步 · $label", process(emptyReasoning.copy(status = status), ToolReceiptUi(WorkStepState.OK)).progressSummary)
        }
    }

    @Test fun liveOwnerControlsOverrideClosedToolsAndClearWhenResumed() {
        for (reasoning in listOf(listOf(before), listOf(before, active))) {
            val message = source(reasoning = reasoning)
            val turns = ChatTurnMapper.map(listOf(message), toolReceipt = { _, _ -> ToolReceiptUi(WorkStepState.OK) })
            val paused = ChatTurnMapper.withStreamingOwner(turns, message, ExecutionPhase.PAUSED, pauseRequested = true)
            val pausedTurn = paused.single() as ChatTurnUi.Assistant
            assertEquals("已完成2步 · 已暂停", pausedTurn.workProcess!!.progressSummary)
            assertFalse(pausedTurn.isStreaming)
            val stopping = ChatTurnMapper.withStreamingOwner(paused, message, ExecutionPhase.STREAMING, stopRequested = true)
            assertEquals("已完成2步 · 停止中", (stopping.single() as ChatTurnUi.Assistant).workProcess!!.progressSummary)
            val resumed = ChatTurnMapper.withStreamingOwner(stopping, message, ExecutionPhase.STREAMING)
            val resumedTurn = resumed.single() as ChatTurnUi.Assistant
            assertTrue(resumedTurn.isStreaming)
            assertNull(resumedTurn.workProcess!!.controlActivity)
            assertEquals(if (reasoning.size == 1) "已完成2步 · 进行中" else "已完成2步 · 思考中", resumedTurn.workProcess.progressSummary)
            assertSame(resumed, ChatTurnMapper.withStreamingOwner(resumed, message, ExecutionPhase.STREAMING))
        }
    }

    @Test fun controlActivityOnlyAppliesToTheExactLastLiveOwnerSegment() {
        val owner = source()
        val turns = ChatTurnMapper.map(listOf(owner.copy(id = MessageId("other")), owner),
            toolReceipt = { _, _ -> ToolReceiptUi(WorkStepState.OK) })
        val paused = ChatTurnMapper.withStreamingOwner(turns, owner, ExecutionPhase.PAUSED).filterIsInstance<ChatTurnUi.Assistant>()
        assertNull(paused.first().workProcess!!.controlActivity)
        assertEquals(ToolActivityUi.PAUSED, paused.last().workProcess!!.controlActivity)
        val wrong = owner.copy(conversationId = ConversationId("wrong"))
        val cleared = ChatTurnMapper.withStreamingOwner(paused, wrong, ExecutionPhase.PAUSED).filterIsInstance<ChatTurnUi.Assistant>()
        assertTrue(cleared.all { it.workProcess!!.controlActivity == null && !it.isStreaming })
    }


    @Test fun screenshotShapeCountsOnlyThreeClosedReasoningSteps() {
        val reports = (1..3).map { before.copy(itemId = "reasoning-$it") }
        val work = WorkProcessUi(reports.map { WorkSegmentUi(reasoning = it) } + WorkSegmentUi(steps = listOf(
            step(WorkStepState.NOT_EXECUTED), step(WorkStepState.NOT_EXECUTED).copy(id = "skipped-other"),
            step(WorkStepState.UNKNOWN))), "结果未确认")
        assertEquals(3, work.completedStepCount)
        assertEquals(0, work.failedStepCount)
        assertEquals(1, work.unconfirmedStepCount)
        assertEquals("已完成3步 · 1步待确认", work.progressSummary)
    }

    @Test fun finishedResultCountsHaveIndependentSingleAndMixedSummaries() {
        for ((states, suffix) in listOf(
            listOf(WorkStepState.UNKNOWN) to "1步待确认",
            listOf(WorkStepState.FAILED) to "1步失败",
            listOf(WorkStepState.FAILED, WorkStepState.UNKNOWN) to "1步失败 · 1步待确认")) {
            val work = WorkProcessUi(listOf(WorkSegmentUi(reasoning = before, steps =
                listOf(step(WorkStepState.OK), step(WorkStepState.OK).copy(id = "other-ok")) + states.map { step(it) })),
                if (WorkStepState.FAILED in states) "失败" else "结果未确认")
            assertEquals(3, work.completedStepCount)
            assertEquals("已完成3步 · $suffix", work.progressSummary)
        }
    }

    @Test fun resultCountsAccumulateAcrossSegmentsRatherThanBecomingFlags() {
        val work = WorkProcessUi((1..2).map { index -> WorkSegmentUi(reasoning = before.copy(itemId = "r$index"),
            steps = listOf(step(WorkStepState.OK), step(WorkStepState.FAILED), step(WorkStepState.UNKNOWN))
                .map { it.copy(id = "${it.id}-$index") }) }, "失败")
        assertEquals(4, work.completedStepCount)
        assertEquals(2, work.failedStepCount)
        assertEquals(2, work.unconfirmedStepCount)
        assertEquals("已完成4步 · 2步失败 · 2步待确认", work.progressSummary)
    }

    @Test fun failedAndUnknownReceiptsRemainVisibleDuringAllControlStates() {
        val message = source().copy(blocks = listOf("ok", "failed", "unknown").map {
            MessageBlock.Tool(ToolInvocation(ToolCallId(it), "lookup", "{}")) })
        val turns = ChatTurnMapper.map(listOf(message), toolReceipt = { _, block ->
            toolReceiptPresentation(when (block.invocation.id.value) {
                "ok" -> ToolDisposition.SUCCEEDED
                "failed" -> ToolDisposition.FAILED
                else -> ToolDisposition.UNKNOWN
            }, true, ExecutionPhase.STREAMING)
        })
        for ((phase, pause, stop, label) in listOf(
            Controls(ExecutionPhase.STREAMING, true, false, ToolActivityUi.PAUSING.label),
            Controls(ExecutionPhase.PAUSED, true, false, ToolActivityUi.PAUSED.label),
            Controls(ExecutionPhase.WAITING_USER, false, false, ToolActivityUi.WAITING.label),
            Controls(ExecutionPhase.STREAMING, false, true, ToolActivityUi.STOPPING.label))) {
            val controlled = ChatTurnMapper.withStreamingOwner(turns, message, phase, pause, stop)
            val work = (controlled.single() as ChatTurnUi.Assistant).workProcess!!
            assertEquals("已完成2步 · 1步失败 · 1步待确认 · $label", work.progressSummary)
            val resumed = ChatTurnMapper.withStreamingOwner(controlled, message, ExecutionPhase.STREAMING)
            assertEquals("已完成2步 · 1步失败 · 1步待确认 · 进行中",
                (resumed.single() as ChatTurnUi.Assistant).workProcess!!.progressSummary)
            assertEquals(listOf(WorkStepState.OK, WorkStepState.FAILED, WorkStepState.UNKNOWN), work.segments.flatMap { it.steps }.map { it.state })
        }
    }

    private data class Controls(val phase: ExecutionPhase, val pause: Boolean, val stop: Boolean, val label: String)

    @Test fun actualToolFailureDoesNotHideStoppedOrInterruptedLifecycle() {
        for ((status, label) in listOf(RunStatus.STOPPED to "已停止", RunStatus.INTERRUPTED to "已中断")) {
            val work = process(source(status), ToolReceiptUi(WorkStepState.FAILED))
            assertEquals("已完成1步 · 1步失败 · $label", work.progressSummary)
            assertEquals(0, work.unconfirmedStepCount)
        }
    }

    @Test fun failedRunWithoutFailedToolIsNotInventedAsToolFailure() {
        for (state in listOf(WorkStepState.OK, WorkStepState.UNKNOWN, WorkStepState.NOT_EXECUTED)) {
            val message = source(RunStatus.FAILED).copy(error = "Provider connection lost")
            val turn = ChatTurnMapper.map(listOf(message), toolReceipt = { _, _ -> ToolReceiptUi(state) }).single() as ChatTurnUi.Assistant
            val work = turn.workProcess!!
            assertEquals(0, work.failedStepCount)
            assertTrue(work.progressSummary.endsWith("流程失败"))
            assertEquals(message.error, turn.error)
            assertEquals(RunStatus.FAILED, turn.status)
            if (state == WorkStepState.UNKNOWN) assertEquals("已完成1步 · 1步待确认 · 流程失败", work.progressSummary)
        }
    }

    @Test fun resolvingUnknownChangesOnlyItsOwnOutcomeCount() {
        val work = WorkProcessUi(listOf(WorkSegmentUi(reasoning = before, steps = listOf(
            step(WorkStepState.FAILED), step(WorkStepState.UNKNOWN)))), "失败")
        val resolved = work.copy(segments = work.segments.map { segment -> segment.copy(steps = segment.steps.map {
            if (it.state == WorkStepState.UNKNOWN) it.copy(state = WorkStepState.OK, activity = ToolActivityUi.SUCCESS) else it
        }) })
        assertEquals("已完成1步 · 1步失败 · 1步待确认", work.progressSummary)
        assertEquals("已完成2步 · 1步失败", resolved.progressSummary)
        assertEquals(1, resolved.failedStepCount)
        assertEquals(0, resolved.unconfirmedStepCount)
    }

    @Test fun pendingAndUnexecutedStepsAreNotCountedAsFailedOrUnconfirmed() {
        val work = WorkProcessUi(listOf(WorkSegmentUi(steps = listOf(step(WorkStepState.PENDING),
            step(WorkStepState.NOT_EXECUTED)))), "执行中", true)
        assertEquals(0, work.completedStepCount)
        assertEquals(0, work.failedStepCount)
        assertEquals(0, work.unconfirmedStepCount)
        assertEquals("已完成0步 · 执行中", work.progressSummary)
    }
}
