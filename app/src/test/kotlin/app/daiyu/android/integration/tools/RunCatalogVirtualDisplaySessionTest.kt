package app.daiyu.android.integration.tools

import app.daiyu.android.ControllerVirtualDisplaySession
import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.RunExecutionOwner
import app.daiyu.android.domain.tools.*
import app.daiyu.android.skills.SkillRunSession
import app.daiyu.android.vd.*
import app.daiyu.android.vdapproval.VirtualDisplayApprovalRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Central production catalog + real Session and leaf. Only endpoint and persistence are test
 * doubles. These in-memory leases are NOT suitable for the production runtime factory. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
class RunCatalogVirtualDisplaySessionTest {
    private val message = Message(MessageId("assistant"), ConversationId("conversation"), RunId("run"),
        Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING)

    private class Journal : R08VirtualDisplayJournal {
        val claims = mutableMapOf<String, R08VirtualDisplayIntent>()
        val finals = mutableListOf<R08VirtualDisplayEvidence>()
        var failFinal = false
        override fun claim(intent: R08VirtualDisplayIntent): R08VirtualDisplayPermit {
            val previous = claims.putIfAbsent(intent.operationId, intent)
            check(previous == null || previous == intent)
            return if (previous == null) R08VirtualDisplayPermit.EXECUTE_ONCE else R08VirtualDisplayPermit.LOOKUP_ONLY
        }
        override fun complete(intent: R08VirtualDisplayIntent, evidence: R08VirtualDisplayEvidence) = Unit
        override fun finalOutcome(bindingKey: String, generation: String, invocationId: String,
                                  evidence: R08VirtualDisplayEvidence) {
            if (failFinal) throw IOException("test final write failure")
            finals.add(evidence)
        }
    }
    private class Lease : ControllerVirtualDisplaySession.Lease {
        override val generation = "test_generation"
        override val journal = Journal()
        override val durable = true
        val releases = AtomicInteger()
        var failRelease = false
        override fun releaseAfterDrain() {
            releases.incrementAndGet()
            if (failRelease) throw IOException("test release failure")
        }
    }
    private class Endpoint : R08VirtualDisplayEndpoint {
        val starts = AtomicInteger()
        val observations = AtomicInteger()
        @Volatile var physical = AndroidVirtualDisplayOwnerHost.Observation(
            AndroidVirtualDisplayOwnerHost.Lifecycle.NOT_STARTED, false, null, null, false)
        override fun start(generation: String, binding: OwnerRunBinding): Boolean {
            starts.incrementAndGet()
            physical = AndroidVirtualDisplayOwnerHost.Observation(
                AndroidVirtualDisplayOwnerHost.Lifecycle.UNKNOWN, false, null, 71, false)
            return true
        }
        override fun initialize() = error("not expected")
        override fun execute(id: String, operation: String, parameters: JSONObject) = error("not expected")
        override fun lookup(id: String, operation: String, parameters: JSONObject) = OwnerReply(OwnerReply.Outcome.UNKNOWN)
        override fun lookupGeneration() = OwnerReply(OwnerReply.Outcome.UNKNOWN)
        override fun observe() = physical.lifecycle
        fun observePhysical(): AndroidVirtualDisplayOwnerHost.Observation { observations.incrementAndGet(); return physical }
        fun drained() { physical = AndroidVirtualDisplayOwnerHost.Observation(
            AndroidVirtualDisplayOwnerHost.Lifecycle.DRAINED, true, 0, 71, true) }
    }

