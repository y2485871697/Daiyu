package app.daiyu.android.domain

import org.junit.Assert.*
import org.junit.Test

class RunnerReasoningOrderTest {
    private fun initial() = Message(MessageId("a"), ConversationId("c"), RunId("r"),
        Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING)
    private fun tool(id: String, result: String? = "done") = MessageBlock.Tool(ToolInvocation(ToolCallId(id), "lookup", "{}"), result)

    @Test fun firstAcceptedBoundarySurvivesDeltasFinalAndFork() {
        val h = RunnerHistorySegments(initial()) { 0L }
        h.acceptReasoning("r1", "first", false, 0)
        val message = initial().copy(blocks = listOf(tool("t1")))
        val fork = h.fork()
        fork.acceptReasoning("r1", " replacement", true, 1)
        fork.acceptReasoning("r2", "second", true, 1)
        assertEquals(listOf(0, 1), fork.project(message).historySegments.single().reportedReasoning.map { it.blockOffset })
        assertEquals(listOf("first"), h.project(message).historySegments.single().reportedReasoning.map { it.text })
    }

    @Test fun snapshotBoundaryBelongsToTheAcceptedSnapshotOrTheLaterTailExactlyOnce() {
        val h = RunnerHistorySegments(initial())
        h.acceptReasoning("prefix", "before snapshot", true, 0)
        h.replaceSessionProtocol(initial(), listOf("raw"))
        assertEquals(1, h.project(initial()).historySegments.size)
        h.acceptReasoning("tail", "after snapshot", true, 0)
        val segments = h.project(initial()).historySegments
        assertEquals(listOf(listOf("prefix"), listOf("tail")), segments.map { it.reportedReasoning.map { r -> r.itemId } })
        assertEquals(listOf(0, 0), segments.flatMap { it.reportedReasoning }.map { it.blockOffset })
    }

    @Test fun splitProjectionRebasesTailAndCompletedToolFilterRebasesAgain() {
        val h = RunnerHistorySegments(initial())
        h.acceptReasoning("r1", "first", true, 0)
        val prefix = initial().copy(blocks = listOf(tool("t1")))
        h.replaceSessionProtocol(prefix, listOf("raw"))
        h.acceptReasoning("r2", "next", true, 1)
        val partial = prefix.copy(blocks = prefix.blocks + tool("not-executed", null))
        h.acceptReasoning("r3", "after pending", true, 2)
        val projected = h.project(partial)
        assertEquals(listOf(listOf(0), listOf(0, 1)), projected.historySegments.map { it.reportedReasoning.map { r -> r.blockOffset } })
        val user = initial().copy(id = MessageId("u"), role = Message.USER_ROLE, status = RunStatus.COMPLETED)
        val sealed = h.sealSession(partial, listOf(user))
        MessageHistory.validateOwner(sealed)
        assertEquals(listOf(0, 0, 0), sealed.historySegments.flatMap { it.reportedReasoning }.map { it.blockOffset })
        assertEquals(listOf("r1", "r2", "r3"), sealed.historySegments.flatMap { it.reportedReasoning }.map { it.itemId })
        h.acceptReasoning("new", "next session", true, partial.blocks.size)
        assertEquals(0, h.project(partial).historySegments.last().reportedReasoning.single().blockOffset)
    }

    @Test fun segmentRejectsOutOfBoundsPositionsButAcceptsLegacyAndEndBoundary() {
        val segment = MessageHistorySegment(MessageId("a"), "assistant", listOf(tool("t")),
            reportedReasoning = listOf(ReportedReasoning("r", "summary", blockOffset = 1)))
        MessageHistory.validateSegment(segment)
        MessageHistory.validateSegment(segment.copy(reportedReasoning = listOf(ReportedReasoning("r", "legacy"))))
        try { MessageHistory.validateSegment(segment.copy(reportedReasoning = listOf(ReportedReasoning("r", "bad", blockOffset = 2)))); fail() }
        catch (_: IllegalArgumentException) { }
        try { ReportedReasoning("r", "bad", blockOffset = -1); fail() } catch (_: IllegalArgumentException) { }
    }

    @Test fun productionRunnerRecordsTwoRealToolRoundsInAcceptedOrder() {
        var round = 0
        val writes = mutableListOf<Message>()
        val runner = ChatRunner(ModelPort { object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                round++
                if (round <= 2) {
                    sink(ModelEvent.ReasoningDelta("r$round", "summary $round"))
                    sink(ModelEvent.ReasoningFinal("r$round", "final summary $round"))
                    sink(ModelEvent.ToolReady(tool("t$round").invocation))
                } else sink(ModelEvent.TextFinal("answer", "done"))
            }
            override fun cancel() {}
        } }, object : ConversationStore { override fun save(message: Message) { writes += message } }, BatteryTool { "done" }, {})
        val result = runner.run(initial(), emptyList())
        assertEquals(RunStatus.COMPLETED, result.status)
        assertEquals(listOf(0, 1), result.historySegments.flatMap { it.reportedReasoning }.map { it.blockOffset })
        assertEquals(listOf("t1", "t2"), result.blocks.filterIsInstance<MessageBlock.Tool>().map { it.invocation.id.value })
        assertEquals(result, writes.last())
    }
}
