package app.daiyu.android.domain.tools

import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.RunExecutionOwner
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

class BarrierOfferTest {
    private val owner = RunExecutionOwner.issue(Message(MessageId("a"), ConversationId("c"), RunId("r"),
        "assistant", emptyList(), RunStatus.RUNNING))
    private val task = TaskAuthorization("s", "r")
    private val call = ToolInvocation(ToolCallId("provider-call"), "ask_user", "{}")
    private class Store : ToolLedgerStore {
        private val durable = InMemoryToolLedgerStore()
        var transactions = 0
        var failAt: Int? = null
        var afterReserve: () -> Unit = {}
        override fun <T> transaction(block: (MutableList<LedgerEntry>) -> T): T {
            transactions++
            if (transactions == failAt) throw IllegalStateException("synthetic commit failure")
            val result = durable.transaction(block)
            if (transactions == 1) afterReserve()
            return result
        }
        fun rows(): List<LedgerEntry> = durable.transaction { it.toList() }
    }
    private fun tool(name: String = "ask_user", effect: ToolEffect = ToolEffect.USER_BARRIER,
        plan: () -> PreparedTool = { PreparedTool.userBarrier() }) = object : RegisteredTool {
        override val definition = ToolDefinition(name, 1, "test", "tools", effect, ToolSchema())
        override fun prepare(arguments: ToolArguments) = plan()
    }
    private fun dispatcher(store: Store, tools: List<RegisteredTool> = listOf(tool()),
        enabled: Set<String> = setOf("tools")): ToolDispatcher {
        val registry = ToolRegistry(); tools.forEach { registry.register(it) }
        return ToolDispatcher(registry.snapshot(), SessionCapabilities("s", enabled), ToolLedger(store))
    }
    private fun completed(start: ReceiptBatchStart) = (start as ReceiptBatchStart.Completed).receipts
    private fun offer(dispatcher: ToolDispatcher, cancelled: () -> Boolean = { false }) =
        (dispatcher.start(task, listOf(call), owner, cancelled) as ReceiptBatchStart.Suspended).offer
    private fun illegal(action: () -> Unit) {
        try { action(); fail("expected IllegalStateException") } catch (_: IllegalStateException) {}
    }
    @Test fun purePlanAndLegacyEntrypointsNeverReserveOrExecuteBarrier() {
        val plan = PreparedTool.userBarrier(); assertTrue(plan.isUserBarrier); illegal { plan.execute() }
        val store = Store(); val dispatcher = dispatcher(store)
        val expected = DispatchOutcome.NotExecuted("user_barrier_requires_start")
        assertEquals(expected, dispatcher.dispatch(task, listOf(call)).single().outcome)
        assertEquals(expected, dispatcher.dispatchOwned(task, listOf(call), owner).single().outcome)
        assertEquals(0, store.transactions)
    }
    @Test fun startCapturesOriginalReferencesAndFinishesOriginalReservationOnce() {
        val store = Store(); val dispatcher = dispatcher(store); val cancelled: () -> Boolean = { false }
        val offer = offer(dispatcher, cancelled)
        assertSame(owner, offer.context.owner); assertSame(call, offer.context.invocation)
        assertSame(task, offer.context.authorization); assertSame(cancelled, offer.context.cancelled)
        assertEquals(call.id, offer.pending.callId); assertFalse(offer.waitingCommitted)
        assertEquals(1, store.transactions); assertEquals(LedgerState.UNKNOWN, store.rows().single().state)
        offer.markWaitingCommitted(); assertTrue(offer.waitingCommitted); illegal { offer.markWaitingCommitted() }
        assertEquals(DispatchOutcome.Confirmed("answer", true), offer.finish(ExecutionReceipt.Confirmed("answer", true)).outcome)
        assertEquals(2, store.transactions); assertEquals(LedgerState.CONFIRMED, store.rows().single().state)
        illegal { offer.finish(ExecutionReceipt.Confirmed("again")) }
        assertEquals(DispatchOutcome.Confirmed("answer", true, replayed = true),
            completed(dispatcher.start(task, listOf(call), owner)).single().outcome)
    }
    @Test fun pendingReservationCannotProduceAnotherLiveOffer() {
        val store = Store(); val dispatcher = dispatcher(store); offer(dispatcher)
        for (current in listOf(dispatcher, dispatcher(store))) {
            assertEquals(DispatchOutcome.Unknown("unknown_effect_protected"),
                completed(current.start(task, listOf(call), owner)).single().outcome)
        }
        assertEquals(LedgerState.UNKNOWN, store.rows().single().state); assertEquals(1, store.rows().size)
    }
    @Test fun failedFinalPersistenceIsUnknownForEveryReceiptAndCannotBeRetried() {
        for (receipt in listOf(ExecutionReceipt.Confirmed("answer"), ExecutionReceipt.NotExecuted("not_sent"), ExecutionReceipt.Unknown("unconfirmed"))) {
            val store = Store().apply { failAt = 2 }; val dispatcher = dispatcher(store); val offer = offer(dispatcher)
            assertEquals(DispatchOutcome.Unknown("receipt_persistence_failed"), offer.finish(receipt).outcome)
            assertEquals(2, store.transactions); assertEquals(LedgerState.UNKNOWN, store.rows().single().state)
            illegal { offer.finish(receipt) }
            assertEquals(DispatchOutcome.Unknown("unknown_effect_protected"), completed(dispatcher.start(task, listOf(call), owner)).single().outcome)
        }
    }
    @Test fun nonConfirmedReceiptsRemainDistinctAndSingleUse() {
        for (receipt in listOf<ExecutionReceipt>(ExecutionReceipt.NotExecuted("not_sent"), ExecutionReceipt.Unknown("unconfirmed"))) {
            val store = Store(); val dispatcher = dispatcher(store); val offer = offer(dispatcher)
            val expected = when (receipt) {
                is ExecutionReceipt.NotExecuted -> DispatchOutcome.NotExecuted(receipt.code)
                is ExecutionReceipt.Unknown -> DispatchOutcome.Unknown(receipt.code)
                else -> error("unexpected test receipt")
            }
            assertEquals(expected, offer.finish(receipt).outcome); illegal { offer.finish(receipt) }
            assertEquals(if (receipt is ExecutionReceipt.Unknown) DispatchOutcome.Unknown("unknown_effect_protected") else expected,
                completed(dispatcher.start(task, listOf(call), owner)).single().outcome)
        }
    }
    @Test fun startPreflightsEntireBatchAndRequiresExclusivePureAskUserPlan() {
        fun reject(expected: String, tools: List<RegisteredTool> = listOf(tool()), calls: List<ToolInvocation> = listOf(call),
            authorization: TaskAuthorization = task, enabled: Set<String> = setOf("tools")) {
            val store = Store()
            val results = completed(dispatcher(store, tools, enabled).start(authorization, calls, owner))
            assertTrue(results.all { it.outcome == DispatchOutcome.NotExecuted(expected) }); assertEquals(0, store.transactions)
        }
        reject("unknown_tool", tools = emptyList())
        reject("invalid_arguments", calls = listOf(ToolInvocation(call.id, call.name, "{")))
        reject("capability_disabled", enabled = emptySet())
        reject("tool_run_owner_mismatch", authorization = TaskAuthorization("s", "other"))
        reject("task_session_mismatch", authorization = TaskAuthorization("other", "r"))
        reject("ask_user_batch_barrier", tools = listOf(tool(), tool("read", ToolEffect.READ_ONLY) { PreparedTool { ExecutionReceipt.Confirmed("read") } }),
            calls = listOf(call, ToolInvocation(ToolCallId("read"), "read", "{}")))
        reject("effect_contract_violation", tools = listOf(tool { PreparedTool { ExecutionReceipt.Confirmed("must not run") } }))
        reject("effect_contract_violation", tools = listOf(tool("read", ToolEffect.READ_ONLY)), calls = listOf(ToolInvocation(call.id, "read", "{}")))
        reject("effect_contract_violation", tools = listOf(tool("wait")), calls = listOf(ToolInvocation(call.id, "wait", "{}")))
    }
    @Test fun cancellationBeforeOrImmediatelyAfterReservationNeverOffersQuestion() {
        val store = Store(); val dispatcher = dispatcher(store)
        assertEquals(DispatchOutcome.NotExecuted("cancelled_before_execution"), completed(dispatcher.start(task, listOf(call), owner) { true }).single().outcome)
        assertEquals(0, store.transactions)
        var cancelled = false; store.afterReserve = { cancelled = true }
        assertEquals(DispatchOutcome.NotExecuted("batch_stopped_before_execution"), completed(dispatcher.start(task, listOf(call), owner) { cancelled }).single().outcome)
        assertEquals(2, store.transactions); assertEquals(LedgerState.NOT_EXECUTED, store.rows().single().state)
    }
    @Test fun ordinaryContextualBatchStillExecutesInOrderAndReplaysSynchronously() {
        val store = Store(); val contexts = mutableListOf<ToolExecutionContext>()
        val read = tool("read", ToolEffect.READ_ONLY) { PreparedTool.contextual { context ->
            contexts += context; ExecutionReceipt.Confirmed("read-${contexts.size}") } }
        val dispatcher = dispatcher(store, listOf(read))
        val calls = listOf(ToolInvocation(ToolCallId("one"), "read", "{}"), ToolInvocation(ToolCallId("two"), "read", "{}"))
        val results = completed(dispatcher.start(task, calls, owner))
        assertEquals(calls.map { it.id }, results.map { it.callId })
        assertEquals(listOf("read-1", "read-2"), results.map { (it.outcome as DispatchOutcome.Confirmed).payload })
        assertTrue(contexts.all { it.owner === owner && it.authorization === task })
        contexts.forEachIndexed { index, context -> assertSame(calls[index], context.invocation) }
        assertEquals(2, contexts.size); assertTrue(dispatcher.dispatchOwned(task, calls, owner).all { (it.outcome as DispatchOutcome.Confirmed).replayed })
        assertEquals(2, contexts.size)
    }
    @Test fun concurrentFinishConsumesOriginalReservationOnlyOnce() {
        val store = Store(); val offer = offer(dispatcher(store)); val gate = CountDownLatch(1)
        val attempts = List(2) { FutureTask { check(gate.await(5, TimeUnit.SECONDS)); runCatching { offer.finish(ExecutionReceipt.Confirmed("answer")) }.isSuccess } }
        val threads = attempts.map { Thread(it).apply { start() } }
        try { gate.countDown(); assertEquals(1, attempts.count { it.get(5, TimeUnit.SECONDS) }) }
        finally { gate.countDown(); threads.forEach { it.join(5000) } }
        assertEquals(2, store.transactions); assertEquals(LedgerState.CONFIRMED, store.rows().single().state)
    }
}
