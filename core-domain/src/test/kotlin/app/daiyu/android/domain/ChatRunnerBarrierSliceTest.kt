package app.daiyu.android.domain

import app.daiyu.android.domain.runtime.*
import app.daiyu.android.domain.tools.*
import org.junit.Assert.*
import org.junit.Test

class ChatRunnerBarrierSliceTest {
    private class MemoryRuntime : AtomicRuntimeStore {
        val attempts = mutableListOf<RunCheckpoint>()
        val commits = mutableListOf<RunCheckpoint>()
        var before: (RunCheckpoint) -> Unit = {}
        var after: (RunCheckpoint) -> Unit = {}
        var read: (RunCheckpoint) -> RunCheckpoint? = { commits.lastOrNull() }
        override fun commitCheckpoint(checkpoint: RunCheckpoint) {
            attempts.add(checkpoint)
            before(checkpoint)
            assertEquals((commits.lastOrNull()?.revision ?: 0L) + 1L, checkpoint.revision)
            commits.lastOrNull()?.let { assertEquals(it.owner, checkpoint.owner) }
            commits.add(checkpoint)
            after(checkpoint)
        }
        override fun readCommittedCheckpoint(checkpoint: RunCheckpoint): RunCheckpoint? = read(checkpoint)
        override fun reconcileCheckpoint(checkpoint: RunCheckpoint): RunCheckpoint = error("live recovery forbidden")
    }
    private class Fixture(private val questions: Int = 1) {
        val runtime = MemoryRuntime()
        val offers = mutableListOf<BarrierOffer>()
        var opens = 0
        var nexts = 0
        var starts = 0
        var finishes = 0
        var synchronousExecutions = 0
        private var owner: RunExecutionOwner? = null
        val initial = Message(MessageId("a"), ConversationId("c"), RunId("r"), "assistant", emptyList(), RunStatus.RUNNING)
        private val executor = object : ReceiptBatchToolExecutor {
            override fun bindOwner(owner: RunExecutionOwner) { assertNull(this@Fixture.owner); this@Fixture.owner = owner }
            override fun execute(message: Message, invocations: List<ToolInvocation>, cancelled: () -> Boolean): List<ToolDispatchResult> {
                synchronousExecutions++; error("synchronous barrier execution forbidden")
            }
            override fun start(message: Message, invocations: List<ToolInvocation>, cancelled: () -> Boolean): ReceiptBatchStart {
                starts++
                val call = invocations.single()
                assertTrue(checkNotNull(owner).owns(message))
                assertEquals(ToolDisposition.IN_FLIGHT, runtime.commits.last().tools.single { it.invocation.id == call.id }.disposition)
                val context = ToolExecutionContext(checkNotNull(owner), call, TaskAuthorization("session", "r"), cancelled)
                lateinit var offer: BarrierOffer
                offer = BarrierOffer(context, PendingToolCheckpoint(call.id)) { receipt ->
                    assertTrue(offer.waitingCommitted)
                    assertEquals(ExecutionPhase.WAITING_USER, runtime.commits.last().phase)
                    finishes++
                    val outcome = when (receipt) {
                        is ExecutionReceipt.Confirmed -> DispatchOutcome.Confirmed(receipt.payload, receipt.isError)
                        is ExecutionReceipt.NotExecuted -> DispatchOutcome.NotExecuted(receipt.code)
                        is ExecutionReceipt.Unknown -> DispatchOutcome.Unknown(receipt.code)
                    }
                    ToolDispatchResult(call.id, outcome)
                }
                offers.add(offer)
                return ReceiptBatchStart.Suspended(offer)
            }
        }
        private val model = ModelPort {
            opens++
            object : ModelSession {
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                    val index = nexts++
                    if (index > 0) {
                        assertEquals(ToolCallId("q${index - 1}"), outputs.single().callId)
                        assertEquals("answer", outputs.single().output)
                        val checkpoint = runtime.commits.last()
                        assertNull(checkpoint.pending)
                        assertEquals(ToolDisposition.SUCCEEDED, checkpoint.tools.single { it.invocation.id == outputs.single().callId }.disposition)
                    }
                    if (index < questions) sink(ModelEvent.ToolReady(ToolInvocation(ToolCallId("q$index"), "ask_user", "{}")))
                    else sink(ModelEvent.TextDelta("done", "done"))
                }
                override fun cancel() {}
            }
        }
        val runner = ChatRunner(model, object : ConversationStore {
            override fun save(message: Message) = error("atomic store owns writes")
        }, BatteryTool { error("fallback forbidden") }, RunnerOptions(runtimeStore = runtime, receiptBatchExecutor = executor), {})
        fun start(): BarrierOffer = (runner.startSlice(initial, emptyList()) as RunSlice.Suspended).offer
    }
    private inline fun <reified T : Throwable> expectFailure(block: () -> Unit) {
        try { block() } catch (failure: Throwable) { if (failure is T) return; throw failure }
        fail("Expected ${T::class.java.simpleName}")
    }
    @Test fun waitingIsDurableBeforeOfferIsMarkedAndOriginalSessionContinues() {
        val f = Fixture()
        f.runtime.before = {
            if (it.phase == ExecutionPhase.WAITING_USER) {
                assertFalse(f.offers.single().waitingCommitted)
                assertEquals(f.offers.single().pending, it.pending)
                assertEquals(ToolDisposition.IN_FLIGHT, it.tools.single().disposition)
            }
        }
        val offer = f.start()
        assertSame(f.offers.single(), offer)
        assertTrue(offer.waitingCommitted)
        assertEquals(ExecutionPhase.WAITING_USER, f.runner.state().phase)
        assertEquals(1, f.nexts); assertEquals(0, f.finishes)
        val completed = f.runner.resumeSlice(offer, ExecutionReceipt.Confirmed("answer")) as RunSlice.Completed
        assertEquals(RunStatus.COMPLETED, completed.message.status)
        assertEquals(1, f.opens); assertEquals(2, f.nexts); assertEquals(1, f.starts)
        assertEquals(1, f.finishes); assertEquals(0, f.synchronousExecutions)
        assertNull(f.runtime.commits.last().pending)
        expectFailure<IllegalStateException> { f.runner.resumeSlice(offer, ExecutionReceipt.Confirmed("answer")) }
        assertEquals(1, f.finishes)
    }
    @Test fun failedWaitingCommitDoesNotMarkOfferOrAttemptAnotherWrite() {
        val f = Fixture()
        f.runtime.before = { if (it.phase == ExecutionPhase.WAITING_USER) error("write failed") }
        expectFailure<PersistenceException> { f.start() }
        assertFalse(f.offers.single().waitingCommitted)
        assertEquals(ExecutionPhase.WAITING_USER, f.runtime.attempts.last().phase)
        assertEquals(1, f.runtime.attempts.count { it.phase == ExecutionPhase.WAITING_USER })
        assertFalse(f.runtime.attempts.any { it.phase == ExecutionPhase.TERMINAL })
        assertEquals(1, f.nexts); assertEquals(0, f.finishes)
    }
    @Test fun lostWaitingReceiptUsesExactReadWithoutRecommitting() {
        val f = Fixture()
        f.runtime.after = { if (it.phase == ExecutionPhase.WAITING_USER) error("receipt lost") }
        val offer = f.start()
        assertTrue(offer.waitingCommitted)
        assertEquals(1, f.runtime.attempts.count { it.phase == ExecutionPhase.WAITING_USER })
        f.runtime.after = {}
        assertEquals(RunStatus.COMPLETED, (f.runner.resumeSlice(offer, ExecutionReceipt.Confirmed("answer")) as RunSlice.Completed).message.status)
        assertEquals(1, f.finishes)
    }
    @Test fun foreignOwnerReadCannotConfirmUncertainWaitingCommit() {
        val f = Fixture()
        f.runtime.after = { if (it.phase == ExecutionPhase.WAITING_USER) error("receipt lost") }
        f.runtime.read = { it.copy(owner = "foreign") }
        expectFailure<PersistenceException> { f.start() }
        assertFalse(f.offers.single().waitingCommitted); assertEquals(0, f.finishes)
        assertFalse(f.runtime.attempts.any { it.phase == ExecutionPhase.TERMINAL })
    }
    @Test fun lookalikeCannotConsumeOriginalContinuation() {
        val f = Fixture(); val original = f.start()
        val lookalike = BarrierOffer(original.context, original.pending) { error("lookalike forbidden") }
        lookalike.markWaitingCommitted()
        expectFailure<IllegalStateException> { f.runner.resumeSlice(lookalike, ExecutionReceipt.Confirmed("answer")) }
        assertEquals(0, f.finishes)
        assertTrue(f.runner.resumeSlice(original, ExecutionReceipt.Confirmed("answer")) is RunSlice.Completed)
        assertEquals(1, f.finishes)
    }
    @Test fun oldOfferCannotResumeSecondBarrierOnSameStack() {
        val f = Fixture(2); val first = f.start()
        val second = (f.runner.resumeSlice(first, ExecutionReceipt.Confirmed("answer")) as RunSlice.Suspended).offer
        assertNotSame(first, second)
        expectFailure<IllegalStateException> { f.runner.resumeSlice(first, ExecutionReceipt.Confirmed("answer")) }
        assertEquals(1, f.finishes)
        assertTrue(f.runner.resumeSlice(second, ExecutionReceipt.Confirmed("answer")) is RunSlice.Completed)
        assertEquals(2, f.finishes); assertEquals(1, f.opens); assertEquals(3, f.nexts)
    }
    @Test fun unknownReceiptClearsPendingAndNeverContinuesModel() {
        val f = Fixture(); val offer = f.start()
        val result = f.runner.resumeSlice(offer, ExecutionReceipt.Unknown()) as RunSlice.Completed
        assertEquals(RunStatus.FAILED, result.message.status)
        assertEquals(1, f.nexts); assertEquals(1, f.finishes)
        assertNull(f.runtime.commits.last().pending)
        assertEquals(ToolDisposition.UNKNOWN, f.runtime.commits.last().tools.single().disposition)
    }
    @Test fun resultCheckpointFailureIsNotConvertedToUnknownOrFollowedByAnotherWrite() {
        val f = Fixture(); val offer = f.start()
        f.runtime.before = { if (it.pending == null && it.tools.any { t -> t.disposition == ToolDisposition.SUCCEEDED }) error("result checkpoint failed") }
        expectFailure<PersistenceException> { f.runner.resumeSlice(offer, ExecutionReceipt.Confirmed("answer")) }
        assertEquals(1, f.finishes); assertEquals(1, f.nexts)
        assertNull(f.runtime.attempts.last().pending)
        assertEquals(ToolDisposition.SUCCEEDED, f.runtime.attempts.last().tools.single().disposition)
        assertFalse(f.runtime.attempts.any { it.phase == ExecutionPhase.TERMINAL })
    }
    @Test fun stoppedDrainUnwindsWithoutFinishingOfferOrCallingModel() {
        val f = Fixture(); val offer = f.start(); f.runner.stop()
        expectFailure<IllegalStateException> { f.runner.resumeSlice(offer, ExecutionReceipt.Confirmed("answer")) }
        val result = f.runner.drainStoppedSlice() as RunSlice.Completed
        assertEquals(RunStatus.STOPPED, result.message.status)
        assertEquals(1, f.nexts); assertEquals(1, f.starts); assertEquals(0, f.finishes)
        val terminal = f.runtime.commits.last()
        assertEquals(ExecutionPhase.TERMINAL, terminal.phase); assertNull(terminal.pending)
        assertEquals(ToolDisposition.UNKNOWN, terminal.tools.single().disposition)
        val writes = f.runtime.attempts.size
        assertEquals(result, f.runner.drainStoppedSlice()); assertEquals(writes, f.runtime.attempts.size)
    }
    @Test fun legacyRunRejectsAskBeforeEitherExecutorEntryPoint() {
        val f = Fixture(); val result = f.runner.run(f.initial, emptyList())
        assertEquals(RunStatus.FAILED, result.status)
        assertEquals(0, f.starts); assertEquals(0, f.synchronousExecutions); assertEquals(0, f.finishes)
        assertEquals(1, f.nexts); assertNull(f.runtime.commits.last().pending)
    }
}
