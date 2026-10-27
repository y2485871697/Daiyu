package app.daiyu.android.context

import app.daiyu.android.domain.*
import app.daiyu.android.domain.context.CompressionPolicy
import app.daiyu.android.domain.runtime.*
import app.daiyu.android.timeline.*
import org.junit.Assert.*
import org.junit.Test

class ProductionCompactionPlacementTest {
    @Test fun failureKeepsTheOriginalBoundaryAndPublishesOneTerminalActivity() {
        val parent = Message(MessageId("assistant"), ConversationId("c"), RunId("r"),
            Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING)
        val events = mutableListOf<CompressionActivity>()
        val runtime = ProductionRunContext(CompressionPolicy(true, 4096, reserveTokens = 64, tokenTail = true),
            parent, null, automatic = false, progress = { events += it })
        runtime.beginCompaction(parent)
        runtime.failed("连接网络失败")
        runtime.finishedCompaction()
        runtime.finishedCompaction()
        val failed = events.single()
        assertFalse(failed.running)
        assertEquals("连接网络失败", failed.failure)
        assertEquals(TimelineAnchor("c", "r", "assistant", 0), failed.anchor)
    }

    @Test fun startedCompressionFailureRetainsItsActivityIdAndDoesNotEmitAnotherTerminal() {
        val parent = Message(MessageId("assistant"), ConversationId("c"), RunId("r"),
            Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING)
        val history = (0..7).map { index -> parent.copy(id = MessageId("u$index"), role = Message.USER_ROLE,
            status = RunStatus.COMPLETED, blocks = listOf(MessageBlock.Text("body", "original evidence ".repeat(120)))) }
        val events = mutableListOf<CompressionActivity>()
        val failure = ModelRequestException(ModelFailure.NETWORK)
        val runtime = ProductionRunContext(CompressionPolicy(true, 4096, reserveTokens = 64, tokenTail = true), parent, {
            BoundSummaryModel(SummaryModelBinding("provider", "summary"), SummaryModelTransport { _, _ -> object : ModelSession {
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) { throw failure }
                override fun cancel() {}
            } }, 20000, 15000, { SummaryRequestDeadline(10000) })
        }, automatic = false, progress = { events += it })
        runtime.beginCompaction(parent)
        val request = RequestCheckpoint("summary", "attempt", "owner", RequestOperation.NEXT)
        assertSame(failure, assertThrows(ModelRequestException::class.java) {
            runtime.compact(history, null, runtime.localTokens(history, emptyList()), request, {}, {}, { false })
        })
        val started = events.single()
        runtime.failed(compressionFailureMessage(failure))
        runtime.finishedCompaction()
        assertEquals(listOf(started, started.copy(running = false, failure = failure.userMessage)), events)
    }

    @Test fun candidateKeepsFrozenOriginalSegmentAndActivityUntilRunnerAcceptsIt() {
        val parent = Message(MessageId("assistant"), ConversationId("c"), RunId("r"),
            Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING)
        val boundary = parent.copy(historySegments = listOf(
            MessageHistorySegment(parent.id, Message.ASSISTANT_ROLE, listOf(MessageBlock.Text("first", "first request"))),
            MessageHistorySegment(MessageId("supplement"), Message.USER_ROLE, listOf(MessageBlock.Text("user", "supplement"))),
            MessageHistorySegment(parent.id, Message.ASSISTANT_ROLE, listOf(MessageBlock.Text("second", "last request")))))
        val history = (0..7).map { index -> parent.copy(id = MessageId("u$index"), role = Message.USER_ROLE,
            status = RunStatus.COMPLETED, blocks = listOf(MessageBlock.Text("body", "original evidence ".repeat(120)))) }
        val events = mutableListOf<CompressionActivity>()
        val reply = SummaryCheckpoint.marker + "\n" + SummaryCheckpoint.sections.joinToString("\n") { "## $it\n- kept" }
        val runtime = ProductionRunContext(CompressionPolicy(true, 4096, reserveTokens = 64, tokenTail = true), parent, {
            BoundSummaryModel(SummaryModelBinding("provider", "summary"), SummaryModelTransport { _, _ -> object : ModelSession {
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                    sink(ModelEvent.TextFinal("summary", reply))
                }
                override fun cancel() {}
            } }, 20000, 15000, { SummaryRequestDeadline(10000) }, requireCheckpointFormat = true)
        }, automatic = false, progress = { events += it })
        runtime.beginCompaction(boundary)
        val request = RequestCheckpoint("summary", "attempt", "owner", RequestOperation.NEXT)
        val result = checkNotNull(runtime.compact(history, null, runtime.localTokens(history, emptyList()), request, {}, {}, { false }))
        assertEquals(1, events.size)
        val started = events.single()
        assertTrue(started.running)
        assertEquals(TimelineAnchor("c", "r", "assistant", 1), started.anchor)
        assertEquals("summary-${started.id}", result.first().id.value)
        runtime.finishedCompaction()
        assertEquals(listOf(started, started.copy(running = false)), events)
        runtime.finishedCompaction()
        assertEquals(2, events.size)
    }
}
