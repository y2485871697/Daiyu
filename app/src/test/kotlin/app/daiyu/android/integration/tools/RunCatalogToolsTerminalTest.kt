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

/** Production parent dispatch/close path, real Runner-issued owner, fake transport only. */
class RunCatalogToolsTerminalTest {
    private val hostCwd = File(System.getProperty("java.io.tmpdir")).absoluteFile.toPath().normalize().toFile()
    private val message = Message(MessageId("assistant"), ConversationId("conversation"), RunId("run"),
        Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING)

    private fun plan() = TerminalPtyPlan(message.conversationId, "session", "run", "environment", "generation",
        TerminalPtyLaunch.Android(listOf("/system/bin/sh"), emptyMap(), hostCwd),
        File(hostCwd, "catalog-terminal-test"), "scope", listOf(
            TerminalPtyStep.Open("open"),
            TerminalPtyStep.Input("input", "host_input", 41, "approved\n".toByteArray()),
            TerminalPtyStep.Resize("resize", "host_resize", 90, 30, 100),
        ), listOf(TerminalPtyRead("page", 0, 1024)))

    private open class Connection : TerminalPtyConnection {
        val closes = AtomicInteger()
        val waits = AtomicInteger()
        val writes = AtomicInteger()
        val resizes = AtomicInteger()
        val callers = mutableListOf<TaskAuthorization>()
        override fun read(authorization: TaskAuthorization, offset: Int, limit: Int): JSONObject {
            callers.add(authorization)
            return JSONObject().put("ok", true).put("stdout", "snapshot")
        }
        override fun write(authorization: TaskAuthorization, requestId: String, sequence: Long, bytes: ByteArray): JSONObject {
            callers.add(authorization); writes.incrementAndGet()
            return JSONObject().put("execution", "executed")
        }
        override fun resize(authorization: TaskAuthorization, requestId: String, sequence: Long, rows: Int, columns: Int): JSONObject {
            callers.add(authorization); resizes.incrementAndGet()
            return JSONObject().put("execution", "executed")
        }
        override fun interrupt(authorization: TaskAuthorization, requestId: String, sequence: Long) = error("not in plan")
        override fun close() { closes.incrementAndGet() }
        override fun awaitTermination(timeoutMillis: Long): Boolean { waits.incrementAndGet(); return true }
    }

    private inner class Fixture(
        val connection: Connection = Connection(),
        val store: ToolLedgerStore = InMemoryToolLedgerStore(),
        enabled: Boolean = true,
        open: (() -> TerminalPtyConnection)? = null,
        capture: ((SessionCapabilities, TaskAuthorization) -> SkillRunSession)? = null,
        bind: Boolean = true,
    ) : AutoCloseable {
        val plan = plan()
        val authorization = TaskAuthorization("session", "run", plan.effects.values.toSet())
        val capabilities = SessionCapabilities("session", setOf("test.audit") +
            if (enabled) setOf(RunTerminalTools.CAPABILITY) else emptySet())
        val opens = AtomicInteger()
        val binding = CatalogTerminalBinding.approved(plan, authorization) { opens.incrementAndGet(); open?.invoke() ?: connection }
        val token = runnerOwner(message)
        val auditContexts = mutableListOf<ToolExecutionContext>()
        val registry = ToolRegistry().register(object : RegisteredTool {
            override val definition = app.daiyu.android.domain.tools.ToolDefinition(
                "audit", 1, "test read", "test.audit", ToolEffect.READ_ONLY, ToolSchema(emptyList()))
            override fun prepare(arguments: ToolArguments) = PreparedTool.contextual {
                auditContexts.add(it)
                ExecutionReceipt.Confirmed("audit")
            }
        })
        val captures = AtomicInteger()
        val tools = RunCatalogTools(registry, capabilities, ToolLedger(store), message.conversationId, message.runId,
            authorization = authorization, terminal = binding) { caps, auth ->
            assertSame(capabilities, caps); assertSame(authorization, auth); captures.incrementAndGet()
            capture?.invoke(caps, auth) ?: SkillRunSession.create(auth, emptySet(), emptyList())
        }
        init { if (bind) tools.bindOwner(token) }
        fun step(step: String, id: String = step) = ToolInvocation(ToolCallId(id), RunTerminalTools.STEP_TOOL,
            JSONObject().put("step_id", step).toString())
        fun audit(id: String = "audit") = ToolInvocation(ToolCallId(id), "audit", "{}")
        fun read(id: String = "page") = ToolInvocation(ToolCallId(id), RunTerminalTools.READ_TOOL, """{"read_id":"page"}""")
        fun dispatch(vararg calls: ToolInvocation, start: Boolean = false): List<ToolDispatchResult> =
            if (start) (tools.start(message, calls.toList()) { false } as ReceiptBatchStart.Completed).receipts
            else tools.execute(message, calls.toList()) { false }
        override fun close() {
            val done = CountDownLatch(1)
            tools.closeWithResult { done.countDown() }
            assertTrue("parent close callback missing", done.await(5, TimeUnit.SECONDS))
        }
    }

