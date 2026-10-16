package app.daiyu.android

import app.daiyu.android.ControllerVirtualDisplayRuntime.LeaseStore
import app.daiyu.android.ControllerVirtualDisplayRuntime.Mutation
import app.daiyu.android.ControllerVirtualDisplayRuntime.OwnerAdapter
import app.daiyu.android.ControllerVirtualDisplayRuntime.WritePoint
import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.RunExecutionOwner
import app.daiyu.android.domain.tools.*
import app.daiyu.android.integration.tools.*
import app.daiyu.android.vd.*
import app.daiyu.android.vd.runtime.OwnerProtocol
import app.daiyu.android.vd.runtime.OwnerV2Contract
import app.daiyu.android.vdapproval.VirtualDisplayApprovalRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

/** Real private filesystem + real v2 client with scripted transport. No su, shell, socket, display,
 * network, production controller, or fake durable journal. Tests are sources, not a pass claim.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], manifest = Config.NONE)
class ControllerVirtualDisplayRuntimeTest {
    private class Fixture : AutoCloseable {
        val base: Path = Files.createTempDirectory("r08-runtime-").toRealPath()
        val root: Path = base.resolve("leases")
        val gate = Semaphore(1)
        val owner = runnerOwner(Message(MessageId("assistant"), ConversationId("conversation"), RunId("run"),
            Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING))
        val capabilities = SessionCapabilities("session", setOf(R08VirtualDisplayCatalogLeaf.CAPABILITY))
        val writes = listOf(R08VirtualDisplayWrite("start", R08VirtualDisplayAction.Start),
            R08VirtualDisplayWrite("initialize", R08VirtualDisplayAction.Initialize))
        val executor = Executors.newSingleThreadExecutor()
        fun plan(generation: String) = R08VirtualDisplayPlan.prepare(owner, capabilities, generation, writes)
        fun store(fault: (Mutation, WritePoint) -> Unit = { _, _ -> }) = LeaseStore({ root }, gate, fault)
        fun <T> work(block: () -> T): T = executor.submit<T> { block() }.get(10, TimeUnit.SECONDS)
        override fun close() {
            executor.shutdownNow()
            // Test-only deletion after references stop executing, never a production recovery path.
            Files.walk(base).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }

    private class Wire(private val reply: (JSONObject) -> String) : OwnerTransport {
        val sent = mutableListOf<JSONObject>()
        override fun exchange(line: String): String {
            OwnerProtocol.parseWireRequest(line)
            val request = JSONObject(line)
            sent += request
            return reply(request)
        }
        override fun disconnect() = Unit
        fun operations() = sent.map { it.getString("op") }
    }
    private class Host(val wire: Wire) : ControllerVirtualDisplayRuntime.Host {
        var starts = 0
        var failStart = false
        override var client: VirtualDisplayOwnerClient? = null
        var observation = AndroidVirtualDisplayOwnerHost.Observation(
            AndroidVirtualDisplayOwnerHost.Lifecycle.NOT_STARTED, false, null, null, false)
        override fun start(generation: String, binding: OwnerRunBinding): AndroidVirtualDisplayOwnerHost.Start {
            starts++
            if (failStart) throw IOException("private launch failure")
            client = VirtualDisplayOwnerClient(generation, binding,
                OwnerCredential("0123456789abcdef0123456789abcdef"), wire)
            observation = AndroidVirtualDisplayOwnerHost.Observation(
                AndroidVirtualDisplayOwnerHost.Lifecycle.UNKNOWN, false, null, 99, false)
            return AndroidVirtualDisplayOwnerHost.Start.READY
        }
        override fun observe() = observation
        fun drained() {
            observation = AndroidVirtualDisplayOwnerHost.Observation(
                AndroidVirtualDisplayOwnerHost.Lifecycle.DRAINED, true, 0, 99, true)
        }
    }
    private class Parent : ControllerVirtualDisplayRuntime.Parent {
        val shown = LinkedBlockingQueue<VirtualDisplayApprovalRequest>()
        var stops = 0
        var auth: TaskAuthorization? = null
        override fun showApproval(request: VirtualDisplayApprovalRequest) { shown.add(request) }
        override fun combinedAuthorization(owner: RunExecutionOwner, capabilities: SessionCapabilities,
                                           approvedEffects: Set<WriteEffect>): TaskAuthorization =
            TaskAuthorization(capabilities.sessionId, owner.runId.value, approvedEffects).also { auth = it }
        override fun stopAcceptedRun(owner: RunExecutionOwner) { stops++ }
        override fun beginBatch(leaf: R08VirtualDisplayCatalogLeaf, originalWholeBatch: List<ToolInvocation>):
            ControllerVirtualDisplaySession.BatchCompletion {
            val originalTicket = leaf.beginBatch(originalWholeBatch)
            return ControllerVirtualDisplaySession.BatchCompletion { leaf.finishBatch(originalTicket, it) }
        }
    }
    private fun miss() = OwnerProtocol.ok("receipt", null).put("receiptOnly", true)
        .put("executed", false).put("found", false).toString()
    private fun shutdownSuccess() = OwnerProtocol.ok("shutdown_uninitialized", null)
        .put("initialized", false).put("receiptsClosed", true).toString()
    private fun normalWire() = Wire { if (it.getString("op") == "receipt") miss() else shutdownSuccess() }
    private fun files(root: Path): Map<String, List<Byte>> = if (!Files.exists(root)) emptyMap() else Files.walk(root).use { paths ->
        paths.filter { Files.isRegularFile(it) && it.fileName.toString() != "lease.lock" }.toList().associate {
            root.relativize(it).toString() to Files.readAllBytes(it).toList()
        }
    }
    private fun rejected(block: () -> Unit) {
        val error = assertThrows(IOException::class.java) { block() }
        assertEquals("virtual_display_runtime_unknown", error.message)
        assertNull(error.cause)
    }

    @Test fun leaseAndJournalAreDurableAndProcessGateRejectsBeforeAnyExecution() = Fixture().use { f ->
        val stages = mutableListOf<WritePoint>()
        val store = f.store { kind, point -> if (kind == Mutation.RESERVE) stages += point }
        assertFalse(Files.exists(f.root))
        val lease = store.reserve(f::plan)
        assertTrue(lease.durable)
        assertEquals(WritePoint.entries.toSet() - setOf(WritePoint.BEFORE_UNLOCK, WritePoint.BEFORE_CLOSE),
            stages.toSet())
        val completed = listOf(WritePoint.DIRECTORY_CREATED, WritePoint.JOURNAL_CREATED,
            WritePoint.PENDING_CREATED, WritePoint.FILE_SYNCED, WritePoint.PUBLISHED, WritePoint.DIRECTORY_SYNCED)
        assertEquals(completed, stages.filter { it in completed })
        assertEquals(0, f.gate.availablePermits())
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(f.root))
        val record = lease.directory.resolve("claim.event")
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(record))
        assertFalse(Files.exists(lease.directory.resolve("claim.pending")))
        rejected { f.store().reserve(f::plan) }
        assertTrue(lease.durable) // same-process gate check only; OS exclusion is tested in a child JVM
        val intent = lease.plan.intent("actual-call", lease.plan.writes.first())
        assertEquals(R08VirtualDisplayPermit.EXECUTE_ONCE, lease.journal.claim(intent))
        assertEquals(R08VirtualDisplayPermit.LOOKUP_ONLY,
            R08PersistentJournal(lease.directory.resolve("journal")).claim(intent))
        lease.releaseAfterDrain()
        assertFalse(lease.durable)
        assertEquals(1, f.gate.availablePermits())
        val next = f.store().reserve(f::plan)
        assertNotEquals(lease.generation, next.generation)
        assertTrue(Files.exists(record)) // release never erases claims or journal evidence
        assertTrue(Files.exists(lease.directory.resolve("released.event")))
        next.releaseAfterDrain()
    }

    @Test fun everyReserveInterruptionRetainsAcquiredGateAndNeverRetriesOrReplacesEvidence() {
        for (point in WritePoint.entries.filter { it !in setOf(WritePoint.BEFORE_UNLOCK, WritePoint.BEFORE_CLOSE) }) Fixture().use { f ->
            val store = f.store { kind, at -> if (kind == Mutation.RESERVE && at == point) throw IOException("private path") }
            rejected { store.reserve(f::plan) }
            val before = files(f.root)
            rejected { store.reserve(f::plan) }
            rejected { f.store().reserve(f::plan) }
            assertEquals(before, files(f.root))
            assertEquals(0, f.gate.availablePermits())
            // No copied-directory "restart" claim: real process-exit histories have separate tests.
        }
    }

    @Test fun releaseInterruptionsBeforeUnlockRetainLiveOwnershipAndConsumeTheAttempt() {
        for (point in listOf(WritePoint.PENDING_CREATED, WritePoint.BEFORE_FILE_FORCE,
            WritePoint.BEFORE_ATOMIC_MOVE, WritePoint.BEFORE_DIRECTORY_FORCE,
            WritePoint.FILE_SYNCED, WritePoint.PUBLISHED,
            WritePoint.DIRECTORY_SYNCED, WritePoint.BEFORE_UNLOCK)) Fixture().use { f ->
            val store = f.store { kind, at -> if (kind == Mutation.RELEASE && at == point) throw IOException("lost receipt") }
            val lease = store.reserve(f::plan)
            rejected { lease.releaseAfterDrain() }
            val before = files(f.root)
            assertThrows(IllegalStateException::class.java) { lease.releaseAfterDrain() }
            rejected { f.store().reserve(f::plan) }
            assertEquals(before, files(f.root))
            assertEquals(0, f.gate.availablePermits())
            assertTrue(lease.durable) // every point in this table precedes successful unlock
            assertTrue(Files.exists(lease.directory.resolve("claim.event")))
        }
    }

    @Test fun evenGateOrRootProviderFailureConsumesTheOnlyReserveAttempt() = Fixture().use { f ->
        val busy = Semaphore(0)
        var calls = 0
        val blocked = LeaseStore({ calls++; f.root }, busy)
        rejected { blocked.reserve(f::plan) }
        busy.release()
        rejected { blocked.reserve(f::plan) }
        assertEquals(0, calls)
        assertEquals(1, busy.availablePermits()) // failed acquisition did not acquire/release a permit
        val early = LeaseStore({ calls++; throw IOException("private root lookup") }, f.gate)
        rejected { early.reserve(f::plan) }
        rejected { early.reserve(f::plan) }
        rejected { f.store().reserve(f::plan) }
        assertEquals(1, calls)
        assertEquals(0, f.gate.availablePermits())
        assertFalse(Files.exists(f.root))
    }

    @Test fun everyForceAndDirectorySyncInvocationCanFailWithoutRetryOrFallback() {
        val boundaryPoints = setOf(WritePoint.BEFORE_FILE_FORCE, WritePoint.BEFORE_DIRECTORY_FORCE,
            WritePoint.BEFORE_ATOMIC_MOVE)
        val counts = mutableMapOf<Pair<Mutation, WritePoint>, Int>()
        Fixture().use { f ->
            val lease = f.store { kind, point ->
                if (point in boundaryPoints) counts[kind to point] = (counts[kind to point] ?: 0) + 1
            }.reserve(f::plan)
            lease.releaseAfterDrain()
        }
        // Multiple directory forces and both the lock-file and claim-file forces are independent.
        for ((boundary, count) in counts) for (occurrence in 1..count) Fixture().use { f ->
            var reached = 0
            val store = f.store { kind, point ->
                if (kind == boundary.first && point == boundary.second && ++reached == occurrence)
                    throw IOException("injected I/O boundary")
            }
            if (boundary.first == Mutation.RESERVE) {
                rejected { store.reserve(f::plan) }
                val before = files(f.root)
                rejected { store.reserve(f::plan) }
                assertEquals(before, files(f.root))
            } else {
                val lease = store.reserve(f::plan)
                rejected { lease.releaseAfterDrain() }
                val before = files(f.root)
                assertThrows(IllegalStateException::class.java) { lease.releaseAfterDrain() }
                assertEquals(before, files(f.root))
            }
            assertEquals(occurrence, reached) // no later force/move invocation or retry
            assertEquals(0, f.gate.availablePermits())
            rejected { f.store().reserve(f::plan) }
        }
    }

    @Test fun successfulUnlockThenFailedCloseDoesNotClaimToRetainTheOsLock() = Fixture().use { f ->
        var closes = 0
        val lease = f.store { kind, point ->
            if (kind == Mutation.RELEASE && point == WritePoint.BEFORE_CLOSE) {
                closes++
                throw IOException("injected close failure")
            }
        }.reserve(f::plan)
        rejected { lease.releaseAfterDrain() }
        assertFalse(lease.durable) // lock.release succeeded; reference/semaphore retention != lock ownership
        assertEquals(0, f.gate.availablePermits())
        assertTrue(Files.exists(lease.directory.resolve("released.event")))
        assertEquals(0, RuntimeChildJvm.probe(f.base, f.root.resolve("lease.lock")))
        assertThrows(IllegalStateException::class.java) { lease.releaseAfterDrain() }
        rejected { f.store().reserve(f::plan) }
        assertEquals(1, closes)
    }

    @Test fun malformedHistoryAndSymlinkCannotBeRepairedIntoFreshOwnership() = Fixture().use { f ->
        val lease = f.store().reserve(f::plan)
        lease.releaseAfterDrain()
        val claim = lease.directory.resolve("claim.event")
        Files.write(claim, byteArrayOf(1, 2, 3))
        val before = Files.readAllBytes(claim)
        rejected { f.store().reserve(f::plan) }
        assertArrayEquals(before, Files.readAllBytes(claim))
    }

    @Test fun symlinkRootIsNeverFollowedOrRepermissioned() = Fixture().use { f ->
        val victim = f.base.resolve("untouched")
        Files.createDirectory(victim)
        val originalMode = Files.getPosixFilePermissions(victim)
        Files.createSymbolicLink(f.root, victim)
        rejected { f.store().reserve(f::plan) }
        assertTrue(Files.isSymbolicLink(f.root))
        assertEquals(originalMode, Files.getPosixFilePermissions(victim))
        assertEquals(0L, Files.list(victim).use { it.count() })
    }

    @Test fun acceptedParentRetainsFirstAndOnlyExecutedLeafStartsTheRealAdapter() = Fixture().use { f ->
        val parent = Parent()
        val host = Host(normalWire())
        val constructions = AtomicInteger()
        val retained = mutableListOf<ControllerVirtualDisplaySession>()
        val mutableWrites = f.writes.toMutableList()
        val session = ControllerVirtualDisplayRuntime.assemble(f.owner, f.capabilities, mutableWrites,
            emptyList(), parent, { retained += it }, f.store()) { constructions.incrementAndGet(); host }
        assertSame(session, retained.single())
        assertFalse(Files.exists(f.root))
        assertEquals(0, constructions.get())
        mutableWrites.clear()
        val pending = f.executor.submit<TaskAuthorization> { session.authorizationFactory(f.owner) }
        val review = checkNotNull(parent.shown.poll(10, TimeUnit.SECONDS))
        assertEquals(listOf("start", "initialize"), review.rows.map { it.operation })
        assertTrue(files(f.root).keys.any { it.endsWith("claim.event") })
        assertEquals(0, f.gate.availablePermits())
        assertEquals(0, constructions.get())
        assertTrue(session.answerFromUi(review, true))
        val auth = pending.get(10, TimeUnit.SECONDS)
        assertSame(parent.auth, auth)
        assertEquals(1, constructions.get())
        assertEquals(0, host.starts)
        val tools = session.registeredTools(f.owner, f.capabilities, auth)
        assertEquals(0, host.starts)
        f.work {
            val registry = ToolRegistry()
            tools.forEach { registry.register(it) }
            val dispatcher = ToolDispatcher(registry.snapshot(), f.capabilities, ToolLedger(InMemoryToolLedgerStore()))
            val original = listOf(ToolInvocation(ToolCallId("actual-start"), R08VirtualDisplayCatalogLeaf.WRITE_TOOL,
                "{\"step_id\":\"start\"}"))
            val ticket = session.beginBatch(original)
            val results = dispatcher.dispatchOwned(auth, original, f.owner)
            session.finishBatch(ticket, results)
            assertTrue(results.single().outcome is DispatchOutcome.Confirmed)
        }
        assertEquals(1, host.starts)
        assertTrue(host.wire.sent.isEmpty()) // READY/start alone never initialize
        session.close()
        assertEquals(ControllerVirtualDisplaySession.CloseResult.Unknown,
            f.work { session.observeCloseOnWorker(true) })
        assertTrue(f.work { session.shutdownUninitializedOnceOnWorker() })
        assertEquals(listOf("receipt", "shutdown_uninitialized"), host.wire.operations())
        assertEquals(ControllerVirtualDisplaySession.CloseResult.Unknown,
            f.work { session.observeCloseOnWorker(true) }) // reply is not physical exit
        assertEquals(0, f.gate.availablePermits())
        host.drained()
        assertEquals(ControllerVirtualDisplaySession.CloseResult.Unknown,
            f.work { session.observeCloseOnWorker(false) })
        assertEquals(ControllerVirtualDisplaySession.CloseResult.Drained,
            f.work { session.observeCloseOnWorker(true) })
        assertEquals(1, f.gate.availablePermits())
        assertSame(session, retained.single()) // only real parent may now evict retention
    }

    @Test fun deniedApprovalNeverConstructsHostAndCanReleaseNeverStartedLease() = Fixture().use { f ->
        val parent = Parent()
        val constructed = AtomicInteger()
        val session = ControllerVirtualDisplayRuntime.assemble(f.owner, f.capabilities, f.writes,
            emptyList(), parent, {}, f.store()) { constructed.incrementAndGet(); Host(normalWire()) }
        val future = f.executor.submit<TaskAuthorization> { session.authorizationFactory(f.owner) }
        val review = checkNotNull(parent.shown.poll(10, TimeUnit.SECONDS))
        assertTrue(session.answerFromUi(review, false))
        assertThrows(ExecutionException::class.java) { future.get(10, TimeUnit.SECONDS) }
        assertEquals(1, parent.stops)
        assertEquals(0, constructed.get())
        assertEquals(ControllerVirtualDisplaySession.CloseResult.Drained,
            f.work { session.observeCloseOnWorker(true) })
        assertEquals(1, f.gate.availablePermits())
    }

    @Test fun reserveFailureIsRetainedStoppedAndNeverPresentedAsDrained() {
        for (point in listOf(WritePoint.BEFORE_RESERVE_IO, WritePoint.BEFORE_LOCK_OPEN,
            WritePoint.PUBLISHED)) Fixture().use { f ->
            val parent = Parent()
            val retained = mutableListOf<ControllerVirtualDisplaySession>()
            val constructed = AtomicInteger()
            var failures = 0
            val store = f.store { kind, at -> if (kind == Mutation.RESERVE && at == point) {
                failures++
                throw IOException()
            } }
            val session = ControllerVirtualDisplayRuntime.assemble(f.owner, f.capabilities, f.writes,
                emptyList(), parent, { retained += it }, store) { constructed.incrementAndGet(); Host(normalWire()) }
            val future = f.executor.submit<TaskAuthorization> { session.authorizationFactory(f.owner) }
            assertThrows(ExecutionException::class.java) { future.get(10, TimeUnit.SECONDS) }
            assertSame(session, retained.single())
            assertEquals(0, constructed.get())
            assertTrue(parent.shown.isEmpty())
            assertEquals(1, parent.stops)
            repeat(2) {
                assertEquals(ControllerVirtualDisplaySession.CloseResult.Unknown,
                    f.work { session.observeCloseOnWorker(true) })
            }
            rejected { store.reserve(f::plan) }
            rejected { f.store().reserve(f::plan) }
            assertEquals(1, failures)
        }
    }

    @Test fun unlockAndCloseFailuresKeepSessionRetainedUnknownWithoutSecondRelease() {
        for (point in listOf(WritePoint.BEFORE_UNLOCK, WritePoint.BEFORE_CLOSE)) Fixture().use { f ->
            val parent = Parent()
            val retained = mutableListOf<ControllerVirtualDisplaySession>()
            var failures = 0
            val store = f.store { kind, at -> if (kind == Mutation.RELEASE && at == point) {
                failures++
                throw IOException()
            } }
            val session = ControllerVirtualDisplayRuntime.assemble(f.owner, f.capabilities, f.writes,
                emptyList(), parent, { retained += it }, store) { error("denied approval must not construct host") }
            val future = f.executor.submit<TaskAuthorization> { session.authorizationFactory(f.owner) }
            val review = checkNotNull(parent.shown.poll(10, TimeUnit.SECONDS))
            assertTrue(session.answerFromUi(review, false))
            assertThrows(ExecutionException::class.java) { future.get(10, TimeUnit.SECONDS) }
            repeat(2) {
                assertEquals(ControllerVirtualDisplaySession.CloseResult.Unknown,
                    f.work { session.observeCloseOnWorker(true) })
            }
            assertSame(session, retained.single())
            assertEquals(1, failures)
            assertEquals(0, f.gate.availablePermits())
            assertEquals(if (point == WritePoint.BEFORE_UNLOCK) 23 else 0,
                RuntimeChildJvm.probe(f.base, f.root.resolve("lease.lock")))
            rejected { f.store().reserve(f::plan) }
        }
    }

    @Test fun hostConstructionFailureRetainsLeaseAndNeverReinvokesFactory() = Fixture().use { f ->
        val parent = Parent()
        val attempts = AtomicInteger()
        val retained = mutableListOf<ControllerVirtualDisplaySession>()
        val session = ControllerVirtualDisplayRuntime.assemble(f.owner, f.capabilities, f.writes,
            emptyList(), parent, { retained += it }, f.store()) {
            attempts.incrementAndGet()
            throw IOException("private construction failure")
        }
        val future = f.executor.submit<TaskAuthorization> { session.authorizationFactory(f.owner) }
        val review = checkNotNull(parent.shown.poll(10, TimeUnit.SECONDS))
        assertTrue(session.answerFromUi(review, true))
        assertThrows(ExecutionException::class.java) { future.get(10, TimeUnit.SECONDS) }
        assertEquals(1, attempts.get())
        assertEquals(1, parent.stops)
        assertSame(session, retained.single())
        assertEquals(ControllerVirtualDisplaySession.CloseResult.Unknown,
            f.work { session.observeCloseOnWorker(true) })
        assertFalse(f.work { session.shutdownUninitializedOnceOnWorker() })
        rejected { f.store().reserve(f::plan) }
        assertEquals(1, attempts.get())
    }

    @Test fun endpointRoutesBusinessAndLookupsToSameV2ClientWithoutImplicitInitialization() = Fixture().use { f -> f.work {
        val plan = f.plan("generation")
        val host = Host(Wire { request -> OwnerProtocol.fail(request.getString("op"),
            "SESSION_UNCERTAIN", "test only").toString() })
        val adapter = OwnerAdapter(host, plan.generation, plan.bindingKey)
        adapter.start(plan.generation, plan.binding)
        val parameters = JSONObject().put("kind", "text").put("text", "test input")
        assertEquals(OwnerReply.Outcome.REPLIED, adapter.execute("input_id", "input", parameters).outcome)
        assertEquals(OwnerReply.Outcome.REPLIED, adapter.lookup("input_id", "input", parameters).outcome)
        assertEquals(OwnerReply.Outcome.REPLIED, adapter.lookupGeneration().outcome)
        assertEquals(listOf("input", "receipt", "receipt"), host.wire.operations())
        assertEquals("input_id", host.wire.sent[0].getString("operationId"))
        assertEquals("input_id", host.wire.sent[1].getString("operationId"))
        assertEquals("input", host.wire.sent[1].getString("operation"))
        assertEquals("test input", host.wire.sent[1].getJSONObject("parameters").getString("text"))
        assertEquals(plan.generation, host.wire.sent[2].getString("generation"))
        assertFalse(host.wire.sent[2].has("operationId"))
        assertEquals(AndroidVirtualDisplayOwnerHost.Lifecycle.UNKNOWN, adapter.observe())
    } }

    @Test fun authenticatedLookupIsOneShotBoundAndSendsOnlyReceiptBeforeShutdown() = Fixture().use { f -> f.work {
        val plan = f.plan("generation")
        val host = Host(normalWire())
        val adapter = OwnerAdapter(host, plan.generation, plan.bindingKey)
        assertEquals(0, host.starts)
        assertTrue(adapter.start(plan.generation, plan.binding))
        assertEquals(ControllerVirtualDisplaySession.UninitializedEvidence.PROVEN_UNINITIALIZED,
            adapter.lookupOnly(plan.generation, plan.binding))
        val request = host.wire.sent.single()
        assertEquals("receipt", request.getString("op"))
        assertEquals(plan.generation, request.getString("generation"))
        assertEquals(f.owner.ownerId, request.getJSONObject("binding").getString("runOwner"))
        assertThrows(IllegalStateException::class.java) { adapter.initialize() }
        adapter.shutdownOnce(plan.generation, plan.binding)
        assertEquals(setOf("v", "op", "token"), host.wire.sent.last().keys().asSequence().toSet())
        assertThrows(IllegalStateException::class.java) { adapter.shutdownOnce(plan.generation, plan.binding) }
        assertEquals(ControllerVirtualDisplaySession.UninitializedEvidence.UNKNOWN,
            adapter.lookupOnly(plan.generation, plan.binding))
        assertEquals(listOf("receipt", "shutdown_uninitialized"), host.wire.operations())
        assertEquals(AndroidVirtualDisplayOwnerHost.Lifecycle.UNKNOWN, adapter.physicalObservation().lifecycle)
    } }

    @Test fun mismatchedIdentityCannotQueryOrShutdown() = Fixture().use { f -> f.work {
        val plan = f.plan("generation")
        val host = Host(normalWire())
        val adapter = OwnerAdapter(host, plan.generation, plan.bindingKey)
        assertTrue(adapter.start(plan.generation, plan.binding))
        val other = OwnerRunBinding("other", "t", "c", "r", "m", "owner")
        assertEquals(ControllerVirtualDisplaySession.UninitializedEvidence.UNKNOWN,
            adapter.lookupOnly(plan.generation, other))
        assertThrows(IllegalStateException::class.java) { adapter.shutdownOnce(plan.generation, other) }
        assertTrue(host.wire.sent.isEmpty())
    } }

    @Test fun lostLaunchAndInitializeNeverGrantShutdownOrSecondStart() = Fixture().use { f -> f.work {
        val plan = f.plan("generation")
        val failedHost = Host(normalWire()).also { it.failStart = true }
        val failed = OwnerAdapter(failedHost, plan.generation, plan.bindingKey)
        assertThrows(IOException::class.java) { failed.start(plan.generation, plan.binding) }
        assertThrows(IllegalStateException::class.java) { failed.start(plan.generation, plan.binding) }
        assertEquals(1, failedHost.starts)
        assertEquals(AndroidVirtualDisplayOwnerHost.Lifecycle.UNKNOWN, failed.physicalObservation().lifecycle)
        assertEquals(ControllerVirtualDisplaySession.UninitializedEvidence.UNKNOWN,
            failed.lookupOnly(plan.generation, plan.binding))
        val host = Host(Wire { throw IOException("lost initialize response") })
        val adapter = OwnerAdapter(host, plan.generation, plan.bindingKey)
        adapter.start(plan.generation, plan.binding)
        assertEquals(OwnerReply.Outcome.UNKNOWN, adapter.initialize().outcome)
        assertThrows(IllegalStateException::class.java) { adapter.initialize() }
        assertEquals(ControllerVirtualDisplaySession.UninitializedEvidence.UNKNOWN,
            adapter.lookupOnly(plan.generation, plan.binding))
        assertThrows(IllegalStateException::class.java) { adapter.shutdownOnce(plan.generation, plan.binding) }
        assertEquals(listOf("initialize"), host.wire.operations())
    } }

    @Test fun lostLookupAndLostShutdownNeverBecomeExitOrRetry() = Fixture().use { f -> f.work {
        val plan = f.plan("generation")
        val host = Host(Wire { if (it.getString("op") == "receipt") miss() else throw IOException("lost shutdown") })
        val adapter = OwnerAdapter(host, plan.generation, plan.bindingKey)
        adapter.start(plan.generation, plan.binding)
        assertEquals(ControllerVirtualDisplaySession.UninitializedEvidence.PROVEN_UNINITIALIZED,
            adapter.lookupOnly(plan.generation, plan.binding))
        assertThrows(IllegalStateException::class.java) { adapter.shutdownOnce(plan.generation, plan.binding) }
        assertThrows(IllegalStateException::class.java) { adapter.shutdownOnce(plan.generation, plan.binding) }
        assertEquals(listOf("receipt", "shutdown_uninitialized"), host.wire.operations())
        assertEquals(AndroidVirtualDisplayOwnerHost.Lifecycle.UNKNOWN, adapter.observe())
        val brokenHost = Host(Wire { throw IOException("lost lookup") })
        val broken = OwnerAdapter(brokenHost, plan.generation, plan.bindingKey)
        broken.start(plan.generation, plan.binding)
        assertEquals(ControllerVirtualDisplaySession.UninitializedEvidence.UNKNOWN,
            broken.lookupOnly(plan.generation, plan.binding))
        assertThrows(IllegalStateException::class.java) { broken.shutdownOnce(plan.generation, plan.binding) }
        assertEquals(listOf("receipt"), brokenHost.wire.operations())
    } }

    @Test fun aFoundEvenValidHistoricalReceiptIsNotUninitializedProof() = Fixture().use { f -> f.work {
        val plan = f.plan("generation")
        val receipt = JSONObject().put("generation", plan.generation).put("operationId", "")
            .put("operation", "").put("generationReceipt", true).put("state", "STARTED")
            .put("digest", OwnerV2Contract.generationDigest(plan.generation, plan.binding.trusted))
            .put("bindingDigest", plan.bindingKey)
        val host = Host(Wire { OwnerProtocol.ok("receipt", plan.generation).put("receiptOnly", true)
            .put("executed", false).put("found", true).put("receipt", receipt).toString() })
        val adapter = OwnerAdapter(host, plan.generation, plan.bindingKey)
        adapter.start(plan.generation, plan.binding)
        assertEquals(ControllerVirtualDisplaySession.UninitializedEvidence.UNKNOWN,
            adapter.lookupOnly(plan.generation, plan.binding))
        assertThrows(IllegalStateException::class.java) { adapter.shutdownOnce(plan.generation, plan.binding) }
        assertEquals(listOf("receipt"), host.wire.operations())
    } }
}
