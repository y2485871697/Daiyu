package app.daiyu.android

import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.RunExecutionOwner
import app.daiyu.android.domain.tools.*
import app.daiyu.android.integration.tools.*
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

/** Test memory storage is deliberately NOT a production journal/lease implementation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
class ControllerVirtualDisplaySessionTest {
    private class Journal : R08VirtualDisplayJournal {
        val claims = mutableMapOf<String, R08VirtualDisplayIntent>()
        override fun claim(intent: R08VirtualDisplayIntent): R08VirtualDisplayPermit {
            val old = claims.putIfAbsent(intent.operationId, intent)
            require(old == null || old == intent)
            return if (old == null) R08VirtualDisplayPermit.EXECUTE_ONCE else R08VirtualDisplayPermit.LOOKUP_ONLY
        }
        override fun complete(intent: R08VirtualDisplayIntent, evidence: R08VirtualDisplayEvidence) = Unit
        override fun finalOutcome(bindingKey: String, generation: String, invocationId: String,
                                  evidence: R08VirtualDisplayEvidence) = Unit
    }
    private class Lease : ControllerVirtualDisplaySession.Lease {
        override val generation = "persisted_generation"
        override val journal = Journal()
        override val durable = true
        val releases = AtomicInteger()
        var failRelease = false
        override fun releaseAfterDrain() {
            releases.incrementAndGet()
            if (failRelease) throw IOException("private storage failure")
        }
    }
    private class Endpoint : R08VirtualDisplayEndpoint {
        var starts = 0
        var initializations = 0
        var writes = 0
        var observations = 0
        var failStart = false
        var physical = AndroidVirtualDisplayOwnerHost.Observation(
            AndroidVirtualDisplayOwnerHost.Lifecycle.NOT_STARTED, false, null, null, false)
        override fun start(generation: String, binding: OwnerRunBinding): Boolean {
            starts++
            physical = AndroidVirtualDisplayOwnerHost.Observation(
                AndroidVirtualDisplayOwnerHost.Lifecycle.UNKNOWN, false, null, 77, false)
            return !failStart
        }
        override fun initialize(): OwnerReply { initializations++; return OwnerReply(OwnerReply.Outcome.UNKNOWN) }
        override fun execute(id: String, operation: String, parameters: JSONObject): OwnerReply {
            writes++; return OwnerReply(OwnerReply.Outcome.UNKNOWN)
        }
        override fun lookup(id: String, operation: String, parameters: JSONObject) = OwnerReply(OwnerReply.Outcome.UNKNOWN)
        override fun lookupGeneration() = OwnerReply(OwnerReply.Outcome.UNKNOWN)
        override fun observe() = physical.lifecycle
        fun observePhysical(): AndroidVirtualDisplayOwnerHost.Observation { observations++; return physical }
        fun drained() {
            physical = AndroidVirtualDisplayOwnerHost.Observation(
                AndroidVirtualDisplayOwnerHost.Lifecycle.DRAINED, true, 0, 77, true)
        }
    }
    private class Shutdown : ControllerVirtualDisplaySession.UninitializedShutdown {
        var lookups = 0
        var shutdowns = 0
        var proof = ControllerVirtualDisplaySession.UninitializedEvidence.PROVEN_UNINITIALIZED
        override fun lookupOnly(generation: String, binding: OwnerRunBinding): ControllerVirtualDisplaySession.UninitializedEvidence {
            lookups++; return proof
        }
        override fun shutdownOnce(generation: String, binding: OwnerRunBinding) { shutdowns++ }
    }
    private class Parent : ControllerVirtualDisplaySession.Parent {
        val lease = Lease()
        val shown = LinkedBlockingQueue<VirtualDisplayApprovalRequest>()
        val stops = AtomicInteger()
        val reserves = AtomicInteger()
        var failReserve = false
        var reserveEntered: CountDownLatch? = null
        var reserveContinue: CountDownLatch? = null
        var auth: TaskAuthorization? = null
        var originalBatch: List<ToolInvocation>? = null
        var completedBatches = 0
        override fun reserve(owner: RunExecutionOwner, capabilities: SessionCapabilities): ControllerVirtualDisplaySession.Lease {
            reserves.incrementAndGet()
            reserveEntered?.countDown()
            reserveContinue?.await(5, TimeUnit.SECONDS)
            if (failReserve) throw IOException("private reserve failure")
            return lease
        }
        override fun showApproval(request: VirtualDisplayApprovalRequest) { shown.put(request) }
        override fun combinedAuthorization(owner: RunExecutionOwner, capabilities: SessionCapabilities,
                                           approvedEffects: Set<WriteEffect>): TaskAuthorization =
            TaskAuthorization(capabilities.sessionId, owner.runId.value, approvedEffects).also { auth = it }
        override fun stopAcceptedRun(owner: RunExecutionOwner) { stops.incrementAndGet() }
        override fun beginBatch(leaf: R08VirtualDisplayCatalogLeaf, originalWholeBatch: List<ToolInvocation>):
            ControllerVirtualDisplaySession.BatchCompletion {
            originalBatch = originalWholeBatch
            val leafTicket = leaf.beginBatch(originalWholeBatch)
            return ControllerVirtualDisplaySession.BatchCompletion { finalResults ->
                completedBatches++
                leaf.finishBatch(leafTicket, finalResults)
            }
        }
    }
    private class Fixture : AutoCloseable {
        val message = Message(MessageId("assistant"), ConversationId("conversation"), RunId("run"),
            Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING)
        val owner = runnerOwner(message)
        val capabilities = SessionCapabilities("session", setOf(R08VirtualDisplayCatalogLeaf.CAPABILITY))
        val parent = Parent()
        val endpoint = Endpoint()
        val shutdown = Shutdown()
        val constructions = AtomicInteger()
        val executor = Executors.newSingleThreadExecutor()
        val mutableWrites = mutableListOf(R08VirtualDisplayWrite("start", R08VirtualDisplayAction.Start),
            R08VirtualDisplayWrite("initialize", R08VirtualDisplayAction.Initialize),
            R08VirtualDisplayWrite("tap", R08VirtualDisplayAction.Tap(12, 34)))
        val session = ControllerVirtualDisplaySession(capabilities, mutableWrites,
            listOf(R08VirtualDisplayRead("status")), parent) {
            constructions.incrementAndGet()
            ControllerVirtualDisplaySession.OwnerHandle(endpoint, endpoint::observePhysical, shutdown)
        }
        fun begin() = executor.submit<TaskAuthorization> { session.authorizationFactory(owner) }
        fun request() = checkNotNull(parent.shown.poll(5, TimeUnit.SECONDS))
        fun approve(): TaskAuthorization {
            val future = begin()
            assertTrue(session.answerFromUi(request(), true))
            return future.get(5, TimeUnit.SECONDS)
        }
        fun <T> work(block: () -> T): T = executor.submit<T> { block() }.get(5, TimeUnit.SECONDS)
        var lastTicket: ControllerVirtualDisplaySession.BatchTicket? = null
        fun dispatch(auth: TaskAuthorization, forward: Boolean = true): Pair<List<ToolInvocation>, List<ToolDispatchResult>> = work {
            val registry = ToolRegistry()
            session.registeredTools(owner, capabilities, auth).forEach { registry.register(it) }
            val dispatcher = ToolDispatcher(registry.snapshot(), capabilities, ToolLedger(InMemoryToolLedgerStore()))
            val batch = listOf(ToolInvocation(ToolCallId("start-call"), R08VirtualDisplayCatalogLeaf.WRITE_TOOL,
                "{\"step_id\":\"start\"}"))
            val ticket = session.beginBatch(batch)
            lastTicket = ticket
            val results = dispatcher.dispatchOwned(auth, batch, owner)
            if (forward) session.finishBatch(ticket, results)
            batch to results
        }
        fun observe(parentDrained: Boolean = true) = work { session.observeCloseOnWorker(parentDrained) }
        override fun close() { session.close(); executor.shutdownNow() }
    }
    private fun assertStopped(future: Future<*>) {
        try { future.get(5, TimeUnit.SECONDS); fail("accepted run continued") }
        catch (failure: ExecutionException) {
            assertTrue(failure.cause is ControllerVirtualDisplaySession.AcceptedRunStopped)
            assertEquals("virtual_display_accepted_run_stopped", failure.cause?.message)
        }
    }

    @Test fun preparationApprovalAndRegistrationDoNotLaunchAndInputsAreFrozen() = Fixture().use { f ->
        f.mutableWrites.clear()
        val pending = f.begin()
        val request = f.request()
        assertEquals(listOf("start", "initialize", "input"), request.rows.map { it.operation })
        assertEquals("{\"kind\":\"tap\",\"x\":12,\"y\":34}", request.rows.last().effectiveParameters)
        assertFalse(pending.isDone)
        assertEquals(0, f.constructions.get())
        assertEquals(0, f.endpoint.starts)
        assertEquals(1, f.parent.reserves.get())
        assertTrue(f.session.answerFromUi(request, true))
        val auth = pending.get(5, TimeUnit.SECONDS)
        assertSame(f.parent.auth, auth)
        assertEquals(0, f.endpoint.starts)
        assertEquals(0, f.endpoint.initializations)
        val tools = f.session.registeredTools(f.owner, f.capabilities, auth)
        assertEquals(2, tools.size)
        try {
            f.session.registeredTools(f.owner, SessionCapabilities("session", f.capabilities.enabled), auth)
            fail("copied capabilities accepted")
        } catch (_: IllegalStateException) { }
        try {
            f.session.registeredTools(f.owner, f.capabilities, TaskAuthorization("session", "run", auth.allowedWrites))
            fail("copied authorization accepted")
        } catch (_: IllegalStateException) { }
        assertTrue(f.dispatch(auth).second.single().outcome is DispatchOutcome.Confirmed)
        assertEquals(1, f.endpoint.starts)
        assertEquals(0, f.endpoint.initializations)
    }

    @Test fun uiThreadCannotEnterTheBlockingAuthorizationFactory() = Fixture().use { f ->
        assertSame(android.os.Looper.getMainLooper().thread, Thread.currentThread())
        try { f.session.authorizationFactory(f.owner); fail("UI worker guard missing") }
        catch (failure: IllegalStateException) { assertEquals("virtual_display_worker_required", failure.message) }
        assertEquals(0, f.parent.reserves.get())
        assertEquals(0, f.constructions.get())
        assertEquals(0, f.endpoint.starts)
    }

    @Test fun declineStopsOnlyAcceptedRunAndNeverLaunchesOrResends() = Fixture().use { f ->
        val future = f.begin()
        val request = f.request()
        assertTrue(f.session.answerFromUi(request, false))
        assertFalse(f.session.answerFromUi(request, true))
        assertStopped(future)
        assertEquals(1, f.parent.stops.get())
        assertEquals(0, f.endpoint.starts)
        assertNull(f.parent.auth)
        assertEquals(ControllerVirtualDisplaySession.CloseResult.Drained, f.observe())
        assertEquals(1, f.parent.lease.releases.get())
    }

    @Test fun closeWakesApprovalWaitAndStaleCallbacksCannotTouchAnotherRequest() {
        Fixture().use { old -> Fixture().use { next ->
            val oldFuture = old.begin()
            val oldRequest = old.request()
            old.session.close()
            assertStopped(oldFuture)
            val nextFuture = next.begin()
            val current = next.request()
            assertFalse(next.session.answerFromUi(oldRequest, true))
            assertFalse(next.session.answerFromUi(oldRequest, false))
            assertSame(current, next.session.pendingApproval())
            assertFalse(nextFuture.isDone)
            assertTrue(next.session.answerFromUi(current, true))
            nextFuture.get(5, TimeUnit.SECONDS)
            assertFalse(next.session.answerFromUi(current, false))
            assertEquals(0, next.parent.stops.get())
        } }
    }

    @Test fun blockedReserveDoesNotBlockCloseAndLateLeaseIsRetainedUntilObserved() = Fixture().use { f ->
        f.parent.reserveEntered = CountDownLatch(1)
        f.parent.reserveContinue = CountDownLatch(1)
        val pending = f.begin()
        assertTrue(f.parent.reserveEntered!!.await(5, TimeUnit.SECONDS))
        f.session.close()
        assertEquals(0, f.parent.lease.releases.get())
        f.parent.reserveContinue!!.countDown()
        assertStopped(pending)
        assertEquals(0, f.constructions.get())
        assertEquals(ControllerVirtualDisplaySession.CloseResult.Drained, f.observe())
    }

    @Test fun physicalDrainRequiresMarkerExitZeroOriginalPidGoneAndParentDrain() = Fixture().use { f ->
        f.dispatch(f.approve())
        f.session.close()
        assertEquals(0, f.shutdown.lookups)
        assertEquals(0, f.parent.lease.releases.get())
        // Even a bogus DRAINED summary is insufficient without each original observation field.
        listOf(
            AndroidVirtualDisplayOwnerHost.Observation(AndroidVirtualDisplayOwnerHost.Lifecycle.DRAINED, false, 0, 77, true),
            AndroidVirtualDisplayOwnerHost.Observation(AndroidVirtualDisplayOwnerHost.Lifecycle.DRAINED, true, 1, 77, true),
            AndroidVirtualDisplayOwnerHost.Observation(AndroidVirtualDisplayOwnerHost.Lifecycle.DRAINED, true, 0, null, true),
            AndroidVirtualDisplayOwnerHost.Observation(AndroidVirtualDisplayOwnerHost.Lifecycle.DRAINED, true, 0, 77, false)
        ).forEach { observation ->
            f.endpoint.physical = observation
            assertEquals(ControllerVirtualDisplaySession.CloseResult.Unknown, f.observe())
        }
        f.endpoint.drained()
        assertEquals(ControllerVirtualDisplaySession.CloseResult.Unknown, f.observe(false))
        assertEquals(ControllerVirtualDisplaySession.CloseResult.Drained, f.observe())
        assertEquals(ControllerVirtualDisplaySession.CloseResult.Drained, f.observe())
        assertEquals(1, f.parent.lease.releases.get())
    }

    @Test fun missingFinalResultsRetainAndCentralUnknownCannotBeClearedByPhysicalExit() = Fixture().use { f ->
        val (batch, results) = f.dispatch(f.approve(), forward = false)
        f.session.close()
        f.endpoint.drained()
        assertEquals(ControllerVirtualDisplaySession.CloseResult.Unknown, f.observe())
        f.work { f.session.finishBatch(checkNotNull(f.lastTicket),
            results.map { it.copy(outcome = DispatchOutcome.Unknown("ledger_unknown")) }) }
        assertEquals(ControllerVirtualDisplaySession.CloseResult.Unknown, f.observe())
        assertEquals(0, f.parent.lease.releases.get())
    }

    @Test fun wholeBatchTicketIsRetainedThroughCloseAndCannotBeFinishedTwiceOrByAnotherSession() {
        Fixture().use { f -> Fixture().use { other ->
            f.approve(); other.approve()
            val batch = listOf(ToolInvocation(ToolCallId("other-call"), "another_catalog_tool", "{}"))
            val ticket = f.work { f.session.beginBatch(batch) }
            assertSame(batch, f.parent.originalBatch)
            other.work {
                try { other.session.finishBatch(ticket, emptyList()); fail("foreign ticket accepted") }
                catch (_: IllegalStateException) { }
            }
            f.session.close()
            assertEquals(ControllerVirtualDisplaySession.CloseResult.Unknown, f.observe())
            val results = listOf(ToolDispatchResult(batch.single().id, DispatchOutcome.NotExecuted("cancelled")))
            f.work { f.session.finishBatch(ticket, results) }
            f.work {
                try { f.session.finishBatch(ticket, results); fail("duplicate completion accepted") }
                catch (_: IllegalStateException) { }
            }
            assertEquals(1, f.parent.completedBatches)
            assertEquals(ControllerVirtualDisplaySession.CloseResult.Drained, f.observe())
        } }
    }

    @Test fun uncertainStartAndReserveRetainNoRetry() {
        Fixture().use { f ->
            f.endpoint.failStart = true
            assertTrue(f.dispatch(f.approve()).second.single().outcome is DispatchOutcome.Unknown)
            f.session.close(); f.endpoint.drained()
            assertEquals(ControllerVirtualDisplaySession.CloseResult.Unknown, f.observe())
            assertEquals(1, f.endpoint.starts)
        }
        Fixture().use { f ->
            f.parent.failReserve = true
            assertStopped(f.begin())
            assertEquals(ControllerVirtualDisplaySession.CloseResult.Unknown, f.observe())
            assertEquals(1, f.parent.reserves.get())
            assertEquals(0, f.parent.lease.releases.get())
        }
    }

    @Test fun failedLeaseReleaseIsUnknownAndNeverRetried() = Fixture().use { f ->
        f.approve(); f.session.close()
        f.parent.lease.failRelease = true
        assertEquals(ControllerVirtualDisplaySession.CloseResult.Unknown, f.observe())
        assertEquals(ControllerVirtualDisplaySession.CloseResult.Unknown, f.observe())
        assertEquals(1, f.parent.lease.releases.get())
    }

    @Test fun explicitUninitializedShutdownIsLookupGatedOnceAndNotDrainEvidence() = Fixture().use { f ->
        f.dispatch(f.approve()); f.session.close()
        assertEquals(0, f.shutdown.lookups)
        assertTrue(f.work { f.session.shutdownUninitializedOnceOnWorker() })
        assertFalse(f.work { f.session.shutdownUninitializedOnceOnWorker() })
        assertEquals(1, f.shutdown.lookups)
        assertEquals(1, f.shutdown.shutdowns)
        assertEquals(0, f.endpoint.initializations)
        assertEquals(0, f.endpoint.writes)
        assertEquals(ControllerVirtualDisplaySession.CloseResult.Unknown, f.observe())
        f.endpoint.drained()
        assertEquals(ControllerVirtualDisplaySession.CloseResult.Drained, f.observe())
    }

    @Test fun uninitializedLookupUnknownNeverSendsShutdownOrRetries() = Fixture().use { f ->
        f.dispatch(f.approve()); f.session.close()
        f.shutdown.proof = ControllerVirtualDisplaySession.UninitializedEvidence.UNKNOWN
        assertFalse(f.work { f.session.shutdownUninitializedOnceOnWorker() })
        f.shutdown.proof = ControllerVirtualDisplaySession.UninitializedEvidence.PROVEN_UNINITIALIZED
        assertFalse(f.work { f.session.shutdownUninitializedOnceOnWorker() })
        assertEquals(1, f.shutdown.lookups)
        assertEquals(0, f.shutdown.shutdowns)
        assertEquals(ControllerVirtualDisplaySession.CloseResult.Unknown, f.observe())
    }
}
