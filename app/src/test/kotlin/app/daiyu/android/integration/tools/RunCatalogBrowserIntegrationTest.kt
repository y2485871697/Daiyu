package app.daiyu.android.integration.tools

import app.daiyu.android.CatalogFixturePort

import app.daiyu.android.assistants.AssistantProfile
import app.daiyu.android.browser.BrowserRunIdentity
import app.daiyu.android.browser.BrowserRunPreparation
import app.daiyu.android.browser.BrowserSettings
import app.daiyu.android.browser.bridge.BrowserCommand
import app.daiyu.android.browser.bridge.BrowserExecutionPort
import app.daiyu.android.browser.bridge.BrowserExecutionResult
import app.daiyu.android.browser.host.BrowserTabLifecycle
import app.daiyu.android.browser.host.BrowserToolSession
import app.daiyu.android.browser.network.BrowserCloseResult
import app.daiyu.android.browser.session.*
import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.*
import app.daiyu.android.domain.tools.*
import app.daiyu.android.skills.SkillRunSession
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Exercises the parent RunCatalogTools, not a hand-built browser dispatcher. Confirmation,
 * binding, registry snapshot, bridge, ledger and browser facade are real. Only native lifetime
 * and close observation are controlled: the latter deliberately avoids Android Handler on JVM.
 */
class RunCatalogBrowserIntegrationTest {
    private val message = Message(MessageId("assistant-message"), ConversationId("conversation"),
        RunId("run"), Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING)
    private val identity = BrowserRunIdentity(message.conversationId, message.runId,
        "assistant-profile", message.id)
    private val profile = AssistantProfile(identity.assistantId, "Test", "")
    private val grant = BrowserGrant(BrowserOwner("run", BrowserOwnerKind.MAIN), BrowserAccess.FULL)
    private val newTab = """{"action":"new_tab"}"""
    private val navigate = """{"action":"navigate","tab_id":0,"url":"https://example.invalid/","timeout_ms":1000}"""
    private val extraEffect = WriteEffect("other-leaf", "write", "target", "exact")