    private inner class Fixture(
        store: ToolLedgerStore = InMemoryToolLedgerStore(),
        direct: Boolean = false,
        val factoryGate: CountDownLatch? = null,
        val captureGate: CountDownLatch? = null
    ) : AutoCloseable {
        val pool = Executors.newCachedThreadPool()
        val owner = runnerOwner(message)
        val caps = SessionCapabilities("session", setOf(R08VirtualDisplayCatalogLeaf.CAPABILITY, "audit", AskUserPlanTool.CAPABILITY))
        val lease = Lease()
        val endpoint = Endpoint()
        val constructions = AtomicInteger()
        val factories = AtomicInteger()
        val reserves = AtomicInteger()
        val finishes = AtomicInteger()
        val factoryEntered = CountDownLatch(1)
        val captureEntered = CountDownLatch(1)
        val finishEntered = CountDownLatch(1)
        var finishGate: CountDownLatch? = null
        var failFeedback = false
        var auditUnknown = false
        var reserveGate: CountDownLatch? = null
        val reserveEntered = CountDownLatch(1)
        val batches = mutableListOf<List<ToolInvocation>>()
        val results = mutableListOf<List<ToolDispatchResult>>()
        val contexts = mutableListOf<ToolExecutionContext>()
        var auth: TaskAuthorization? = null
        val retained = mutableListOf<ControllerVirtualDisplaySession>()
        lateinit var leaf: R08VirtualDisplayCatalogLeaf
        val session: ControllerVirtualDisplaySession = ControllerVirtualDisplaySession(caps,
            listOf(R08VirtualDisplayWrite("start", R08VirtualDisplayAction.Start)), emptyList(),
            object : ControllerVirtualDisplaySession.Parent {
                override fun reserve(owner: RunExecutionOwner, capabilities: SessionCapabilities): ControllerVirtualDisplaySession.Lease {
                    assertSame(this@Fixture.owner, owner); assertSame(caps, capabilities)
                    reserves.incrementAndGet(); reserveEntered.countDown()
                    reserveGate?.let { check(it.await(5, TimeUnit.SECONDS)) }
                    return lease
                }
                override fun showApproval(request: VirtualDisplayApprovalRequest) {
                    assertEquals(1, reserves.get())
                    assertTrue(session.answerFromUi(request, true))
                }
                override fun combinedAuthorization(owner: RunExecutionOwner, capabilities: SessionCapabilities,
                                                   approvedEffects: Set<WriteEffect>): TaskAuthorization {
                    assertSame(this@Fixture.owner, owner); assertSame(caps, capabilities)
                    return TaskAuthorization(caps.sessionId, owner.runId.value, approvedEffects).also { auth = it }
                }
                override fun stopAcceptedRun(owner: RunExecutionOwner) { assertSame(this@Fixture.owner, owner) }
                override fun beginBatch(leaf: R08VirtualDisplayCatalogLeaf, originalWholeBatch: List<ToolInvocation>):
                    ControllerVirtualDisplaySession.BatchCompletion {
                    this@Fixture.leaf = leaf
                    batches.add(originalWholeBatch)
                    val originalTicket = leaf.beginBatch(originalWholeBatch)
                    return ControllerVirtualDisplaySession.BatchCompletion { final ->
                        finishes.incrementAndGet()
                        results.add(final)
                        finishEntered.countDown()
                        finishGate?.let { check(it.await(5, TimeUnit.SECONDS)) }
                        if (failFeedback) throw IOException("test lost final feedback")
                        leaf.finishBatch(originalTicket, final)
                    }
                }
            }) {
            constructions.incrementAndGet()
            ControllerVirtualDisplaySession.OwnerHandle(endpoint, endpoint::observePhysical)
        }
        val registry = ToolRegistry().register(AskUserPlanTool).register(object : RegisteredTool {
            override val definition = app.daiyu.android.domain.tools.ToolDefinition(
                "audit", 1, "read", "audit", ToolEffect.READ_ONLY, ToolSchema())
            override fun prepare(arguments: ToolArguments) = PreparedTool.contextual {
                assertSame(owner, it.owner); assertSame(auth, it.authorization)
                contexts.add(it)
                if (auditUnknown) ExecutionReceipt.Unknown("audit_unknown") else ExecutionReceipt.Confirmed("audit")
            }
        })
        val tools = RunCatalogTools(registry, caps, ToolLedger(store), message.conversationId, message.runId,
            virtualDisplaySession = if (direct) session else null,
            virtualDisplaySessionFactory = if (direct) null else { actual, actualCaps ->
                assertSame(owner, actual); assertSame(caps, actualCaps)
                factories.incrementAndGet()
                retained.add(session) // Actual parent must retain BEFORE the factory returns.
                factoryEntered.countDown()
                factoryGate?.let { check(it.await(5, TimeUnit.SECONDS)) }
                session
            }, capture = { actualCaps, actualAuth ->
                assertSame(caps, actualCaps); assertSame(auth, actualAuth)
                captureEntered.countDown()
                captureGate?.let { check(it.await(5, TimeUnit.SECONDS)) }
                SkillRunSession.create(actualAuth, emptySet(), emptyList())
            })
        fun <T> async(block: () -> T): Future<T> = pool.submit<T> { block() }
        fun <T> work(block: () -> T): T = async(block).get(10, TimeUnit.SECONDS)
        fun prepare() = work { tools.bindOwner(owner); tools.definitions }
        fun step(id: String = "start-call") = ToolInvocation(ToolCallId(id), R08VirtualDisplayCatalogLeaf.WRITE_TOOL, "{\"step_id\":\"start\"}")
        fun audit() = ToolInvocation(ToolCallId("audit-call"), "audit", "{}")
        fun dispatch(calls: List<ToolInvocation>, start: Boolean = false, cancelled: () -> Boolean = { false }) = work {
            if (start) (tools.start(message, calls, cancelled) as ReceiptBatchStart.Completed).receipts
            else tools.execute(message, calls, cancelled)
        }
        fun observe(parentDrained: Boolean = true) = work { tools.observeVirtualDisplayCloseOnWorker(parentDrained) }
        override fun close() {
            factoryGate?.countDown(); captureGate?.countDown(); finishGate?.countDown(); reserveGate?.countDown()
            tools.close(); pool.shutdownNow()
        }
    }

