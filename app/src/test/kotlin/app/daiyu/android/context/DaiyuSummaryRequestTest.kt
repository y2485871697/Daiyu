package app.daiyu.android.context

import app.daiyu.android.domain.*
import app.daiyu.android.domain.context.DaiyuContextEstimate
import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.model.SummaryReasoningLevel
import org.junit.Assert.*
import org.junit.Test

class DaiyuSummaryRequestTest {
    private val row = Message(MessageId("u"), ConversationId("c"), RunId("r"), Message.USER_ROLE,
        listOf(MessageBlock.Text("source", "evidence")), RunStatus.COMPLETED)
    private val truncated = ModelRequestException(ModelFailure.INCOMPLETE,
        ModelRequestDiagnostic(confirmedOutputLimit = true))

    @Test fun confirmedCapRetriesOnceWithFreshAdmissionAndNeverKeepsPartialText() {
        for (stopAgain in listOf(false, true)) {
            val limits = mutableListOf<Int>()
            val deadlines = mutableListOf<SummaryRequestDeadline>()
            val order = mutableListOf<String>()
            var admissions = 0
            val source = listOf(row)
            fun admit(): BoundSummaryModel {
                val attempt = ++admissions
                val transport = object : SummaryModelTransport, DaiyuSummaryOutputPolicy {
                    override val initialOutputLimit = 8_000
                    override fun retryLimit(current: Int, inputTokens: Long) =
                        DaiyuContextEstimate.summaryRetryLimit(current, 32_000, inputTokens)
                    override fun openWithoutTools(history: List<Message>, deadline: SummaryRequestDeadline): ModelSession = error("expected budgeted open")
                    override fun openSummary(history: List<Message>, tools: List<ToolDefinition>, systemPrompt: String?,
                        deadline: SummaryRequestDeadline, reasoning: SummaryReasoningLevel,
                        cancellation: ContextPreparationCancellation, outputLimit: Int, inputTokens: Long): ModelSession {
                        assertSame(source, history)
                        assertTrue(tools.isEmpty())
                        limits += outputLimit; deadlines += deadline; order += "open-$attempt"
                        return object : ModelSession {
                            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                                if (attempt == 1 || stopAgain) {
                                    sink(ModelEvent.TextDelta("same", "partial")); throw truncated
                                }
                                sink(ModelEvent.TextDelta("same", "final"))
                            }
                            override fun cancel() { order += "close-$attempt" }
                        }
                    }
                }
                return BoundSummaryModel(SummaryModelBinding("p", "m"), transport, 32_000, 22_000,
                    { SummaryRequestDeadline(10_000) })
            }
            val model = admit()
            val request = DaiyuSummaryRequest(model, ContextPreparationCancellation(), model.startDeadline(), ::admit)
            if (stopAgain) assertSame(truncated, assertThrows(ModelRequestException::class.java) { request.complete(source, 2_000) })
            else assertEquals("final", request.complete(source, 2_000))
            assertEquals(listOf(8_000, 16_000), limits)
            assertEquals(listOf("open-1", "close-1", "open-2", "close-2"), order)
            assertSame(deadlines[0], deadlines[1])
            assertEquals(2, admissions)
        }
    }

    @Test fun retryBudgetUsesRealWindowAndRejectsTinyGrowth() {
        assertEquals(8_000, DaiyuContextEstimate.summaryOutputLimit(32_000))
        assertEquals(8_192, DaiyuContextEstimate.summaryOutputLimit(32_768))
        assertEquals(32_768, DaiyuContextEstimate.summaryRetryLimit(16_384, 200_000, 130_000))
        assertEquals(12_400, DaiyuContextEstimate.summaryRetryLimit(8_000, 32_000, 18_000))
        assertNull(DaiyuContextEstimate.summaryRetryLimit(8_000, 32_000, 18_500))
        assertNull(DaiyuContextEstimate.summaryRetryLimit(8_000, 32_000, 1_000, providerRoom = 12_000))
    }

    @Test fun opaqueReplayCutKeepsNewestBatchAndOnlyResponsesCountsReduction() {
        fun assistant(name: String) = row.copy(id = MessageId(name), role = "assistant", blocks = emptyList(),
            protocolItems = listOf("""{"type":"reasoning","encrypted_content":"not-a-token-price"}"""))
        val old = assistant("old")
        val newest = assistant("new")
        val history = listOf(row, old, newest)
        assertEquals(2, DaiyuCompaction.cut(history, 32_000, protocol = ProviderProtocol.RESPONSES))
        val largerSummary = row.copy(blocks = listOf(MessageBlock.Text("body", "summary ".repeat(60))))
        assertTrue(DaiyuCompaction.reduced(history, listOf(largerSummary, newest), 1, 32_000, ProviderProtocol.RESPONSES))
        assertFalse(DaiyuCompaction.reduced(history, listOf(largerSummary, newest), 1, 32_000, ProviderProtocol.MESSAGES))
        assertFalse(DaiyuCompaction.reduced(history, listOf(largerSummary, old), 1, 32_000, ProviderProtocol.RESPONSES))
    }

    @Test fun toolPruningPreservesUnicodeAndDoesNotMutateOriginal() {
        val raw = "😀".repeat(9_000)
        val source = row.copy(role = "assistant", blocks = listOf(MessageBlock.Tool(
            ToolInvocation(ToolCallId("call"), "test", "{}"), raw)))
        val archived = mutableListOf<Message>()
        val prepared = DaiyuSummaryEvidence.prepare(listOf(source), ContextPreparationCancellation()) { original, _ ->
            archived += original
            "example"
        }
        val replacement = (prepared.messages.single().blocks.single() as MessageBlock.Tool).result.orEmpty()
        assertEquals(listOf(source), archived)
        assertTrue(replacement.startsWith("😀".repeat(4096) + "\n"))
        assertTrue(replacement.endsWith("\n" + "😀".repeat(1024)))
        assertTrue(replacement.contains("context-checkpoint:example；read_compacted_history"))
        assertEquals(raw, (source.blocks.single() as MessageBlock.Tool).result)
    }
}