    private fun rejected(expected: String, block: () -> Unit) {
        try { block(); fail("accepted: $expected") }
        catch (failure: IllegalArgumentException) { assertEquals(expected, failure.message) }
        catch (failure: IllegalStateException) { assertEquals(expected, failure.message) }
    }
    private fun worker(failure: AtomicReference<Throwable?>, block: () -> Unit) = Thread {
        try { block() } catch (caught: Throwable) { failure.set(caught) }
    }
    private fun joined(thread: Thread, failure: AtomicReference<Throwable?>) {
        thread.join(5000); assertFalse("worker did not finish", thread.isAlive)
        failure.get()?.let { throw it }
    }

    @Test fun productionCatalogUsesOneOwnerAuthorizationAndLedgerAndDoesNotOpenAtSetup() {
        Fixture().use { f ->
            assertEquals(0, f.captures.get()); assertEquals(0, f.opens.get())
            assertEquals(setOf("audit", RunTerminalTools.STEP_TOOL, RunTerminalTools.READ_TOOL),
                f.tools.definitions.map { it.name }.toSet())
            assertEquals(1, f.captures.get()); assertEquals(0, f.opens.get())
            assertTrue(f.store.transaction { it.isEmpty() })
            assertTrue(f.dispatch(f.step("open"), f.audit(), start = true).all { it.outcome is DispatchOutcome.Confirmed })
            assertEquals(1, f.opens.get())
            assertSame(f.token, f.auditContexts.single().owner)
            assertSame(f.authorization, f.auditContexts.single().authorization)
            assertTrue(f.token.owns(message))
            assertTrue(f.dispatch(f.read()).single().outcome is DispatchOutcome.Confirmed)
            assertSame(f.authorization, f.connection.callers.single())
            assertTrue((f.dispatch(f.step("open")).single().outcome as DispatchOutcome.Confirmed).replayed)
            assertEquals(1, f.opens.get())
            assertEquals(setOf("open", "audit", "page"), f.store.transaction { entries -> entries.map { it.callId }.toSet() })
        }
    }

    @Test fun defaultCatalogAndCapabilityAloneNeverEnableTerminalAndDisabledBindingNeverOpens() {
        for (enabled in listOf(false, true)) {
            val caps = SessionCapabilities("session", if (enabled) setOf(RunTerminalTools.CAPABILITY) else emptySet())
            val tools = RunCatalogTools(ToolRegistry(), caps, ToolLedger(InMemoryToolLedgerStore()),
                message.conversationId, message.runId) { _, auth ->
                assertTrue(auth.allowedWrites.isEmpty())
                SkillRunSession.create(auth, emptySet(), emptyList())
            }
            tools.bindOwner(runnerOwner(message))
            try { assertTrue(tools.definitions.isEmpty()) } finally { tools.close() }
        }
        Fixture(enabled = false).use { f ->
            assertEquals(listOf("audit"), f.tools.definitions.map { it.name })
            assertEquals(DispatchOutcome.NotExecuted("unknown_tool"), f.dispatch(f.step("open")).single().outcome)
            assertEquals(0, f.opens.get()); assertTrue(f.store.transaction { it.isEmpty() })
        }
    }

