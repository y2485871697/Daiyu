package app.daiyu.android.integration.tools

import app.daiyu.android.domain.*
import app.daiyu.android.domain.delegation.*
import app.daiyu.android.domain.tools.*
import app.daiyu.android.shizuku.*
import app.daiyu.android.skills.SkillRunSession
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class RunCatalogShizukuTest {
    private class Backend : ShizukuBackend {
        override val identity = ShizukuIdentity(2000, "service", "permission")
        var executions = 0
        var closes = 0
        var delayedClose = false
        var closeResult: ShizukuCloseResult = ShizukuCloseResult.Drained
        var onClose: ((ShizukuCloseResult) -> Unit)? = null
        override fun execute(command: ShizukuCommand): ShizukuDispatch {
            executions++
            return ShizukuDispatch.Dispatched(ShizukuCommandHandle(command.requestId, identity))
        }
        override fun poll(handle: ShizukuCommandHandle, offset: Int, limit: Int) =
            ShizukuCommandPage(ShizukuOutcome.Completed(0), byteArrayOf(), 0, 0, 0, false, true)
        override fun cancel(handle: ShizukuCommandHandle) = ShizukuOutcome.Unknown("cancel_requested")
        override fun close() { closes++ }
        override fun closeWithResult(onResult: (ShizukuCloseResult) -> Unit) {
            close()
            if (delayedClose) onClose = onResult else onResult(closeResult)
        }
    }

    private val message = Message(MessageId("assistant"), ConversationId("conversation"), RunId("run"),
        Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING)
    private fun session(session: String = "session", run: String = "run") = ShizukuRunSession(session, run).apply {
        edit(2000, "printf exact", 1234)
        approve(state)
    }
    private fun call(id: String = "shell", script: String = "printf exact") = ToolInvocation(ToolCallId(id),
        RunShizukuCommands.TOOL_NAME, """{"script":"$script","timeout_ms":1234}""")
    private fun rejected(block: () -> Unit) {
        try { block(); fail("expected fail-closed rejection") }
        catch (_: IllegalArgumentException) {}
        catch (_: IllegalStateException) {}
    }
    private fun emptySkills(caps: SessionCapabilities, auth: TaskAuthorization) =
        SkillRunSession.create(auth, caps.enabled, emptyList())

    @Test fun explicitAuthorizationAndCapabilitiesReachApprovedCommandsAndExistingLedgerRoute() {
        val session = session()
        val backend = Backend()
        val binding = CatalogShizukuBinding.open(session) { uid, _ -> assertEquals(2000, uid); backend }
        val caps = SessionCapabilities("session", setOf(RunShizukuCommands.CAPABILITY))
        val auth = TaskAuthorization("session", "run", session.allowedWrites)
        val token = runnerOwner(message)
        val store = InMemoryToolLedgerStore()
        var captures = 0
        val tools = RunCatalogTools(ToolRegistry(), caps, ToolLedger(store), message.conversationId, message.runId,
            authorization = auth, shizuku = binding) { capturedCaps, capturedAuth ->
            captures++
            assertSame(caps, capturedCaps); assertSame(auth, capturedAuth)
            emptySkills(capturedCaps, capturedAuth)
        }
        try {
            tools.bindOwner(token)
            // Rebinding succeeds only for the exact owner, authorization AND capability snapshot objects.
            binding.commands.bind(token, caps, auth)
            rejected { binding.commands.bind(token, caps, TaskAuthorization("session", "run", session.allowedWrites)) }
            rejected { binding.commands.bind(token, SessionCapabilities("session", caps.enabled), auth) }
            assertEquals(0, backend.executions)
            assertEquals(listOf(RunShizukuCommands.TOOL_NAME), tools.definitions.map { it.name })
            assertTrue(tools.definitions.single().sideEffect)
            assertEquals(1, captures)
            val originalGrants = auth.allowedWrites.toSet()
            val denied = tools.execute(message, listOf(call("different", "printf changed"))) { false }.single()
            assertEquals(DispatchOutcome.NotExecuted("task_write_not_authorized"), denied.outcome)
            assertTrue(store.transaction { it.isEmpty() })
            val first = tools.start(message, listOf(call())) { false } as ReceiptBatchStart.Completed
            assertTrue(first.receipts.single().outcome is DispatchOutcome.Confirmed)
            val replay = tools.execute(message, listOf(call())) { false }.single().outcome as DispatchOutcome.Confirmed
            assertTrue(replay.replayed)
            assertEquals(1, backend.executions)
            assertEquals(originalGrants, auth.allowedWrites)
            assertTrue(session.state.receipt is ExecutionReceipt.Confirmed)
            val entry = store.transaction { it.single() }
            assertEquals("session", entry.sessionId); assertEquals("run", entry.taskId)
            assertEquals(LedgerState.CONFIRMED, entry.state)
        } finally { tools.close() }
    }

    @Test fun shizukuRequiresExplicitAuthorizationAndMatchingSessionAndRun() {
        val session = session()
        val binding = CatalogShizukuBinding.open(session) { _, _ -> Backend() }
        val caps = SessionCapabilities("session", setOf(RunShizukuCommands.CAPABILITY))
        fun create(c: SessionCapabilities = caps, auth: TaskAuthorization? = null) = RunCatalogTools(
            ToolRegistry(), c, ToolLedger(InMemoryToolLedgerStore()), message.conversationId, message.runId,
            authorization = auth, shizuku = binding, capture = ::emptySkills)
        try {
            rejected { create() }
            rejected { create(auth = TaskAuthorization("other", "run")) }
            rejected { create(auth = TaskAuthorization("session", "other")) }
            rejected { create(SessionCapabilities("other", caps.enabled), TaskAuthorization("other", "run")) }
            val foreign = this.session(run = "other")
            CatalogShizukuBinding.open(foreign) { _, _ -> Backend() }.use { wrongBinding ->
                rejected {
                    RunCatalogTools(ToolRegistry(), caps, ToolLedger(InMemoryToolLedgerStore()),
                        message.conversationId, message.runId, authorization = TaskAuthorization("session", "run"),
                        shizuku = wrongBinding, capture = ::emptySkills)
                }
            }
        } finally { binding.close() }
    }

    @Test fun capabilitiesAndApprovalNeverManufactureTaskWriteGrants() {
        for (enabled in listOf(true, false)) {
            val session = session()
            val backend = Backend()
            val binding = CatalogShizukuBinding.open(session) { _, _ -> backend }
            val caps = SessionCapabilities("session", if (enabled) setOf(RunShizukuCommands.CAPABILITY) else emptySet())
            val auth = TaskAuthorization("session", "run", if (enabled) emptySet() else session.allowedWrites)
            val store = InMemoryToolLedgerStore()
            val tools = RunCatalogTools(ToolRegistry(), caps, ToolLedger(store), message.conversationId, message.runId,
                authorization = auth, shizuku = binding, capture = ::emptySkills)
            try {
                tools.bindOwner(runnerOwner(message))
                assertEquals(enabled, tools.definitions.any { it.name == RunShizukuCommands.TOOL_NAME })
                val result = tools.execute(message, listOf(call())) { false }.single().outcome
                assertEquals(DispatchOutcome.NotExecuted(if (enabled) "task_write_not_authorized" else "capability_disabled"), result)
                assertEquals(0, backend.executions)
                assertTrue(store.transaction { it.isEmpty() })
                if (enabled) assertTrue(auth.allowedWrites.isEmpty())
            } finally { tools.close() }
        }
    }

    @Test fun finalDispatcherUnknownAfterConfirmedAdapterReplyMarksSessionAndDoesNotRetry() {
        for (useStart in listOf(false, true)) {
            val session = session()
            val backend = Backend()
            val binding = CatalogShizukuBinding.open(session) { _, _ -> backend }
            val caps = SessionCapabilities("session", setOf(RunShizukuCommands.CAPABILITY))
            val auth = TaskAuthorization("session", "run", session.allowedWrites)
            val memory = InMemoryToolLedgerStore()
            var transactions = 0
            val failing = object : ToolLedgerStore {
                override fun <T> transaction(block: (MutableList<LedgerEntry>) -> T): T {
                    if (++transactions == 2) error("finish_failed")
                    return memory.transaction(block)
                }
            }
            val tools = RunCatalogTools(ToolRegistry(), caps, ToolLedger(failing), message.conversationId, message.runId,
                authorization = auth, shizuku = binding, capture = ::emptySkills)
            try {
                tools.bindOwner(runnerOwner(message)); tools.definitions
                val receipts = if (useStart) (tools.start(message, listOf(call())) { false } as ReceiptBatchStart.Completed).receipts
                    else tools.execute(message, listOf(call())) { false }
                assertEquals(DispatchOutcome.Unknown("receipt_persistence_failed"), receipts.single().outcome)
                assertEquals(ExecutionReceipt.Unknown("receipt_persistence_failed"), session.state.receipt)
                assertTrue(session.allowedWrites.isEmpty())
                assertEquals(LedgerState.UNKNOWN, memory.transaction { it.single().state })
                assertEquals(DispatchOutcome.Unknown("unknown_effect_protected"),
                    tools.execute(message, listOf(call())) { false }.single().outcome)
                assertTrue(tools.execute(message, listOf(call("fresh"))) { false }.single().outcome is DispatchOutcome.Unknown)
                assertEquals(1, backend.executions)
            } finally { tools.close() }
        }
    }

    @Test fun closeResultIsTerminalCachedAndUnknownNeverCallsLegacyDrainedCallback() {
        for (result in listOf(ShizukuCloseResult.Drained, ShizukuCloseResult.Unknown("remote_cleanup_unconfirmed"))) {
            val session = session()
            val backend = Backend().apply { delayedClose = true }
            val binding = CatalogShizukuBinding.open(session) { _, _ -> backend }
            val tools = RunCatalogTools(ToolRegistry(), SessionCapabilities("session", emptySet()),
                ToolLedger(InMemoryToolLedgerStore()), message.conversationId, message.runId,
                authorization = TaskAuthorization("session", "run"), shizuku = binding, capture = ::emptySkills)
            val replies = mutableListOf<RunCatalogCloseResult>()
            var legacy = 0
            tools.closeWithResult { replies.add(it) }
            tools.closeWhenIdle { legacy++ }
            assertTrue(replies.isEmpty()); assertEquals(0, legacy)
            checkNotNull(backend.onClose).invoke(result)
            val expected = when (result) {
                ShizukuCloseResult.Drained -> RunCatalogCloseResult.Drained
                is ShizukuCloseResult.Unknown -> RunCatalogCloseResult.Unknown(result.reason)
            }
            assertEquals(listOf(expected), replies)
            tools.closeWithResult { replies.add(it) }
            assertEquals(listOf(expected, expected), replies)
            assertEquals(if (result == ShizukuCloseResult.Drained) 1 else 0, legacy)
            assertEquals(1, backend.closes)
        }
    }

    private class NoChildWork : ChildTaskStore {
        val enqueues = AtomicInteger()
        override fun enqueue(parent: app.daiyu.android.domain.runtime.RunExecutionOwner, tasks: List<ChildTaskRecord>) {
            enqueues.incrementAndGet()
            error("closed parent submitted work")
        }
        override fun started(task: ChildTaskRecord) = error("no child work expected")
        override fun finished(task: ChildTaskRecord, status: ChildTaskStatus, text: String, detail: String?) =
            error("no child work expected")
        override fun list(conversationId: String, limit: Int): List<ChildTaskRecord> = emptyList()
    }

    @Test fun closeDuringBackgroundFactorySealsLateResultBeforeReportingDrained() {
        val session = session()
        val backend = Backend()
        val binding = CatalogShizukuBinding.open(session) { _, _ -> backend }
        val caps = SessionCapabilities("session", setOf(RunChildTasks.CAPABILITY, RunShizukuCommands.CAPABILITY))
        val auth = TaskAuthorization("session", "run", session.allowedWrites)
        val grants = auth.allowedWrites.toSet()
        val token = runnerOwner(message)
        val journal = NoChildWork()
        val supervisor = RetainedChildTasks(journal)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closeReturned = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val late = AtomicReference<RunChildTasks?>()
        val bindFailure = AtomicReference<Throwable?>()
        val closeFailure = AtomicReference<Throwable?>()
        val result = AtomicReference<RunCatalogCloseResult?>()
        val admissionAtClose = AtomicReference<DispatchOutcome?>()
        val factoryCalls = AtomicInteger()
        val tools = RunCatalogTools(ToolRegistry(), caps, ToolLedger(InMemoryToolLedgerStore()),
            message.conversationId, message.runId, backgroundFactory = { actualOwner, actualCaps, actualAuth ->
                assertSame(token, actualOwner); assertSame(caps, actualCaps); assertSame(auth, actualAuth)
                factoryCalls.incrementAndGet()
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                val childBinding = supervisor.bind(actualOwner, actualCaps, actualAuth,
                    mapOf("review" to ChildModelDefinition("pool", 1) { error("no model may open") }))
                RunChildTasks(supervisor, childBinding, setOf("review")).also { late.set(it) }
            }, authorization = auth, shizuku = binding, capture = ::emptySkills)
        fun lateAdmission(): DispatchOutcome {
            val dispatcher = ToolDispatcher(ToolRegistry().register(checkNotNull(late.get()).tool()).snapshot(),
                caps, ToolLedger(InMemoryToolLedgerStore()))
            val invocation = ToolInvocation(ToolCallId("late"), RunChildTasks.TOOL_NAME,
                """{"tasks":[{"profile_id":"review","prompt":"review"}]}""")
            return dispatcher.dispatchOwned(auth, listOf(invocation), token).single().outcome
        }
        val worker = Thread {
            try { tools.bindOwner(token); bindFailure.set(AssertionError("late factory published")) }
            catch (failure: Throwable) { bindFailure.set(failure) }
        }
        val closer = Thread {
            try {
                tools.closeWithResult {
                    try { admissionAtClose.set(lateAdmission()); result.set(it) }
                    catch (failure: Throwable) { closeFailure.set(failure) }
                    finally { closed.countDown() }
                }
            } catch (failure: Throwable) { closeFailure.set(failure) }
            finally { closeReturned.countDown() }
        }
        try {
            worker.start()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            closer.start()
            // A dedicated latch proves close returned while the factory is still parked.
            assertTrue(closeReturned.await(5, TimeUnit.SECONDS))
            closeFailure.get()?.let { throw it }
            assertNull(result.get()); assertEquals(1L, closed.count)
            assertEquals(1, backend.closes)
            release.countDown()
            assertTrue(closed.await(5, TimeUnit.SECONDS))
            worker.join(5000); closer.join(5000)
            assertFalse(worker.isAlive); assertFalse(closer.isAlive)
            closeFailure.get()?.let { throw it }
            assertTrue(bindFailure.get() is IllegalStateException)
            assertEquals("run_closed", bindFailure.get()?.message)
            assertEquals(RunCatalogCloseResult.Drained, result.get())
            assertEquals(DispatchOutcome.NotExecuted("child_binding_closed"), admissionAtClose.get())
            assertEquals(0, journal.enqueues.get()); assertEquals(1, factoryCalls.get())
            assertEquals(grants, auth.allowedWrites)
            rejected { tools.definitions }
            rejected { tools.bindOwner(token) }
            var cached: RunCatalogCloseResult? = null
            tools.closeWithResult { cached = it }
            assertEquals(result.get(), cached)
            assertEquals(1, backend.closes)
        } finally {
            release.countDown(); worker.join(5000)
            if (closer.state != Thread.State.NEW) closer.join(5000)
            tools.close(); supervisor.close()
        }
    }

    @Test fun throwingInFlightFactoryEndsWithUnknownAndIsNotRetried() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val attempts = AtomicInteger()
        val result = AtomicReference<RunCatalogCloseResult?>()
        val failure = AtomicReference<Throwable?>()
        val expected = IllegalStateException("factory_failed")
        val tools = RunCatalogTools(ToolRegistry(), SessionCapabilities("session", emptySet()),
            ToolLedger(InMemoryToolLedgerStore()), message.conversationId, message.runId,
            backgroundFactory = { _, _, _ ->
                attempts.incrementAndGet(); entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                throw expected
            }, capture = ::emptySkills)
        val token = runnerOwner(message)
        val worker = Thread {
            try { tools.bindOwner(token) } catch (caught: Throwable) { failure.set(caught) }
        }
        try {
            worker.start(); assertTrue(entered.await(5, TimeUnit.SECONDS))
            tools.closeWithResult { result.set(it) }
            assertNull(result.get())
            release.countDown(); worker.join(5000)
            assertFalse(worker.isAlive); assertSame(expected, failure.get())
            assertEquals(RunCatalogCloseResult.Unknown("catalog_close_unconfirmed"), result.get())
            rejected { tools.bindOwner(token) }
            var cached: RunCatalogCloseResult? = null
            tools.closeWithResult { cached = it }
            assertEquals(result.get(), cached); assertEquals(1, attempts.get())
        } finally { release.countDown(); worker.join(5000); tools.close() }
    }

    @Test fun throwingResourceStopStillClosesOthersAndCachesUnknownWithoutRetry() {
        val session = session()
        val backend = Backend()
        val binding = CatalogShizukuBinding.open(session) { _, _ -> backend }
        val caps = SessionCapabilities("session", setOf(SkillRunSession.READ))
        val auth = TaskAuthorization("session", "run")
        val devices = RunDeviceResources(BatteryTool { error("no device I/O expected") })
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val attempts = AtomicInteger()
        val otherStops = AtomicInteger()
        val replies = java.util.concurrent.CopyOnWriteArrayList<RunCatalogCloseResult>()
        val failure = AtomicReference<Throwable?>()
        lateinit var scope: SkillRunSession
        val tools = RunCatalogTools(ToolRegistry(), caps, ToolLedger(InMemoryToolLedgerStore()),
            message.conversationId, message.runId, deviceResources = devices, authorization = auth,
            shizuku = binding, closeResource = { resource ->
                if (resource === binding) {
                    attempts.incrementAndGet(); entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    error("injected_resource_close_failure")
                }
                resource.close(); otherStops.incrementAndGet()
            }) { actualCaps, actualAuth -> emptySkills(actualCaps, actualAuth).also { scope = it } }
        val token = runnerOwner(message)
        tools.bindOwner(token); tools.definitions
        assertFalse(scope.availableTools.isEmpty())
        val closer = Thread {
            try { tools.closeWithResult { replies.add(it) } }
            catch (caught: Throwable) { failure.set(caught) }
        }
        try {
            closer.start()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            tools.closeWithResult { replies.add(it) }
            assertTrue(replies.isEmpty()); assertEquals(1, attempts.get())
            release.countDown(); closer.join(5000)
            assertFalse(closer.isAlive); failure.get()?.let { throw it }
            val unknown = RunCatalogCloseResult.Unknown("catalog_close_unconfirmed")
            assertEquals(listOf(unknown, unknown), replies.toList())
            assertEquals(2, otherStops.get())
            assertTrue(scope.availableTools.isEmpty())
            rejected { devices.bind(token, auth, caps) }
            // Callback exceptions/reentrant registration cannot suppress other or cached observers.
            tools.closeWithResult {
                tools.closeWithResult { nested -> replies.add(nested) }
                throw IllegalStateException("observer_failed")
            }
            var legacy = 0
            tools.closeWhenIdle { legacy++ }
            tools.close()
            assertEquals(listOf(unknown, unknown, unknown), replies.toList())
            assertEquals(0, legacy); assertEquals(1, attempts.get()); assertEquals(2, otherStops.get())
            assertEquals(0, backend.closes)
        } finally {
            release.countDown(); closer.join(5000)
            tools.close(); binding.close()
        }
    }

    @Test fun closeWaitsForFinalLedgerOutcomeNotOnlyAdapterDrain() {
        val session = session()
        val backend = Backend()
        val binding = CatalogShizukuBinding.open(session) { _, _ -> backend }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val memory = InMemoryToolLedgerStore()
        val transactions = AtomicInteger()
        val store = object : ToolLedgerStore {
            override fun <T> transaction(block: (MutableList<LedgerEntry>) -> T): T {
                if (transactions.incrementAndGet() == 2) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    error("finish_failed")
                }
                return memory.transaction(block)
            }
        }
        val tools = RunCatalogTools(ToolRegistry(), SessionCapabilities("session", setOf(RunShizukuCommands.CAPABILITY)),
            ToolLedger(store), message.conversationId, message.runId,
            authorization = TaskAuthorization("session", "run", session.allowedWrites), shizuku = binding, capture = ::emptySkills)
        tools.bindOwner(runnerOwner(message)); tools.definitions
        val failure = AtomicReference<Throwable?>()
        val worker = Thread {
            try { tools.execute(message, listOf(call())) { false } } catch (caught: Throwable) { failure.set(caught) }
        }
        val closed = AtomicReference<RunCatalogCloseResult?>()
        val receiptAtClose = AtomicReference<ExecutionReceipt?>()
        worker.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            tools.closeWithResult { receiptAtClose.set(session.state.receipt); closed.set(it) }
            assertNull(closed.get())
        } finally { release.countDown() }
        worker.join(5000)
        assertFalse(worker.isAlive); failure.get()?.let { throw it }
        assertEquals(RunCatalogCloseResult.Drained, closed.get())
        assertEquals(ExecutionReceipt.Unknown("receipt_persistence_failed"), receiptAtClose.get())
    }
}
