package app.daiyu.android.domain.context

import app.daiyu.android.domain.*
import org.junit.Assert.*
import org.junit.Test

class ContextPolicyBoundaryTest {
    private val owner = ConversationId("boundary")

    private fun turn(n: Int, run: RunId = RunId("r$n")): List<Message> = listOf(
        Message(MessageId("u$n"), owner, run, "user",
            listOf(MessageBlock.Text("question", "question $n")), RunStatus.COMPLETED),
        Message(MessageId("a$n"), owner, run, "assistant",
            listOf(MessageBlock.Text("answer", "answer $n")), RunStatus.COMPLETED)
    )

    private fun tool(id: String, result: String? = "done") =
        MessageBlock.Tool(ToolInvocation(ToolCallId(id), "read", "{}"), result)

    @Test fun toolNamesUseTheSameUtf8EstimateAsArgumentsAndResults() {
        fun message(name: String, arguments: String, result: String) = turn(1)[1].copy(
            blocks = listOf(MessageBlock.Tool(ToolInvocation(ToolCallId("utf8"), name, arguments), result))
        )
        val ascii = estimateTokens(listOf(message("abc", "def", "ghi")))
        assertEquals(3L, estimateTokens(listOf(message("工具", "def", "ghi"))) - ascii)
        assertEquals(3L, estimateTokens(listOf(message("abc", "参数", "ghi"))) - ascii)
        assertEquals(3L, estimateTokens(listOf(message("abc", "def", "结果"))) - ascii)
    }

    @Test fun completeToolBatchAndProtocolRemainInsideOneTurn() {
        val messages = (1..6).flatMap { turn(it) }.toMutableList()
        messages[3] = messages[3].copy(
            blocks = listOf(tool("one"), MessageBlock.Text("middle", "text"), tool("two", "")),
            protocolItems = listOf("call one", "result one", "call two", "result two")
        )
        assertEquals(8, compressiblePrefix(messages, 2))
        messages[3] = messages[3].copy(blocks = listOf(tool("one"), tool("two", null)))
        assertEquals(0, compressiblePrefix(messages, 2))
    }

    @Test fun pendingToolInEitherMessagePreventsCompression() {
        for (index in listOf(2, 3)) {
            val messages = (1..5).flatMap { turn(it) }.toMutableList()
            messages[index] = messages[index].copy(blocks = listOf(tool("pending", null)))
            assertEquals("pending tool at $index", 0, compressiblePrefix(messages, 1))
        }
    }

    @Test fun sameRunAcrossTailBoundaryRollsBackToPreviousCompleteRun() {
        val shared = RunId("shared")
        val messages = (1..6).flatMap { turn(it, if (it in 4..5) shared else RunId("r$it")) }
        // The old pair-only boundary was 8, splitting shared between turns 4 and 5.
        assertEquals(6, compressiblePrefix(messages, 2))
        assertEquals(10, compressiblePrefix(messages, 1))
    }

    @Test fun aSingleRunCannotBePartlyCompressedEvenWithManyCompletePairs() {
        val messages = (1..6).flatMap { turn(it, RunId("shared")) }
        assertEquals(0, compressiblePrefix(messages, 1))
        assertEquals(0, compressiblePrefix(messages, 2))
    }

    @Test fun terminalOrLiveContinuationCannotLeavePartOfItsRunInPrefix() {
        val shared = RunId("shared")
        for (status in listOf(RunStatus.RUNNING, RunStatus.FAILED, RunStatus.STOPPED, RunStatus.INTERRUPTED)) {
            val messages = (1..6).flatMap { turn(it, if (it in 2..5) shared else RunId("r$it")) }.toMutableList()
            messages[9] = messages[9].copy(status = status)
            // Four completed pairs precede the barrier, but turns 2..5 are one run.
            assertEquals(status.name, 2, compressiblePrefix(messages, 1))
        }
    }

    @Test fun pendingToolContinuationCannotLeavePartOfItsRunInPrefix() {
        val shared = RunId("shared")
        val messages = (1..6).flatMap { turn(it, if (it in 2..5) shared else RunId("r$it")) }.toMutableList()
        messages[9] = messages[9].copy(blocks = listOf(tool("pending", null)))
        assertEquals(2, compressiblePrefix(messages, 1))
    }