    @Test fun parentRejectsMissingCopiedOrForeignAuthorizationAndForeignPlanBeforeCapture() {
        Fixture(bind = false).use { f ->
            fun create(auth: TaskAuthorization?, caps: SessionCapabilities = f.capabilities,
                conversation: ConversationId = message.conversationId, run: RunId = message.runId) =
                RunCatalogTools(ToolRegistry(), caps, ToolLedger(f.store), conversation, run,
                    authorization = auth, terminal = f.binding) { _, _ -> error("must not capture") }
            rejected("terminal_task_authorization_required") { create(null) }
            rejected("tool_run_authorization_mismatch") {
                create(TaskAuthorization("session", "run", f.authorization.allowedWrites))
            }
            rejected("tool_run_authorization_mismatch") { create(TaskAuthorization("foreign", "run")) }
            rejected("tool_run_owner_mismatch") { create(f.authorization, conversation = ConversationId("foreign")) }
            rejected("tool_run_owner_mismatch") { create(f.authorization, caps = SessionCapabilities("foreign", emptySet())) }
            rejected("tool_run_owner_mismatch") { create(f.authorization, run = RunId("foreign")) }
            assertEquals(0, f.opens.get()); assertEquals(0, f.captures.get())
            assertTrue(f.store.transaction { it.isEmpty() })
        }
    }

    @Test fun realRunnerOwnerCannotBeSubstitutedEvenWithMatchingRunIds() {
        Fixture().use { f ->
            f.tools.definitions
            rejected("tool_owner_already_bound") { f.tools.bindOwner(runnerOwner(message)) }
            rejected("tool_run_owner_mismatch") {
                f.tools.execute(message.copy(id = MessageId("foreign-assistant")), listOf(f.step("open"))) { false }
            }
            rejected("tool_run_owner_mismatch") {
                f.tools.start(message.copy(runId = RunId("foreign")), listOf(f.step("open"))) { false }
            }
            assertTrue(f.store.transaction { it.isEmpty() }); assertEquals(0, f.opens.get())
            f.tools.bindOwner(f.token)
            assertTrue(f.dispatch(f.step("open")).single().outcome is DispatchOutcome.Confirmed)
        }
        Fixture(bind = false).use { f ->
            f.binding.bind(runnerOwner(message), f.capabilities)
            rejected("tool_owner_already_bound") { f.tools.bindOwner(f.token) }
            assertEquals(0, f.opens.get()); assertTrue(f.store.transaction { it.isEmpty() })
        }
    }

    @Test fun centralPersistenceUnknownAfterConfirmedOpenPoisonsNewStepsOnBothRoutes() {
        for (start in listOf(false, true)) {
            val backing = InMemoryToolLedgerStore()
            val transactions = AtomicInteger()
            val failing = object : ToolLedgerStore {
                override fun <T> transaction(block: (MutableList<LedgerEntry>) -> T): T {
                    if (transactions.incrementAndGet() == 2) error("finish failed")
                    return backing.transaction(block)
                }
            }
            Fixture(store = failing).use { f ->
                f.tools.definitions
                assertEquals(DispatchOutcome.Unknown("receipt_persistence_failed"), f.dispatch(f.step("open"), start = start).single().outcome)
                assertEquals(1, f.opens.get())
                assertEquals(LedgerState.UNKNOWN, backing.transaction { it.single().state })
                assertEquals(DispatchOutcome.Unknown("terminal_outcome_unknown_no_resend"), f.dispatch(f.step("input"), start = start).single().outcome)
                assertTrue(f.dispatch(f.step("open", "new-open-id"), start = start).single().outcome is DispatchOutcome.Unknown)
                assertEquals(0, f.connection.writes.get()); assertEquals(1, f.opens.get())
            }
        }
    }

