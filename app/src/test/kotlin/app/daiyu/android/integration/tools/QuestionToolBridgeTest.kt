package app.daiyu.android.integration.tools

import app.daiyu.android.domain.*
import app.daiyu.android.domain.tools.*
import app.daiyu.android.skills.SkillRunSession
import org.junit.Assert.*
import org.junit.Test

class QuestionToolBridgeTest {
    private val message = Message(MessageId("assistant"), ConversationId("conversation"), RunId("run"),
        Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING)
    private val arguments = """{"title":"Choose","question":"Which?","options":[{"id":"a","label":"A"},{"id":"b","label":"B"}]}"""
    private fun question() = ToolInvocation(ToolCallId("provider-question"), "ask_user", arguments)
    private inner class Fixture {
        val owner = runnerOwner(message)
        val task = TaskAuthorization("session", message.runId.value)
        val store = InMemoryToolLedgerStore()
        val capabilities = SessionCapabilities("session", setOf(AskUserPlanTool.CAPABILITY, "read"))
        var reads = 0
        val contexts = mutableListOf<ToolExecutionContext>()
        val read = ToolInvocation(ToolCallId("provider-read"), "read", "{}")
        val registry = ToolRegistry().register(AskUserPlanTool).register(object : RegisteredTool {
            override val definition = app.daiyu.android.domain.tools.ToolDefinition("read", 1, "fixture read", "read", ToolEffect.READ_ONLY, ToolSchema())
            override fun prepare(arguments: ToolArguments) = PreparedTool.contextual {
                contexts.add(it); reads++; ExecutionReceipt.Confirmed("read-result")
            }
        })
        val bridge = CatalogModelBridge(registry.snapshot(), capabilities, ToolLedger(store),
            message.conversationId, message.runId, task).apply { bindOwner(owner) }
    }
    @Test(timeout = 2000) fun offerRetainsOriginalIdentityAndUnknownReservation() {
        val f = Fixture(); val call = question()
        val offer = (f.bridge.start(message, listOf(call)) { false } as ReceiptBatchStart.Suspended).offer
        assertSame(f.owner, offer.context.owner); assertSame(f.task, offer.context.authorization); assertSame(call, offer.context.invocation)
        assertEquals(call.id, offer.pending.callId); assertFalse(offer.waitingCommitted); assertFalse(offer.context.cancelled())
        assertEquals(0, f.reads); assertEquals(LedgerState.UNKNOWN, f.store.transaction { it.single().state })
    }
    @Test fun synchronousAndInvalidWholeBatchCannotReserve() {
        val f = Fixture(); val call = question()
        assertTrue(f.bridge.execute(message, listOf(call)) { false }.all { it.outcome is DispatchOutcome.NotExecuted })
        val invalidArguments = listOf("{}", arguments.replace("\"id\":\"b\"", "\"id\":\"a\""),
            arguments.replace("\"title\":\"Choose\"", "\"title\":\" \""),
            arguments.replace("\"options\":", "\"recommended_option_id\":\"missing\",\"options\":"))
        val batches = listOf(listOf(f.read, call), listOf(call, call.copy(id = ToolCallId("second-question")))) + invalidArguments.map { listOf(call.copy(arguments = it)) }
        for (batch in batches) {
            val result = f.bridge.start(message, batch) { false } as ReceiptBatchStart.Completed
            assertEquals(batch.map { it.id }, result.receipts.map { it.callId })
            assertTrue(result.receipts.all { it.outcome is DispatchOutcome.NotExecuted }); assertTrue(f.store.transaction { it.isEmpty() })
        }
        assertEquals(0, f.reads)
    }
    @Test fun ordinaryStartAndSynchronousReplayKeepContext() {
        val f = Fixture()
        val first = (f.bridge.start(message, listOf(f.read)) { false } as ReceiptBatchStart.Completed).receipts.single().outcome as DispatchOutcome.Confirmed
        assertEquals("read-result", first.payload); assertFalse(first.replayed); assertEquals(1, f.reads)
        assertSame(f.owner, f.contexts.single().owner); assertSame(f.task, f.contexts.single().authorization); assertSame(f.read, f.contexts.single().invocation)
        assertTrue((f.bridge.execute(message, listOf(f.read)) { false }.single().outcome as DispatchOutcome.Confirmed).replayed)
        assertEquals(1, f.reads)
    }
    @Test fun disabledOrCancelledStartsCannotReserve() {
        val f = Fixture()
        assertTrue((f.bridge.start(message, listOf(question())) { true } as ReceiptBatchStart.Completed).receipts.all { it.outcome is DispatchOutcome.NotExecuted })
        val disabled = CatalogModelBridge(f.registry.snapshot(), SessionCapabilities("session", emptySet()), ToolLedger(f.store),
            message.conversationId, message.runId, f.task).apply { bindOwner(f.owner) }
        assertTrue(disabled.definitions.isEmpty())
        assertTrue((disabled.start(message, listOf(question())) { false } as ReceiptBatchStart.Completed).receipts.all { it.outcome is DispatchOutcome.NotExecuted })
        assertTrue(f.store.transaction { it.isEmpty() })
    }
    @Test fun runBridgeCapturesAuthorizationOnceAndOfferSeesCloseFence() {
        val f = Fixture(); var captures = 0; var captured: TaskAuthorization? = null
        val tools = RunCatalogTools(f.registry, f.capabilities, ToolLedger(f.store), message.conversationId, message.runId) { capabilities, task ->
            assertSame(f.capabilities, capabilities); captures++; captured = task; SkillRunSession.create(task, emptySet(), emptyList())
        }
        try {
            tools.bindOwner(f.owner)
            assertTrue(tools.definitions.any { it.name == "ask_user" && it.sideEffect })
            val call = question(); val offer = (tools.start(message, listOf(call)) { false } as ReceiptBatchStart.Suspended).offer
            assertSame(f.owner, offer.context.owner); assertSame(captured, offer.context.authorization); assertSame(call, offer.context.invocation)
            assertFalse(offer.context.cancelled()); tools.close(); assertTrue(offer.context.cancelled())
            assertEquals(DispatchOutcome.NotExecuted("run_closed"), (tools.start(message, listOf(call.copy(id = ToolCallId("after-close")))) { false } as ReceiptBatchStart.Completed).receipts.single().outcome)
            assertEquals(1, f.store.transaction { it.size }); assertEquals(1, captures)
        } finally { tools.close() }
    }
}
