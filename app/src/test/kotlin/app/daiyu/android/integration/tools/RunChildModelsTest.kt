package app.daiyu.android.integration.tools

import app.daiyu.android.domain.*
import app.daiyu.android.domain.delegation.*
import app.daiyu.android.domain.runtime.RunnerOptions
import app.daiyu.android.domain.tools.*
import app.daiyu.android.skills.SkillRunSession
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class RunChildModelsTest {
    private val parent = Message(MessageId("parent-message"), ConversationId("conversation"), RunId("parent-run"),
        Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING)
    private fun call(profile: String = "review", id: String = "parent-call") = ToolInvocation(ToolCallId(id),
        RunChildModels.TOOL_NAME, """{"tasks":[{"profile_id":"$profile","prompt":"Review this task"}]}""")
    private fun model(opened: AtomicInteger, body: (List<Message>, (ModelEvent) -> Unit) -> Unit): ModelPort =
        object : ModelPort {
            override fun open(history: List<Message>): ModelSession = error("default tools must not be advertised")
            override fun open(history: List<Message>, definitions: List<app.daiyu.android.domain.ToolDefinition>): ModelSession {
                assertTrue(definitions.isEmpty()); opened.incrementAndGet()
                assertTrue(history.all { it.conversationId == parent.conversationId && it.runId != parent.runId })
                return object : ModelSession {
                    override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                        assertTrue(outputs.isEmpty()); body(history, sink)
                    }
                    override fun cancel() {}
                }
            }
        }
    private fun tools(models: Map<String, ChildModelDefinition>, ledger: InMemoryToolLedgerStore): RunCatalogTools {
        val children = RunChildModels(ChildModelBatch(models), models.keys)
        val capabilities = SessionCapabilities("session", setOf(RunChildModels.CAPABILITY))
        return RunCatalogTools(ToolRegistry(), capabilities, ToolLedger(ledger), parent.conversationId,
            parent.runId, children = children) { grants, auth -> SkillRunSession.create(auth, grants.enabled, emptyList()) }
    }
    private fun definition(port: ModelPort) = ChildModelDefinition("provider/api-model", 1) { port }

    @Test fun parentRunnerConsumesRealChildThroughOriginalCatalogAndLedger() {
        val opened = AtomicInteger(); val ledger = InMemoryToolLedgerStore()
        val port = model(opened) { _, sink ->
            assertTrue(ledger.transaction { it.toList() }.isNotEmpty())
            sink(ModelEvent.TextDelta("text", "discarded delta"))
            sink(ModelEvent.TextFinal("text", "frozen child answer"))
        }
        val catalog = tools(mapOf("review" to definition(port)), ledger)
        var rounds = 0
        val parentPort = ModelPort { object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                if (rounds++ == 0) { assertTrue(outputs.isEmpty()); sink(ModelEvent.ToolReady(call())) }
                else {
                    assertEquals(ToolCallId("parent-call"), outputs.single().callId)
                    assertTrue(outputs.single().output.contains("frozen child answer"))
                    assertFalse(outputs.single().output.contains("discarded delta"))
                    sink(ModelEvent.TextFinal("result", "parent continued"))
                }
            }
            override fun cancel() {}
        } }
        try {
            val result = ChatRunner(parentPort, object : ConversationStore { override fun save(message: Message) {} },
                catalog, RunnerOptions(receiptBatchExecutor = catalog)) {}.run(parent, emptyList())
            assertEquals(RunStatus.COMPLETED, result.status)
            assertEquals(2, rounds); assertEquals(1, opened.get())
            assertEquals(1, ledger.transaction { it.toList() }.size)
        } finally { catalog.close() }
    }

    @Test fun confirmedReplayDoesNotOpenChildAgainAndMapIsDetached() {
        val opened = AtomicInteger(); val ledger = InMemoryToolLedgerStore()
        val models = mutableMapOf("review" to definition(model(opened) { _, sink -> sink(ModelEvent.TextFinal("text", "answer")) }))
        val catalog = tools(models, ledger); models.clear()
        try {
            catalog.bindOwner(runnerOwner(parent)); catalog.definitions
            val first = catalog.execute(parent, listOf(call())) { false }.single().outcome as DispatchOutcome.Confirmed
            assertTrue(first.payload.contains("answer")); assertFalse(first.replayed)
            val replay = catalog.execute(parent, listOf(call())) { false }.single().outcome as DispatchOutcome.Confirmed
            assertTrue(replay.replayed); assertEquals(first.payload, replay.payload); assertEquals(1, opened.get())
        } finally { catalog.close() }
    }

    @Test fun unknownProfileRejectsEntireBatchBeforeLedgerOrNetwork() {
        val opened = AtomicInteger(); val ledger = InMemoryToolLedgerStore()
        val catalog = tools(mapOf("review" to definition(model(opened) { _, _ -> fail("unexpected model") })), ledger)
        try {
            catalog.bindOwner(runnerOwner(parent)); catalog.definitions
            val results = catalog.execute(parent, listOf(call(), call("unbound", "bad"))) { false }
            assertTrue(results.all { it.outcome is DispatchOutcome.NotExecuted })
            assertEquals(0, opened.get()); assertTrue(ledger.transaction { it.toList() }.isEmpty())
        } finally { catalog.close() }
    }

    @Test fun unexpectedChildToolCannotExecuteOrAutomaticallyRetry() {
        val opened = AtomicInteger(); val ledger = InMemoryToolLedgerStore()
        val catalog = tools(mapOf("review" to definition(model(opened) { _, sink ->
            sink(ModelEvent.ToolReady(ToolInvocation(ToolCallId("evil"), "device_status", "{}")))
        })), ledger)
        try {
            catalog.bindOwner(runnerOwner(parent)); catalog.definitions
            val first = catalog.execute(parent, listOf(call())) { false }.single().outcome
            assertTrue(first is DispatchOutcome.Unknown)
            assertTrue(catalog.execute(parent, listOf(call())) { false }.single().outcome is DispatchOutcome.Unknown)
            assertEquals(1, opened.get())
        } finally { catalog.close() }
    }

    @Test fun closeCancelsChildButIdleWaitsForRealExecutionExit() {
        val entered = CountDownLatch(1); val cancelled = CountDownLatch(1); val release = CountDownLatch(1)
        val idle = CountDownLatch(1); val result = AtomicReference<ToolDispatchResult>(); val failure = AtomicReference<Throwable>()
        val port = object : ModelPort {
            override fun open(history: List<Message>): ModelSession = error("default tools")
            override fun open(history: List<Message>, definitions: List<app.daiyu.android.domain.ToolDefinition>) = object : ModelSession {
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                    entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); sink(ModelEvent.TextFinal("late", "not success"))
                }
                override fun cancel() { cancelled.countDown() }
            }
        }
        val catalog = tools(mapOf("review" to definition(port)), InMemoryToolLedgerStore())
        catalog.bindOwner(runnerOwner(parent)); catalog.definitions
        val thread = Thread { try { result.set(catalog.execute(parent, listOf(call())) { false }.single()) }
            catch (e: Throwable) { failure.set(e) } }
        try {
            thread.start(); assertTrue(entered.await(5, TimeUnit.SECONDS))
            catalog.closeWhenIdle { idle.countDown() }
            assertTrue(cancelled.await(5, TimeUnit.SECONDS)); assertEquals(1L, idle.count)
            release.countDown(); thread.join(5000); assertFalse(thread.isAlive)
            assertNull(failure.get()); assertTrue(idle.await(5, TimeUnit.SECONDS))
            assertFalse(result.get().outcome is DispatchOutcome.Confirmed)
        } finally { release.countDown(); catalog.close(); thread.join(5000) }
    }
}