    @Test fun reservationUnknownInNonterminalBatchMemberPoisonsTerminalWithoutExecutingIt() {
        for (start in listOf(false, true)) Fixture().use { f ->
            f.tools.definitions
            f.dispatch(f.step("open"), f.audit())
            // Simulate an earlier unconfirmed central reservation, preserving its exact request key.
            f.store.transaction { entries ->
                val index = entries.indexOfFirst { it.callId == "audit" }
                entries[index] = entries[index].copy(state = LedgerState.UNKNOWN, payload = "")
            }
            val before = f.store.transaction { it.toList() }
            val batch = f.dispatch(f.read(), f.audit(), start = start)
            assertTrue(batch[0].outcome is DispatchOutcome.NotExecuted)
            assertEquals(DispatchOutcome.Unknown("unknown_effect_protected"), batch[1].outcome)
            assertEquals(before, f.store.transaction { it.toList() })
            assertEquals(1, f.auditContexts.size); assertTrue(f.connection.callers.isEmpty())
            assertEquals(DispatchOutcome.Unknown("terminal_outcome_unknown_no_resend"), f.dispatch(f.step("input")).single().outcome)
            assertEquals(0, f.connection.writes.get())
        }
    }

    @Test fun terminalDeliveryUnknownNeverAllowsAnotherWriteButReadRemainsASnapshot() {
        Fixture().use { f ->
            f.tools.definitions; f.dispatch(f.step("open"))
            assertTrue(f.dispatch(f.step("input"), f.step("resize")).first().outcome is DispatchOutcome.Unknown)
            assertEquals(1, f.connection.writes.get()); assertEquals(0, f.connection.resizes.get())
            assertEquals(DispatchOutcome.Unknown("terminal_outcome_unknown_no_resend"),
                f.dispatch(f.step("resize", "later-resize")).single().outcome)
            assertTrue(f.dispatch(f.read()).single().outcome is DispatchOutcome.Confirmed)
            assertTrue(f.connection.callers.all { it === f.authorization })
        }
    }

    @Test fun parentReceiptPersistenceBlocksCloseEvenWhenPtyHasAlreadyExited() {
        for (start in listOf(false, true)) {
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val memory = InMemoryToolLedgerStore(); val transactions = AtomicInteger()
            val store = object : ToolLedgerStore {
                override fun <T> transaction(block: (MutableList<LedgerEntry>) -> T): T {
                    if (transactions.incrementAndGet() == 2) {
                        entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                    }
                    return memory.transaction(block)
                }
            }
            val f = Fixture(store = store)
            val failure = AtomicReference<Throwable?>(); val receipt = AtomicReference<DispatchOutcome?>()
            val result = AtomicReference<RunCatalogCloseResult?>(); val done = CountDownLatch(1)
            f.tools.definitions
            val dispatch = worker(failure) { receipt.set(f.dispatch(f.step("open"), start = start).single().outcome) }
            try {
                dispatch.start(); assertTrue(entered.await(5, TimeUnit.SECONDS))
                f.tools.closeWithResult { result.set(it); done.countDown() }
                assertTrue(f.connection.closes.get() >= 1)
                // The adapter has returned and our fake process is already exited. The missing
                // parent receipt commit still forbids both the join observation and Drained.
                assertEquals(0, f.connection.waits.get()); assertNull(result.get())
                assertEquals(DispatchOutcome.NotExecuted("run_closed"), f.dispatch(f.step("input")).single().outcome)
                release.countDown(); joined(dispatch, failure)
                assertTrue(done.await(5, TimeUnit.SECONDS))
                assertTrue(receipt.get() is DispatchOutcome.Confirmed)
                assertEquals(RunCatalogCloseResult.Drained, result.get()); assertEquals(1, f.connection.waits.get())
                var cached: RunCatalogCloseResult? = null
                f.tools.closeWithResult { cached = it }
                assertEquals(result.get(), cached); assertEquals(1, f.connection.waits.get())
            } finally { release.countDown(); dispatch.join(5000); f.close() }
        }
    }

