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
        var onClaim: (R08VirtualDisplayIntent) -> Unit = {}
        override fun claim(intent: R08VirtualDisplayIntent): R08VirtualDisplayPermit {
            onClaim(intent)
            val old = claims.putIfAbsent(intent.operationId, intent)
            require(old == null || old == intent)
            return if (old == null) R08VirtualDisplayPermit.EXECUTE_ONCE else R08VirtualDisplayPermit.LOOKUP_ONLY
        }
        var failHandoffComplete = false
        var onComplete: (R08VirtualDisplayIntent) -> Unit = {}
        var failCleanupComplete = false
        val evidence = mutableListOf<Pair<R08VirtualDisplayIntent, R08VirtualDisplayEvidence>>()
        override fun complete(intent: R08VirtualDisplayIntent, evidence: R08VirtualDisplayEvidence) {
            if (failHandoffComplete && intent.operation == "handoff") throw IOException("lost handoff completion")
            if (failCleanupComplete && intent.invocationId == "host-cleanup-release-v1") throw IOException("lost completion")
            this.evidence += intent to evidence
            onComplete(intent)
        }
        val finals = mutableListOf<Pair<String, R08VirtualDisplayEvidence>>()
        override fun finalOutcome(bindingKey: String, generation: String, invocationId: String,
                                  evidence: R08VirtualDisplayEvidence) { finals += invocationId to evidence }
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
        var statusBody: JSONObject? = null
        var confirmInitialize = false
        var confirmRelease = false
        var confirmLaunch = false
        var confirmHandoff = false
        var handoffBody: JSONObject? = null
        val executed = mutableListOf<Triple<String, String, String>>()
        val handoffIds = mutableListOf<String>()
        var releaseEntered: CountDownLatch? = null
        var releaseContinue: CountDownLatch? = null
        val releaseIds = mutableListOf<String>()
        override fun initialize(): OwnerReply {
            initializations++
            return if (confirmInitialize) OwnerReply(OwnerReply.Outcome.REPLIED, JSONObject()
                .put("ok", true).put("initialized", true).put("displayId", 7)
                .put("receipt", JSONObject().put("state", "STARTED"))) else OwnerReply(OwnerReply.Outcome.UNKNOWN)
        }
        override fun execute(id: String, operation: String, parameters: JSONObject): OwnerReply {
            writes++
            executed += Triple(id, operation, parameters.toString())
            if (operation == "status" && statusBody != null) return OwnerReply(OwnerReply.Outcome.REPLIED, statusBody)
            if (operation == "launch" && confirmLaunch) return OwnerReply(OwnerReply.Outcome.REPLIED, JSONObject()
                .put("ok", true).put("launched", true).put("receipt", JSONObject().put("state", "SUCCEEDED")))
            if (operation == "handoff") {
                handoffIds += id
                if (handoffBody != null) return OwnerReply(OwnerReply.Outcome.REPLIED, handoffBody)
                if (confirmHandoff) return OwnerReply(OwnerReply.Outcome.REPLIED, JSONObject()
                    .put("ok", true).put("handedOff", true).put("sourceEmpty", true)
                    .put("receipt", JSONObject().put("state", "SUCCEEDED")))
            }
            if (operation == "release") {
                releaseIds += id
                releaseEntered?.countDown()
                releaseContinue?.await(5, TimeUnit.SECONDS)
                if (confirmRelease) return OwnerReply(OwnerReply.Outcome.REPLIED, JSONObject()
                    .put("ok", true).put("released", true).put("receipt", JSONObject().put("state", "SUCCEEDED")))
            }
            return OwnerReply(OwnerReply.Outcome.UNKNOWN)
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
    private class Parent(override val requiresExplicitApproval: Boolean = true) : ControllerVirtualDisplaySession.Parent {
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
    private class Fixture(includeRelease: Boolean = false, includeHandoff: Boolean = false,
                          includeLaunch: Boolean = false, explicitApproval: Boolean = true) : AutoCloseable {
        val message = Message(MessageId("assistant"), ConversationId("conversation"), RunId("run"),
            Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING)
        val owner = runnerOwner(message)
        val capabilities = SessionCapabilities("session", setOf(R08VirtualDisplayCatalogLeaf.CAPABILITY))
        val parent = Parent(explicitApproval)
        val endpoint = Endpoint()
        val shutdown = Shutdown()
        val constructions = AtomicInteger()
        val executor = Executors.newSingleThreadExecutor()
        val mutableWrites = mutableListOf(R08VirtualDisplayWrite("start", R08VirtualDisplayAction.Start),
            R08VirtualDisplayWrite("initialize", R08VirtualDisplayAction.Initialize),
            R08VirtualDisplayWrite("tap", R08VirtualDisplayAction.Tap(12, 34))).apply {
                if (includeLaunch) add(R08VirtualDisplayWrite("launch",
                    R08VirtualDisplayAction.Launch("org.example.notes", "org.example.notes/.MainActivity")))
                if (includeHandoff) add(R08VirtualDisplayWrite("handoff", R08VirtualDisplayAction.Handoff))
                if (includeRelease) add(R08VirtualDisplayWrite("release", R08VirtualDisplayAction.Release))
            }
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
        fun steps(auth: TaskAuthorization, vararg ids: String, forward: Boolean = true): List<ToolDispatchResult> = work {
            val registry = ToolRegistry()
            session.registeredTools(owner, capabilities, auth).forEach { registry.register(it) }
            val dispatcher = ToolDispatcher(registry.snapshot(), capabilities, ToolLedger(InMemoryToolLedgerStore()))
            val batch = ids.map { id -> ToolInvocation(ToolCallId("$id-call"), R08VirtualDisplayCatalogLeaf.WRITE_TOOL,
                JSONObject().put("step_id", id).toString()) }
            val ticket = session.beginBatch(batch)
            lastTicket = ticket
            dispatcher.dispatchOwned(auth, batch, owner).also { if (forward) session.finishBatch(ticket, it) }
        }
        fun initialized(): TaskAuthorization {
            endpoint.confirmInitialize = true
            val auth = approve()
            assertTrue(steps(auth, "start", "initialize").all { it.outcome is DispatchOutcome.Confirmed })
            return auth
        }
        fun observe(parentDrained: Boolean = true) = work { session.observeCloseOnWorker(parentDrained) }
        override fun close() { session.close(); executor.shutdownNow() }
    }

    private fun retainedTasks(f: Fixture): ControllerVirtualDisplaySession.RetainedTasks {
        f.initialized()
        f.session.close()
        f.endpoint.statusBody = JSONObject().put("ok", true).put("receiptOnly", false)
            .put("receipt", JSONObject().put("state", "SUCCEEDED")).put("ready", true)
            .put("session", "active").put("mutationUncertain", false).put("finishing", false)
            .put("handoffComplete", false).put("releaseAttempted", false).put("sourceState", "occupied")
            .put("displayId", 7).put("retainedTaskIds", org.json.JSONArray(listOf(12, 23, 34)))
            .put("liveTaskIds", org.json.JSONArray(listOf(12, 23))).put("goneTaskIds", org.json.JSONArray(listOf(34)))
        return (f.work { f.session.observeTasksOnceOnWorker(true) } as
            ControllerVirtualDisplaySession.TasksResult.Observed).tasks
    }

    private fun deliveryRequest(f: Fixture): app.daiyu.android.vdapproval.R08CleanupApprovalRequest {
        val tasks = retainedTasks(f)
        assertSame(tasks, (f.work { f.session.observeTasksOnceOnWorker(true) } as
            ControllerVirtualDisplaySession.TasksResult.Observed).tasks)
        assertEquals(1, f.endpoint.executed.count { it.second == "status" })
        assertNull(f.work { f.session.prepareTaskHandoffOnWorker(tasks, listOf(12, 12), true) })
        assertNull(f.work { f.session.prepareTaskHandoffOnWorker(tasks, listOf(34), true) })
        val request = checkNotNull(f.work { f.session.prepareTaskHandoffOnWorker(tasks, listOf(23), true) })
        assertSame(request, f.work { f.session.prepareTaskHandoffOnWorker(tasks, listOf(23), true) })
        assertTrue(request.deliversTasks)
        assertEquals(listOf(23), request.selectedTaskIds)
        assertEquals(listOf("handoff", "release"), request.review.rows.map { it.operation })
        assertSame(f.owner, request.review.plan.owner)
        assertSame(f.capabilities, request.review.plan.capabilities)
        f.endpoint.handoffBody = JSONObject().put("ok", true).put("handedOff", true).put("sourceEmpty", true)
            .put("cleanupOnly", false).put("keptTaskIds", org.json.JSONArray(listOf(23)))
            .put("receipt", JSONObject().put("state", "SUCCEEDED"))
        assertTrue(f.session.answerCleanupFromUi(request, true))
        return request
    }

    @Test fun productDefaultsGrantFrozenPlanWithoutPublishingOrStartingOwner() = Fixture(explicitApproval = false).use { f ->
        val original = f.mutableWrites.toList()
        f.mutableWrites.clear()
        val auth = f.begin().get(5, TimeUnit.SECONDS)
        assertSame(f.parent.auth, auth)
        assertTrue(f.parent.shown.isEmpty()); assertNull(f.session.pendingApproval())
        val prepared = R08VirtualDisplayPlan.prepare(f.owner, f.capabilities, f.parent.lease.generation,
            original, listOf(R08VirtualDisplayRead("status")))
        assertEquals(prepared.effects, auth.allowedWrites)
        assertEquals(1, f.parent.reserves.get()); assertEquals(1, f.constructions.get())
        assertEquals(0, f.endpoint.starts); assertEquals(0, f.endpoint.initializations); assertEquals(0, f.endpoint.writes)
        assertTrue(f.dispatch(auth).second.single().outcome is DispatchOutcome.Confirmed)
        assertEquals(1, f.endpoint.starts)
        f.session.close()
        assertThrows(IllegalStateException::class.java) { f.session.registeredTools(f.owner, f.capabilities, auth) }
        assertEquals(0, f.parent.lease.releases.get()) // revocation != physical exit
    }

    @Test fun productDefaultCancellationDuringRealReservationNeverIssuesOrRevivesGrant() = Fixture(explicitApproval = false).use { f ->
        f.parent.reserveEntered = CountDownLatch(1); f.parent.reserveContinue = CountDownLatch(1)
        val pending = f.begin()
        assertTrue(f.parent.reserveEntered!!.await(5, TimeUnit.SECONDS))
        f.session.close()
        f.parent.reserveContinue!!.countDown()
        assertStopped(pending)
        assertNull(f.parent.auth); assertTrue(f.parent.shown.isEmpty())
        assertEquals(0, f.constructions.get()); assertEquals(0, f.endpoint.starts)
        assertThrows(ExecutionException::class.java) { f.begin().get(5, TimeUnit.SECONDS) }
        assertEquals(1, f.parent.reserves.get())
    }

    @Test fun defaultAppPermissionDoesNotFakeRootStartAndUnknownDoesNotRetry() = Fixture(explicitApproval = false).use { f ->
        f.endpoint.failStart = true
        val auth = f.begin().get(5, TimeUnit.SECONDS)
        assertEquals(0, f.endpoint.starts)
        val result = f.dispatch(auth).second.single()
        assertFalse(result.outcome is DispatchOutcome.Confirmed)
        assertEquals(1, f.endpoint.starts)
        assertEquals(0, f.endpoint.initializations)
        f.session.close()
        assertEquals(ControllerVirtualDisplaySession.CloseResult.Unknown, f.work { f.session.observeCloseOnWorker(true) })
        assertEquals(0, f.parent.lease.releases.get())
        assertEquals(1, f.endpoint.starts)
    }

    @Test fun defaultCleanupGrantsOnlyFreshSelectedPlanAndStillRequiresDrainAndLiveAdmission() = Fixture().use { f ->
        val tasks = retainedTasks(f)
        val request = checkNotNull(f.work { f.session.prepareTaskHandoffOnWorker(tasks, emptyList(), true) })
        assertFalse(f.session.authorizeCleanupWithProductDefaults(request, parentDrained = false))
        assertFalse(f.session.authorizeCleanupWithProductDefaults(request, true) { false })
        assertTrue(f.session.authorizeCleanupWithProductDefaults(request, true))
        assertFalse(f.session.authorizeCleanupWithProductDefaults(request, true))
        assertFalse(f.session.answerCleanupFromUi(request, true))
        assertEquals(ControllerVirtualDisplaySession.CleanupResult.UNAVAILABLE,
            f.work { f.session.releaseInitializedOnceOnWorker(request, true) { false } })
        assertEquals(listOf("status"), f.endpoint.executed.map { it.second })
        assertEquals(0, f.parent.lease.releases.get())
    }

    @Test fun explicitEmptyHandoffCleansWithoutClaimingTaskDelivery() = Fixture().use { f ->
        val tasks = retainedTasks(f)
        val request = checkNotNull(f.work { f.session.prepareTaskHandoffOnWorker(tasks, emptyList(), true) })
        assertTrue(request.hasHandoff)
        assertTrue(request.cleanupOnly)
        assertFalse(request.deliversTasks)
        assertEquals(emptyList<Int>(), request.selectedTaskIds)
        assertEquals("{\"taskIds\":[]}", request.review.plan.writes.first().parameters().toString())
        assertEquals(listOf("status"), f.endpoint.executed.map { it.second })
        f.endpoint.handoffBody = JSONObject().put("ok", true).put("handedOff", true).put("sourceEmpty", true)
            .put("cleanupOnly", true).put("keptTaskIds", org.json.JSONArray())
            .put("receipt", JSONObject().put("state", "SUCCEEDED"))
        f.endpoint.confirmRelease = true
        assertTrue(f.session.answerCleanupFromUi(request, true))
        assertEquals(ControllerVirtualDisplaySession.CleanupResult.CLEANED_AND_RELEASED,
            f.work { f.session.releaseInitializedOnceOnWorker(request, true) })
        assertEquals(listOf("status", "handoff", "release"), f.endpoint.executed.map { it.second })
        assertEquals(0, f.parent.lease.releases.get())
    }

    @Test fun emptyHandoffRequiresCleanupReceiptBeforeRelease() = Fixture().use { f ->
        val tasks = retainedTasks(f)
        val request = checkNotNull(f.work { f.session.prepareTaskHandoffOnWorker(tasks, emptyList(), true) })
        f.endpoint.handoffBody = JSONObject().put("ok", true).put("handedOff", true).put("sourceEmpty", true)
            .put("cleanupOnly", false).put("keptTaskIds", org.json.JSONArray())
            .put("receipt", JSONObject().put("state", "SUCCEEDED"))
        assertTrue(f.session.answerCleanupFromUi(request, true))
        assertEquals(ControllerVirtualDisplaySession.CleanupResult.UNKNOWN,
            f.work { f.session.releaseInitializedOnceOnWorker(request, true) })
        assertTrue(f.endpoint.releaseIds.isEmpty())
    }

    @Test fun shutdownRequiresParentDrainAndLiveVisitBeforeConsumingLookup() = Fixture().use { f ->
        f.dispatch(f.approve()); f.session.close()
        assertFalse(f.work { f.session.shutdownUninitializedOnceOnWorker(false) })
        assertFalse(f.work { f.session.shutdownUninitializedOnceOnWorker(true) { false } })
        assertEquals(0, f.shutdown.lookups)
        assertTrue(f.work { f.session.shutdownUninitializedOnceOnWorker(true) })
        assertEquals(1, f.shutdown.lookups)
        assertEquals(0, f.parent.lease.releases.get())
    }

    @Test fun selectedTasksAreFrozenAndDeliveredBeforeReleaseWithoutReopeningOriginalAuthorization() = Fixture().use { f ->
        val request = deliveryRequest(f)
        f.endpoint.confirmRelease = true
        assertEquals(ControllerVirtualDisplaySession.CleanupResult.DELIVERED_AND_RELEASED,
            f.work { f.session.releaseInitializedOnceOnWorker(request, true) })
        assertEquals(listOf("status", "handoff", "release"), f.endpoint.executed.map { it.second })
        assertEquals("{\"taskIds\":[23]}", f.endpoint.executed.single { it.second == "handoff" }.third)
        assertEquals(0, f.parent.lease.releases.get())
        assertEquals(ControllerVirtualDisplaySession.CleanupResult.UNAVAILABLE,
            f.work { f.session.releaseInitializedOnceOnWorker(request, true) })
        assertEquals(1, f.endpoint.handoffIds.size)
        assertEquals(1, f.endpoint.releaseIds.size)
        f.endpoint.drained()
        assertEquals(ControllerVirtualDisplaySession.CloseResult.Drained, f.observe())
    }

    @Test fun deliveryUnknownOrUnpersistedHandoffNeverSendsReleaseOrReplays() {
        for (lostCompletion in listOf(false, true)) Fixture().use { f ->
            val request = deliveryRequest(f)
            f.endpoint.confirmRelease = true
            if (lostCompletion) f.parent.lease.journal.failHandoffComplete = true
            else f.endpoint.handoffBody = null
            assertEquals(ControllerVirtualDisplaySession.CleanupResult.UNKNOWN,
                f.work { f.session.releaseInitializedOnceOnWorker(request, true) })
            assertEquals(0, f.endpoint.releaseIds.size)
            assertEquals(ControllerVirtualDisplaySession.CleanupResult.UNAVAILABLE,
                f.work { f.session.releaseInitializedOnceOnWorker(request, true) })
            assertEquals(1, f.endpoint.handoffIds.size)
        }
    }

    @Test fun closingAfterHandoffPersistenceAllowsOnlySeparatelyApprovedUnclaimedRelease() = Fixture().use { f ->
        val request = deliveryRequest(f)
        var active = true
        f.parent.lease.journal.onComplete = { if (it.operation == "handoff") active = false }
        assertEquals(ControllerVirtualDisplaySession.CleanupResult.DELIVERED_RETAINED,
            f.work { f.session.releaseInitializedOnceOnWorker(request, true) { active } })
        assertEquals(0, f.endpoint.releaseIds.size)
        val release = checkNotNull(f.work { f.session.prepareInitializedCleanupOnWorker(true) })
        assertFalse(release.deliversTasks)
        assertTrue(f.session.answerCleanupFromUi(release, true))
        f.endpoint.confirmRelease = true
        assertEquals(ControllerVirtualDisplaySession.CleanupResult.CONFIRMED,
            f.work { f.session.releaseInitializedOnceOnWorker(release, true) })
        assertEquals(1, f.endpoint.handoffIds.size)
        assertEquals(1, f.endpoint.releaseIds.size)
    }

    @Test fun revocationDuringEachDurableClaimFencesTheNotYetSentStep() {
        for (operation in listOf("handoff", "release")) Fixture().use { f ->
            val request = deliveryRequest(f)
            var active = true
            f.parent.lease.journal.onClaim = { if (it.operation == operation) active = false }
            val expected = if (operation == "handoff") ControllerVirtualDisplaySession.CleanupResult.UNAVAILABLE
                else ControllerVirtualDisplaySession.CleanupResult.DELIVERED_RETAINED
            assertEquals(expected, f.work { f.session.releaseInitializedOnceOnWorker(request, true) { active } })
            assertEquals(0, f.endpoint.executed.count { it.second == operation })
            assertTrue(f.parent.lease.journal.evidence.any {
                it.first.operation == operation && it.second == R08VirtualDisplayEvidence.NOT_EXECUTED })
            assertNull(f.work { f.session.prepareInitializedCleanupOnWorker(true) })
        }
    }

    @Test fun confirmedDeliveryWithUnknownReleaseKeepsLeaseAndDoesNotOfferReplay() = Fixture().use { f ->
        val request = deliveryRequest(f)
        assertEquals(ControllerVirtualDisplaySession.CleanupResult.DELIVERED_RETAINED,
            f.work { f.session.releaseInitializedOnceOnWorker(request, true) })
        assertNull(f.work { f.session.prepareInitializedCleanupOnWorker(true) })
        assertEquals(1, f.endpoint.handoffIds.size)
        assertEquals(1, f.endpoint.releaseIds.size)
        assertEquals(0, f.parent.lease.releases.get())
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

    @Test fun stoppedInitializedOwnerNeedsSeparateExactApprovalAndKnownReleaseDoesNotDrainLease() = Fixture().use { f ->
        val auth = f.initialized()
        assertNull(f.work { f.session.prepareInitializedCleanupOnWorker(true) }) // no Stop yet
        f.session.close()
        repeat(2) { assertEquals(ControllerVirtualDisplaySession.CloseResult.Unknown, f.observe()) }
        assertEquals(0, f.endpoint.writes)
        assertNull(f.work { f.session.prepareInitializedCleanupOnWorker(false) })
        val request = checkNotNull(f.work { f.session.prepareInitializedCleanupOnWorker(true) })
        assertSame(request, f.work { f.session.prepareInitializedCleanupOnWorker(true) })
        assertSame(f.owner, request.review.plan.owner)
        assertSame(f.capabilities, request.review.plan.capabilities)
        assertEquals(f.parent.lease.generation, request.review.plan.generation)
        assertEquals(listOf("release"), request.review.rows.map { it.operation })
        assertEquals("{}", request.review.rows.single().effectiveParameters)
        assertEquals(ControllerVirtualDisplaySession.CleanupResult.NOT_APPROVED,
            f.work { f.session.releaseInitializedOnceOnWorker(request, true) })
        assertTrue(f.session.answerCleanupFromUi(request, true))
        assertFalse(f.session.answerCleanupFromUi(request, false))
        assertEquals(0, f.endpoint.writes) // approval itself does not send
        assertEquals(ControllerVirtualDisplaySession.CleanupResult.UNAVAILABLE,
            f.work { f.session.releaseInitializedOnceOnWorker(request, false) })
        f.endpoint.confirmRelease = true
        assertEquals(ControllerVirtualDisplaySession.CleanupResult.CONFIRMED,
            f.work { f.session.releaseInitializedOnceOnWorker(request, true) })
        assertEquals(ControllerVirtualDisplaySession.CleanupResult.UNAVAILABLE,
            f.work { f.session.releaseInitializedOnceOnWorker(request, true) })
        assertNull(f.work { f.session.prepareInitializedCleanupOnWorker(true) })
        assertEquals(1, f.endpoint.releaseIds.size)
        val (intent, evidence) = f.parent.lease.journal.evidence.last()
        assertEquals("host-cleanup-release-v1", intent.invocationId)
        assertEquals(intent.operationId, f.endpoint.releaseIds.single())
        assertEquals(R08VirtualDisplayEvidence.CONFIRMED, evidence)
        assertEquals(ControllerVirtualDisplaySession.CloseResult.Unknown, f.observe())
        assertEquals(0, f.parent.lease.releases.get())
        try { f.session.registeredTools(f.owner, f.capabilities, auth); fail("old authority revived") }
        catch (_: IllegalStateException) { }
        f.endpoint.drained()
        assertEquals(ControllerVirtualDisplaySession.CloseResult.Drained, f.observe())
        assertEquals(1, f.parent.lease.releases.get())
        assertEquals(1, f.endpoint.starts)
        assertEquals(1, f.endpoint.initializations)
        assertEquals(0, f.shutdown.shutdowns)
    }

    @Test fun cleanupDenialAndForeignRequestNeverAuthorizeAnotherRetainedSession() {
        Fixture().use { first -> Fixture().use { second ->
            first.initialized(); second.initialized(); first.session.close(); second.session.close()
            val a = checkNotNull(first.work { first.session.prepareInitializedCleanupOnWorker(true) })
            val b = checkNotNull(second.work { second.session.prepareInitializedCleanupOnWorker(true) })
            assertFalse(second.session.answerCleanupFromUi(a, true))
            assertEquals(ControllerVirtualDisplaySession.CleanupResult.NOT_APPROVED,
                second.work { second.session.releaseInitializedOnceOnWorker(a, true) })
            assertTrue(first.session.answerCleanupFromUi(a, false))
            assertFalse(first.session.answerCleanupFromUi(a, true))
            assertNull(first.work { first.session.prepareInitializedCleanupOnWorker(true) })
            assertEquals(ControllerVirtualDisplaySession.CleanupResult.NOT_APPROVED,
                first.work { first.session.releaseInitializedOnceOnWorker(a, true) })
            assertTrue(second.session.answerCleanupFromUi(b, false))
            assertEquals(0, first.endpoint.writes + second.endpoint.writes)
        } }
    }

    @Test fun recordedCleanupUnknownRetainsOutcomeButIndependentPhysicalExitReleasesLease() = Fixture().use { f ->
        f.initialized(); f.session.close()
        val request = checkNotNull(f.work { f.session.prepareInitializedCleanupOnWorker(true) })
        assertTrue(f.session.answerCleanupFromUi(request, true))
        assertEquals(ControllerVirtualDisplaySession.CleanupResult.UNKNOWN,
            f.work { f.session.releaseInitializedOnceOnWorker(request, true) })
        assertEquals(R08VirtualDisplayEvidence.UNKNOWN, f.parent.lease.journal.evidence.last().second)
        assertEquals(ControllerVirtualDisplaySession.CloseResult.Unknown, f.observe())
        assertEquals(0, f.parent.lease.releases.get())
        f.endpoint.confirmRelease = true
        assertEquals(ControllerVirtualDisplaySession.CleanupResult.UNAVAILABLE,
            f.work { f.session.releaseInitializedOnceOnWorker(request, true) })
        f.endpoint.drained()
        assertEquals(ControllerVirtualDisplaySession.CloseResult.Unknown,
            f.work { f.session.observeCloseOnWorker(false) })
        assertEquals(0, f.parent.lease.releases.get())
        assertEquals(ControllerVirtualDisplaySession.CloseResult.Drained, f.observe())
        assertEquals(ControllerVirtualDisplaySession.CloseResult.Drained, f.observe())
        assertEquals(1, f.parent.lease.releases.get())
        assertEquals(1, f.endpoint.releaseIds.size)
        assertNull(f.work { f.session.prepareInitializedCleanupOnWorker(true) })
        assertEquals(R08VirtualDisplayEvidence.UNKNOWN, f.parent.lease.journal.evidence.last().second)
    }

    @Test fun failedCleanupCompletionRemainsUnknownEvenAfterPhysicalExitWithoutReplay() = Fixture().use { f ->
        f.initialized(); f.session.close()
        val request = checkNotNull(f.work { f.session.prepareInitializedCleanupOnWorker(true) })
        assertTrue(f.session.answerCleanupFromUi(request, true))
        f.endpoint.confirmRelease = true
        f.parent.lease.journal.failCleanupComplete = true
        assertEquals(ControllerVirtualDisplaySession.CleanupResult.UNKNOWN,
            f.work { f.session.releaseInitializedOnceOnWorker(request, true) })
        assertEquals(ControllerVirtualDisplaySession.CleanupResult.UNAVAILABLE,
            f.work { f.session.releaseInitializedOnceOnWorker(request, true) })
        f.endpoint.drained()
        assertEquals(ControllerVirtualDisplaySession.CloseResult.Unknown, f.observe())
        assertNull(f.work { f.session.prepareInitializedCleanupOnWorker(true) })
        assertEquals(1, f.endpoint.releaseIds.size)
        assertEquals(0, f.parent.lease.releases.get())
    }

    @Test fun preexistingCleanupClaimIsLookupOnlyNeverReplayedWithNewOperationId() = Fixture().use { f ->
        f.initialized(); f.session.close()
        val request = checkNotNull(f.work { f.session.prepareInitializedCleanupOnWorker(true) })
        f.session.answerCleanupFromUi(request, true)
        val plan = request.review.plan
        val intent = plan.intent("host-cleanup-release-v1", plan.writes.single())
        assertEquals(R08VirtualDisplayPermit.EXECUTE_ONCE, f.parent.lease.journal.claim(intent))
        assertEquals(ControllerVirtualDisplaySession.CleanupResult.UNKNOWN,
            f.work { f.session.releaseInitializedOnceOnWorker(request, true) })
        assertEquals(0, f.endpoint.releaseIds.size)
        assertEquals(R08VirtualDisplayEvidence.UNKNOWN, f.parent.lease.journal.evidence.last().second)
        f.endpoint.drained()
        assertEquals(ControllerVirtualDisplaySession.CloseResult.Drained, f.observe())
        assertEquals(1, f.parent.lease.releases.get())
        assertEquals(0, f.endpoint.releaseIds.size)
        assertEquals(R08VirtualDisplayEvidence.UNKNOWN, f.parent.lease.journal.evidence.last().second)
    }

    @Test fun originalReleaseResultAndPendingFinalFeedbackFenceCleanup() {
        Fixture(includeRelease = true).use { f ->
            val auth = f.initialized()
            f.endpoint.confirmRelease = true
            assertTrue(f.steps(auth, "release").single().outcome is DispatchOutcome.Confirmed)
            f.session.close()
            assertNull(f.work { f.session.prepareInitializedCleanupOnWorker(true) })
            assertEquals(1, f.endpoint.releaseIds.size)
        }
        Fixture(includeRelease = true).use { f ->
            val auth = f.initialized()
            assertTrue(f.steps(auth, "release").single().outcome is DispatchOutcome.Unknown)
            f.session.close()
            assertNull(f.work { f.session.prepareInitializedCleanupOnWorker(true) })
            assertEquals(1, f.endpoint.releaseIds.size)
        }
        Fixture().use { f ->
            f.endpoint.confirmInitialize = true
            f.steps(f.approve(), "start", "initialize", forward = false)
            f.session.close()
            assertNull(f.work { f.session.prepareInitializedCleanupOnWorker(true) })
            assertEquals(0, f.endpoint.writes)
        }
    }

    @Test fun centrallyReplayedOriginalReleaseCannotBeReplacedByCleanup() = Fixture(includeRelease = true).use { f ->
        f.initialized()
        f.work {
            val call = ToolInvocation(ToolCallId("replayed-release"), R08VirtualDisplayCatalogLeaf.WRITE_TOOL,
                "{\"step_id\":\"release\"}")
            val ticket = f.session.beginBatch(listOf(call))
            // Actual central-ledger replay feedback: no local adapter execute callback.
            f.session.finishBatch(ticket, listOf(ToolDispatchResult(call.id,
                DispatchOutcome.Confirmed("{\"ok\":true}", false, replayed = true))))
        }
        f.session.close()
        assertNull(f.work { f.session.prepareInitializedCleanupOnWorker(true) })
        assertEquals(0, f.endpoint.releaseIds.size)
    }

    @Test fun cleanupInFlightFencesConcurrentObservationAndDuplicateExecution() = Fixture().use { f ->
        f.initialized(); f.session.close()
        val request = checkNotNull(f.work { f.session.prepareInitializedCleanupOnWorker(true) })
        f.session.answerCleanupFromUi(request, true)
        f.endpoint.confirmRelease = true
        f.endpoint.releaseEntered = CountDownLatch(1)
        f.endpoint.releaseContinue = CountDownLatch(1)
        val extra = Executors.newSingleThreadExecutor()
        try {
            val sending = extra.submit<ControllerVirtualDisplaySession.CleanupResult> {
                f.session.releaseInitializedOnceOnWorker(request, true)
            }
            assertTrue(f.endpoint.releaseEntered!!.await(5, TimeUnit.SECONDS))
            f.endpoint.drained()
            assertEquals(ControllerVirtualDisplaySession.CloseResult.Unknown, f.observe())
            assertEquals(ControllerVirtualDisplaySession.CleanupResult.UNAVAILABLE,
                f.work { f.session.releaseInitializedOnceOnWorker(request, true) })
            assertEquals(0, f.parent.lease.releases.get())
            f.endpoint.releaseContinue!!.countDown()
            assertEquals(ControllerVirtualDisplaySession.CleanupResult.CONFIRMED, sending.get(5, TimeUnit.SECONDS))
        } finally { f.endpoint.releaseContinue!!.countDown(); extra.shutdownNow() }
        assertEquals(1, f.endpoint.releaseIds.size)
    }

    @Test fun exactLaunchHandoffReleaseUsesOneApprovalJournalAndPhysicalDrainGate() =
        Fixture(includeRelease = true, includeHandoff = true, includeLaunch = true).use { f ->
            val pending = f.begin()
            val request = f.request()
            val launch = request.plan.writes.single { it.operation == "launch" }
            assertEquals("org.example.notes/.MainActivity", launch.parameters().getString("component"))
            assertEquals(listOf("start", "initialize", "input", "launch", "handoff", "release"),
                request.rows.map { it.operation })
            assertEquals(listOf("{\"taskIds\":[]}", "{}"), request.rows.takeLast(2).map { it.effectiveParameters })
            f.mutableWrites.clear()
            assertTrue(f.session.answerFromUi(request, true))
            val auth = pending.get(5, TimeUnit.SECONDS)
            f.endpoint.confirmInitialize = true
            f.endpoint.confirmLaunch = true
            f.endpoint.confirmHandoff = true
            f.endpoint.confirmRelease = true
            assertTrue(f.steps(auth, "start", "initialize", "launch", "handoff", "release")
                .all { it.outcome is DispatchOutcome.Confirmed })
            assertEquals(listOf("launch", "handoff", "release"), f.endpoint.executed.map { it.second })
            val sentLaunch = JSONObject(f.endpoint.executed.first().third)
            assertEquals(launch.canonicalParameters, request.plan.intent("launch-call", launch).canonicalParameters)
            assertEquals(launch.parameters().toString(), sentLaunch.toString())
            val claims = f.parent.lease.journal.claims.values
            assertEquals(setOf(f.parent.lease.generation), claims.map { it.generation }.toSet())
            assertEquals(setOf(request.plan.bindingKey), claims.map { it.bindingKey }.toSet())
            assertEquals(launch.canonicalParameters, claims.single { it.operation == "launch" }.canonicalParameters)
            assertEquals(listOf("start-call", "initialize-call", "launch-call", "handoff-call", "release-call"),
                f.parent.lease.journal.finals.map { it.first })
            assertTrue(f.parent.lease.journal.finals.all { it.second == R08VirtualDisplayEvidence.CONFIRMED })
            f.session.close()
            assertNull(f.work { f.session.prepareInitializedCleanupOnWorker(true) })
            assertEquals(ControllerVirtualDisplaySession.CloseResult.Unknown, f.observe())
            assertEquals(0, f.parent.lease.releases.get())
            f.endpoint.drained()
            assertEquals(ControllerVirtualDisplaySession.CloseResult.Drained, f.observe())
            assertEquals(1, f.parent.reserves.get())
            assertEquals(1, f.parent.lease.releases.get())
        }

    @Test fun incompleteHandoffBusinessEvidenceNeverSendsReleaseOrReplaysHandoff() {
        val success = JSONObject().put("ok", true).put("handedOff", true).put("sourceEmpty", true)
            .put("receipt", JSONObject().put("state", "SUCCEEDED"))
        val badBodies = listOf<JSONObject?>(null,
            JSONObject(success.toString()).put("sourceEmpty", false),
            JSONObject(success.toString()).put("handedOff", false),
            JSONObject(success.toString()).put("receiptOnly", true),
            JSONObject(success.toString()).put("mutationUncertain", true),
            JSONObject(success.toString()).put("receipt", JSONObject().put("state", "UNKNOWN")),
            JSONObject().put("ok", false).put("error", "HANDOFF_UNCERTAIN")
                .put("sideEffectsAttempted", true).put("retryable", false)
                .put("handoffPhase", "remove-anchor")
                .put("completedTaskIds", org.json.JSONArray().put(557))
                .put("receipt", JSONObject().put("state", "UNKNOWN")))
        badBodies.forEach { body -> Fixture(includeRelease = true, includeHandoff = true).use { f ->
            val auth = f.initialized()
            f.endpoint.handoffBody = body
            f.endpoint.confirmRelease = true
            val results = f.steps(auth, "handoff", "release")
            assertEquals(2, results.size)
            assertTrue(results[0].outcome is DispatchOutcome.Unknown)
            assertEquals(DispatchOutcome.NotExecuted("batch_stopped_before_execution"), results[1].outcome)
            assertTrue(f.steps(auth, "handoff").single().outcome is DispatchOutcome.Unknown)
            assertEquals(1, f.endpoint.handoffIds.size)
            assertTrue(f.endpoint.releaseIds.isEmpty())
            f.session.close()
            assertNull(f.work { f.session.prepareInitializedCleanupOnWorker(true) })
            f.endpoint.drained()
            assertEquals(ControllerVirtualDisplaySession.CloseResult.Unknown, f.observe())
            assertEquals(0, f.parent.lease.releases.get())
            assertEquals(1, f.parent.reserves.get())
        } }
    }

    @Test fun releaseCannotSkipApprovedHandoffAndUnknownDoesNotTriggerRepairSend() =
        Fixture(includeRelease = true, includeHandoff = true).use { f ->
            val auth = f.initialized()
            f.endpoint.confirmRelease = true
            f.endpoint.confirmHandoff = true
            assertTrue(f.steps(auth, "release").single().outcome is DispatchOutcome.Unknown)
            assertTrue(f.steps(auth, "handoff").single().outcome is DispatchOutcome.Unknown)
            assertTrue(f.endpoint.executed.isEmpty())
            f.session.close()
            assertNull(f.work { f.session.prepareInitializedCleanupOnWorker(true) })
            assertEquals(1, f.parent.reserves.get())
        }

    @Test fun stoppedLaunchWithoutOriginalHandoffCannotGrowReleaseCleanupIntoUnapprovedHandoff() =
        Fixture(includeHandoff = true, includeLaunch = true).use { f ->
            val auth = f.initialized()
            f.endpoint.confirmLaunch = true
            assertTrue(f.steps(auth, "launch").single().outcome is DispatchOutcome.Confirmed)
            f.session.close()
            assertNull(f.work { f.session.prepareInitializedCleanupOnWorker(true) })
            assertTrue(f.endpoint.handoffIds.isEmpty())
            assertTrue(f.endpoint.releaseIds.isEmpty())
            assertEquals(ControllerVirtualDisplaySession.CloseResult.Unknown, f.observe())
        }

    @Test fun knownOriginalHandoffAllowsOnlySeparatelyApprovedReleaseCleanup() =
        Fixture(includeHandoff = true, includeLaunch = true).use { f ->
            val auth = f.initialized()
            f.endpoint.confirmLaunch = true
            f.endpoint.confirmHandoff = true
            assertTrue(f.steps(auth, "launch", "handoff").all { it.outcome is DispatchOutcome.Confirmed })
            f.session.close()
            val request = checkNotNull(f.work { f.session.prepareInitializedCleanupOnWorker(true) })
            assertEquals(listOf("release"), request.review.rows.map { it.operation })
            assertTrue(f.session.answerCleanupFromUi(request, true))
            f.endpoint.confirmRelease = true
            assertEquals(ControllerVirtualDisplaySession.CleanupResult.CONFIRMED,
                f.work { f.session.releaseInitializedOnceOnWorker(request, true) })
            assertEquals(listOf("launch", "handoff", "release"), f.endpoint.executed.map { it.second })
            assertEquals(1, f.endpoint.handoffIds.size)
            assertEquals(ControllerVirtualDisplaySession.CloseResult.Unknown, f.observe())
            f.endpoint.drained()
            assertEquals(ControllerVirtualDisplaySession.CloseResult.Drained, f.observe())
        }

    @Test fun lostHandoffCompletionAndCentralDowngradeBothFenceReleaseAndCleanup() {
        listOf(false, true).forEach { lostCompletion -> Fixture(includeRelease = true, includeHandoff = true).use { f ->
            val auth = f.initialized()
            f.endpoint.confirmHandoff = true
            f.parent.lease.journal.failHandoffComplete = lostCompletion
            val results = f.steps(auth, "handoff", forward = false)
            f.work {
                f.session.finishBatch(checkNotNull(f.lastTicket), if (lostCompletion) results else
                    listOf(ToolDispatchResult(results.single().callId, DispatchOutcome.Unknown("central-write-lost"))))
            }
            assertTrue(f.steps(auth, "release").single().outcome is DispatchOutcome.Unknown)
            assertEquals(1, f.endpoint.handoffIds.size)
            assertTrue(f.endpoint.releaseIds.isEmpty())
            assertTrue(f.parent.lease.journal.finals.any {
                it.first == "handoff-call" && it.second == R08VirtualDisplayEvidence.UNKNOWN
            })
            f.session.close()
            assertNull(f.work { f.session.prepareInitializedCleanupOnWorker(true) })
            f.endpoint.drained()
            assertEquals(ControllerVirtualDisplaySession.CloseResult.Unknown, f.observe())
        } }
    }

    @Test fun centralHandoffReplayAloneIsNotAuthorityForExtraCleanupRelease() =
        Fixture(includeHandoff = true).use { f ->
            f.initialized()
            f.work {
                val call = ToolInvocation(ToolCallId("replayed-handoff"), R08VirtualDisplayCatalogLeaf.WRITE_TOOL,
                    "{\"step_id\":\"handoff\"}")
                val ticket = f.session.beginBatch(listOf(call))
                f.session.finishBatch(ticket, listOf(ToolDispatchResult(call.id,
                    DispatchOutcome.Confirmed("{\"ok\":true}", false, replayed = true))))
            }
            f.session.close()
            assertNull(f.work { f.session.prepareInitializedCleanupOnWorker(true) })
            assertTrue(f.endpoint.executed.isEmpty())
            assertEquals(0, f.parent.lease.releases.get())
        }

    @Test fun explicitUninitializedShutdownIsLookupGatedOnceAndNotDrainEvidence() = Fixture().use { f ->
        f.dispatch(f.approve()); f.session.close()
        assertEquals(0, f.shutdown.lookups)
        assertTrue(f.work { f.session.shutdownUninitializedOnceOnWorker(true) })
        assertFalse(f.work { f.session.shutdownUninitializedOnceOnWorker(true) })
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
        assertFalse(f.work { f.session.shutdownUninitializedOnceOnWorker(true) })
        f.shutdown.proof = ControllerVirtualDisplaySession.UninitializedEvidence.PROVEN_UNINITIALIZED
        assertFalse(f.work { f.session.shutdownUninitializedOnceOnWorker(true) })
        assertEquals(1, f.shutdown.lookups)
        assertEquals(0, f.shutdown.shutdowns)
        assertEquals(ControllerVirtualDisplaySession.CloseResult.Unknown, f.observe())
    }
}
