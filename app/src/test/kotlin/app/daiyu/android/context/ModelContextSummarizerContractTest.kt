package app.daiyu.android.context

import app.daiyu.android.domain.*
import org.junit.Assert.*
import org.junit.Test

class ModelContextSummarizerContractTest {
    private val binding = SummaryModelBinding("provider", "summary")
    private fun checkpoint() = SummaryCheckpoint.marker + "\n" +
        SummaryCheckpoint.sections.joinToString("\n") { "## $it\n- preserved fact" }
    private fun input(text: String) = CompressionInput(listOf(Message(MessageId("m"), ConversationId("c"),
        RunId("r"), "user", listOf(MessageBlock.Text("body", text)), RunStatus.COMPLETED)), "revision")
    private class Fixture(val replies: (Int) -> String) {
        val prompts = mutableListOf<String>()
        var bindings = 0
        fun model(): BoundSummaryModel {
            bindings++
            return BoundSummaryModel(SummaryModelBinding("provider", "summary"), SummaryModelTransport { history, deadline ->
                deadline.check()
                val text = (history.single().blocks.single() as MessageBlock.Text).text
                assertTrue(TokenTailCompression.textTokens(text) + 16 <= 2500)
                prompts += text
                object : ModelSession {
                    override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                        assertTrue(outputs.isEmpty())
                        sink(ModelEvent.TextFinal("answer", replies(prompts.size)))
                    }
                    override fun cancel() {}
                }
            }, 4000, 2500, { SummaryRequestDeadline(120000) }, requireCheckpointFormat = true)
        }
    }
    @Test fun validCheckpointUsesOneRequestAndNoTools() {
        val f = Fixture { checkpoint() }
        assertEquals(checkpoint(), ModelContextSummarizer(f.model(), ContextPreparationCancellation(), f::model).summarize(input("facts")))
        assertEquals(1, f.prompts.size); assertEquals(1, f.bindings)
    }
    @Test fun invalidCheckpointGetsExactlyOneIndependentlyBoundRepair() {
        val f = Fixture { if (it == 1) "incomplete candidate" else checkpoint() }
        assertEquals(checkpoint(), ModelContextSummarizer(f.model(), ContextPreparationCancellation(), f::model).summarize(input("facts")))
        assertEquals(2, f.bindings); assertEquals(2, f.prompts.size)
        assertTrue(f.prompts.last().contains("只修复"))
    }
    @Test fun oversizedUnicodeEvidenceActuallyChunksAndMergesWithinEveryFullPromptBudget() {
        val f = Fixture { checkpoint() }
        val result = ModelContextSummarizer(f.model(), ContextPreparationCancellation(), f::model)
            .summarize(input("start:" + "facts 😀 中 ".repeat(1800) + ":end"))
        assertEquals(checkpoint(), result)
        assertTrue(f.prompts.size > 2); assertEquals(f.prompts.size, f.bindings)
        assertTrue(f.prompts.first().contains("start:"))
        assertTrue(f.prompts.any { it.contains(":end") })
        assertTrue(f.prompts.last().contains("中间摘要"))
        assertTrue(f.prompts.all { !it.contains('\uFFFD') })
    }
    @Test fun invalidRepairAndFailedSecondChunkDoNotReturnPartialSummary() {
        val f = Fixture { "still invalid" }
        try {
            ModelContextSummarizer(f.model(), ContextPreparationCancellation(), f::model).summarize(input("facts"))
            fail("invalid checkpoint accepted")
        } catch (_: IllegalArgumentException) {}
        assertEquals(2, f.prompts.size)
        val broken = Fixture { if (it == 2) error("second chunk failed") else checkpoint() }
        try {
            ModelContextSummarizer(broken.model(), ContextPreparationCancellation(), broken::model)
                .summarize(input("x".repeat(30000)))
            fail("partial summary accepted")
        } catch (failure: IllegalStateException) { assertEquals("second chunk failed", failure.message) }
        assertEquals(2, broken.prompts.size)
    }
    @Test fun tooManySourceChunksFailsBeforeAnyPostAndCancellationNeverOpens() {
        val f = Fixture { checkpoint() }
        try {
            ModelContextSummarizer(f.model(), ContextPreparationCancellation(), f::model).summarize(input("中".repeat(100000)))
            fail("too many chunks accepted")
        } catch (_: IllegalArgumentException) {}
        assertTrue(f.prompts.isEmpty())
        val cancellation = ContextPreparationCancellation().also { it.cancel() }
        try { ModelContextSummarizer(f.model(), cancellation, f::model).summarize(input("facts")); fail("cancelled") }
        catch (_: java.util.concurrent.CancellationException) {}
        assertTrue(f.prompts.isEmpty())
    }
}