    @Test fun closeSealsBeforeAsyncJoinAndWaitsForTruePtyTermination() {
        val joining = CountDownLatch(1); val exit = CountDownLatch(1)
        val joinThread = AtomicReference<Thread?>()
        val connection = object : Connection() {
            override fun awaitTermination(timeoutMillis: Long): Boolean {
                waits.incrementAndGet(); joinThread.set(Thread.currentThread()); joining.countDown()
                return exit.await(5, TimeUnit.SECONDS)
            }
        }
        val f = Fixture(connection)
        val result = AtomicReference<RunCatalogCloseResult?>(); val done = CountDownLatch(1)
        try {
            f.tools.definitions; f.dispatch(f.step("open"))
            val caller = Thread.currentThread()
            f.tools.closeWithResult { result.set(it); done.countDown() }
            assertTrue(joining.await(5, TimeUnit.SECONDS))
            assertNotSame(caller, joinThread.get()); assertNull(result.get())
            assertTrue(connection.closes.get() >= 1)
            assertEquals(DispatchOutcome.NotExecuted("run_closed"), f.dispatch(f.step("input")).single().outcome)
            assertEquals(0, connection.writes.get())
            exit.countDown(); assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals(RunCatalogCloseResult.Drained, result.get())
        } finally { exit.countDown(); f.close() }
    }

    @Test fun unconfirmedPtyTerminationIsCachedUnknownNeverLegacyDrainedOrRetried() {
        val connection = object : Connection() {
            override fun awaitTermination(timeoutMillis: Long): Boolean { waits.incrementAndGet(); return false }
        }
        Fixture(connection).use { f ->
            f.tools.definitions; f.dispatch(f.step("open"))
            val result = AtomicReference<RunCatalogCloseResult?>(); val done = CountDownLatch(1)
            f.tools.closeWithResult { result.set(it); done.countDown() }
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals(RunCatalogCloseResult.Unknown("terminal_termination_unconfirmed"), result.get())
            var cached: RunCatalogCloseResult? = null; var legacy = false
            f.tools.closeWithResult { cached = it }; f.tools.closeWhenIdle { legacy = true }
            assertEquals(result.get(), cached); assertFalse(legacy); assertEquals(1, connection.waits.get())
        }
    }

    @Test fun closeDuringCaptureWaitsForCreationAndDoesNotOpenOrPublishLateSkills() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val snapshot = AtomicReference<SkillRunSession?>()
        val f = Fixture(capture = { _, auth ->
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
            SkillRunSession.create(auth, setOf(SkillRunSession.READ), emptyList()).also {
                assertFalse(it.availableTools.isEmpty()); snapshot.set(it)
            }
        })
        val failure = AtomicReference<Throwable?>(); val done = CountDownLatch(1)
        val result = AtomicReference<RunCatalogCloseResult?>()
        val creation = worker(failure) { rejected("run_closed") { f.tools.definitions } }
        try {
            creation.start(); assertTrue(entered.await(5, TimeUnit.SECONDS))
            f.tools.closeWithResult { result.set(it); done.countDown() }
            assertNull(result.get()); assertEquals(0, f.opens.get())
            release.countDown(); joined(creation, failure)
            assertTrue(done.await(5, TimeUnit.SECONDS)); assertEquals(RunCatalogCloseResult.Drained, result.get())
            assertTrue(checkNotNull(snapshot.get()).availableTools.isEmpty()); assertEquals(0, f.opens.get())
        } finally { release.countDown(); creation.join(5000); f.close() }
    }

    @Test fun lateOpenAfterCloseIsRetainedStoppedAndJoinedByParent() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val connection = Connection()
        val f = Fixture(connection, open = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); connection })
        val failure = AtomicReference<Throwable?>(); val done = CountDownLatch(1)
        val result = AtomicReference<RunCatalogCloseResult?>()
        f.tools.definitions
        val dispatch = worker(failure) { f.dispatch(f.step("open")) }
        try {
            dispatch.start(); assertTrue(entered.await(5, TimeUnit.SECONDS))
            f.tools.closeWithResult { result.set(it); done.countDown() }
            assertNull(result.get()); assertEquals(0, connection.waits.get())
            release.countDown(); joined(dispatch, failure)
            assertTrue(done.await(5, TimeUnit.SECONDS)); assertEquals(RunCatalogCloseResult.Drained, result.get())
            assertTrue(connection.closes.get() >= 1); assertEquals(1, connection.waits.get()); assertEquals(1, f.opens.get())
        } finally { release.countDown(); dispatch.join(5000); f.close() }
    }
}