    @Test fun factoryAndDirectSessionUseOneRealOwnerAndAuthBeforeOriginalSnapshotWithoutLaunching() {
        for (direct in listOf(false, true)) Fixture(direct = direct).use { f ->
            assertEquals(0, f.factories.get()); assertEquals(0, f.reserves.get()); assertEquals(0, f.constructions.get())
            assertEquals(0, f.endpoint.starts.get())
            val definitions = f.prepare()
            assertEquals(1, f.reserves.get()); assertEquals(1, f.constructions.get())
            assertEquals(if (direct) 0 else 1, f.factories.get())
            assertSame(f.session, f.tools.virtualDisplaySessionForRetention())
            assertTrue(definitions.any { it.name == R08VirtualDisplayCatalogLeaf.WRITE_TOOL })
            assertTrue(definitions.any { it.name == "audit" })
            val batch = listOf(f.step(), f.audit())
            val final = f.dispatch(batch)
            assertTrue(final.all { it.outcome is DispatchOutcome.Confirmed })
            assertEquals(batch, f.batches.single()); assertSame(final, f.results.single())
            assertEquals(1, f.finishes.get()); assertEquals(1, f.endpoint.starts.get())
            assertSame(batch[1], f.contexts.single().invocation)
            f.work { f.tools.bindOwner(f.owner) }
            assertEquals(if (direct) 0 else 1, f.factories.get())
        }
    }

