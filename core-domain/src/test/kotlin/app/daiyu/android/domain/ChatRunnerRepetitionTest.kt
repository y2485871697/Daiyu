package app.daiyu.android.domain

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ChatRunnerRepetitionTest {
    private val initial = Message(MessageId("a"), ConversationId("c"), RunId("r"), "assistant", emptyList(), RunStatus.RUNNING)
    private val call = ToolInvocation(ToolCallId("call"), "get_battery_status", "0,".repeat(5000))
    private val saved = mutableListOf<Message>()
    private val cancelled = CountDownLatch(1)
    private var executions = 0
    private var rounds = 0
    private fun awaitCancelled(): Boolean = cancelled.await(2, TimeUnit.SECONDS)
    private fun run(cancelThrows: Boolean = false, events: (Int, (ModelEvent) -> Unit) -> Unit): Message {
        val model = ModelPort { object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) { events(++rounds, sink) }
            override fun cancel() { cancelled.countDown(); if (cancelThrows) throw IllegalStateException("cancel fixture") }
        } }
        return ChatRunner(model, object : ConversationStore {
            override fun save(message: Message) { saved.add(message) }
        }, BatteryTool { executions++; "result" }, {}).run(initial, emptyList())
    }
    @Test fun repeatedDeltasAcrossIdsFailOnceAndKeepAcceptedPrefix() {
        val result = run { _, sink ->
            repeat(20) { sink(ModelEvent.TextDelta("id-$it", "OK.\n".repeat(128))) }
        }
        assertEquals(RunStatus.FAILED, result.status)
        assertTrue(result.error!!.contains("高度重复"))
        assertTrue(result.blocks.isNotEmpty())
        assertTrue(awaitCancelled())
        assertEquals(1, rounds)
        assertEquals(1, saved.count { it.status != RunStatus.RUNNING })
    }
    @Test fun hugeRejectedDeltaIsNotPersistedAndPendingToolDoesNotExecute() {
        val result = run { _, sink ->
            sink(ModelEvent.ToolReady(call))
            sink(ModelEvent.TextDelta("t", "accepted"))
            sink(ModelEvent.TextDelta("t", "Write. ".repeat(150000)))
        }
        assertEquals(RunStatus.FAILED, result.status)
        assertEquals("accepted", (result.blocks.last() as MessageBlock.Text).text)
        assertEquals(0, executions)
        assertTrue(awaitCancelled())
    }
    @Test fun identicalFinalSnapshotDoesNotCountDeltasTwice() {
        val text = "OK.\n".repeat(800)
        val result = run { _, sink ->
            sink(ModelEvent.TextDelta("t", text))
            repeat(5) { sink(ModelEvent.TextFinal("t", text)) }
        }
        assertEquals(RunStatus.COMPLETED, result.status)
        assertEquals(text, (result.blocks.single() as MessageBlock.Text).text)
    }
    @Test fun correctedFinalDoesNotAccumulateReplacementAsNewText() {
        val result = run { _, sink ->
            sink(ModelEvent.TextDelta("t", "Draft.\n".repeat(500)))
            repeat(3) { sink(ModelEvent.TextFinal("t", "Final.\n".repeat(500))) }
        }
        assertEquals(RunStatus.COMPLETED, result.status)
    }
    @Test fun cumulativeSnapshotsTriggerWithoutCountingPriorSnapshotsAgain() {
        val result = run { _, sink ->
            repeat(20) { sink(ModelEvent.TextFinal("t", "OK.\n".repeat((it + 1) * 128))) }
        }
        assertEquals(RunStatus.FAILED, result.status)
        assertTrue((result.blocks.single() as MessageBlock.Text).text.length < 4096)
    }
    @Test fun replacementByRepetitiveFinalIsRejected() {
        val result = run { _, sink ->
            sink(ModelEvent.TextDelta("t", "accepted draft"))
            sink(ModelEvent.TextFinal("t", "OK.\n".repeat(2000)))
        }
        assertEquals(RunStatus.FAILED, result.status)
        assertEquals("accepted draft", (result.blocks.single() as MessageBlock.Text).text)
    }
    @Test fun finalOnlyRepetitionFails() {
        assertEquals(RunStatus.FAILED, run { _, sink ->
            sink(ModelEvent.TextFinal("t", "OK.\n".repeat(2000)))
        }.status)
    }
    @Test fun realToolProgressResetsWindowAndArgumentsAreExcluded() {
        val result = run { round, sink ->
            if (round == 1) {
                sink(ModelEvent.TextDelta("before", "OK.\n".repeat(800)))
                sink(ModelEvent.ToolReady(call))
            } else sink(ModelEvent.TextDelta("answer", "OK.\n".repeat(800)))
        }
        assertEquals(RunStatus.COMPLETED, result.status)
        assertEquals(1, executions)
    }
    @Test fun pendingToolDoesNotEraseRepetitionHistory() {
        val result = run { _, sink ->
            sink(ModelEvent.TextDelta("before", "OK.\n".repeat(800)))
            sink(ModelEvent.ToolReady(call))
            sink(ModelEvent.TextDelta("after", "OK.\n".repeat(800)))
        }
        assertEquals(RunStatus.FAILED, result.status)
        assertEquals(0, executions)
    }
    @Test fun completedToolResultsSurviveLaterRepetition() {
        val result = run { round, sink ->
            if (round == 1) sink(ModelEvent.ToolReady(call))
            else sink(ModelEvent.TextDelta("answer", "OK.\n".repeat(2000)))
        }
        assertEquals(RunStatus.FAILED, result.status)
        assertEquals("result", (result.blocks.single() as MessageBlock.Tool).result)
        assertEquals(1, executions)
        assertEquals(2, rounds)
    }
    @Test fun swallowedSinkFailureStillCannotCompleteOrExecuteTools() {
        val result = run { _, sink ->
            sink(ModelEvent.ToolReady(call))
            try { sink(ModelEvent.TextDelta("t", "OK.\n".repeat(2000))) } catch (_: RuntimeException) { }
        }
        assertEquals(RunStatus.FAILED, result.status)
        assertEquals(0, executions)
    }
    @Test fun reasoningRepetitionIsIndependentAndNeverPublishedAsBody() {
        val result = run { _, sink ->
            sink(ModelEvent.TextDelta("body", "accepted"))
            repeat(20) { sink(ModelEvent.ReasoningDelta("reason-$it", "OK.\n".repeat(128))) }
        }
        assertEquals(RunStatus.FAILED, result.status)
        assertTrue(result.error!!.contains("思考摘要"))
        assertEquals("accepted", (result.blocks.single() as MessageBlock.Text).text)
        assertEquals(1, rounds)
        assertTrue(awaitCancelled())
        assertEquals(1, saved.count { it.status != RunStatus.RUNNING })
    }
    @Test fun reasoningFinalSnapshotsAreIdempotentAndCorrectionsReplace() {
        val result = run { _, sink ->
            sink(ModelEvent.ReasoningDelta("r", "Draft.\n".repeat(500)))
            repeat(5) { sink(ModelEvent.ReasoningFinal("r", "Final.\n".repeat(500))) }
            sink(ModelEvent.TextFinal("body", "answer"))
        }
        assertEquals(RunStatus.COMPLETED, result.status)
        assertEquals("answer", (result.blocks.single() as MessageBlock.Text).text)
    }
    @Test fun finalOnlyAndGrowingReasoningSnapshotsFail() {
        assertEquals(RunStatus.FAILED, run { _, sink ->
            sink(ModelEvent.ReasoningFinal("r", "OK.\n".repeat(2000)))
        }.status)
        saved.clear()
        assertEquals(RunStatus.FAILED, run { _, sink ->
            repeat(20) { sink(ModelEvent.ReasoningFinal("r", "OK.\n".repeat((it + 1) * 128))) }
        }.status)
    }
    @Test fun visibleBodyProgressResetsReasoningWithoutDoubleCountingOldSnapshot() {
        val text = "OK.\n".repeat(800)
        val result = run { _, sink ->
            sink(ModelEvent.ReasoningFinal("r", text))
            sink(ModelEvent.TextFinal("body", "answer"))
            repeat(4) { sink(ModelEvent.ReasoningFinal("r", text)) }
            sink(ModelEvent.ReasoningFinal("r", text + text))
        }
        assertEquals(RunStatus.COMPLETED, result.status)
    }
    @Test fun duplicateOrWhitespaceBodyDoesNotResetReasoning() {
        for (body in listOf("answer", "answer ")) {
            val result = run { _, sink ->
                sink(ModelEvent.TextFinal("body", "answer"))
                sink(ModelEvent.ReasoningDelta("r", "OK.\n".repeat(800)))
                sink(ModelEvent.TextFinal("body", body))
                sink(ModelEvent.ReasoningDelta("r", "OK.\n".repeat(800)))
            }
            assertEquals(RunStatus.FAILED, result.status)
        }
    }
    @Test fun reasoningCorrectionAfterBodyProgressDoesNotRecountOtherItems() {
        val result = run { _, sink ->
            sink(ModelEvent.ReasoningFinal("old", "OK.\n".repeat(800)))
            sink(ModelEvent.TextFinal("body", "answer"))
            sink(ModelEvent.ReasoningFinal("new", "Draft.\n".repeat(500)))
            sink(ModelEvent.ReasoningFinal("new", "Final.\n".repeat(500)))
        }
        assertEquals(RunStatus.COMPLETED, result.status)
    }
    @Test fun reasoningNeverResetsBodyWindowAndPendingToolIsNotProgress() {
        val result = run { _, sink ->
            sink(ModelEvent.TextDelta("body", "OK.\n".repeat(800)))
            sink(ModelEvent.ReasoningFinal("r", "changing summary"))
            sink(ModelEvent.ToolReady(call))
            sink(ModelEvent.TextDelta("body", "OK.\n".repeat(800)))
        }
        assertEquals(RunStatus.FAILED, result.status)
        assertTrue(result.error!!.contains("正文"))
        assertEquals(0, executions)
    }
    @Test fun reasoningFailureAfterToolPreservesResultsAndRejectsPendingTool() {
        val result = run { round, sink ->
            if (round == 1) sink(ModelEvent.ToolReady(call))
            else {
                sink(ModelEvent.ToolReady(call.copy(id = ToolCallId("pending"))))
                sink(ModelEvent.ReasoningFinal("r", "OK.\n".repeat(2000)))
            }
        }
        assertEquals(RunStatus.FAILED, result.status)
        assertEquals(1, executions)
        assertEquals("result", (result.blocks.first() as MessageBlock.Tool).result)
        assertNull((result.blocks.last() as MessageBlock.Tool).result)
    }
    @Test fun swallowedReasoningFailureStillRejectsLaterEventsAndCompletion() {
        val result = run { _, sink ->
            sink(ModelEvent.ToolReady(call))
            try { sink(ModelEvent.ReasoningFinal("r", "OK.\n".repeat(2000))) } catch (_: RuntimeException) { }
            try { sink(ModelEvent.TextFinal("body", "must not save")) } catch (_: RuntimeException) { }
        }
        assertEquals(RunStatus.FAILED, result.status)
        assertEquals(0, executions)
        assertTrue(result.blocks.none { it is MessageBlock.Text })
    }
    @Test fun cancellationFailureDoesNotReplaceCompletionOrRepetitionFailure() {
        val complete = run(cancelThrows = true) { _, sink -> sink(ModelEvent.TextFinal("body", "answer")) }
        assertEquals(RunStatus.COMPLETED, complete.status)
        val failed = run(cancelThrows = true) { _, sink -> sink(ModelEvent.ReasoningFinal("r", "OK.\n".repeat(2000))) }
        assertEquals(RunStatus.FAILED, failed.status)
        assertTrue(failed.error!!.contains("思考摘要"))
    }
    @Test fun stopWithThrowingCancellationRejectsLateReasoningAndHasOneTerminal() {
        lateinit var runner: ChatRunner
        val model = ModelPort { object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                sink(ModelEvent.TextFinal("body", "accepted"))
                runner.stop()
                sink(ModelEvent.ReasoningFinal("r", "OK.\n".repeat(2000)))
            }
            override fun cancel() { throw IllegalStateException("cancel fixture") }
        } }
        runner = ChatRunner(model, object : ConversationStore {
            override fun save(message: Message) { saved.add(message) }
        }, BatteryTool { executions++; "result" }, {})
        val result = runner.run(initial, emptyList())
        assertEquals(RunStatus.STOPPED, result.status)
        assertNull(result.error)
        assertEquals("accepted", (result.blocks.single() as MessageBlock.Text).text)
        assertEquals(1, saved.count { it.status != RunStatus.RUNNING })
    }
    @Test fun trimmingBodyWhitespaceDoesNotCountAsReasoningProgress() {
        val result = run { _, sink ->
            sink(ModelEvent.TextFinal("body", "answer "))
            sink(ModelEvent.ReasoningDelta("r", "OK.\n".repeat(800)))
            sink(ModelEvent.TextFinal("body", "answer"))
            sink(ModelEvent.ReasoningDelta("r", "OK.\n".repeat(800)))
        }
        assertEquals(RunStatus.FAILED, result.status)
    }
    @Test fun reasoningBaselineTruncationAndRegrowthDoNotRecountOldText() {
        val text = "OK.\n".repeat(800)
        val result = run { _, sink ->
            sink(ModelEvent.ReasoningFinal("r", text))
            sink(ModelEvent.TextFinal("body", "answer"))
            sink(ModelEvent.ReasoningFinal("new", "Final.\n".repeat(500)))
            sink(ModelEvent.ReasoningFinal("r", text.take(2800)))
            sink(ModelEvent.ReasoningFinal("r", text))
            sink(ModelEvent.ReasoningDelta("r", "OK.\n".repeat(100)))
        }
        assertEquals(RunStatus.COMPLETED, result.status)
    }
}
