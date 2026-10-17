package app.daiyu.android.integration.tools

import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.*
import app.daiyu.android.domain.tools.*
import app.daiyu.android.skills.SkillRunSession
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real Runner owner / central catalog / ledger, injectable fixed-command backend. No shell/network. */
class TerminalJobSessionTest {
    private val message = Message(MessageId("assistant"), ConversationId("conversation"), RunId("run"),
        Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING)
    private fun plan(mode: String = "async") = TerminalJobPlan(message.conversationId, message.runId,
        TerminalPtyLaunch.Android(listOf("/system/bin/sh"), mapOf("PATH" to "/system/bin"), File(System.getProperty("java.io.tmpdir")).absoluteFile),
        "printf 'approved exact command'", mode)
    private fun owner(): RunExecutionOwner {
        var value: RunExecutionOwner? = null
        val executor = object : ReceiptBatchToolExecutor {
            override fun bindOwner(owner: RunExecutionOwner) { value = owner }
            override fun execute(message: Message, invocations: List<ToolInvocation>, cancelled: () -> Boolean) = error("unused")
        }
        ChatRunner(ModelPort { object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {}
            override fun cancel() {}
        } }, object : ConversationStore { override fun save(message: Message) {} }, BatteryTool { error("unused") },
            RunnerOptions(receiptBatchExecutor = executor)) {}.run(message, emptyList())
        return checkNotNull(value)
    }
    private open class Backend : TerminalJobTransport {
        val calls = mutableListOf<Pair<String, JSONObject>>()
        val closes = AtomicInteger()
        val waits = AtomicInteger()
        var exited = true
        val id = "00000000-0000-0000-0000-000000000001"
        override fun execute(name: String, arguments: JSONObject): JSONObject {
            calls.add(name to JSONObject(arguments.toString()))
            return if (name == "terminal_open") JSONObject().put("ok", true).put("session_id", "session")
            else JSONObject().put("ok", true).put("execution", "unknown").put("job_id", id)
        }
        override fun inspect(id: String) = JSONObject().put("ok", true).put("job_id", id)
            .put("stdout", "original log").put("execution", "unknown")
        override fun stop(id: String) = JSONObject().put("ok", true).put("job_id", id).put("stopped", false)
        override fun close() { closes.incrementAndGet() }
        override fun awaitOrdinaryTermination(timeoutMillis: Long): Boolean { waits.incrementAndGet(); return exited }
    }
    private inner class Fixture(val backend: Backend = Backend(), mode: String = "async",
        store: ToolLedgerStore = InMemoryToolLedgerStore(), enabled: Boolean = true,
        create: (() -> TerminalJobTransport)? = null) {
        val plan = plan(mode)
        val token = TaskAuthorization(plan.sessionId, plan.runId.value, setOf(plan.effect))
        val capabilities = SessionCapabilities(plan.sessionId, if (enabled) setOf(TerminalJobSession.CAPABILITY) else emptySet())
        val creates = AtomicInteger()
        val session = TerminalJobSession.approved(plan, token) { creates.incrementAndGet(); create?.invoke() ?: backend }
        val tools = RunCatalogTools(ToolRegistry(), capabilities, ToolLedger(store), message.conversationId, message.runId,
            authorization = token, terminalJobs = session) { _, authorization ->
            assertSame(token, authorization)
            SkillRunSession.create(authorization, emptySet(), emptyList())
        }
        val owner = owner()
        init { tools.bindOwner(owner) }
        fun call(name: String = TerminalJobSession.START, id: String = "start", args: String = "{}", start: Boolean = false): DispatchOutcome {
            tools.definitions
            val invocation = ToolInvocation(ToolCallId(id), name, args)
            return if (start) (tools.start(message, listOf(invocation)) { false } as ReceiptBatchStart.Completed).receipts.single().outcome
            else tools.execute(message, listOf(invocation)) { false }.single().outcome
        }
        fun close(): RunCatalogCloseResult {
            val done = CountDownLatch(1)
            val result = AtomicReference<RunCatalogCloseResult>()
            tools.closeWithResult { result.set(it); done.countDown() }
            assertTrue(done.await(5, TimeUnit.SECONDS))
            return result.get()
        }
    }
    @Test fun definitionsAndConfirmationDoNotCreateBackendAndOnlyEmptyArgumentsAreExposed() {
        val f = Fixture()
        assertEquals(0, f.creates.get())
        assertEquals(setOf(TerminalJobSession.START, TerminalJobSession.READ), f.tools.definitions.map { it.name }.toSet())
        assertEquals(0, f.creates.get())
        assertTrue(f.call(args = """{"command":"unapproved"}""") is DispatchOutcome.NotExecuted)
        assertEquals(0, f.creates.get())
        assertEquals(RunCatalogCloseResult.Drained, f.close())
    }
    @Test fun exactApprovedCommandReachesOneRegistrySessionAndUnknownNeverResendsWithNewCallId() {
        val f = Fixture()
        assertTrue(f.call() is DispatchOutcome.Unknown)
        assertEquals(1, f.creates.get())
        assertEquals(listOf("terminal_open", "terminal_exec"), f.backend.calls.map { it.first })
        val raw = f.backend.calls.last().second
        assertEquals(f.plan.command, raw.getString("command"))
        assertEquals("async", raw.getString("mode"))
        assertEquals("command", raw.getString("request_id"))
        assertEquals(f.backend.id, f.session.jobId)
        assertTrue(f.call(id = "another", start = true) is DispatchOutcome.Unknown)
        assertEquals(2, f.backend.calls.size)
        assertEquals(RunCatalogCloseResult.Drained, f.close())
        assertEquals("original log", f.session.inspectOnWorker().getString("stdout"))
    }
    @Test fun daemonHasSeparateExactEffectAndRemainsInspectableAndExplicitlyStoppableAfterRunClose() {
        assertNotEquals(plan("async").effect, plan("daemon").effect)
        val f = Fixture(mode = "daemon")
        f.call()
        assertEquals(RunCatalogCloseResult.Drained, f.close())
        assertEquals(f.backend.id, f.session.inspectOnWorker().getString("job_id"))
        assertFalse(f.session.stopOnWorker().getBoolean("stopped"))
        assertEquals("daemon", f.backend.calls.last().second.getString("mode"))
        assertEquals(2, f.backend.calls.size)
    }
    @Test fun capabilityWithoutApprovalDoesNotAdvertiseAndDisabledApprovalDoesNotOpen() {
        val f = Fixture(enabled = false)
        assertTrue(f.tools.definitions.isEmpty())
        assertTrue(f.call() is DispatchOutcome.NotExecuted)
        assertEquals(0, f.creates.get())
        assertEquals(RunCatalogCloseResult.Drained, f.close())
        val p = plan()
        try { TerminalJobSession.approved(p, TaskAuthorization(p.sessionId, p.runId.value)) { Backend() }; fail() }
        catch (_: IllegalArgumentException) {}
    }
    @Test fun copiedAuthorizationAndOwnerCannotAttachToOriginalSession() {
        val f = Fixture()
        try {
            RunCatalogTools(ToolRegistry(), f.capabilities, ToolLedger(InMemoryToolLedgerStore()), message.conversationId,
                message.runId, authorization = TaskAuthorization(f.token.sessionId, f.token.taskId, f.token.allowedWrites),
                terminalJobs = f.session) { _, auth -> SkillRunSession.create(auth, emptySet(), emptyList()) }
            fail()
        } catch (_: IllegalArgumentException) {}
        try { f.session.bind(owner(), f.capabilities, f.token); fail() } catch (_: IllegalStateException) {}
        assertEquals(0, f.creates.get())
        f.close()
    }
    @Test fun failedFactoryDoesNotPretendAbsentResourceIsDrained() {
        val f = Fixture(create = { error("partially constructed backend") })
        assertTrue(f.call() is DispatchOutcome.Unknown)
        assertTrue(f.close() is RunCatalogCloseResult.Unknown)
        assertEquals(1, f.creates.get())
    }
    @Test fun unconfirmedPhysicalExitIsUnknownAndDoesNotAutomaticallyRepeatObservation() {
        val backend = Backend().apply { exited = false }
        val f = Fixture(backend)
        f.call()
        assertTrue(f.close() is RunCatalogCloseResult.Unknown)
        val attempts = backend.waits.get()
        backend.exited = true
        assertTrue(f.close() is RunCatalogCloseResult.Unknown)
        assertEquals(attempts, backend.waits.get())
    }
    @Test fun parentFinalReceiptMustFinishBeforeJobPhysicalDrainStarts() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val rows = mutableListOf<LedgerEntry>()
        val store = object : ToolLedgerStore {
            override fun <T> transaction(block: (MutableList<LedgerEntry>) -> T): T = synchronized(rows) {
                val copy = rows.toMutableList()
                val result = block(copy)
                if (copy.any { it.code.startsWith("terminal_job_dispatched_or_unconfirmed:") }) {
                    entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                }
                rows.clear(); rows.addAll(copy)
                result
            }
        }
        val f = Fixture(store = store)
        val failure = AtomicReference<Throwable>()
        val dispatch = Thread { try { f.call() } catch (caught: Throwable) { failure.set(caught) } }.apply { start() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val closed = CountDownLatch(1)
        f.tools.closeWithResult { closed.countDown() }
        assertEquals(0, f.backend.waits.get())
        assertEquals(1L, closed.count)
        release.countDown(); dispatch.join(5000)
        assertFalse(dispatch.isAlive); failure.get()?.let { throw it }
        assertTrue(closed.await(5, TimeUnit.SECONDS))
        assertEquals(1, f.backend.waits.get())
    }
    @Test fun lateBackendAfterCloseIsStoppedWithoutOpeningSessionOrDispatchingCommand() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val backend = Backend()
        val f = Fixture(backend, create = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); backend })
        val worker = Thread { f.call() }.apply { start() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val done = CountDownLatch(1)
        f.tools.closeWithResult { done.countDown() }
        assertEquals(1L, done.count)
        release.countDown(); worker.join(5000)
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertTrue(backend.calls.isEmpty())
        assertTrue(backend.closes.get() > 0)
    }
}