    @Test fun sessionInputsAreMutuallyExclusiveWithLegacyFactories() = Fixture().use { f ->
        fun rejected(make: () -> RunCatalogTools) {
            try { make(); fail("ambiguous ownership accepted") } catch (_: IllegalArgumentException) { }
        }
        val capture: (SessionCapabilities, TaskAuthorization) -> SkillRunSession = { _, a ->
            SkillRunSession.create(a, emptySet(), emptyList())
        }
        rejected { RunCatalogTools(f.registry, f.caps, ToolLedger(InMemoryToolLedgerStore()), message.conversationId, message.runId,
            virtualDisplaySession = f.session, virtualDisplaySessionFactory = { _, _ -> f.session }, capture = capture) }
        rejected { RunCatalogTools(f.registry, f.caps, ToolLedger(InMemoryToolLedgerStore()), message.conversationId, message.runId,
            virtualDisplaySession = f.session, virtualDisplayFactory = { _, _, _ -> error("must not run") }, capture = capture) }
        rejected { RunCatalogTools(f.registry, f.caps, ToolLedger(InMemoryToolLedgerStore()), message.conversationId, message.runId,
            virtualDisplaySession = f.session, authorizationFactory = { error("must not run") }, capture = capture) }
        assertEquals(0, f.reserves.get())
    }

    @Test fun centralReservationFailureAndCancellationStillFinishWholeTicketExactlyOnce() {
        val unavailable = object : ToolLedgerStore {
            override fun <T> transaction(block: (MutableList<LedgerEntry>) -> T): T = throw IOException("test ledger unavailable")
        }
        for (start in listOf(false, true)) for (cancel in listOf(false, true)) {
            Fixture(store = unavailable).use { f ->
                f.prepare()
                val calls = listOf(f.step(), f.audit())
                val final = f.dispatch(calls, start) { cancel }
                assertEquals(calls, f.batches.single()); assertSame(final, f.results.single())
                assertEquals(1, f.finishes.get()); assertEquals(0, f.endpoint.starts.get())
                assertTrue(final.all { it.outcome == DispatchOutcome.NotExecuted(
                    if (cancel) "cancelled_before_execution" else "ledger_unavailable") })
                assertEquals(0, f.leaf.heldState().pendingFinalOutcomes)
            }
        }
    }

    @Test fun receiptPersistenceFailureAndLedgerUnknownAreForwardedNotAdapterSuccess() {
        for (start in listOf(false, true)) {
            val memory = InMemoryToolLedgerStore()
            val tx = AtomicInteger()
            val store = object : ToolLedgerStore {
                override fun <T> transaction(block: (MutableList<LedgerEntry>) -> T): T {
                    if (tx.incrementAndGet() == 2) throw IOException("test finish failure")
                    return memory.transaction(block)
                }
            }
            Fixture(store).use { f ->
                f.prepare()
                val first = f.dispatch(listOf(f.step()), start)
                assertEquals(DispatchOutcome.Unknown("receipt_persistence_failed"), first.single().outcome)
                assertSame(first, f.results.single())
                assertEquals(listOf(R08VirtualDisplayEvidence.UNKNOWN), f.lease.journal.finals)
                assertEquals(1, f.endpoint.starts.get())
                assertTrue(f.leaf.heldState().uncertain)
            }
            val reserved = InMemoryToolLedgerStore()
            Fixture(reserved).use { f ->
                f.prepare()
                assertTrue(f.dispatch(listOf(f.step()), start).single().outcome is DispatchOutcome.Confirmed)
                reserved.transaction { entries -> entries[0] = entries[0].copy(state = LedgerState.UNKNOWN) }
                val final = f.dispatch(listOf(f.step("later")), start)
                assertEquals(DispatchOutcome.Unknown("unknown_effect_protected"), final.single().outcome)
                assertSame(final, f.results.last()); assertEquals(1, f.endpoint.starts.get())
                assertEquals(2, f.finishes.get()); assertTrue(f.leaf.heldState().uncertain)
            }
        }
    }

    @Test fun nonVirtualDisplayUnknownIsNotFilteredFromFinalResults() = Fixture().use { f ->
        f.prepare(); f.auditUnknown = true
        val batch = listOf(f.audit(), f.step())
        val final = f.dispatch(batch)
        assertEquals(DispatchOutcome.Unknown("audit_unknown"), final[0].outcome)
        assertTrue(final[1].outcome is DispatchOutcome.NotExecuted)
        assertSame(final, f.results.single()); assertEquals(batch, f.batches.single())
        assertEquals(1, f.finishes.get()); assertEquals(0, f.endpoint.starts.get())
    }

