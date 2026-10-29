package app.daiyu.android.integration.tools

import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.*
import app.daiyu.android.domain.tools.*
import app.daiyu.android.terminal.*
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real central owner/authorization/ledger; deterministic transports, no shell or network. */
class RunTerminalToolsTest {
    private val hostCwd = File(System.getProperty("java.io.tmpdir")).absoluteFile.toPath().normalize().toFile()
    private fun message() = Message(MessageId("assistant"), ConversationId("conversation"), RunId("run"),
        Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING)

    private fun owner(message: Message = message()): RunExecutionOwner {
        var result: RunExecutionOwner? = null
        val receiver = object : ReceiptBatchToolExecutor {
            override fun bindOwner(owner: RunExecutionOwner) { result = owner }
            override fun execute(message: Message, invocations: List<ToolInvocation>, cancelled: () -> Boolean) = error("unused")
        }
        val runner = ChatRunner(ModelPort { object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {}
            override fun cancel() {}
        } }, object : ConversationStore { override fun save(message: Message) {} }, BatteryTool { error("unused") },
            RunnerOptions(receiptBatchExecutor = receiver)) {}
        runner.run(message, emptyList())
        return checkNotNull(result)
    }

    private open class Connection : TerminalPtyConnection {
        val writes = mutableListOf<Triple<String, Long, ByteArray>>()
        var closed = 0
        var exited = false
        var waits = 0
        override fun read(authorization: TaskAuthorization, offset: Int, limit: Int): JSONObject =
            JSONObject().put("ok", true).put("offset", offset).put("limit", limit).put("stdout", "snapshot")
        override fun write(authorization: TaskAuthorization, requestId: String, sequence: Long, bytes: ByteArray): JSONObject {
            writes.add(Triple(requestId, sequence, bytes.copyOf()))
            return JSONObject().put("execution", "executed").put("pty_delivery", "unknown").put("command_completion", "unknown")
        }
        override fun resize(authorization: TaskAuthorization, requestId: String, sequence: Long, rows: Int, columns: Int) =
            JSONObject().put("execution", "executed")
        override fun interrupt(authorization: TaskAuthorization, requestId: String, sequence: Long) = JSONObject().put("execution", "executed")
        override fun close() { closed++ }
        override fun awaitTermination(timeoutMillis: Long): Boolean { waits++; return exited }
    }
    private class HeldProcess : Process() {
        var exited = false
        var destroys = 0
        override fun getInputStream() = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream() = ByteArrayInputStream(ByteArray(0))
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun waitFor() = 0
        override fun waitFor(timeout: Long, unit: TimeUnit) = exited
        override fun exitValue(): Int { check(exited); return 0 }
        override fun destroy() { destroys++ }
        override fun isAlive() = !exited
    }

    private fun plan(
        generation: String = "generation",
        input: ByteArray = "printf approved\n".toByteArray(),
        sequence: Long = 41,
        launch: TerminalPtyLaunch = TerminalPtyLaunch.Android(listOf("/system/bin/sh"), mapOf("PATH" to "/system/bin"), hostCwd),
    ) = TerminalPtyPlan(ConversationId("conversation"), "session", "run", "environment", generation, launch,
        File(hostCwd, "terminal-test-journal"), "scope", listOf(
            TerminalPtyStep.Open("open"), TerminalPtyStep.Input("input", "host_request", sequence, input),
            TerminalPtyStep.Resize("resize", "host_resize", 90, 30, 100),
        ), listOf(TerminalPtyRead("page", 0, 1024)))

    private class Fixture(
        val plan: TerminalPtyPlan,
        val connection: Connection = Connection(),
        val store: ToolLedgerStore = InMemoryToolLedgerStore(),
        enabled: Boolean = true,
        open: (() -> TerminalPtyConnection)? = null,
        bind: Boolean = true,
    ) {
        val calls = AtomicInteger()
        val authorization = TaskAuthorization("session", "run", plan.effects.values.toSet())
        val capabilities = SessionCapabilities("session", if (enabled) setOf(RunTerminalTools.CAPABILITY) else emptySet())
        val binding = CatalogTerminalBinding.approved(plan, authorization) { calls.incrementAndGet(); open?.invoke() ?: connection }
        val adapter = RunTerminalTools(binding, capabilities, ToolLedger(store))
        val owner = ownerToken()
        val message = Message(MessageId("assistant"), ConversationId("conversation"), RunId("run"),
            Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING)
        val dispatcher = ToolDispatcher(ToolRegistry().also { r -> adapter.registeredTools.forEach(r::register) }.snapshot(), capabilities, ToolLedger(store))
        init { if (bind) adapter.bindOwner(owner) }
        fun invocation(step: String, id: String = step) = ToolInvocation(ToolCallId(id), RunTerminalTools.STEP_TOOL,
            JSONObject().put("step_id", step).toString())
        fun call(step: String, id: String = step) = adapter.execute(message, listOf(invocation(step, id))) { false }.single().outcome
        companion object { private fun ownerToken() = RunTerminalToolsTest().owner() }
    }

    @Test fun approvalRequiresEveryExactEffectAndCapabilityAloneAdvertisesNothing() {
        val plan = plan()
        try {
            CatalogTerminalBinding.approved(plan, TaskAuthorization("session", "run", setOf(plan.effects.getValue("open")))) { Connection() }
            fail("partial plan accepted")
        } catch (_: IllegalArgumentException) {}
        val f = Fixture(plan, enabled = false)
        assertTrue(f.adapter.definitions.isEmpty())
        assertTrue(f.adapter.registeredTools.isEmpty())
        assertEquals(0, f.calls.get())
        f.adapter.close()
        assertEquals(TerminalToolsCloseResult.Drained, f.adapter.awaitClose(1))
    }

    @Test fun preparationIsPureAndSchemaExposesOnlyFiniteHostPlanIds() {
        val f = Fixture(plan())
        val tool = f.adapter.registeredTools.first()
        tool.prepare(tool.definition.schema.parse("""{"step_id":"input"}"""))
        assertEquals(0, f.calls.get())
        assertTrue(f.store.transaction { it.isEmpty() })
        assertTrue(tool.definition.schema.jsonSchema().contains("\"enum\""))
        val forged = ToolInvocation(ToolCallId("forged"), RunTerminalTools.STEP_TOOL,
            """{"step_id":"input","sequence":42,"command":"unapproved"}""")
        assertEquals(DispatchOutcome.NotExecuted("invalid_arguments"),
            f.dispatcher.dispatchOwned(f.authorization, listOf(forged), f.owner).single().outcome)
        assertEquals(0, f.calls.get())
    }

    @Test fun ownerAndSameAuthorizationAreCheckedBeforeReservationAndReplay() {
        val f = Fixture(plan())
        assertTrue(f.call("open") is DispatchOutcome.Confirmed)
        val before = f.store.transaction { it.toList() }
        val copy = TaskAuthorization("session", "run", f.authorization.allowedWrites)
        assertEquals(DispatchOutcome.NotExecuted("tool_run_authorization_mismatch"),
            f.dispatcher.dispatchOwned(copy, listOf(f.invocation("open")), f.owner).single().outcome)
        assertEquals(DispatchOutcome.NotExecuted("tool_run_owner_mismatch"),
            f.dispatcher.dispatchOwned(f.authorization, listOf(f.invocation("open")), owner()).single().outcome)
        assertEquals(before, f.store.transaction { it.toList() })
        assertEquals(1, f.calls.get())
        f.adapter.close()
        assertEquals(DispatchOutcome.NotExecuted("terminal_run_closed"),
            f.dispatcher.dispatchOwned(f.authorization, listOf(f.invocation("open")), f.owner).single().outcome)
    }

    @Test fun unboundAndUnownedInvocationDoNotTouchLedger() {
        val f = Fixture(plan(), bind = false)
        assertEquals(DispatchOutcome.NotExecuted("terminal_owner_not_bound"),
            f.dispatcher.dispatchOwned(f.authorization, listOf(f.invocation("open")), f.owner).single().outcome)
        assertEquals(DispatchOutcome.NotExecuted("tool_execution_context_required"),
            f.dispatcher.dispatch(f.authorization, listOf(f.invocation("open"))).single().outcome)
        assertTrue(f.store.transaction { it.isEmpty() })
        assertEquals(0, f.calls.get())
    }

    @Test fun mismatchedInvocationNameIsRejectedByContextAdmissionNotJustExecution() {
        val f = Fixture(plan())
        val original = f.adapter.registeredTools.first()
        val alias = object : RegisteredTool {
            override val definition = original.definition.copy(name = "wrong_terminal_name")
            override fun prepare(arguments: ToolArguments) = original.prepare(arguments)
        }
        val dispatcher = ToolDispatcher(ToolRegistry().register(alias).snapshot(), f.capabilities, ToolLedger(f.store))
        val call = f.invocation("open").copy(name = alias.definition.name)
        assertEquals(DispatchOutcome.NotExecuted("tool_invocation_mismatch"),
            dispatcher.dispatchOwned(f.authorization, listOf(call), f.owner).single().outcome)
        assertTrue(f.store.transaction { it.isEmpty() })
        assertEquals(0, f.calls.get())
    }

    @Test fun mismatchedPreparedArgumentsRejectBeforeAnyLedgerTransaction() {
        val backing = InMemoryToolLedgerStore()
        var transactions = 0
        val counting = object : ToolLedgerStore {
            override fun <T> transaction(block: (MutableList<LedgerEntry>) -> T): T {
                transactions++
                return backing.transaction(block)
            }
        }
        val f = Fixture(plan(), store = counting)
        val original = f.adapter.registeredTools.first()
        val swapped = object : RegisteredTool {
            override val definition = original.definition
            override fun prepare(arguments: ToolArguments) = original.prepare(definition.schema.parse("""{"step_id":"open"}"""))
        }
        val dispatcher = ToolDispatcher(ToolRegistry().register(swapped).snapshot(), f.capabilities, ToolLedger(counting))
        assertEquals(DispatchOutcome.NotExecuted("tool_invocation_mismatch"),
            dispatcher.dispatchOwned(f.authorization, listOf(f.invocation("input")), f.owner).single().outcome)
        assertEquals(0, transactions)
        assertEquals(0, f.calls.get())
    }

    @Test fun oneShotPlanCannotBeApprovedIntoAnotherBinding() {
        val plan = plan()
        val f = Fixture(plan)
        try {
            CatalogTerminalBinding.approved(plan, f.authorization) { Connection() }
            fail("same frozen plan reopened")
        } catch (_: IllegalStateException) {}
        f.adapter.close()
        assertEquals(TerminalToolsCloseResult.Drained, f.adapter.awaitClose(1))
        assertEquals(0, f.calls.get())
    }

    @Test fun wholeBatchValidationAndCancellationPrecedeAnyOpen() {
        val f = Fixture(plan())
        val bad = f.invocation("not_approved", "bad")
        val results = f.dispatcher.dispatchOwned(f.authorization, listOf(f.invocation("open"), bad), f.owner)
        assertTrue(results.all { it.outcome is DispatchOutcome.NotExecuted })
        assertTrue(f.store.transaction { it.isEmpty() })
        assertEquals(DispatchOutcome.NotExecuted("cancelled_before_execution"),
            f.dispatcher.dispatchOwned(f.authorization, listOf(f.invocation("open")), f.owner) { true }.single().outcome)
        assertEquals(0, f.calls.get())
    }

    @Test fun hostInputAndSequencesAreFrozenAndUnknownNeverResendsOrReopens() {
        val input = "printf approved\n".toByteArray()
        val argv = mutableListOf("/system/bin/sh")
        val env = mutableMapOf("PATH" to "/system/bin")
        val launch = TerminalPtyLaunch.Android(argv, env, hostCwd)
        val plan = plan(input = input, launch = launch)
        val originalEffect = plan.effects.getValue("input")
        input.fill(0); argv[0] = "/different"; env["PATH"] = "/different"
        assertEquals(listOf("/system/bin/sh"), launch.argv)
        assertEquals("/system/bin", launch.environment["PATH"])
        assertEquals(originalEffect, plan.effects.getValue("input"))
        val f = Fixture(plan)
        assertTrue(f.call("open") is DispatchOutcome.Confirmed)
        assertTrue((f.call("open", "another_model_call") as DispatchOutcome.Confirmed).replayed)
        assertEquals(1, f.calls.get())
        assertTrue(f.call("input", "model_random_id") is DispatchOutcome.Unknown)
        val written = f.connection.writes.single()
        assertEquals("host_request", written.first)
        assertEquals(41L, written.second)
        assertArrayEquals("printf approved\n".toByteArray(), written.third)
        assertTrue(f.call("input", "retry") is DispatchOutcome.Unknown)
        assertTrue(f.call("resize") is DispatchOutcome.Unknown)
        assertEquals(1, f.connection.writes.size)
        assertEquals(1, f.calls.get())
        val read = ToolInvocation(ToolCallId("read"), RunTerminalTools.READ_TOOL, """{"read_id":"page"}""")
        val snapshot = f.adapter.execute(f.message, listOf(read)) { false }.single().outcome as DispatchOutcome.Confirmed
        assertEquals(1024, JSONObject(snapshot.payload).getInt("limit"))
    }

    @Test fun backendGenerationInputAndSequenceArePartOfExactApproval() {
        val original = plan().effects
        assertNotEquals(original, plan(generation = "other").effects)
        assertNotEquals(original, plan(input = "different\n".toByteArray()).effects)
        assertNotEquals(original, plan(sequence = 42).effects)
        assertNotEquals(original, plan(launch = TerminalPtyLaunch.Android(listOf("/other/sh"), emptyMap(), hostCwd)).effects)
    }

    /** Real dispatcher-issued context, with cancellation gated only at the binding entry.
     * A fresh test ledger lets these tests exercise the binding fence/claim independently of replay. */
    private fun dispatchAtBinding(f: Fixture, read: Boolean = false, cancelled: () -> Boolean): DispatchOutcome {
        val original = f.adapter.registeredTools.single {
            it.definition.name == if (read) RunTerminalTools.READ_TOOL else RunTerminalTools.STEP_TOOL
        }
        var inBinding = false
        val tool = object : RegisteredTool {
            override val definition = original.definition
            override fun prepare(arguments: ToolArguments) = PreparedTool.contextual(
                writeEffect = if (read) null else f.plan.effects.getValue("open"),
                admit = { context -> f.binding.admit(context, definition.name, definition.schema, arguments) },
                execute = { context ->
                    inBinding = true
                    try {
                        if (read) f.binding.read(f.plan.reads.single(), context)
                        else f.binding.execute(f.plan.steps.first(), context)
                    } finally { inBinding = false }
                })
        }
        val invocation = if (read) ToolInvocation(ToolCallId("read"), RunTerminalTools.READ_TOOL, """{"read_id":"page"}""")
            else f.invocation("open")
        return ToolDispatcher(ToolRegistry().register(tool).snapshot(), f.capabilities, ToolLedger(InMemoryToolLedgerStore()))
            .dispatchOwned(f.authorization, listOf(invocation), f.owner) { inBinding && cancelled() }.single().outcome
    }

    private fun assertBlockingCancellationDoesNotHoldCloseLock(read: Boolean) {
        val reads = AtomicInteger()
        val connection = object : Connection() {
            override fun read(authorization: TaskAuthorization, offset: Int, limit: Int): JSONObject {
                reads.incrementAndGet()
                return super.read(authorization, offset, limit)
            }
        }
        val f = Fixture(plan(), connection)
        if (read) assertTrue(f.call("open") is DispatchOutcome.Confirmed)
        // Even an already-terminated resource cannot make a blocked callback count as drained.
        connection.exited = true
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closeReturned = CountDownLatch(1)
        val result = AtomicReference<DispatchOutcome?>()
        val failure = AtomicReference<Throwable?>()
        val worker = Thread {
            try {
                result.set(dispatchAtBinding(f, read) {
                    entered.countDown()
                    check(release.await(30, TimeUnit.SECONDS))
                    false // close, not cancellation, must fence the subsequent claim/read
                })
            } catch (t: Throwable) { failure.set(t) }
        }
        val closer = Thread {
            try { f.adapter.close() } catch (t: Throwable) { failure.set(t) }
            finally { closeReturned.countDown() }
        }
        try {
            worker.start()
            assertTrue("binding cancellation callback not entered", entered.await(5, TimeUnit.SECONDS))
            closer.start()
            assertTrue("close blocked on caller cancellation callback", closeReturned.await(5, TimeUnit.SECONDS))
            assertTrue(f.adapter.awaitClose(1) is TerminalToolsCloseResult.Unknown)
            assertFalse(f.binding.awaitTermination(1)) // no standalone-dispatch counter to hide a false drain
            assertEquals(if (read) 1 else 0, connection.closed)
            assertEquals(0, connection.waits)
            assertEquals(if (read) 1 else 0, f.calls.get())
            assertEquals(0, reads.get())
        } finally {
            release.countDown()
            worker.join(5000)
            closer.join(5000)
        }
        assertFalse(worker.isAlive)
        assertFalse(closer.isAlive)
        assertNull(failure.get())
        assertEquals(DispatchOutcome.NotExecuted("terminal_run_closed"), result.get())
        assertEquals(if (read) 1 else 0, f.calls.get())
        assertEquals(0, reads.get())
        assertTrue(connection.writes.isEmpty())
        assertEquals(TerminalToolsCloseResult.Drained, f.adapter.awaitClose(1))
    }

    @Test fun blockedExecuteCancellationAllowsCloseButPreventsPrematureDrain() {
        assertBlockingCancellationDoesNotHoldCloseLock(read = false)
    }

    @Test fun blockedReadCancellationAllowsCloseButPreventsPrematureDrain() {
        assertBlockingCancellationDoesNotHoldCloseLock(read = true)
    }

    @Test fun cancellationDoesNotConsumeStepAndValidStepCanOnlyBeClaimedOnce() {
        val f = Fixture(plan())
        assertEquals(DispatchOutcome.NotExecuted("terminal_run_closed"), dispatchAtBinding(f) { true })
        assertEquals(0, f.calls.get())
        assertTrue(dispatchAtBinding(f) { false } is DispatchOutcome.Confirmed)
        assertEquals(DispatchOutcome.NotExecuted("terminal_step_out_of_order_or_consumed"), dispatchAtBinding(f) { false })
        assertEquals(1, f.calls.get())
        f.connection.exited = true
        f.adapter.close()
        assertEquals(TerminalToolsCloseResult.Drained, f.adapter.awaitClose(1))
    }

    @Test fun delayedCancellationCheckRechecksClaimBeforeOpening() {
        val f = Fixture(plan())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val claimed = CountDownLatch(1)
        val result = AtomicReference<DispatchOutcome?>()
        val winner = AtomicReference<DispatchOutcome?>()
        val failure = AtomicReference<Throwable?>()
        val worker = Thread {
            try {
                result.set(dispatchAtBinding(f) {
                    entered.countDown()
                    check(release.await(30, TimeUnit.SECONDS))
                    false
                })
            } catch (t: Throwable) { failure.set(t) }
        }
        val contender = Thread {
            try { winner.set(dispatchAtBinding(f) { false }) }
            catch (t: Throwable) { failure.set(t) }
            finally { claimed.countDown() }
        }
        try {
            worker.start()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            contender.start()
            assertTrue(claimed.await(5, TimeUnit.SECONDS))
            assertTrue(winner.get() is DispatchOutcome.Confirmed)
            assertEquals(1, f.calls.get())
        } finally {
            release.countDown()
            worker.join(5000)
            contender.join(5000)
        }
        assertFalse(worker.isAlive)
        assertFalse(contender.isAlive)
        assertNull(failure.get())
        assertEquals(DispatchOutcome.NotExecuted("terminal_step_out_of_order_or_consumed"), result.get())
        assertEquals(1, f.calls.get())
        f.connection.exited = true
        f.adapter.close()
        assertEquals(TerminalToolsCloseResult.Drained, f.adapter.awaitClose(1))
    }

    @Test fun throwingCancellationCheckDoesNotLeakDrainCount() {
        val f = Fixture(plan())
        assertEquals(DispatchOutcome.Unknown("adapter_result_unconfirmed_invalid_state"), dispatchAtBinding(f) { error("cancel callback failed") })
        assertEquals(0, f.calls.get())
        f.adapter.close()
        assertEquals(TerminalToolsCloseResult.Drained, f.adapter.awaitClose(1))
    }

    @Test fun lateOpenStaysOwnedUntilActualTermination() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val connection = Connection()
        val f = Fixture(plan(), connection, open = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); connection })
        val failure = AtomicReference<Throwable?>()
        val worker = Thread { try { f.call("open") } catch (t: Throwable) { failure.set(t) } }
        worker.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        f.adapter.close()
        assertTrue(f.adapter.awaitClose(1) is TerminalToolsCloseResult.Unknown)
        assertEquals(0, connection.waits)
        release.countDown(); worker.join(5000)
        assertFalse(worker.isAlive); assertNull(failure.get())
        assertTrue(connection.closed >= 1)
        assertTrue(f.adapter.awaitClose(1) is TerminalToolsCloseResult.Unknown)
        connection.exited = true
        assertEquals(TerminalToolsCloseResult.Drained, f.adapter.awaitClose(1))
        assertEquals(1, f.calls.get())
    }

    @Test fun startupFailureRetainsOriginalProcessCleanupUntilTrueJoin() {
        val process = HeldProcess()
        val cleanup = PtyStartupCleanup(process) { process.destroy() }
        val f = Fixture(plan(), open = { throw PtyStartupFailure("startup", cleanup) })
        assertTrue(f.call("open") is DispatchOutcome.Unknown)
        f.adapter.close()
        assertTrue(f.adapter.awaitClose(1) is TerminalToolsCloseResult.Unknown)
        assertTrue(process.destroys >= 1)
        assertTrue(f.call("open", "retry") is DispatchOutcome.NotExecuted)
        process.exited = true
        assertEquals(TerminalToolsCloseResult.Drained, f.adapter.awaitClose(1))
        assertEquals(1, f.calls.get())
    }

    @Test fun failedCloseAndFailedWaitRemainRetryableWithoutFalseDrained() {
        val connection = object : Connection() {
            var failWait = true
            override fun close() { super.close(); if (closed == 1) error("close failed") }
            override fun awaitTermination(timeoutMillis: Long): Boolean {
                if (failWait) { failWait = false; error("join failed") }
                return super.awaitTermination(timeoutMillis)
            }
        }
        val f = Fixture(plan(), connection)
        f.call("open"); f.adapter.close()
        assertTrue(f.adapter.awaitClose(1) is TerminalToolsCloseResult.Unknown)
        assertTrue(f.adapter.awaitClose(1) is TerminalToolsCloseResult.Unknown)
        connection.exited = true
        assertEquals(TerminalToolsCloseResult.Drained, f.adapter.awaitClose(1))
        assertTrue(connection.closed >= 3)
    }

    @Test fun pendingCentralReceiptPersistencePreventsDrainedEvenAfterProcessExit() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val backing = InMemoryToolLedgerStore()
        val blocking = object : ToolLedgerStore {
            override fun <T> transaction(block: (MutableList<LedgerEntry>) -> T): T = backing.transaction { entries ->
                val result = block(entries)
                if (entries.any { it.state == LedgerState.CONFIRMED }) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
                result
            }
        }
        val f = Fixture(plan(), store = blocking)
        val failure = AtomicReference<Throwable?>()
        val worker = Thread { try { f.call("open") } catch (t: Throwable) { failure.set(t) } }
        worker.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        f.connection.exited = true
        f.adapter.close()
        assertTrue(f.adapter.awaitClose(1) is TerminalToolsCloseResult.Unknown)
        release.countDown(); worker.join(5000)
        assertFalse(worker.isAlive); assertNull(failure.get())
        assertEquals(TerminalToolsCloseResult.Drained, f.adapter.awaitClose(1))
    }

    @Test fun ledgerCommitFailureFencesLaterWritesAndDoesNotOpenAgain() {
        val backing = InMemoryToolLedgerStore()
        val failing = object : ToolLedgerStore {
            override fun <T> transaction(block: (MutableList<LedgerEntry>) -> T): T = backing.transaction { entries ->
                val result = block(entries)
                if (entries.any { it.state == LedgerState.CONFIRMED }) error("commit failed")
                result
            }
        }
        val f = Fixture(plan(), store = failing)
        assertEquals(DispatchOutcome.Unknown("receipt_persistence_failed"), f.call("open"))
        assertTrue(f.call("open", "retry") is DispatchOutcome.Unknown)
        assertTrue(f.call("input") is DispatchOutcome.Unknown)
        assertTrue(f.connection.writes.isEmpty())
        assertEquals(1, f.calls.get())
    }

    private class LinuxFixture : AutoCloseable {
        val root = Files.createTempDirectory("terminal-binding-test-").toFile()
        private val rootfs = File(root, "rootfs").apply {
            File(this, "bin").mkdirs()
            File(this, "bin/sh").writeText("fixture only; never executed")
        }
        private val proot = File(root, "proot").apply { writeText("fixture only"); check(setExecutable(true)) }
        private val loader = File(root, "loader").apply { writeText("fixture only") }
        val temp = File(root, "temp").apply { mkdirs() }
        val cwd = File(root, "cwd").apply { mkdirs() }
        val expected = LinuxGenerationRef("environment", "generation")
        private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val installation = LinuxInstallation("fixture", expected.environmentId, expected.generation,
            rootfs, proot, hash(proot), loader, hash(loader))
        val store = LinuxEnvironmentStore(File(root, "store")).apply { select(installation) }
        fun freeze() = TerminalPtyLaunch.Linux.freeze(store, expected, temp, cwd)
        fun assertPinned() {
            try { store.clearSelection(); fail("selection not pinned") }
            catch (failure: IllegalStateException) { assertEquals("linux_environment_in_use", failure.message) }
        }
        override fun close() { root.deleteRecursively() }
    }

    @Test fun linuxLaunchIsFrozenBeforeAdvertisingAndHeldThroughActualTermination() {
        assumeTrue(System.getProperty("os.name").startsWith("Linux"))
        LinuxFixture().use { linux ->
            val launch = linux.freeze()
            linux.assertPinned()
            assertTrue(launch.argv.contains(linux.installation.rootfs.path))
            val f = Fixture(plan(launch = launch))
            assertTrue(f.call("open") is DispatchOutcome.Confirmed)
            f.adapter.close()
            assertTrue(f.adapter.awaitClose(1) is TerminalToolsCloseResult.Unknown)
            linux.assertPinned()
            f.connection.exited = true
            assertEquals(TerminalToolsCloseResult.Drained, f.adapter.awaitClose(1))
            linux.store.clearSelection()
        }
    }

    @Test fun linuxFailedStartupKeepsBothOriginalCleanupAndApprovalPinUntilJoin() {
        assumeTrue(System.getProperty("os.name").startsWith("Linux"))
        LinuxFixture().use { linux ->
            val launch = linux.freeze()
            val process = HeldProcess()
            val startup = PtyStartupFailure("failed after process allocation", PtyStartupCleanup(process) { process.destroy() })
            val originalCleanup = LinuxPtyStartupCleanup(startup.cleanup, linux.store.acquire(linux.expected))
            val f = Fixture(plan(launch = launch), open = { throw LinuxPtyStartupFailure(startup, originalCleanup) })
            assertTrue(f.call("open") is DispatchOutcome.Unknown)
            f.adapter.close()
            assertTrue(f.adapter.awaitClose(1) is TerminalToolsCloseResult.Unknown)
            linux.assertPinned()
            process.exited = true
            assertEquals(TerminalToolsCloseResult.Drained, f.adapter.awaitClose(1))
            linux.store.clearSelection()
        }
    }

    @Test fun abandonedLinuxApprovalCanReleaseOnlyItsUnclaimedSnapshot() {
        assumeTrue(System.getProperty("os.name").startsWith("Linux"))
        LinuxFixture().use { linux ->
            val launch = linux.freeze()
            val plan = plan(launch = launch)
            try {
                CatalogTerminalBinding.approved(plan, TaskAuthorization("session", "run")) { Connection() }
                fail("missing authorization accepted")
            } catch (_: IllegalArgumentException) {}
            launch.close()
            linux.store.clearSelection()
        }
    }

    @Test fun nonMonotonicOrReusedControlSequencesAreRejectedBeforeApproval() {
        try {
            TerminalPtyPlan(ConversationId("conversation"), "session", "run", "environment", "generation",
                TerminalPtyLaunch.Android(listOf("/system/bin/sh"), emptyMap(), hostCwd), File(hostCwd, "journal"), "scope",
                listOf(TerminalPtyStep.Open("open"), TerminalPtyStep.Resize("resize", "one", 0, 24, 80),
                    TerminalPtyStep.Interrupt("interrupt", "two", 0)))
            fail("reused control sequence accepted")
        } catch (_: IllegalArgumentException) {}
    }
}
