package app.daiyu.android.integration.tools

import app.daiyu.android.CatalogFixturePort

import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.*
import app.daiyu.android.domain.tools.*
import app.daiyu.android.skills.SkillRunSession
import app.daiyu.android.terminal.*
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
        ChatRunner(CatalogFixturePort { object : ModelSession {
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
        override fun inspectTail(id: String) = JSONObject().put("ok", true).put("job_id", id).put("stdout", "tail log")
        override fun stop(id: String) = JSONObject().put("ok", true).put("job_id", id).put("stopped", false)
        override fun close() { closes.incrementAndGet() }
        override fun awaitOrdinaryTermination(timeoutMillis: Long): Boolean { waits.incrementAndGet(); return exited }
    }
    @Test fun daemonWorkspaceOutlivesCatalogUntilPhysicalExit() {
        val released = AtomicInteger()
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        val daemonBackend = object : Backend() {
            override fun awaitJobTermination(id: String, timeoutMillis: Long): Boolean = finished.get()
        }
        val daemonPlan = TerminalJobPlan(message.conversationId, message.runId,
            TerminalPtyLaunch.Android(listOf("/system/bin/sh"), emptyMap(), File(System.getProperty("java.io.tmpdir")).absoluteFile),
            "printf daemon", "daemon", AutoCloseable { released.incrementAndGet() })
        val fixture = Fixture(backend = daemonBackend, suppliedPlan = daemonPlan)
        fixture.call()
        assertEquals(RunCatalogCloseResult.Drained, fixture.close())
        assertEquals(0, released.get())
        finished.set(true)
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (released.get() == 0 && System.nanoTime() < until) Thread.sleep(10)
        assertEquals(1, released.get())
    }

    private inner class Fixture(val backend: Backend = Backend(), mode: String = "async",
        store: ToolLedgerStore = InMemoryToolLedgerStore(), enabled: Boolean = true,
        create: (() -> TerminalJobTransport)? = null, suppliedPlan: TerminalJobPlan? = null) {
        val plan = suppliedPlan ?: plan(mode)
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
    @Test fun jobsInSameRunHaveIndependentTransportJournals() {
        val first = plan()
        val second = plan()
        assertEquals(first.sessionId, second.sessionId)
        assertNotEquals(first.transportScope, second.transportScope)
        val directory = java.nio.file.Files.createTempDirectory("job-journals").toFile()
        try {
            var opens = 0
            for (plan in listOf(first, second)) {
                val journal = hostJournal(directory, plan.transportScope)
                val result = journal.once("open", "terminal_open", JSONObject()) {
                    opens++
                    JSONObject().put("ok", true).put("session_id", plan.transportScope)
                }
                assertEquals(plan.transportScope, result.getString("session_id"))
            }
            assertEquals(2, opens)
        } finally { directory.deleteRecursively() }
    }

    @Test fun rootDaemonIsRejectedBeforeTransportWithoutAppUidFallback() {
        val appPlan = plan()
        try {
            TerminalJobPlan(message.conversationId, message.runId, appPlan.launch, appPlan.command, "daemon",
                androidConfiguration = appPlan.configuration.copy(root = true))
            fail("unsupported Root daemon admitted")
        } catch (expected: IllegalArgumentException) {
            assertEquals("android_root_daemon_not_supported", expected.message)
        }
    }

    @Test fun hostRootJobConfigurationIsFrozenAndSeparatedFromAppUidGrant() {
        val appPlan = plan()
        val rootConfiguration = appPlan.configuration.copy(root = true, rootExecutable = "/system/bin/su")
        val rootPlan = TerminalJobPlan(message.conversationId, message.runId, appPlan.launch,
            appPlan.command, appPlan.mode, androidConfiguration = rootConfiguration)
        val otherSu = TerminalJobPlan(message.conversationId, message.runId, appPlan.launch,
            appPlan.command, appPlan.mode, androidConfiguration = rootConfiguration.copy(rootExecutable = "/sbin/su"))
        assertTrue(rootPlan.configuration.root)
        assertEquals("/system/bin/su", rootPlan.configuration.rootExecutable)
        assertNotEquals(appPlan.effect.target, rootPlan.effect.target)
        assertNotEquals(rootPlan.effect.target, otherSu.effect.target)
        assertEquals(appPlan.command, rootPlan.command)
    }

    @Test fun androidJobRejectsConfigurationFromAnotherWorkspace() {
        val appPlan = plan()
        try {
            TerminalJobPlan(message.conversationId, message.runId, appPlan.launch, appPlan.command, appPlan.mode,
                androidConfiguration = appPlan.configuration.copy(workingDirectory = File("/other")))
            fail("mismatched host configuration admitted")
        } catch (expected: IllegalArgumentException) {
            assertEquals("terminal_job_android_configuration_mismatch", expected.message)
        }
    }

    @Test fun defaultConsentDoesNotBroadenFrozenJobCommandOrModeGrant() {
        assertFalse(CommandPermissionDefaults.REQUIRE_INTERACTIVE_APPROVAL)
        val original = plan("async")
        val grant = TaskAuthorization(original.sessionId, original.runId.value, setOf(original.effect))
        val changed = TerminalJobPlan(original.conversationId, original.runId, original.launch,
            original.command + " ", "async")
        for (other in listOf(changed, plan("daemon"))) {
            assertNotEquals(original.effect, other.effect)
            try {
                TerminalJobSession.approved(other, grant) { fail("wrong grant must never create transport"); Backend() }
                fail("frozen job grant leaked")
            } catch (_: IllegalArgumentException) {}
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
    @Test fun confirmedDispatchAcknowledgesAcceptanceNotCompletion() {
        val backend = object : Backend() {
            override fun execute(name: String, arguments: JSONObject): JSONObject =
                super.execute(name, arguments).also { if (name == "terminal_exec") it.put("state", "dispatched") }
        }
        val plan = plan()
        val token = owner()
        val auth = TaskAuthorization("run", "run", setOf(plan.effect))
        val session = TerminalJobSession.approved(plan, auth) { backend }
        val capabilities = SessionCapabilities("run", setOf(TerminalJobSession.CAPABILITY))
        session.bind(token, capabilities, auth)
        val dispatcher = ToolDispatcher(ToolRegistry().apply { session.tools(capabilities).forEach(::register) }.snapshot(),
            capabilities, ToolLedger(InMemoryToolLedgerStore()))
        val result = dispatcher.dispatchOwned(auth, listOf(ToolInvocation(ToolCallId("accepted"),
            TerminalJobSession.START, "{}")), token).single().outcome as DispatchOutcome.Confirmed
        val payload = JSONObject(result.payload)
        assertTrue(payload.getBoolean("accepted"))
        assertFalse(payload.getBoolean("completed"))
        assertEquals(backend.id, payload.getString("job_id"))
        session.close()
        assertTrue(session.awaitCloseOnWorker(1000))
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
        assertEquals("tail log", f.session.logTailOnWorker().getString("stdout"))
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
    private fun linuxJob(test: (File, LinuxEnvironmentStore, TerminalJobPlan) -> Unit) {
        val root = java.nio.file.Files.createTempDirectory("linux-fixed-job-").toFile()
        val rootfs = File(root, "rootfs").apply { mkdirs() }
        File(rootfs, "bin").mkdirs(); File(rootfs, "bin/sh").writeText("test-only; never executed")
        val proot = File(root, "proot").apply { writeText("test-only; never executed"); check(setExecutable(true)) }
        val loader = File(root, "loader").apply { writeText("test-only loader") }
        fun digest(file: File) = java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
        val spec = LinuxInstallation("fixture", "environment", "generation", rootfs, proot, digest(proot), loader, digest(loader))
        val store = hostLinuxStore(File(root, "selection")).apply { select(spec) }
        val launch = TerminalPtyLaunch.Linux.freeze(store, LinuxGenerationRef("environment", "generation"), root, root)
        try { test(root, store, TerminalJobPlan(message.conversationId, message.runId, launch, "printf 'fixed'", "async")) }
        finally { root.deleteRecursively() }
    }
    private fun pinCount(root: File) = File(root, "selection").listFiles()!!.count { it.name.startsWith(".runner-pin-") }

    @Test fun linuxFixedCommandPinsBeforeFactoryAndOnlyOriginalOrdinaryDrainSettlesIt() = linuxJob { root, store, plan ->
        val backend = Backend().apply { exited = false }
        val f = Fixture(backend, suppliedPlan = plan, create = {
            assertEquals(1, pinCount(root))
            assertEquals("DENIED", runLinuxPinChild(File(root, "selection"), "admit"))
            backend
        })
        assertEquals(0, pinCount(root))
        assertTrue(f.call() is DispatchOutcome.Unknown)
        assertEquals(1, pinCount(root))
        assertTrue(f.close() is RunCatalogCloseResult.Unknown)
        assertEquals(1, pinCount(root))
        backend.exited = true
        assertTrue(f.session.awaitCloseOnWorker(1000))
        assertEquals(0, pinCount(root))
        store.clearSelection()
    }

    @Test fun unstartedLinuxJobClosesOnlyItsPreviewLeaseWithoutCreatingAPin() = linuxJob { root, store, plan ->
        val f = Fixture(suppliedPlan = plan)
        assertEquals(0, pinCount(root)); assertEquals(0, f.creates.get())
        assertEquals(RunCatalogCloseResult.Drained, f.close())
        assertEquals(0, pinCount(root)); store.clearSelection()
    }

    @Test fun foreignPinAfterApprovalPreventsEvenTheCommandBackendFactory() = linuxJob { root, _, plan ->
        val f = Fixture(suppliedPlan = plan)
        assertEquals("CLAIMED", runLinuxPinChild(File(root, "selection"), "claim"))
        assertTrue(f.call() is DispatchOutcome.Unknown)
        assertEquals(0, f.creates.get()); assertTrue(f.backend.calls.isEmpty())
        assertEquals(RunCatalogCloseResult.Drained, f.close())
        assertEquals(1, pinCount(root))
    }

    @Test fun knownUnstartedFactoryFailureReleasesOnlyAfterOriginalDrain() = linuxJob { root, store, plan ->
        val f = Fixture(suppliedPlan = plan, create = {
            assertEquals(1, pinCount(root))
            throw TerminalJobNotStarted(java.io.IOException("configuration failed before any process"))
        })
        assertTrue(f.call() is DispatchOutcome.NotExecuted)
        assertEquals(1, pinCount(root))
        assertEquals(RunCatalogCloseResult.Drained, f.close())
        assertEquals(0, pinCount(root)); store.clearSelection()
    }

    @Test fun failedCloseKeepsOriginalPinAndExplicitDrainCanRetry() = linuxJob { root, store, plan ->
        var allowClose = false
        val backend = object : Backend() {
            override fun close() {
                super.close()
                if (!allowClose) throw java.io.IOException("close failed")
            }
        }
        val f = Fixture(backend, suppliedPlan = plan)
        assertTrue(f.call() is DispatchOutcome.Unknown)
        assertEquals(RunCatalogCloseResult.Unknown("terminal_job_drain_unconfirmed"), f.close())
        assertEquals(1, pinCount(root))
        allowClose = true
        assertTrue(f.session.awaitCloseOnWorker(1000))
        assertEquals(0, pinCount(root)); store.clearSelection()
    }

    @Test fun daemonRetainsWorkspaceUntilOriginalJobDrain() {
        val root = java.nio.file.Files.createTempDirectory("chroot-daemon-pins-").toFile().canonicalFile
        val workspaceCloses = AtomicInteger()
        val jobExited = java.util.concurrent.atomic.AtomicBoolean(false)
        val observed = CountDownLatch(1)
        val backend = object : TerminalJobTransport {
            override fun execute(name: String, arguments: JSONObject) = if (name == "terminal_open")
                JSONObject().put("ok", true).put("session_id", "s") else
                JSONObject().put("ok", true).put("execution", "unknown").put("job_id", "11111111-1111-1111-1111-111111111111")
            override fun inspect(id: String) = JSONObject().put("ok", true)
            override fun stop(id: String) = JSONObject().put("ok", true)
            override fun close() { }
            override fun awaitOrdinaryTermination(timeoutMillis: Long) = true
            override fun awaitJobTermination(id: String, timeoutMillis: Long): Boolean { observed.countDown(); return jobExited.get() }
        }
        val launch = TerminalPtyLaunch.Android(listOf("/system/bin/sh"), emptyMap(), root)
        val plan = TerminalJobPlan(message.conversationId, message.runId, launch, "cat; printf fixed", "daemon",
            AutoCloseable { workspaceCloses.incrementAndGet() })
        try {
            val f = Fixture(suppliedPlan = plan, create = { backend })
            assertTrue(f.call() is DispatchOutcome.Unknown)
            assertTrue(f.close() is RunCatalogCloseResult.Drained)
            assertTrue(observed.await(2000, TimeUnit.MILLISECONDS))
            assertEquals(0, workspaceCloses.get())
            jobExited.set(true)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (workspaceCloses.get() == 0 && System.nanoTime() < deadline) Thread.sleep(10)
            assertEquals(1, workspaceCloses.get())
            assertTrue(f.session.awaitCloseOnWorker(1000))
            assertEquals(1, workspaceCloses.get())
        } finally { jobExited.set(true); root.deleteRecursively() }
    }

}