    private fun question() = ToolInvocation(ToolCallId("question"), "ask_user",
        """{"title":"Choose","question":"Which?","options":[{"id":"a","label":"A"},{"id":"b","label":"B"}]}""")

    @Test fun pureBarrierNeverGetsTicketButMixedBarrierStillFinishesOriginalRejectedBatch() {
        Fixture().use { f ->
            f.prepare()
            assertTrue(f.work { f.tools.start(message, listOf(question())) { false } } is ReceiptBatchStart.Suspended)
            assertTrue(f.batches.isEmpty()); assertEquals(0, f.finishes.get())
            val closed = AtomicReference<RunCatalogCloseResult?>()
            f.tools.closeWithResult { closed.set(it) }
            assertNull(closed.get()) // Catalog cannot infer that the suspended Runner has drained.
            assertTrue(f.observe(false) is RunCatalogCloseResult.Unknown)
            assertEquals(RunCatalogCloseResult.Drained, f.observe())
            assertEquals(RunCatalogCloseResult.Drained, closed.get())
        }
        for (start in listOf(false, true)) Fixture().use { f ->
            f.prepare()
            val calls = listOf(f.step(), question())
            val final = f.dispatch(calls, start)
            assertTrue(final.all { it.outcome == DispatchOutcome.NotExecuted("ask_user_batch_barrier") })
            assertEquals(calls, f.batches.single()); assertSame(final, f.results.single())
            assertEquals(1, f.finishes.get()); assertEquals(0, f.endpoint.starts.get())
        }
    }

    @Test fun closeWaitsForFinalForwardingThenParentRunnerAndPhysicalEvidenceBeforeRelease() = Fixture().use { f ->
        f.prepare(); f.finishGate = CountDownLatch(1)
        val dispatch = f.async { f.tools.execute(message, listOf(f.step())) { false } }
        assertTrue(f.finishEntered.await(5, TimeUnit.SECONDS))
        val closed = AtomicReference<RunCatalogCloseResult?>()
        f.tools.closeWithResult { closed.set(it) }
        assertTrue(f.observe() is RunCatalogCloseResult.Unknown)
        assertEquals(0, f.endpoint.observations.get()); assertEquals(0, f.lease.releases.get())
        assertNull(closed.get())
        f.finishGate!!.countDown(); dispatch.get(5, TimeUnit.SECONDS)
        assertTrue(f.observe(false) is RunCatalogCloseResult.Unknown)
        assertEquals(0, f.endpoint.observations.get())
        assertTrue(f.observe() is RunCatalogCloseResult.Unknown) // start succeeded; no physical drain yet
        assertNull(closed.get()); assertEquals(0, f.lease.releases.get())
        f.endpoint.drained()
        assertEquals(RunCatalogCloseResult.Drained, f.observe())
        assertEquals(RunCatalogCloseResult.Drained, closed.get()); assertEquals(1, f.lease.releases.get())
        assertEquals(RunCatalogCloseResult.Drained, f.observe()); assertEquals(1, f.lease.releases.get())
        assertSame(f.session, f.tools.virtualDisplaySessionForRetention())
    }

    @Test fun closeDuringLateFactoryAndCaptureRetainsSessionWithoutEarlyRelease() {
        for (duringFactory in listOf(false, true)) Fixture(
            factoryGate = if (duringFactory) CountDownLatch(1) else null,
            captureGate = if (duringFactory) null else CountDownLatch(1)).use { f ->
            val creation = f.async { f.tools.bindOwner(f.owner); f.tools.definitions }
            assertTrue((if (duringFactory) f.factoryEntered else f.captureEntered).await(5, TimeUnit.SECONDS))
            val closed = AtomicReference<RunCatalogCloseResult?>()
            f.tools.closeWithResult { closed.set(it) }
            assertTrue(f.observe() is RunCatalogCloseResult.Unknown); assertNull(closed.get())
            assertEquals(0, f.lease.releases.get())
            (if (duringFactory) f.factoryGate else f.captureGate)!!.countDown()
            try { creation.get(5, TimeUnit.SECONDS); fail("closed creation published") } catch (_: ExecutionException) { }
            assertSame(f.session, f.tools.virtualDisplaySessionForRetention())
            assertEquals(RunCatalogCloseResult.Drained, f.observe())
            assertEquals(RunCatalogCloseResult.Drained, closed.get()); assertEquals(0, f.endpoint.starts.get())
            assertEquals(if (duringFactory) 0 else 1, f.lease.releases.get())
        }
    }

