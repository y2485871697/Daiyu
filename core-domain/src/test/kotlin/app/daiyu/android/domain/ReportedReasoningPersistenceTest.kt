package app.daiyu.android.domain

import app.daiyu.android.domain.runtime.RunnerOptions
import org.junit.Assert.*
import org.junit.Test

class ReportedReasoningPersistenceTest {
    private fun initial() = Message(MessageId("a"), ConversationId("c"), RunId("r"), "assistant", emptyList(), RunStatus.RUNNING)

    @Test fun acceptedSnapshotsArePersistedBeforeObserverAndNeverBecomeBodyOrProtocol() {
        val writes = mutableListOf<Message>()
        val store = object : ConversationStore { override fun save(message: Message) { writes += message } }
        val reported = listOf(ModelEvent.ReasoningDelta("r", "draft"), ModelEvent.ReasoningFinal("r", "reported final"))
        var observed = 0
        val model = ModelPort { object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                reported.forEach(sink)
                sink(ModelEvent.TextFinal("body", "answer"))
            }
            override fun cancel() {}
        } }
        val result = ChatRunner(model, store, BatteryTool { error("unused") }, RunnerOptions(onReasoning = {
            val expected = if (observed++ == 0) "draft" else "reported final"
            assertEquals(expected, writes.last().historySegments.flatMap { it.reportedReasoning }.single().text)
        })) {}.run(initial(), emptyList())
        assertEquals(2, observed)
        assertEquals(listOf(MessageBlock.Text("body", "answer")), result.blocks)
        assertTrue(result.protocolItems.isEmpty())
        assertEquals(listOf(ReportedReasoning("r", "reported final")), MessageHistory.expand(listOf(result)).single().reportedReasoning)
    }

    @Test fun acceptedPartialReasoningSurvivesFailureAndLateTerminalEventsCannotOverwriteIt() {
        lateinit var oldSink: (ModelEvent) -> Unit
        var requests = 0
        val writes = mutableListOf<Message>()
        val model = ModelPort { object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                requests++; oldSink = sink
                sink(ModelEvent.ReasoningDelta("r", "actually reported partial"))
                throw ModelRequestException(ModelFailure.INCOMPLETE)
            }
            override fun cancel() {}
        } }
        val result = ChatRunner(model, object : ConversationStore { override fun save(message: Message) { writes += message } },
            BatteryTool { error("no tools") }) {}.run(initial(), emptyList())
        assertEquals(RunStatus.FAILED, result.status)
        assertEquals(1, requests)
        assertTrue(result.protocolItems.isEmpty()); assertTrue(result.blocks.isEmpty())
        val saved = writes.toList()
        oldSink(ModelEvent.ReasoningFinal("r", "not accepted"))
        assertEquals(saved, writes)
        assertEquals("actually reported partial", MessageHistory.expand(listOf(result)).single().reportedReasoning.single().text)
    }

    @Test fun historyCodecProjectionDoesNotRepeatReasoningAcrossRawFreeTailOrSessions() {
        val history = RunnerHistorySegments(initial())
        history.acceptReasoning("r", "first session", final = true)
        var message = initial().copy(blocks = listOf(MessageBlock.Text("before", "before")))
        history.replaceSessionProtocol(message, listOf("native"))
        message = message.copy(blocks = message.blocks + MessageBlock.Text("after", "after"))
        val projected = history.project(message)
        assertEquals(2, projected.historySegments.size)
        assertEquals(listOf("first session"), projected.historySegments.flatMap { it.reportedReasoning }.map { it.text })
        message = history.sealSession(message, emptyList())
        history.acceptReasoning("r", "second session", final = true)
        val final = history.project(message)
        assertEquals(listOf("first session", "second session"), MessageHistory.expand(listOf(final)).flatMap { it.reportedReasoning }.map { it.text })
        assertEquals(listOf("before", "after"), final.blocks.filterIsInstance<MessageBlock.Text>().map { it.text })
    }
}
