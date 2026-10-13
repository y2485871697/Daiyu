package app.daiyu.android.domain

import org.junit.Assert.*
import org.junit.Test

class ChatRunnerRepetitionTest {
    private val initial = Message(MessageId("a"), ConversationId("c"), RunId("r"), "assistant", emptyList(), RunStatus.RUNNING)
    private val call = ToolInvocation(ToolCallId("call"), "get_battery_status", "0,".repeat(5000))
    private val saved = mutableListOf<Message>()
    private var cancelled = false
    private var executions = 0
    private var rounds = 0
    private fun run(events: (Int, (ModelEvent) -> Unit) -> Unit): Message {
        val model = ModelPort { object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) { events(++rounds, sink) }
            override fun cancel() { cancelled = true }
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
        assertTrue(cancelled)
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
        assertTrue(cancelled)
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
}