    @Test fun missingOrThrowingFinalAndJournalFailureNeverBecomeDrainOrReplay() {
        for (mode in listOf("missing", "throwing", "journal")) Fixture().use { f ->
            f.prepare(); f.failFeedback = mode == "throwing"; f.lease.journal.failFinal = mode == "journal"
            try {
                f.dispatch(listOf(f.step()), cancelled = { if (mode == "missing") throw IOException("test cancelled callback") else false })
                assertEquals("journal", mode)
            } catch (_: ExecutionException) { assertNotEquals("journal", mode) }
            val closed = AtomicReference<RunCatalogCloseResult?>()
            f.tools.closeWithResult { closed.set(it) }
            f.endpoint.drained()
            assertTrue(f.observe() is RunCatalogCloseResult.Unknown)
            assertEquals(0, f.lease.releases.get())
            assertEquals(if (mode == "missing") 0 else 1, f.finishes.get())
            assertTrue(f.leaf.heldState().pendingFinalOutcomes > 0)
            assertSame(f.session, f.tools.virtualDisplaySessionForRetention())
        }
    }

    @Test fun closeDuringApprovalReserveDoesNotWaitOrReleaseUntilWorkerReturns() = Fixture().use { f ->
        f.reserveGate = CountDownLatch(1)
        val binding = f.async { f.tools.bindOwner(f.owner) }
        assertTrue(f.reserveEntered.await(5, TimeUnit.SECONDS))
        val closed = AtomicReference<RunCatalogCloseResult?>()
        f.tools.closeWithResult { closed.set(it) }
        assertTrue(f.observe() is RunCatalogCloseResult.Unknown)
        assertNull(closed.get()); assertEquals(0, f.constructions.get()); assertEquals(0, f.lease.releases.get())
        f.reserveGate!!.countDown()
        try { binding.get(5, TimeUnit.SECONDS); fail("closed approval published") } catch (_: ExecutionException) { }
        assertEquals(RunCatalogCloseResult.Drained, f.observe())
        assertEquals(1, f.lease.releases.get()); assertEquals(0, f.endpoint.starts.get())
    }

    @Test fun closingBeforeBindingNeverCreatesOrReservesAndObservationMustBeOnWorker() = Fixture().use { f ->
        val closed = AtomicReference<RunCatalogCloseResult?>()
        f.tools.closeWithResult { closed.set(it) }
        try { f.tools.observeVirtualDisplayCloseOnWorker(true); fail("main thread maintenance allowed") }
        catch (_: IllegalStateException) { }
        assertEquals(RunCatalogCloseResult.Drained, f.observe())
        assertEquals(RunCatalogCloseResult.Drained, closed.get())
        assertEquals(0, f.factories.get()); assertEquals(0, f.reserves.get()); assertEquals(0, f.constructions.get())
    }

    @Test fun releaseFailureIsUnknownRetainedAndNeverRetried() = Fixture().use { f ->
        f.prepare(); f.lease.failRelease = true
        val closed = AtomicReference<RunCatalogCloseResult?>()
        f.tools.closeWithResult { closed.set(it) }
        repeat(2) { assertTrue(f.observe() is RunCatalogCloseResult.Unknown) }
        assertNull(closed.get()); assertEquals(1, f.lease.releases.get())
        assertSame(f.session, f.tools.virtualDisplaySessionForRetention())
    }
}
