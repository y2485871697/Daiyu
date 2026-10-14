package app.daiyu.android.domain.context

import app.daiyu.android.domain.*
import org.junit.Assert.*
import org.junit.Test

class ContextPolicyTest {
    private val owner = ConversationId("c")
    private fun turn(n: Int): List<Message> {
        val run = RunId("r$n")
        return listOf(Message(MessageId("u$n"), owner, run, "user", listOf(MessageBlock.Text("t", "question $n")), RunStatus.COMPLETED),
            Message(MessageId("a$n"), owner, run, "assistant", listOf(MessageBlock.Text("t", "answer $n")), RunStatus.COMPLETED))
    }
    @Test fun revisionsIncludeContentStatusToolStateAndProtocol() {
        val original = turn(1); val revision = ContextRevision.digest(original)
        assertNotEquals(revision, ContextRevision.digest(original.map { it.copy(blocks = listOf(MessageBlock.Text("t", "correction"))) }))
        assertNotEquals(revision, ContextRevision.digest(original.map { it.copy(status = RunStatus.FAILED) }))
        assertNotEquals(revision, ContextRevision.digest(original.map { it.copy(protocolItems = listOf("{}")) }))
        assertNotEquals(revision, ContextRevision.digest(original.map { it.copy(error = "") }))
        assertEquals(revision, ContextRevision.digest(original.toList()))
    }
    @Test fun completeToolBatchRemainsAtomicAndTailProtected() {
        val all = (1..6).flatMap(::turn).toMutableList()
        all[3] = all[3].copy(blocks = listOf(MessageBlock.Tool(ToolInvocation(ToolCallId("call"), "read", "{}"), "done")))
        assertEquals(8, compressiblePrefix(all, 2))
        all[3] = all[3].copy(blocks = listOf(MessageBlock.Tool(ToolInvocation(ToolCallId("call"), "read", "{}"))))
        assertEquals(0, compressiblePrefix(all, 2))
    }
    @Test fun liveFailedAndMismatchedTurnsCannotBeCompressed() {
        val all = (1..6).flatMap(::turn).toMutableList()
        all[3] = all[3].copy(status = RunStatus.RUNNING)
        assertEquals(0, compressiblePrefix(all, 2))
        all[3] = all[3].copy(status = RunStatus.COMPLETED, runId = RunId("wrong"))
        assertEquals(0, compressiblePrefix(all, 2))
        assertEquals(0, compressiblePrefix(turn(1), 1))
    }
    @Test fun automaticThresholdRequiresKnownWindowAndEnabledPolicy() {
        assertFalse(CompressionPolicy(enabled = true).shouldCompress(Long.MAX_VALUE))
        val p = CompressionPolicy(true, 10000, 80, 1000, 2)
        assertFalse(p.shouldCompress(7199)); assertTrue(p.shouldCompress(7200))
        assertFalse(p.copy(enabled = false).shouldCompress(10000))
    }
    @Test fun usageReceiptsAreDeduplicatedWithoutInventingUnknowns() {
        val a = UsageRecord("q1", "r1", "b", 100, 20, 30, 4)
        val b = UsageRecord("q2", "r2", "b", null, null)
        val totals = aggregateUsage(listOf(a, a, b))
        assertEquals(2L, totals.requests); assertEquals(1L, totals.reportedRequests)
        assertEquals(120L, totals.totalTokens); assertTrue(totals.incomplete)
        assertEquals(30L, totals.cachedTokens); assertEquals(4L, totals.reasoningTokens)
    }
    @Test(expected = IllegalArgumentException::class) fun conflictingUsageIsRejected() {
        val a = UsageRecord("q", "r", "b", 10, 20)
        aggregateUsage(listOf(a, a.copy(outputTokens = 21)))
    }
    @Test(expected = IllegalArgumentException::class) fun impossibleCachedUsageIsRejected() {
        UsageRecord("q", "r", "b", 10, 20, 11)
    }
}