    @Test fun nonAdjacentReusedRunCannotCrossTheCut() {
        val shared = RunId("shared")
        val messages = (1..5).flatMap { turn(it, if (it == 1 || it == 3) shared else RunId("r$it")) }
        assertEquals(0, compressiblePrefix(messages, 3))
        assertEquals(6, compressiblePrefix(messages, 2))
    }

    @Test fun incompleteTrailingUserCannotLeaveItsRunInPrefix() {
        val shared = RunId("shared")
        val messages = (1..4).flatMap { turn(it, if (it in 2..4) shared else RunId("r$it")) } +
            turn(5, shared).first()
        assertEquals(2, compressiblePrefix(messages, 1))
    }

    @Test fun barriersAreNotSkippedToReachLaterCompletedTurns() {
        for (status in listOf(RunStatus.RUNNING, RunStatus.FAILED, RunStatus.STOPPED, RunStatus.INTERRUPTED)) {
            for (index in listOf(4, 5)) {
                val messages = (1..6).flatMap { turn(it) }.toMutableList()
                messages[index] = messages[index].copy(status = status)
                assertEquals("$status at $index", 2, compressiblePrefix(messages, 1))
            }
        }
    }

    @Test fun malformedRoleOrRunPairStopsThePrefix() {
        val messages = (1..6).flatMap { turn(it) }.toMutableList()
        messages[5] = messages[5].copy(runId = RunId("mismatch"))
        assertEquals(2, compressiblePrefix(messages, 1))
        messages[5] = turn(3)[1].copy(role = "tool")
        assertEquals(2, compressiblePrefix(messages, 1))
    }

    @Test fun keepTurnsProtectsCompleteTailAndAnyUnpairedTail() {
        assertEquals(0, compressiblePrefix(emptyList(), 1))
        assertEquals(0, compressiblePrefix(turn(1).take(1), 1))
        assertEquals(0, compressiblePrefix(turn(1) + turn(2), 2))
        val messages = (1..5).flatMap { turn(it) }
        assertEquals(6, compressiblePrefix(messages, 2))
        assertEquals(6, compressiblePrefix(messages + turn(6).first(), 2))
        assertEquals(0, compressiblePrefix(messages, Int.MAX_VALUE))
    }

    @Test(expected = IllegalArgumentException::class)
    fun zeroRetainedTurnsIsRejected() {
        compressiblePrefix(turn(1), 0)
    }

    @Test fun unknownWindowNeverTriggersAutomaticCompression() {
        val unknown = CompressionPolicy(enabled = true, contextTokens = null, reserveTokens = Long.MAX_VALUE)
        assertFalse(unknown.shouldCompress(Long.MAX_VALUE))
        val known = CompressionPolicy(true, 10000, 80, 1000, 2)
        assertFalse(known.shouldCompress(7199))
        assertTrue(known.shouldCompress(7200))
        assertFalse(known.copy(enabled = false).shouldCompress(Long.MAX_VALUE))
    }

    @Test fun estimatesDoNotBecomeProviderUsageWhenReceiptsAreUnknownOrPartial() {
        val messages = turn(1).map { it.copy(blocks = listOf(MessageBlock.Text("text", "large".repeat(1000)))) }
        assertTrue(estimateTokens(messages) > 10000)
        val unknown = aggregateUsage(listOf(UsageRecord("unknown", "r1", "branch", null, null)))
        assertEquals(0L, unknown.totalTokens)
        assertEquals(0L, unknown.reportedRequests)
        assertTrue(unknown.incomplete)
        val partial = aggregateUsage(listOf(UsageRecord("partial", "r1", "branch", 12, null)))
        assertEquals(12L, partial.totalTokens)
        assertEquals(0L, partial.reportedRequests)
        assertTrue(partial.incomplete)
    }

    @Test fun cachedAndReasoningAreSubsetsNotExtraProviderUsage() {
        val receipt = UsageRecord("reported", "r1", "branch", 100, 20, 30, 4)
        val usage = aggregateUsage(listOf(receipt, receipt))
        assertEquals(1L, usage.requests)
        assertEquals(1L, usage.reportedRequests)
        assertEquals(120L, usage.totalTokens)
        assertEquals(30L, usage.cachedTokens)
        assertEquals(4L, usage.reasoningTokens)
        assertFalse(usage.incomplete)
    }
}
