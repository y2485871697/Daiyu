package app.daiyu.android.domain

import org.junit.Assert.*
import org.junit.Test

/** Source-message times, not session/token times, are authoritative for every segment. */
class RunnerHistorySegmentsTimeTest {
    private fun assistant(time: Long?) = Message(MessageId("a"), ConversationId("c"), RunId("r"),
        Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING, createdAt = time)
    private fun user(id: String, time: Long?) = Message(MessageId(id), ConversationId("c"), RunId("r"),
        Message.USER_ROLE, listOf(MessageBlock.Text(id, id)), RunStatus.COMPLETED, createdAt = time)

    @Test fun protocolPartialTailAndSupplementEachCopyTheirSourceTime() {
        val initial = assistant(100L)
        val history = RunnerHistorySegments(initial)
        val first = initial.copy(blocks = listOf(MessageBlock.Text("first", "first")))
        history.replaceSessionProtocol(first, listOf("raw"))
        val partial = first.copy(blocks = first.blocks + MessageBlock.Text("tail", "partial"))
        assertEquals(listOf(100L, 100L), history.project(partial).historySegments.map { it.createdAt })
        val supplements = listOf(user("epoch", 0L), user("known", 200L), user("legacy", null))
        val sealed = history.sealSession(partial, supplements)
        assertEquals(listOf(100L, 100L, 0L, 200L, null), sealed.historySegments.map { it.createdAt })
        val next = partial.copy(blocks = partial.blocks + MessageBlock.Text("next", "next"))
        val projected = history.project(next)
        assertEquals(100L, projected.createdAt)
        assertEquals(listOf(100L, 100L, 0L, 200L, null, 100L), projected.historySegments.map { it.createdAt })
        assertEquals(projected.historySegments.map { it.createdAt },
            MessageHistory.expand(listOf(projected) + supplements).map { it.createdAt })
    }

    @Test fun restoredUnknownOwnerAndSupplementRemainUnknown() {
        val initial = assistant(null)
        val history = RunnerHistorySegments(initial)
        val partial = initial.copy(blocks = listOf(MessageBlock.Text("first", "first")))
        val sealed = history.sealSession(partial, listOf(user("legacy", null)))
        val restored = RunnerHistorySegments(sealed)
        val next = sealed.copy(blocks = sealed.blocks + MessageBlock.Text("next", "next"))
        val projected = restored.project(next)
        assertNull(projected.createdAt)
        assertEquals(listOf(null, null, null), projected.historySegments.map { it.createdAt })
        assertTrue(MessageHistory.expand(listOf(projected)).all { it.createdAt == null })
    }

    @Test fun runnerStreamingReopenAndCompletionNeverRestampOwnerOrSupplement() {
        val initial = assistant(0L)
        val supplement = user("supplement", 9876L)
        lateinit var runner: ChatRunner
        var opens = 0
        val saved = mutableListOf<Message>()
        runner = ChatRunner(ModelPort { history ->
            opens++
            if (opens == 2) {
                assertEquals(listOf(0L, 9876L), MessageHistory.expand(history).map { it.createdAt })
            }
            object : ModelSession {
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                    sink(ModelEvent.TextDelta("text-$opens", "partial"))
                    sink(ModelEvent.TextFinal("text-$opens", "complete"))
                    if (opens == 1) assertTrue(runner.supplement(supplement))
                }
                override fun cancel() {}
            }
        }, object : ConversationStore {
            override fun save(message: Message) { saved.add(message) }
        }, BatteryTool { "unused" }, {})
        val result = runner.run(initial, emptyList())
        assertEquals(RunStatus.COMPLETED, result.status)
        assertEquals(2, opens)
        assertTrue(saved.isNotEmpty())
        assertTrue(saved.all { it.createdAt == 0L })
        assertEquals(listOf(0L, 9876L, 0L), result.historySegments.map { it.createdAt })
        assertEquals(listOf(0L, 9876L, 0L), MessageHistory.expand(listOf(result)).map { it.createdAt })
    }
}
