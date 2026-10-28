package app.daiyu.android.domain

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class ReportedReasoningTimingTest {
    private fun initial() = Message(MessageId("a"), ConversationId("c"), RunId("r"),
        Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING)

    @Test fun observedDeltasAccumulateButFinishedAndWaitingIntervalsDoNot() {
        var now = 100_000_000L
        val history = RunnerHistorySegments(initial()) { now }
        fun item() = history.project(initial()).historySegments.flatMap { it.reportedReasoning }.single()
        history.acceptReasoning("r", "first", false)
        now += 1_200_000_000L
        assertEquals(1_200L, item().elapsedAt(now))
        history.acceptReasoning("r", " more", false)
        assertEquals(100_000_000L, item().activeSinceNanos)
        history.finishReasoning()
        now += 9_000_000_000L
        assertEquals(1_200L, item().elapsedAt(now))
        history.acceptReasoning("r", " resumed", false)
        now += 300_000_000L
        history.acceptReasoning("r", "final", true)
        assertEquals(1_500L, item().elapsedAt(now))
        assertNull(item().activeSinceNanos)
    }

    @Test fun switchingItemsDoesNotDoubleCountAndFinalOnlyDoesNotInventTime() {
        var now = 0L
        val history = RunnerHistorySegments(initial()) { now }
        history.acceptReasoning("one", "first", false)
        now = 1_000_000_000L
        history.acceptReasoning("two", "second", false)
        now = 1_500_000_000L
        history.finishReasoning()
        val items = history.project(initial()).historySegments.flatMap { it.reportedReasoning }
        assertEquals(listOf(1_000L, 500L), items.map { it.elapsedMillis })
        history.acceptReasoning("old", "final only", true)
        assertNull(history.project(initial()).historySegments.flatMap { it.reportedReasoning }.last().elapsedMillis)
    }

    @Test fun forkDoesNotMutateAcceptedAnchorsAndFrozenSnapshotIsStable() {
        var now = -2_000_000_000L
        val history = RunnerHistorySegments(initial()) { now }
        history.acceptReasoning("r", "accepted", false)
        val candidate = history.fork()
        now += 500_000_000L
        candidate.acceptReasoning("r", "candidate", true)
        val accepted = history.project(initial())
        val item = accepted.historySegments.single().reportedReasoning.single()
        assertEquals(-2_000_000_000L, item.activeSinceNanos)
        assertEquals("accepted", item.text)
        val expected = accepted.frozenReasoning(now)
        now += 10_000_000_000L
        assertEquals(expected, expected.frozenReasoning(now))
        assertEquals(500L, expected.historySegments.single().reportedReasoning.single().elapsedMillis)
        assertNotNull(item.activeSinceNanos)
    }

    @Test fun runnerFreezesBeforeBodyAndToolAndFailure() {
        val seen = mutableListOf<Message>()
        val model = ModelPort { object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                sink(ModelEvent.ReasoningDelta("r", "observed"))
                sink(ModelEvent.TextDelta("body", "answer"))
                throw ModelRequestException(ModelFailure.INCOMPLETE)
            }
            override fun cancel() {}
        } }
        val result = ChatRunner(model, object : ConversationStore {
            override fun save(message: Message) {
                assertTrue(message.historySegments.flatMap { it.reportedReasoning }.none { it.activeSinceNanos != null })
            }
        }, BatteryTool { error("unused") }) { seen += it }.run(initial(), emptyList())
        assertNotNull(seen.first { it.historySegments.isNotEmpty() }.historySegments.single().reportedReasoning.single().activeSinceNanos)
        val duringBody = seen.first { it.blocks.isNotEmpty() }.historySegments.single().reportedReasoning.single()
        assertNull(duringBody.activeSinceNanos)
        val frozen = result.historySegments.single().reportedReasoning.single()
        assertNotNull(frozen.elapsedMillis)
        assertNull(frozen.activeSinceNanos)
        assertEquals(duringBody.elapsedMillis, frozen.elapsedMillis)
    }
    @Test fun stopCutoffExcludesSlowTransportCancellation() {
        lateinit var runner: ChatRunner
        var anchor = 0L
        val firstCancellation = AtomicLong(Long.MIN_VALUE)
        val cancellationStarted = CountDownLatch(1)
        val cancellationFinished = CountDownLatch(1)
        val model = ModelPort { object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                sink(ModelEvent.ReasoningDelta("r", "partial"))
                check(cancellationFinished.await(5, TimeUnit.SECONDS)) { "slow transport did not exit" }
            }
            override fun cancel() {
                if (firstCancellation.compareAndSet(Long.MIN_VALUE, System.nanoTime())) {
                    cancellationStarted.countDown()
                    try { Thread.sleep(120) } finally { cancellationFinished.countDown() }
                }
            }
        } }
        runner = ChatRunner(model, object : ConversationStore { override fun save(message: Message) {} },
            BatteryTool { error("unused") }) { message ->
            val item = message.historySegments.flatMap { it.reportedReasoning }.firstOrNull()
            if (item?.activeSinceNanos != null) {
                anchor = checkNotNull(item.activeSinceNanos)
                runner.stop()
            }
        }
        val result = runner.run(initial(), emptyList())
        assertTrue("transport cancellation started", cancellationStarted.await(5, TimeUnit.SECONDS))
        assertTrue("slow cancellation completed", cancellationFinished.await(5, TimeUnit.SECONDS))
        assertEquals(RunStatus.STOPPED, result.status)
        val item = result.historySegments.flatMap { it.reportedReasoning }.single()
        assertNull(item.activeSinceNanos)
        assertTrue(checkNotNull(item.elapsedMillis) <= (firstCancellation.get() - anchor) / 1_000_000)
    }


    @Test fun cumulativeSnapshotsKeepTheAnchorAndFinalFreezesTheWholeItem() {
        var now = 0L
        val history = RunnerHistorySegments(initial()) { now }
        fun item() = history.project(initial()).historySegments.single().reportedReasoning.single()
        history.acceptReasoning("r", "second", final = false, replace = true)
        now = 100_000_000L
        history.acceptReasoning("r", "firstsecond", final = false, replace = true)
        assertEquals("firstsecond", item().text)
        assertEquals(0L, item().activeSinceNanos)
        assertEquals(100L, item().elapsedAt(now))
        now = 200_000_000L
        history.acceptReasoning("r", "corrected", final = false, replace = true)
        assertEquals("corrected", item().text)
        assertEquals(0L, item().activeSinceNanos)
        now = 300_000_000L
        history.acceptReasoning("r", "corrected", final = true)
        now = 9_000_000_000L
        assertNull(item().activeSinceNanos)
        assertEquals(300L, item().elapsedAt(now))
    }
}
