package app.daiyu.android.domain.usage

import app.daiyu.android.domain.*
import app.daiyu.android.domain.delegation.*
import app.daiyu.android.domain.runtime.*
import app.daiyu.android.domain.tools.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class ChildUsagePipelineTest {
    private val parent = Message(MessageId("parent"), ConversationId("conversation"), RunId("parent-run"),
        Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING)
    private val owner = RunExecutionOwner.issue(parent)
    private val capabilities = SessionCapabilities("session", setOf("delegation.text.review"))
    private val authorization = TaskAuthorization("session", parent.runId.value)
    private fun context() = ToolExecutionContext(owner,
        ToolInvocation(ToolCallId("call"), "run_child_models", "{}"), authorization) { false }
    private fun execute(recorder: ChildUsageRecorder, count: Int = 1, body: ((ModelEvent) -> Unit) -> Unit): ExecutionReceipt {
        val definition = ChildModelDefinition("pool", 0) { ModelPort {
            object : ModelSession {
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) = body(sink)
                override fun cancel() {}
            }
        } }
        val batch = ChildModelBatch(mapOf("review" to definition), usageRecorder = recorder)
        batch.bind(owner, capabilities, authorization)
        return try { batch.execute(context(), List(count) { ChildModelTask("review", "question $it") }) }
        finally { batch.close() }
    }
    @Test fun latestSnapshotsUseActualChildRequestsAndSameValuesFromSiblingsStayDistinct() {
        val values = ConcurrentHashMap<String, List<AttributedRequestUsage>>()
        val result = execute(ChildUsageRecorder { task, cp ->
            if (cp.usage.isNotEmpty()) values[task.owner.childRunId] = childRequestUsage(task, cp)
        }, 2) { sink ->
            sink(ModelEvent.Usage(10, 20, 5)); sink(ModelEvent.Usage(null, 0, 0)); sink(ModelEvent.TextFinal("t", "answer"))
        }
        assertTrue(result is ExecutionReceipt.Confirmed); assertEquals(2, values.size)
        val rows = values.values.flatten(); assertEquals(2, rows.map { it.request.requestId }.distinct().size)
        assertTrue(rows.all { it.parentRunId == parent.runId.value && it.parentMessageId == parent.id.value })
        val totals = aggregateAttributedUsage(rows)
        assertEquals(2L, totals.child.requestSnapshotCount); assertNull(totals.child.input.reportedTokens)
        assertEquals(0L, totals.child.output.reportedTokens); assertEquals(0L, totals.main.requestSnapshotCount)
    }
    @Test fun usageAfterFinalTextBeforeSessionReturnsIsStillRetained() {
        val values = ConcurrentHashMap<String, List<AttributedRequestUsage>>()
        val result = execute(ChildUsageRecorder { task, cp ->
            if (cp.usage.isNotEmpty()) values[task.owner.childRunId] = childRequestUsage(task, cp)
        }) { sink ->
            sink(ModelEvent.TextFinal("t", "answer"))
            sink(ModelEvent.Usage(11, 12, 3))
        }
        assertTrue(result is ExecutionReceipt.Confirmed)
        assertEquals(ModelEvent.Usage(11, 12, 3), values.values.single().single().snapshot)
    }
    @Test fun failedChildRetainsReportedUsageWithoutBeingCalledSuccessful() {
        val values = ConcurrentHashMap<String, List<AttributedRequestUsage>>()
        val opens = AtomicInteger()
        val result = execute(ChildUsageRecorder { task, cp ->
            if (cp.usage.isNotEmpty()) values[task.owner.childRunId] = childRequestUsage(task, cp)
        }) { sink -> opens.incrementAndGet(); sink(ModelEvent.Usage(7, null)); throw ModelRequestException(ModelFailure.NETWORK) }
        assertTrue(result is ExecutionReceipt.Unknown); assertEquals(1, opens.get())
        assertEquals(7L, values.values.single().single().snapshot.inputTokens)
    }
    @Test fun persistenceFailureCannotPublishSuccessfulResultOrReopenRequest() {
        val calls = AtomicInteger()
        val result = execute(ChildUsageRecorder { _, cp -> if (cp.usage.isNotEmpty()) error("disk failure") }) { sink ->
            calls.incrementAndGet(); sink(ModelEvent.Usage(4, 5)); sink(ModelEvent.TextFinal("t", "must not publish"))
        }
        assertTrue(result is ExecutionReceipt.Unknown); assertEquals(1, calls.get())
    }
    @Test fun noProviderUsageDoesNotCreateZeroUsage() {
        val values = ConcurrentHashMap<String, List<AttributedRequestUsage>>()
        assertTrue(execute(ChildUsageRecorder { task, cp -> values[task.owner.childRunId] = childRequestUsage(task, cp) }) {
            it(ModelEvent.TextFinal("t", "answer"))
        } is ExecutionReceipt.Confirmed)
        assertTrue(values.values.flatten().isEmpty())
    }
}
