package app.daiyu.android.context

import app.daiyu.android.domain.*
import app.daiyu.android.model.SummaryReasoningLevel
import org.junit.Assert.*
import org.junit.Test

class HarnessContextSummarizerTest {
    private val input = CompressionInput(listOf(Message(MessageId("u"), ConversationId("c"), RunId("r"),
        "user", listOf(MessageBlock.Text("source", "evidence")), RunStatus.COMPLETED)), "revision")

    @Test fun checkpointTargetScalesWithPrefixAndOmitsFixedSectionsForShortInputs() {
        assertEquals(255L, compactionCheckpointTarget(511))
        val short = HarnessCompactionPrompt.forCheckpointTarget(compactionCheckpointTarget(511))
        assertTrue(short.contains("under about 255 tokens"))
        assertTrue(short.contains("no headings"))
        assertFalse(short.contains("## Primary Request"))
        assertEquals(256L, compactionCheckpointTarget(512))
        assertEquals(1500L, compactionCheckpointTarget(3000))
        assertEquals(4000L, compactionCheckpointTarget(100000))
        assertEquals(HarnessCompactionPrompt.instruction, HarnessCompactionPrompt.forCheckpointTarget(null))
        val directive = HarnessCompactionPrompt.forCheckpointTarget(compactionCheckpointTarget(3000))
        assertTrue(directive.startsWith(HarnessCompactionPrompt.instruction))
        assertTrue(directive.contains("under about 1500 tokens"))
    }

    @Test fun onlyAnExplicitReasoningRejectionNegotiatesOnceWithTheSameReplayAndDeadline() {
        val levels = mutableListOf<SummaryReasoningLevel>()
        val histories = mutableListOf<List<Message>>()
        val deadlines = mutableListOf<SummaryRequestDeadline>()
        val order = mutableListOf<String>()
        var bounds = 0
        fun model(): BoundSummaryModel {
            val index = ++bounds
            return BoundSummaryModel(SummaryModelBinding("p", "m"), object : SummaryModelTransport {
                override fun openWithoutTools(history: List<Message>, deadline: SummaryRequestDeadline): ModelSession = error("no projection")
                override fun openReplay(history: List<Message>, tools: List<ToolDefinition>, systemPrompt: String,
                    deadline: SummaryRequestDeadline, reasoning: SummaryReasoningLevel,
                    cancellation: ContextPreparationCancellation): ModelSession {
                    levels += reasoning; histories += history; deadlines += deadline; order += "open-$index"
                    return object : ModelSession {
                        override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                            if (index == 1) throw ModelRequestException(ModelFailure.REQUEST,
                                ModelRequestDiagnostic(httpStatus = 400, reasoningLevelRejected = true))
                            sink(ModelEvent.TextFinal("summary", "kept"))
                        }
                        override fun cancel() { order += "cancel-$index" }
                    }
                }
            }, 4000, 3000, { SummaryRequestDeadline(10000) },
                reasoningLevels = listOf(SummaryReasoningLevel.OFF, SummaryReasoningLevel.LOW))
        }
        assertEquals("kept", HarnessContextSummarizer(model(), ContextPreparationCancellation(), nextModel = ::model).summarize(input))
        assertEquals(listOf(SummaryReasoningLevel.OFF, SummaryReasoningLevel.LOW), levels)
        assertEquals(histories[0], histories[1]); assertSame(deadlines[0], deadlines[1])
        assertTrue(order.indexOf("cancel-1") < order.indexOf("open-2")); assertEquals(2, bounds)
    }

    @Test fun unknownFailuresNeverRebindAndRepeatedReasoningRejectionStopsAfterOneNegotiation() {
        for (explicit in listOf(false, true)) {
            var opens = 0; var bounds = 0
            val failure = ModelRequestException(ModelFailure.REQUEST,
                ModelRequestDiagnostic(httpStatus = 400, reasoningLevelRejected = explicit))
            fun model(): BoundSummaryModel {
                bounds++
                return BoundSummaryModel(SummaryModelBinding("p", "m"), object : SummaryModelTransport {
                    override fun openWithoutTools(history: List<Message>, deadline: SummaryRequestDeadline): ModelSession = error("no projection")
                    override fun openReplay(history: List<Message>, tools: List<ToolDefinition>, systemPrompt: String,
                        deadline: SummaryRequestDeadline, reasoning: SummaryReasoningLevel,
                        cancellation: ContextPreparationCancellation): ModelSession {
                        opens++
                        return object : ModelSession {
                            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) { throw failure }
                            override fun cancel() {}
                        }
                    }
                }, 4000, 3000, { SummaryRequestDeadline(10000) },
                    reasoningLevels = listOf(SummaryReasoningLevel.OFF, SummaryReasoningLevel.LOW, SummaryReasoningLevel.HIGH))
            }
            val caught = assertThrows(ModelRequestException::class.java) {
                HarnessContextSummarizer(model(), ContextPreparationCancellation(), nextModel = ::model).summarize(input)
            }
            assertSame(failure, caught); assertEquals(if (explicit) 2 else 1, opens); assertEquals(opens, bounds)
        }
    }
}