    /** Obtain the opaque token through the same public Runner callback used by production. */
    private fun issuedOwner(): RunExecutionOwner {
        var token: RunExecutionOwner? = null
        val receiver = object : ReceiptBatchToolExecutor {
            override fun bindOwner(owner: RunExecutionOwner) { token = owner }
            override fun execute(message: Message, invocations: List<ToolInvocation>, cancelled: () -> Boolean) =
                error("no model tool calls expected")
        }
        val runner = ChatRunner(CatalogFixturePort { object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {}
            override fun cancel() {}
        } }, object : ConversationStore { override fun save(message: Message) {} },
            BatteryTool { error("no model tool calls expected") }, RunnerOptions(receiptBatchExecutor = receiver)) {}
        assertEquals(RunStatus.COMPLETED, runner.run(message, emptyList()).status)
        return checkNotNull(token)
    }

    private class RecordingStore : ToolLedgerStore {
        private val memory = InMemoryToolLedgerStore()
        val accesses = AtomicInteger()
        var beforeTransaction: (Int) -> Unit = {}
        override fun <T> transaction(block: (MutableList<LedgerEntry>) -> T): T {
            beforeTransaction(accesses.incrementAndGet())
            return memory.transaction(block)
        }
        // Observation must not change the transaction ordinals used by the persistence gate.
        fun rows(): List<LedgerEntry> = memory.transaction { it.toList() }
    }

    private class NativeHost(grant: BrowserGrant) : CatalogBrowserBinding.Host {
        val allocations = AtomicInteger()
        val releases = AtomicInteger()
        val executions = AtomicInteger()
        val requests = AtomicInteger()
        val observations = AtomicInteger()
        var onCreate: () -> Unit = {}
        var onCloseRequest: () -> Unit = {}
        @Volatile var status = BrowserCloseResult(true, pending = setOf("NATIVE_TEARDOWN"))
        override val session = BrowserToolSession(grant, BrowserSessionRegistry(), object : BrowserTabLifecycle {
            override fun create(lease: BrowserTabLease) { allocations.incrementAndGet(); onCreate() }
            override fun close(lease: BrowserTabLease) { releases.incrementAndGet() }
        }, object : BrowserExecutionPort {
            override fun execute(command: BrowserCommand, lease: BrowserTabLease): BrowserExecutionResult {
                executions.incrementAndGet()
                return BrowserExecutionResult("native-result")
            }
        })
        override fun closeWithResult(): BrowserCloseResult {
            requests.incrementAndGet()
            onCloseRequest()
            session.close()
            return status
        }
        override fun closeStatus(): BrowserCloseResult { observations.incrementAndGet(); return status }
    }

    private inner class Fixture(
        bind: Boolean = true,
        authorizationFactory: (Set<WriteEffect>) -> TaskAuthorization = {
            TaskAuthorization("run", "run", it + extraEffect)
        },
        capture: ((TaskAuthorization) -> SkillRunSession)? = null,
        closeResource: (AutoCloseable) -> Unit = { it.close() },
    ) : AutoCloseable {
        val preparation = BrowserRunPreparation.prepare(identity, BrowserSettings(BrowserAccess.FULL),
            grant, setOf("new_tab", "navigate", "list_tabs"),
            listOf(newTab, this@RunCatalogBrowserIntegrationTest.navigate))
        val confirmed = preparation.confirm(preparation.preview)
        val host = NativeHost(grant)
        val binding = CatalogBrowserBinding.create(profile, confirmed) { host }
        val token = issuedOwner()
        val authorization = authorizationFactory(binding.requiredWrites)
        val capabilities = SessionCapabilities("run", setOf("test.audit",
            RunBrowserTools.READ_CAPABILITY, RunBrowserTools.WRITE_CAPABILITY))
        val store = RecordingStore()
        val contexts = mutableListOf<ToolExecutionContext>()
        fun auditTool(name: String = "audit") = object : RegisteredTool {
            override val definition = app.daiyu.android.domain.tools.ToolDefinition(
                name, 1, "Context witness", "test.audit", ToolEffect.READ_ONLY, ToolSchema(emptyList()))
            override fun prepare(arguments: ToolArguments) = PreparedTool.contextual {
                contexts.add(it)
                ExecutionReceipt.Confirmed("audit")
            }
        }
        val registry = ToolRegistry().register(auditTool())
        val captures = AtomicInteger()
        val observationRegistrations = AtomicInteger()
        val observedBinding = AtomicReference<CatalogBrowserBinding?>()
        val browserDone = AtomicReference<((RunCatalogCloseResult) -> Unit)?>()
        val tools = RunCatalogTools(registry, capabilities, ToolLedger(store), message.conversationId, message.runId,
            authorization = authorization, browser = binding,
            observeBrowserClose = { actual, done ->
                observedBinding.set(actual)
                observationRegistrations.incrementAndGet()
                check(browserDone.compareAndSet(null, done)) { "second browser observer" }
            }, closeResource = closeResource) { caps, auth ->
            assertSame(capabilities, caps)
            assertSame(authorization, auth)
            captures.incrementAndGet()
            capture?.invoke(auth) ?: SkillRunSession.create(auth, emptySet(), emptyList())
        }
        init { if (bind) tools.bindOwner(token) }
        fun write(id: String = "write") = ToolInvocation(ToolCallId(id), RunBrowserTools.WRITE_TOOL_NAME, newTab)
        fun navigate(id: String = "navigate") = ToolInvocation(ToolCallId(id), RunBrowserTools.WRITE_TOOL_NAME,
            this@RunCatalogBrowserIntegrationTest.navigate)
        fun read(id: String = "read") = ToolInvocation(ToolCallId(id), RunBrowserTools.READ_TOOL_NAME,
            """{"action":"list_tabs"}""")
        fun audit(id: String = "audit") = ToolInvocation(ToolCallId(id), "audit", "{}")
        fun dispatch(vararg calls: ToolInvocation, start: Boolean = false): List<ToolDispatchResult> =
            if (start) (tools.start(message, calls.toList()) { false } as ReceiptBatchStart.Completed).receipts
            else tools.execute(message, calls.toList()) { false }
        fun deliver(status: BrowserCloseResult) {
            host.status = status
            checkNotNull(browserDone.get())(checkNotNull(binding.closeResult()))
        }
        override fun close() {
            tools.closeWithResult {}
            host.status = BrowserCloseResult(true)
            // Keep cleanup deterministic even if a test assertion failed before observing close.
            browserDone.get()?.invoke(checkNotNull(binding.closeResult()))
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
        thread.join(5000)
        assertFalse("worker did not finish", thread.isAlive)
        failure.get()?.let { throw it }
    }

    @Test fun parentRegistersBrowserInItsRealSnapshotAndUsesOriginalOwnerAndSharedTaskOnBothRoutes() {
        for (start in listOf(false, true)) Fixture().use { f ->
            assertTrue(f.token.owns(message))
            val bound = f.binding.bindOwner(f.token, f.authorization)
            assertSame(f.authorization, bound.taskAuthorization)
            assertSame(f.authorization, f.confirmed.taskAuthorization(f.token, profile.id, f.host.session))
            assertSame(f.host.session, f.binding.session)
            assertEquals(setOf(extraEffect) + f.binding.requiredWrites, f.authorization.allowedWrites)
            assertEquals(listOf("audit"), f.registry.snapshot().definitions.map { it.name })
            assertEquals(0, f.captures.get())
            assertEquals(0, f.host.allocations.get())
            assertEquals(0, f.store.accesses.get())

            val definitions = f.tools.definitions
            assertEquals(setOf("audit", RunBrowserTools.READ_TOOL_NAME, RunBrowserTools.WRITE_TOOL_NAME),
                definitions.map { it.name }.toSet())
            assertSame(definitions, f.tools.definitions)
            assertEquals(1, f.captures.get())
            val registered = f.registry.snapshot().definitions
            bound.tools.forEach { tool ->
                assertSame(tool.definition, registered.single { it.name == tool.definition.name })
            }
            assertFalse(definitions.single { it.name == RunBrowserTools.READ_TOOL_NAME }.sideEffect)
            assertTrue(definitions.single { it.name == RunBrowserTools.WRITE_TOOL_NAME }.sideEffect)
            assertEquals(0, f.host.allocations.get())
            assertTrue(f.store.rows().isEmpty())

            val audit = f.audit()
            val result = f.dispatch(f.write(), f.navigate(), audit, f.read(), start = start)
            assertTrue(result.all { it.outcome is DispatchOutcome.Confirmed })
            assertSame(f.token, f.contexts.single().owner)
            assertSame(f.authorization, f.contexts.single().authorization)
            assertSame(audit, f.contexts.single().invocation)
            assertEquals(1, f.host.allocations.get())
            assertEquals(1, f.host.executions.get()) // Only navigate enters the native execution port.
            assertNotNull(f.binding.session.currentTab)
            assertEquals(setOf("write", "navigate", "audit", "read"), f.store.rows().map { it.callId }.toSet())
            assertTrue(f.store.rows().all { it.state == LedgerState.CONFIRMED })
            assertTrue(f.dispatch(f.write("same-tab-new-id"), f.navigate("same-navigation-new-id"), start = start)
                .all { (it.outcome as DispatchOutcome.Confirmed).replayed })
            assertEquals(1, f.host.allocations.get())
            assertEquals(1, f.host.executions.get())
            // The central ledger records provider aliases without executing either effect again.
            assertEquals(6, f.store.rows().size)
        }
    }

    @Test fun wholeBatchValidationAndFrozenCatalogCannotBeBypassedByASeparateBrowserDispatcher() {
        for (start in listOf(false, true)) Fixture().use { f ->
            f.tools.definitions
            f.registry.register(f.auditTool("late_tool"))
            assertTrue(f.registry.snapshot().definitions.any { it.name == "late_tool" })
            assertFalse(f.tools.definitions.any { it.name == "late_tool" })
            val late = ToolInvocation(ToolCallId("late"), "late_tool", "{}")
            val batches = listOf(
                listOf(f.write(), f.audit(), late),
                listOf(f.write(), f.audit(), f.read().copy(arguments = """{"action":"get_text"}""")),
                listOf(f.write(), f.write("duplicate-effect")),
            )
            val codes = listOf("unknown_tool", "preparation_failed", "duplicate_batch_effect")
            batches.zip(codes).forEach { (batch, code) ->
                assertTrue(f.dispatch(*batch.toTypedArray(), start = start).all {
                    it.outcome == DispatchOutcome.NotExecuted(code)
                })
            }
            assertEquals(0, f.store.accesses.get())
            assertTrue(f.store.rows().isEmpty())
            assertTrue(f.contexts.isEmpty())
            assertEquals(0, f.host.allocations.get())
            assertEquals(0, f.host.executions.get())
            rejected("whole_batch_dispatch_required") { f.tools.execute(f.write()) }
        }
    }

    @Test fun parentRejectsMissingAuthorizationAndBindingRejectsMissingEffectsOrCopiedTaskBeforeCapture() {
        Fixture(bind = false).use { f ->
            rejected("browser_task_authorization_required") {
                RunCatalogTools(f.registry, f.capabilities, ToolLedger(f.store), message.conversationId,
                    message.runId, browser = f.binding) { _, _ -> error("must not capture") }
            }
            assertEquals(0, f.captures.get())
            assertEquals(0, f.host.requests.get())
            assertEquals(0, f.host.allocations.get())
            assertEquals(0, f.store.accesses.get())
        }
        Fixture(bind = false, authorizationFactory = { TaskAuthorization("run", "run") }).use { f ->
            rejected("browser_task_authorization_mismatch") { f.tools.bindOwner(f.token) }
            assertEquals(0, f.captures.get())
            assertEquals(0, f.host.allocations.get())
            assertEquals(0, f.store.accesses.get())
            assertEquals(1, f.host.requests.get()) // Failed parent binding still closes its resource.
        }
        Fixture(bind = false).use { f ->
            val copy = TaskAuthorization("run", "run", f.authorization.allowedWrites)
            f.binding.bindOwner(f.token, copy)
            rejected("browser_task_authorization_mismatch") { f.tools.bindOwner(f.token) }
            assertEquals(0, f.captures.get())
            assertEquals(0, f.host.allocations.get())
            assertEquals(0, f.store.accesses.get())
        }
    }

    @Test fun matchingIdsCannotReplaceTheOpaqueParentOwnerOrItsSharedAuthorization() {
        Fixture().use { f ->
            f.tools.definitions
            val replacement = issuedOwner()
            assertNotSame(f.token, replacement)
            rejected("tool_owner_already_bound") { f.tools.bindOwner(replacement) }
            rejected("browser_owner_already_bound") { f.binding.bindOwner(replacement, f.authorization) }
            rejected("browser_task_authorization_mismatch") {
                f.binding.bindOwner(f.token, TaskAuthorization("run", "run", f.authorization.allowedWrites))
            }
            rejected("tool_run_owner_mismatch") {
                f.tools.execute(message.copy(id = MessageId("other-assistant")), listOf(f.write())) { false }
            }
            rejected("tool_run_owner_mismatch") {
                f.tools.start(message.copy(runId = RunId("other-run")), listOf(f.write())) { false }
            }
            assertEquals(0, f.store.accesses.get())
            assertEquals(0, f.host.allocations.get())
            assertTrue(f.dispatch(f.write()).single().outcome is DispatchOutcome.Confirmed)
        }
    }

    @Test fun revokeFencesFreshCallsAndConfirmedReplayBeforeTheParentsLedger() {
        for (start in listOf(false, true)) Fixture().use { f ->
            f.tools.definitions
            assertTrue(f.dispatch(f.write(), f.read(), start = start).all { it.outcome is DispatchOutcome.Confirmed })
            val rows = f.store.rows()
            val accesses = f.store.accesses.get()
            f.binding.revoke()
            f.binding.revoke()
            for (call in listOf(f.read(), f.read("fresh-read"), f.write(), f.write("fresh-write"))) {
                assertEquals(DispatchOutcome.NotExecuted("preparation_failed"),
                    f.dispatch(call, start = start).single().outcome)
            }
            rejected("browser_binding_closed") { f.binding.bindOwner(f.token, f.authorization) }
            rejected("browser_authorization_revoked") {
                f.confirmed.taskAuthorization(f.token, profile.id, f.host.session)
            }
            assertEquals(rows, f.store.rows())
            assertEquals(accesses, f.store.accesses.get())
            assertEquals(1, f.host.allocations.get())
            assertEquals(0, f.host.requests.get()) // Admission fence is not a fabricated drain.
            assertTrue(f.dispatch(f.audit(), start = start).single().outcome is DispatchOutcome.Confirmed)
        }
    }

    @Test fun closePendingWaitsForInjectedObservationAndCachesDrainedOrUnknownExactlyOnce() {
        for (successful in listOf(true, false)) Fixture().use { f ->
            f.tools.definitions
            val results = mutableListOf<RunCatalogCloseResult>()
            var legacy = 0
            f.tools.closeWithResult { results.add(it) }
            f.tools.closeWhenIdle { legacy++ }
            assertEquals(1, f.host.requests.get())
            assertEquals(1, f.observationRegistrations.get())
            assertSame(f.binding, f.observedBinding.get())
            assertNull(f.binding.closeResult())
            assertTrue(results.isEmpty())
            assertEquals(0, legacy)
            assertEquals(DispatchOutcome.NotExecuted("run_closed"), f.dispatch(f.write()).single().outcome)
            rejected("browser_authorization_revoked") {
                f.confirmed.taskAuthorization(f.token, profile.id, f.host.session)
            }
            val finalStatus = if (successful) BrowserCloseResult(true)
                else BrowserCloseResult(true, failures = setOf("DESTROY_FAILED"))
            f.host.status = finalStatus
            val expected = checkNotNull(f.binding.closeResult())
            // Even a successful host snapshot alone cannot stand in for the parent's observation.
            assertTrue(results.isEmpty())
            f.deliver(finalStatus)
            assertEquals(listOf(expected), results)
            assertEquals(if (successful) 1 else 0, legacy)
            assertEquals(if (successful) RunCatalogCloseResult.Drained
                else RunCatalogCloseResult.Unknown("browser_close_failed"), expected)
            f.tools.closeWithResult { results.add(it) }
            checkNotNull(f.browserDone.get())(RunCatalogCloseResult.Drained) // Duplicate cannot upgrade Unknown.
            assertEquals(listOf(expected, expected), results)
            assertEquals(1, f.observationRegistrations.get())
            assertEquals(1, f.host.requests.get())
        }
    }

    @Test fun closeDuringParentCaptureRetainsLateCreationUntilItIsSealedBeforeBrowserObservation() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val snapshot = AtomicReference<SkillRunSession?>()
        val f = Fixture(capture = { auth ->
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            SkillRunSession.create(auth, setOf(SkillRunSession.READ), emptyList()).also { snapshot.set(it) }
        })
        val failure = AtomicReference<Throwable?>()
        val result = AtomicReference<RunCatalogCloseResult?>()
        val creation = worker(failure) { rejected("run_closed") { f.tools.definitions } }
        try {
            creation.start()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            f.tools.closeWithResult { result.set(it) }
            assertEquals(1, f.host.requests.get())
            assertEquals(0, f.observationRegistrations.get())
            assertNull(result.get())
            release.countDown()
            joined(creation, failure)
            assertTrue(checkNotNull(snapshot.get()).availableTools.isEmpty())
            assertEquals(listOf("audit"), f.registry.snapshot().definitions.map { it.name })
            assertEquals(1, f.observationRegistrations.get())
            assertNull(result.get())
            f.deliver(BrowserCloseResult(true))
            assertSame(RunCatalogCloseResult.Drained, result.get())
            assertEquals(0, f.host.allocations.get())
            assertEquals(0, f.store.accesses.get())
        } finally { release.countDown(); creation.join(5000); f.close() }
    }

    @Test fun parentReceiptPersistenceMustSettleBeforeBrowserCloseCanReleaseTheRunOnEitherRoute() {
        for (start in listOf(false, true)) {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val f = Fixture()
            f.store.beforeTransaction = { ordinal ->
                if (ordinal == 2) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            }
            f.tools.definitions
            val failure = AtomicReference<Throwable?>()
            val receipt = AtomicReference<DispatchOutcome?>()
            val result = AtomicReference<RunCatalogCloseResult?>()
            val dispatch = worker(failure) { receipt.set(f.dispatch(f.write(), start = start).single().outcome) }
            try {
                dispatch.start()
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertEquals(1, f.host.allocations.get()) // Adapter returned; central commit is still pending.
                f.host.status = BrowserCloseResult(true)
                f.tools.closeWithResult { result.set(it) }
                assertEquals(1, f.host.requests.get())
                assertEquals(1, f.host.releases.get())
                assertSame(RunCatalogCloseResult.Drained, f.binding.closeResult())
                assertEquals(0, f.observationRegistrations.get())
                assertNull(result.get())
                assertEquals(DispatchOutcome.NotExecuted("run_closed"),
                    f.dispatch(f.read(), start = start).single().outcome)
                release.countDown()
                joined(dispatch, failure)
                assertTrue(receipt.get() is DispatchOutcome.Confirmed)
                assertEquals(LedgerState.CONFIRMED, f.store.rows().single().state)
                assertEquals(1, f.observationRegistrations.get())
                assertNull(result.get())
                f.deliver(BrowserCloseResult(true))
                assertSame(RunCatalogCloseResult.Drained, result.get())
            } finally { release.countDown(); dispatch.join(5000); f.close() }
        }
    }

    @Test fun inFlightBrowserAllocationIsNotReleasedOrObservedUntilItsLateCleanupCompletes() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val f = Fixture()
        f.host.onCreate = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        f.tools.definitions
        val failure = AtomicReference<Throwable?>()
        val receipt = AtomicReference<DispatchOutcome?>()
        val result = AtomicReference<RunCatalogCloseResult?>()
        val dispatch = worker(failure) { receipt.set(f.dispatch(f.write()).single().outcome) }
        try {
            dispatch.start()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            f.tools.closeWithResult { result.set(it) }
            assertEquals(1, f.host.requests.get())
            assertEquals(0, f.host.releases.get())
            assertEquals(0, f.observationRegistrations.get())
            assertNull(result.get())
            release.countDown()
            joined(dispatch, failure)
            assertEquals(DispatchOutcome.Unknown("browser_execution_unconfirmed"), receipt.get())
            assertEquals(1, f.host.releases.get())
            assertNull(f.binding.session.currentTab)
            assertEquals(LedgerState.UNKNOWN, f.store.rows().single().state)
            assertEquals(1, f.observationRegistrations.get())
            assertNull(result.get())
            f.deliver(BrowserCloseResult(true))
            assertSame(RunCatalogCloseResult.Drained, result.get())
        } finally { release.countDown(); dispatch.join(5000); f.close() }
    }

    @Test fun failedNativeCloseRetainsOriginalCleanupHandleAndUnknownNeverBecomesDrainedAfterRetry() {
        Fixture().use { f ->
            f.tools.definitions
            f.dispatch(f.write())
            f.host.onCloseRequest = { error("native request failed") }
            f.host.status = BrowserCloseResult(true) // Optimistic native status must not hide request failure.
            val results = mutableListOf<RunCatalogCloseResult>()
            var legacy = false
            f.tools.closeWithResult { results.add(it) }
            assertSame(f.host.session, f.binding.session)
            assertEquals(1, f.host.requests.get())
            assertEquals(0, f.host.releases.get())
            assertNotNull(f.binding.session.currentTab) // The original cleanup capability is still needed.
            assertTrue(results.isEmpty())
            assertEquals(RunCatalogCloseResult.Unknown("browser_close_failed"), f.binding.closeResult())
            f.deliver(BrowserCloseResult(true))
            assertEquals(listOf(RunCatalogCloseResult.Unknown("browser_close_failed")), results)
            f.tools.closeWhenIdle { legacy = true }
            assertFalse(legacy)
            assertEquals(1, f.host.requests.get()) // Parent never silently retries or discards Unknown.
            f.host.onCloseRequest = {}
            f.binding.close() // Explicit retry through the same retained binding/session, not a new host.
            assertEquals(2, f.host.requests.get())
            assertEquals(1, f.host.releases.get())
            assertNull(f.binding.session.currentTab)
            assertEquals(RunCatalogCloseResult.Unknown("browser_close_failed"), f.binding.closeResult())
            f.tools.closeWithResult { results.add(it) }
            assertEquals(listOf(results.first(), results.first()), results)
            assertEquals(1, f.observationRegistrations.get())
        }
    }

    @Test fun throwingParentStopStillRevokesAndRetainsTheBindingWithoutClaimingSuccessfulCleanup() {
        val stopped = mutableListOf<AutoCloseable>()
        Fixture(closeResource = { resource ->
            stopped.add(resource)
            if (resource is CatalogBrowserBinding) error("injected stop failure")
            resource.close()
        }).use { f ->
            f.tools.definitions
            f.dispatch(f.write())
            val result = AtomicReference<RunCatalogCloseResult?>()
            var legacy = false
            f.tools.closeWithResult { result.set(it) }
            assertEquals(RunCatalogCloseResult.Unknown("catalog_close_unconfirmed"), result.get())
            assertEquals(1, stopped.count { it === f.binding })
            assertEquals(0, f.observationRegistrations.get())
            assertEquals(0, f.host.requests.get())
            assertNotNull(f.binding.session.currentTab)
            rejected("browser_authorization_revoked") {
                f.confirmed.taskAuthorization(f.token, profile.id, f.host.session)
            }
            f.tools.closeWhenIdle { legacy = true }
            assertFalse(legacy)
            assertEquals(1, stopped.count { it === f.binding })
            // The exact failed resource, rather than a replacement session, remains cleanable.
            assertSame(f.binding, stopped.single { it === f.binding })
            f.binding.close()
            assertEquals(1, f.host.requests.get())
            assertEquals(1, f.host.releases.get())
            f.tools.closeWithResult { assertEquals(result.get(), it) }
        }
    }
}
