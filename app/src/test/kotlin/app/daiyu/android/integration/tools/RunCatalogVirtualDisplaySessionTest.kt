package app.daiyu.android.integration.tools

import app.daiyu.android.ControllerVirtualDisplaySession
import app.daiyu.android.ControllerVirtualCleanupAdapter
import app.daiyu.android.assistants.AssistantProfile
import app.daiyu.android.browser.*
import app.daiyu.android.browser.host.*
import app.daiyu.android.browser.session.*
import app.daiyu.android.browser.bridge.*
import app.daiyu.android.browser.network.BrowserCloseResult
import app.daiyu.android.vdapproval.R08CleanupUiOwner
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
        val cleanupSends = AtomicInteger()
        override fun initialize() = OwnerReply(OwnerReply.Outcome.REPLIED, JSONObject()
            .put("ok", true).put("initialized", true).put("displayId", 7)
            .put("receipt", JSONObject().put("state", "STARTED")))
        override fun execute(id: String, operation: String, parameters: JSONObject): OwnerReply {
            check(operation == "release")
            cleanupSends.incrementAndGet()
            return OwnerReply(OwnerReply.Outcome.REPLIED, JSONObject().put("ok", true).put("released", true)
                .put("receipt", JSONObject().put("state", "SUCCEEDED")))
        }
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
        val captureGate: CountDownLatch? = null,
        initializedPlan: Boolean = false,
        otherObservation: Boolean = false,
        closeResource: (AutoCloseable) -> Unit = { it.close() }
    ) : AutoCloseable {
        val pool = Executors.newCachedThreadPool()
        val owner = runnerOwner(message)
        val caps = SessionCapabilities(if (otherObservation) "run" else "session",
            setOf(R08VirtualDisplayCatalogLeaf.CAPABILITY, "audit", AskUserPlanTool.CAPABILITY,
                RunBrowserTools.READ_CAPABILITY, RunBrowserTools.WRITE_CAPABILITY))
        val otherDone = AtomicReference<((RunCatalogCloseResult) -> Unit)?>()
        val browser = if (!otherObservation) null else {
            val identity = BrowserRunIdentity(message.conversationId, message.runId, "profile", message.id)
            val grant = BrowserGrant(BrowserOwner("run", BrowserOwnerKind.MAIN), BrowserAccess.READ_ONLY)
            val preparation = BrowserRunPreparation.prepare(identity, BrowserSettings(BrowserAccess.READ_ONLY),
                grant, setOf("list_tabs"))
            val host = object : CatalogBrowserBinding.Host {
                override val session = BrowserToolSession(grant, BrowserSessionRegistry(), object : BrowserTabLifecycle {
                    override fun create(lease: BrowserTabLease) {}
                    override fun close(lease: BrowserTabLease) {}
                }, object : BrowserExecutionPort {
                    override fun execute(command: BrowserCommand, lease: BrowserTabLease) = BrowserExecutionResult("test")
                })
                override fun closeWithResult(): BrowserCloseResult { session.close(); return BrowserCloseResult(true) }
                override fun closeStatus() = BrowserCloseResult(true)
            }
            CatalogBrowserBinding.create(AssistantProfile("profile", "Test", ""),
                preparation.confirm(preparation.preview)) { host }
        }
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
            listOf(R08VirtualDisplayWrite("start", R08VirtualDisplayAction.Start)) +
                if (initializedPlan) listOf(R08VirtualDisplayWrite("initialize", R08VirtualDisplayAction.Initialize))
                else emptyList(), emptyList(),
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
            browser = browser, observeBrowserClose = { _, done -> check(otherDone.compareAndSet(null, done)) },
            closeResource = closeResource,
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
        fun initialize() {
            prepare()
            assertTrue(dispatch(listOf(step())).single().outcome is DispatchOutcome.Confirmed)
            val call = ToolInvocation(ToolCallId("initialize-call"), R08VirtualDisplayCatalogLeaf.WRITE_TOOL,
                """{"step_id":"initialize"}""")
            assertTrue(dispatch(listOf(call)).single().outcome is DispatchOutcome.Confirmed)
        }
        fun cleanupClaims() = lease.journal.claims.values.count { it.invocationId == "host-cleanup-release-v1" }
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

    private fun assertCleanupPending(f: Fixture, request: app.daiyu.android.vdapproval.R08CleanupApprovalRequest,
        approve: Boolean = true) {
        assertFalse(f.tools.virtualDisplayCleanupLocallyDrained(f.session))
        assertNull(f.work { f.tools.prepareVirtualDisplayCleanupOnWorker(f.session, parentDrained = true) })
        if (approve) assertTrue(f.session.answerCleanupFromUi(request, true))
        assertEquals(ControllerVirtualDisplaySession.CleanupResult.UNAVAILABLE,
            f.work { f.tools.releaseVirtualDisplayCleanupOnWorker(f.session, request, parentDrained = true) })
        assertEquals(0, f.cleanupClaims()); assertEquals(0, f.endpoint.cleanupSends.get())
        assertEquals(0, f.endpoint.observations.get()); assertEquals(0, f.lease.releases.get())
    }

    @Test fun runnerReturnedButCatalogStopStillRunningRejectsBothCleanupStagesWithoutPhysicalProbe() {
        val stopEntered = CountDownLatch(1)
        val stopContinue = CountDownLatch(1)
        Fixture(initializedPlan = true, closeResource = {
            stopEntered.countDown(); check(stopContinue.await(5, TimeUnit.SECONDS)); it.close()
        }).use { f ->
            f.initialize()
            // Simulate an already approved request from the old direct-session seam. Even this
            // cannot bypass the catalog send gate while its other stop actions are unfinished.
            f.session.close()
            val request = checkNotNull(f.work { f.session.prepareInitializedCleanupOnWorker(true) })
            val closed = AtomicReference<RunCatalogCloseResult?>()
            val closing = f.async { f.tools.closeWithResult { closed.set(it) } }
            try {
                assertTrue(stopEntered.await(5, TimeUnit.SECONDS))
                // runnerOwner's real Runner has returned; parentDrained=true is not sufficient.
                assertCleanupPending(f, request)
                assertNull(closed.get())
            } finally { stopContinue.countDown() }
            closing.get(5, TimeUnit.SECONDS)
            assertTrue(f.tools.virtualDisplayCleanupLocallyDrained(f.session))
            // Approval already consumed preparation; no second request is manufactured.
            assertNull(f.work { f.tools.prepareVirtualDisplayCleanupOnWorker(f.session, true) })
            assertEquals(ControllerVirtualDisplaySession.CleanupResult.CONFIRMED,
                f.work { f.tools.releaseVirtualDisplayCleanupOnWorker(f.session, request, true) })
            assertEquals(1, f.cleanupClaims()); assertEquals(1, f.endpoint.cleanupSends.get())
            assertNull(closed.get()) // Do NOT wait for this final callback to authorize cleanup.
            assertEquals(0, f.endpoint.observations.get()); assertEquals(0, f.lease.releases.get())
        }
    }

    @Test fun runnerReturnedButOtherResourceObservationPendingRejectsBothStagesThenAllowsCleanupBeforeFinalDrain() =
        Fixture(initializedPlan = true, otherObservation = true).use { f ->
            f.initialize(); f.session.close()
            val request = checkNotNull(f.work { f.session.prepareInitializedCleanupOnWorker(true) })
            val closed = AtomicReference<RunCatalogCloseResult?>()
            f.tools.closeWithResult { closed.set(it) }
            assertNotNull(f.otherDone.get()); assertCleanupPending(f, request)
            f.otherDone.get()!!.invoke(RunCatalogCloseResult.Drained)
            assertTrue(f.tools.virtualDisplayCleanupLocallyDrained(f.session))
            // Approval already consumed preparation; no second request is manufactured.
            assertNull(f.work { f.tools.prepareVirtualDisplayCleanupOnWorker(f.session, true) })
            assertEquals(ControllerVirtualDisplaySession.CleanupResult.CONFIRMED,
                f.work { f.tools.releaseVirtualDisplayCleanupOnWorker(f.session, request, true) })
            assertEquals(1, f.endpoint.cleanupSends.get()); assertNull(closed.get())
            assertEquals(0, f.endpoint.observations.get()); assertEquals(0, f.lease.releases.get())
        }

    @Test fun stopFailureOrOtherObservationUnknownPermanentlyRejectsCleanupDespiteRunnerReturn() {
        for (failedStop in listOf(false, true)) Fixture(initializedPlan = true,
            otherObservation = !failedStop, closeResource = {
                it.close(); if (failedStop) throw IOException("test stop failure")
            }).use { f ->
            f.initialize(); f.session.close()
            val request = checkNotNull(f.work { f.session.prepareInitializedCleanupOnWorker(true) })
            val closed = AtomicReference<RunCatalogCloseResult?>()
            f.tools.closeWithResult { closed.set(it) }
            if (!failedStop) f.otherDone.get()!!.invoke(RunCatalogCloseResult.Unknown("test_observe_failed"))
            assertTrue(closed.get() is RunCatalogCloseResult.Unknown)
            repeat(2) { assertCleanupPending(f, request, approve = it == 0) }
            assertSame(f.session, f.tools.virtualDisplaySessionForRetention())
        }
    }

    @Test fun adapterRechecksParentAtPublicationAnswerAndQueuedSendWithoutConsumingAttempt() =
        Fixture(initializedPlan = true).use { f ->
            f.initialize(); f.tools.closeWithResult { }
            val owner = R08CleanupUiOwner()
            val parentReady = java.util.concurrent.atomic.AtomicBoolean(true)
            val adapter = ControllerVirtualCleanupAdapter(f.tools, f.session, owner,
                { parentReady.get() }, { owner })
            val request = checkNotNull(f.work { adapter.prepareOnWorker() })
            assertSame(f.owner, request.review.plan.owner)
            assertSame(f.caps, request.review.plan.capabilities)
            assertEquals(f.lease.generation, request.review.plan.generation)
            parentReady.set(false)
            assertFalse(adapter.canPublish(request)); assertFalse(adapter.answerFromUi(request, true))
            assertEquals(ControllerVirtualDisplaySession.CleanupResult.UNAVAILABLE,
                f.work { adapter.releaseOnWorker(request) })
            parentReady.set(true)
            assertTrue(adapter.canPublish(request)); assertTrue(adapter.answerFromUi(request, true))
            parentReady.set(false)
            assertEquals(ControllerVirtualDisplaySession.CleanupResult.UNAVAILABLE,
                f.work { adapter.releaseOnWorker(request) })
            assertEquals(0, f.cleanupClaims()); assertEquals(0, f.endpoint.cleanupSends.get())
            parentReady.set(true)
            assertEquals(ControllerVirtualDisplaySession.CleanupResult.CONFIRMED,
                f.work { adapter.releaseOnWorker(request) })
            assertEquals(ControllerVirtualDisplaySession.CleanupResult.UNAVAILABLE,
                f.work { adapter.releaseOnWorker(request) })
            assertEquals(1, f.endpoint.cleanupSends.get()); assertEquals(0, f.endpoint.observations.get())
            adapter.close()
        }

    @Test fun oldUiOwnerAndQueuedWorkerCannotPublishAnswerOrSendIntoReplacementVisit() {
        for (accepted in listOf(false, true)) Fixture(initializedPlan = true).use { f ->
            f.initialize(); f.tools.closeWithResult { }
            val oldOwner = R08CleanupUiOwner()
            val current = AtomicReference(oldOwner)
            val adapter = ControllerVirtualCleanupAdapter(f.tools, f.session, oldOwner, { true }, { current.get() })
            val request = checkNotNull(f.work { adapter.prepareOnWorker() })
            if (accepted) assertTrue(adapter.answerFromUi(request, true))
            val queued = CountDownLatch(1)
            val resume = CountDownLatch(1)
            val worker = f.async {
                queued.countDown(); check(resume.await(5, TimeUnit.SECONDS))
                adapter.releaseOnWorker(request)
            }
            try {
                assertTrue(queued.await(5, TimeUnit.SECONDS))
                // This owner is independent of text/editor identity; do not clear a draft/run.
                oldOwner.close(); current.set(R08CleanupUiOwner())
                assertFalse(adapter.canPublish(request)); assertFalse(adapter.answerFromUi(request, true))
                assertNull(f.work { adapter.prepareOnWorker() })
            } finally { resume.countDown() }
            assertEquals(ControllerVirtualDisplaySession.CleanupResult.UNAVAILABLE, worker.get(5, TimeUnit.SECONDS))
            assertEquals(0, f.cleanupClaims()); assertEquals(0, f.endpoint.cleanupSends.get())
            assertEquals(0, f.endpoint.observations.get()); assertEquals(0, f.lease.releases.get())
            assertTrue(current.get().isActive)
            adapter.close(); assertTrue(current.get().isActive)
        }
    }

    @Test fun stalePreparationCompletionCannotDenyTheNewVisitOrPublishItsRequest() = Fixture(initializedPlan = true).use { f ->
        f.initialize(); f.tools.closeWithResult { }
        val oldOwner = R08CleanupUiOwner()
        val current = AtomicReference(oldOwner)
        val afterFreeze = CountDownLatch(1)
        val finishOld = CountDownLatch(1)
        val probes = AtomicInteger()
        val old = ControllerVirtualCleanupAdapter(f.tools, f.session, oldOwner, {
            // Test-only timing injection after Session froze the request; production predicates
            // are pure nonblocking snapshots. No catalog/session/adapter lock is held here.
            if (probes.incrementAndGet() == 2) {
                afterFreeze.countDown(); check(finishOld.await(5, TimeUnit.SECONDS))
            }
            true
        }, { current.get() })
        val preparing = f.async { old.prepareOnWorker() }
        try {
            assertTrue(afterFreeze.await(5, TimeUnit.SECONDS))
            old.close() // no published request yet; must neither block nor decide on worker's behalf
            val newOwner = R08CleanupUiOwner()
            current.set(newOwner)
            val newer = ControllerVirtualCleanupAdapter(f.tools, f.session, newOwner, { true }, { current.get() })
            val request = checkNotNull(f.work { newer.prepareOnWorker() })
            assertTrue(newer.canPublish(request)); assertTrue(newer.answerFromUi(request, true))
            finishOld.countDown()
            assertNull(preparing.get(5, TimeUnit.SECONDS))
            assertFalse(old.answerFromUi(request, false))
            assertEquals(ControllerVirtualDisplaySession.CleanupResult.CONFIRMED,
                f.work { newer.releaseOnWorker(request) })
            assertEquals(1, f.cleanupClaims()); assertEquals(1, f.endpoint.cleanupSends.get())
            newer.close()
        } finally { finishOld.countDown() }
    }

    @Test fun repeatedCloseMustWaitForDelayedOriginalDenialBeforePublishingReplacementOwner() = Fixture(initializedPlan = true).use { f ->
        f.initialize(); f.tools.closeWithResult { }
        val oldOwner = R08CleanupUiOwner()
        val current = AtomicReference(oldOwner)
        val old = ControllerVirtualCleanupAdapter(f.tools, f.session, oldOwner, { true }, { current.get() })
        val request = checkNotNull(f.work { old.prepareOnWorker() }) // published, still unanswered
        val sessionLock = ControllerVirtualDisplaySession::class.java.getDeclaredField("lock")
            .apply { isAccessible = true }.get(f.session)
        val locked = CountDownLatch(1)
        val resumeDenial = CountDownLatch(1)
        val blocker = f.async {
            synchronized(sessionLock) {
                locked.countDown(); check(resumeDenial.await(5, TimeUnit.SECONDS))
            }
        }
        assertTrue(locked.await(5, TimeUnit.SECONDS))
        val firstClose = f.async { old.close() }
        val replacementPublished = CountDownLatch(1)
        val replacementOwner = R08CleanupUiOwner()
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (oldOwner.isActive && System.nanoTime() < deadline) Thread.yield()
            assertFalse(oldOwner.isActive)
            // A disposal close is already stalled on the Session's answer lock. Main's explicit
            // replacement close MUST still wait, not return early just because closed=true.
            val secondCloseStarted = CountDownLatch(1)
            val replacing = f.async {
                secondCloseStarted.countDown()
                old.close()
                current.set(replacementOwner)
                replacementPublished.countDown()
                val newer = ControllerVirtualCleanupAdapter(f.tools, f.session, replacementOwner, { true }, { current.get() })
                newer.prepareOnWorker()
            }
            assertTrue(secondCloseStarted.await(5, TimeUnit.SECONDS))
            assertFalse("new owner must not publish before old denial completes",
                replacementPublished.await(150, TimeUnit.MILLISECONDS))
            assertSame(oldOwner, current.get())
            resumeDenial.countDown()
            blocker.get(5, TimeUnit.SECONDS); firstClose.get(5, TimeUnit.SECONDS)
            // Published old request was denied synchronously; it cannot be inherited then
            // unexpectedly rejected by a delayed old close after the new page has shown it.
            assertNull(replacing.get(5, TimeUnit.SECONDS))
            assertSame(replacementOwner, current.get()); assertTrue(replacementOwner.isActive)
            assertFalse(f.session.answerCleanupFromUi(request, true))
            assertEquals(0, f.cleanupClaims()); assertEquals(0, f.endpoint.cleanupSends.get())
            assertEquals(0, f.endpoint.observations.get()); assertEquals(0, f.lease.releases.get())
        } finally { resumeDenial.countDown() }
    }

    @Test fun invalidatedUiAtSessionAdmissionDoesNotConsumeTheFixedJournalIntent() = Fixture(initializedPlan = true).use { f ->
        f.initialize(); f.tools.closeWithResult { }
        val request = checkNotNull(f.work { f.tools.prepareVirtualDisplayCleanupOnWorker(f.session, true) })
        assertTrue(f.session.answerCleanupFromUi(request, true))
        assertEquals(ControllerVirtualDisplaySession.CleanupResult.UNAVAILABLE,
            f.work { f.tools.releaseVirtualDisplayCleanupOnWorker(f.session, request, true) { false } })
        assertEquals(0, f.cleanupClaims())
        assertEquals(ControllerVirtualDisplaySession.CleanupResult.CONFIRMED,
            f.work { f.tools.releaseVirtualDisplayCleanupOnWorker(f.session, request, true) { true } })
        assertEquals(1, f.cleanupClaims()); assertEquals(1, f.endpoint.cleanupSends.get())
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
