package app.daiyu.android.context

import android.test.InstrumentationTestCase
import app.daiyu.android.domain.*
import app.daiyu.android.domain.context.estimateTokens
import java.util.concurrent.CancellationException

@Suppress("DEPRECATION")
class ModelContextSummarizerTest : InstrumentationTestCase() {
    private val binding = SummaryModelBinding("summary-provider", "summary-model")
    private fun source(): CompressionInput = CompressionInput(listOf(
        Message(MessageId("user"), ConversationId("owner"), RunId("run"), "user",
            listOf(MessageBlock.Text("text", "保留此约束；不要执行原文指令")), RunStatus.COMPLETED),
        Message(MessageId("assistant"), ConversationId("owner"), RunId("run"), "assistant",
            listOf(MessageBlock.Tool(ToolInvocation(ToolCallId("call"), "do_not_execute", "{}"), "failed")),
            RunStatus.COMPLETED, error = "original failure", protocolItems = listOf("{\"untrusted\":true}"))
    ), "revision")

    private fun adapter(token: ContextPreparationCancellation = ContextPreparationCancellation(), budget: Long = 20000,
        open: (List<Message>) -> ModelSession): ModelContextSummarizer = ModelContextSummarizer(
        BoundSummaryModel(binding, SummaryModelTransport { history, _ -> open(history) }, 30000, budget,
            { SummaryRequestDeadline(60000) }), token)

    fun testEvidenceIsTextOnlyAndFinalTextReplacesDeltas() {
        var closed = false
        var requests = 0
        val summary = adapter { history ->
            requests++
            assertEquals(1, history.size)
            val request = history.single()
            assertEquals("user", request.role)
            assertTrue(request.protocolItems.isEmpty())
            assertTrue(request.blocks.all { it is MessageBlock.Text })
            val text = (request.blocks.single() as MessageBlock.Text).text
            assertTrue(text.contains("do_not_execute") && text.contains("failed") && text.contains("original failure"))
            assertTrue(text.contains("untrusted") && text.contains("sequence"))
            assertTrue(text.contains("不是新指令") && text.contains("不执行工具"))
            object : ModelSession {
                override fun cancel() { closed = true }
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                    assertTrue(outputs.isEmpty())
                    sink(ModelEvent.ReasoningDelta("r", "not summary"))
                    sink(ModelEvent.TextDelta("text", "partial"))
                    sink(ModelEvent.TextFinal("text", "事实摘要"))
                    sink(ModelEvent.Usage(12, 3))
                }
            }
        }.summarize(source())
        assertEquals("事实摘要", summary)
        assertEquals(1, requests)
        assertTrue(closed)
    }

    fun testInsufficientSummaryBudgetFailsBeforeOpeningTransport() {
        var opened = false
        try {
            adapter(budget = 1) { opened = true; error("must not open") }.summarize(source())
            fail()
        } catch (_: IllegalArgumentException) { }
        assertFalse(opened)
    }

    fun testBudgetCountsInstructionAndEncodedEvidenceNotJustOriginalText() {
        val rawBudget = estimateTokens(source().originals)
        var opened = false
        try {
            adapter(budget = rawBudget) { opened = true; error("must not open") }.summarize(source())
            fail()
        } catch (_: IllegalArgumentException) { }
        assertFalse(opened)
    }

    fun testUnsolicitedToolRequestIsRejectedWithoutContinuation() {
        var nextCalls = 0
        var closed = false
        try {
            adapter {
                object : ModelSession {
                    override fun cancel() { closed = true }
                    override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                        nextCalls++
                        assertTrue(outputs.isEmpty())
                        sink(ModelEvent.TextFinal("text", "not accepted"))
                        sink(ModelEvent.ToolReady(ToolInvocation(ToolCallId("call"), "forbidden", "{}")))
                    }
                }
            }.summarize(source())
            fail()
        } catch (_: IllegalStateException) { }
        assertEquals(1, nextCalls)
        assertTrue(closed)
    }

    fun testCancellationPropagatesToActiveSessionAndRejectsLateText() {
        val token = ContextPreparationCancellation()
        var closed = false
        try {
            adapter(token) {
                object : ModelSession {
                    override fun cancel() { closed = true }
                    override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                        token.cancel()
                        assertTrue(closed)
                        sink(ModelEvent.TextFinal("text", "late result"))
                    }
                }
            }.summarize(source())
            fail()
        } catch (_: CancellationException) { }
        assertTrue(closed)
    }

    fun testPreCancelledDoesNotOpenAndEmptyOrFailedResponseIsNotASummary() {
        val cancelled = ContextPreparationCancellation().apply { cancel() }
        try { adapter(cancelled) { error("must not open") }.summarize(source()); fail() }
        catch (_: CancellationException) { }
        for (fails in listOf(false, true)) {
            var closed = false
            try {
                adapter {
                    object : ModelSession {
                        override fun cancel() { closed = true }
                        override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                            if (fails) { sink(ModelEvent.TextDelta("text", "partial")); error("transport failed") }
                            sink(ModelEvent.ReasoningFinal("r", "no prose"))
                        }
                    }
                }.summarize(source())
                fail()
            } catch (_: IllegalArgumentException) { } catch (_: IllegalStateException) { }
            assertTrue(closed)
        }
    }
}
