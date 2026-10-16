package app.daiyu.android.integration.tools

import app.daiyu.android.domain.*
import app.daiyu.android.domain.tools.*
import app.daiyu.android.vd.*
import app.daiyu.android.vd.runtime.OwnerV2Contract
import app.daiyu.android.vdapproval.VirtualDisplayApproval
import app.daiyu.android.vdapproval.VirtualDisplayApprovalRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Offline sources only. Real Runner-issued token and central dispatcher; no process/socket/display. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
class R08VirtualDisplayCatalogLeafTest {
    private val message = Message(MessageId("assistant"), ConversationId("conversation"), RunId("run"),
        Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING)

    /** Intentionally test-only memory journal. Production must provide durable atomic claims. */
    private class Journal : R08VirtualDisplayJournal {
        val claims = linkedMapOf<String, R08VirtualDisplayIntent>()
        val final = mutableListOf<R08VirtualDisplayEvidence>()
        var lookupOnly = false
        var failClaim = false
        var failFinal = false
        val finalEntered = CountDownLatch(1)
        var finalPause: CountDownLatch? = null
        override fun claim(intent: R08VirtualDisplayIntent): R08VirtualDisplayPermit {
            if (failClaim) throw IOException("test journal unavailable")
            val old = claims.putIfAbsent(intent.operationId, intent)
            check(old == null || old == intent)
            return if (lookupOnly || old != null) R08VirtualDisplayPermit.LOOKUP_ONLY else R08VirtualDisplayPermit.EXECUTE_ONCE
        }
        override fun complete(intent: R08VirtualDisplayIntent, evidence: R08VirtualDisplayEvidence) = Unit
        override fun finalOutcome(bindingKey: String, generation: String, invocationId: String, evidence: R08VirtualDisplayEvidence) {
            finalEntered.countDown()
            finalPause?.await(5, TimeUnit.SECONDS)
            if (failFinal) throw IOException("test final journal unavailable")
            final.add(evidence)
        }
    }

    /** Uses the production v2 client and validator with a scripted transport. */
    private class Endpoint(val plan: R08VirtualDisplayPlan) : R08VirtualDisplayEndpoint {
        var starts = 0
        var observations = 0
        var drop = false
        var receiptOnly = false
        var negative = false
        var lifecycle = AndroidVirtualDisplayOwnerHost.Lifecycle.UNKNOWN
        val requests = mutableListOf<JSONObject>()
        val entered = CountDownLatch(1)
        var pause: CountDownLatch? = null
        private val transport = object : OwnerTransport {
            override fun exchange(line: String): String {
                val request = JSONObject(line)
                requests.add(request)
                entered.countDown()
                pause?.await(5, TimeUnit.SECONDS)
                if (drop) throw IOException("test EOF")
                return response(request).toString()
            }
            override fun disconnect() = Unit
        }
        private val client = VirtualDisplayOwnerClient(plan.generation, plan.binding,
            OwnerCredential("00000000000000000000000000000000"), transport)
        override fun start(generation: String, binding: OwnerRunBinding): Boolean { starts++; return true }
        override fun initialize() = client.initialize()
        override fun execute(id: String, operation: String, parameters: JSONObject) = client.execute(id, operation, parameters)
        override fun lookup(id: String, operation: String, parameters: JSONObject) = client.lookup(id, operation, parameters)
        override fun lookupGeneration() = client.lookupGeneration()
        override fun observe(): AndroidVirtualDisplayOwnerHost.Lifecycle { observations++; return lifecycle }

        private fun response(q: JSONObject): JSONObject {
            val wire = q.getString("op")
            val id = q.optString("operationId", "")
            val op = if (wire == "receipt") q.optString("operation", "") else wire
            val only = receiptOnly || wire == "receipt"
            val state = if (negative) "UNKNOWN" else if (wire == "initialize") "STARTED" else "SUCCEEDED"
            val digest = if (id.isEmpty()) OwnerV2Contract.generationDigest(plan.generation, plan.binding.trusted)
            else OwnerV2Contract.identity(plan.generation, id, op, plan.binding.trusted, q.getJSONObject("parameters"))
            val receipt = JSONObject().put("generation", plan.generation).put("operationId", id)
                .put("operation", if (id.isEmpty()) "" else op).put("generationReceipt", id.isEmpty())
                .put("digest", digest).put("bindingDigest", plan.bindingKey).put("state", state)
            if (state != "STARTED") receipt.put("result", JSONObject().put("ok", !negative)
                .put("released", !negative && (id.isEmpty() || op == "release")))
            val result = JSONObject().put("v", 2).put("op", wire).put("ok", !negative)
                .put("generation", plan.generation).put("receipt", receipt)
            if (only) return result.put("receiptOnly", true).put("found", true).put("executed", false)
            if (negative) return result.put("receiptOnly", false).put("error", "INPUT_FAILED").put("message", "test failure")
            when (wire) {
                "initialize" -> result.put("initialized", true).put("displayId", 7).put("uniqueId", "virtual:test")
                "input" -> result.put("receiptOnly", false).put("executed", true)
                    .put("kind", q.getJSONObject("parameters").getString("kind")).put("displayId", 7).put("exitCode", 0)
                "snapshot" -> result.put("receiptOnly", false).put("displayId", 7).put("frameCount", 1)
                    .put("timestampNs", 1L).put("format", "png").put("width", 640).put("height", 480)
                "release" -> result.put("receiptOnly", false).put("released", true).put("displayId", 7).put("uniqueId", "virtual:test")
                else -> error("unexpected request")
            }
            return result
        }
    }

    private inner class Fixture(store: ToolLedgerStore = InMemoryToolLedgerStore(), enabled: Boolean = true) {
        val owner = runnerOwner(message)
        val caps = SessionCapabilities("session", if (enabled) setOf(R08VirtualDisplayCatalogLeaf.CAPABILITY) else emptySet())
        val plan = R08VirtualDisplayPlan.prepare(owner, caps, "stable_generation", listOf(
            R08VirtualDisplayWrite("start", R08VirtualDisplayAction.Start),
            R08VirtualDisplayWrite("initialize", R08VirtualDisplayAction.Initialize),
            R08VirtualDisplayWrite("tap", R08VirtualDisplayAction.Tap(12, 34)),
            R08VirtualDisplayWrite("release", R08VirtualDisplayAction.Release)
        ), listOf(R08VirtualDisplayRead("frame", snapshot = true, include = false)))
        val request = VirtualDisplayApprovalRequest(plan)
        val approval: VirtualDisplayApproval = run {
            var approved: VirtualDisplayApproval? = null
            request.answerFromUi(true) { approved = it }
            checkNotNull(approved)
        }
        val auth = TaskAuthorization("session", "run", approval.requiredEffects)
        val journal = Journal()
        val endpoint = Endpoint(plan)
        val leaf = approval.bind(owner, caps, auth, endpoint, journal)
        val registry = ToolRegistry().also { registry -> leaf.registeredTools.forEach { registry.register(it) } }
        val dispatcher = ToolDispatcher(registry.snapshot(), caps, ToolLedger(store))
        fun step(id: String, call: String = id) = ToolInvocation(ToolCallId(call), R08VirtualDisplayCatalogLeaf.WRITE_TOOL,
            JSONObject().put("step_id", id).toString())
        fun frame() = ToolInvocation(ToolCallId("frame-call"), R08VirtualDisplayCatalogLeaf.READ_TOOL, "{\"read_id\":\"frame\"}")
        fun dispatch(vararg calls: ToolInvocation, authorization: TaskAuthorization = auth): List<ToolDispatchResult> = worker {
            val batch = calls.toList()
            val ticket = leaf.beginBatch(batch)
            dispatcher.dispatchOwned(authorization, batch, owner).also { leaf.finishBatch(ticket, it) }
        }
        fun initialize() { assertConfirmed(dispatch(step("start"))); assertConfirmed(dispatch(step("initialize"))) }
    }

    private fun <T> worker(block: () -> T): T {
        val executor = Executors.newSingleThreadExecutor()
        return try { executor.submit<T> { block() }.get(10, TimeUnit.SECONDS) } finally { executor.shutdownNow() }
    }
    private fun assertConfirmed(results: List<ToolDispatchResult>) = assertTrue(results.all { it.outcome is DispatchOutcome.Confirmed })
    private fun assertDenied(results: List<ToolDispatchResult>) = assertTrue(results.all { it.outcome is DispatchOutcome.NotExecuted })
    private fun closeResult(f: Fixture): R08VirtualDisplayCloseResult {
        val done = CountDownLatch(1)
        var result: R08VirtualDisplayCloseResult? = null
        f.leaf.close()
        f.leaf.observeClose { result = it; done.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        return checkNotNull(result)
    }

    @Test fun approvalRequiresExplicitAnswerAndCannotBeReboundToCopiedCapabilities() {
        val f = Fixture()
        var called = false
        val request = VirtualDisplayApprovalRequest(f.plan)
        assertEquals(listOf("start", "initialize", "input", "release"), request.rows.map { it.operation })
        assertFalse(called)
        request.answerFromUi(false) { assertNull(it); called = true }
        assertTrue(called)
        try {
            request.answerFromUi(true) { fail("cancelled approval was reused") }
            fail("second answer accepted")
        } catch (_: IllegalStateException) { }
        try {
            f.leaf.bind(f.owner, SessionCapabilities("session", f.caps.enabled), f.auth)
            fail("copied capabilities accepted")
        } catch (_: IllegalArgumentException) { }
        val otherRequest = VirtualDisplayApprovalRequest(f.plan)
        var otherApproval: VirtualDisplayApproval? = null
        otherRequest.answerFromUi(true) { otherApproval = it }
        try {
            checkNotNull(otherApproval).bind(f.owner, f.caps,
                TaskAuthorization("session", "run", setOf(f.plan.writes.first().effect!!)), f.endpoint, f.journal)
            fail("start approval silently authorized initialize/GUI writes")
        } catch (_: IllegalArgumentException) { }
        assertEquals(0, f.endpoint.starts)
        assertTrue(f.endpoint.requests.isEmpty())
    }

    @Test fun setupApprovalDefinitionsPrepareAndInvalidWholeBatchDoNoIO() {
        val f = Fixture()
        assertEquals(0, f.endpoint.starts)
        assertTrue(f.endpoint.requests.isEmpty())
        f.leaf.registeredTools.forEach { tool ->
            assertFalse(tool.definition.schema.jsonSchema().contains("generation"))
            val args = if (tool.definition.effect == ToolEffect.WRITE) "{\"step_id\":\"start\"}" else "{\"read_id\":\"frame\"}"
            tool.prepare(tool.definition.schema.parse(args))
        }
        assertDenied(f.dispatch(f.step("start"), ToolInvocation(ToolCallId("bad"), "unknown_tool", "{}")))
        assertEquals(0, f.endpoint.starts)
        assertTrue(f.journal.claims.isEmpty())
        f.endpoint.lifecycle = AndroidVirtualDisplayOwnerHost.Lifecycle.NOT_STARTED
        assertEquals(R08VirtualDisplayCloseResult.Drained, closeResult(f))
    }

    @Test fun readsNeverStartOrInitializeAndStartDoesNotCreateDisplay() {
        val f = Fixture()
        assertDenied(f.dispatch(f.frame()))
        assertEquals(0, f.endpoint.starts)
        assertConfirmed(f.dispatch(f.step("start")))
        assertFalse(f.leaf.heldState().initialized)
        assertTrue(f.endpoint.requests.isEmpty())
        // A different invocation ID avoids replaying the earlier definite denial.
        assertDenied(f.dispatch(f.frame().copy(id = ToolCallId("frame-before-init"))))
        assertConfirmed(f.dispatch(f.step("initialize")))
        assertConfirmed(f.dispatch(f.frame().copy(id = ToolCallId("frame-after-init"))))
        assertEquals(listOf("initialize", "snapshot"), f.endpoint.requests.map { it.getString("op") })
        assertTrue(f.leaf.heldState().initialized)
    }

    @Test fun copiedAuthorizationDisabledCapabilityAndOwnerFieldInjectionFailBeforeIO() {
        val f = Fixture()
        assertDenied(f.dispatch(f.step("start"), authorization = TaskAuthorization("session", "run", f.auth.allowedWrites)))
        for (key in listOf("token", "generation", "classpath", "envelope", "display_id", "operation_id")) {
            val args = JSONObject().put("step_id", "start").put(key, "forbidden").toString()
            assertDenied(f.dispatch(ToolInvocation(ToolCallId("inject-$key"), R08VirtualDisplayCatalogLeaf.WRITE_TOOL, args)))
        }
        assertEquals(0, f.endpoint.starts)
        val disabled = Fixture(enabled = false)
        assertDenied(disabled.dispatch(disabled.step("start")))
        assertEquals(0, disabled.endpoint.starts)
    }

    @Test fun effectsAreExactAndOperationIdsAreStableRealCallDerivedAndNotStepIds() {
        val f = Fixture()
        val tap = f.plan.writes.single { it.id == "tap" }
        val same = R08VirtualDisplayPlan.prepare(f.owner, f.caps, "stable_generation",
            listOf(R08VirtualDisplayWrite("another-label", R08VirtualDisplayAction.Tap(12, 34))))
        val changed = R08VirtualDisplayPlan.prepare(f.owner, f.caps, "stable_generation",
            listOf(R08VirtualDisplayWrite("tap", R08VirtualDisplayAction.Tap(12, 35))))
        assertEquals(tap.effect, same.writes.single().effect)
        assertNotEquals(tap.effect, changed.writes.single().effect)
        val a = f.plan.intent("actual/model call id !", tap)
        assertEquals(a, f.plan.intent("actual/model call id !", tap))
        assertEquals(a, same.intent("actual/model call id !", same.writes.single()))
        assertTrue(a.operationId.matches(Regex("[a-f0-9]{64}")))
        assertNotEquals(a.operationId, f.plan.intent("different-call", tap).operationId)
        assertEquals("{\"kind\":\"tap\",\"x\":12,\"y\":34}", a.canonicalParameters)
        val snapshot = f.plan.reads.single().parameters()
        assertEquals(6 * 1024 * 1024, snapshot.getInt("maxBytes"))
        assertFalse(snapshot.has("displayId"))
        assertEquals(4, f.plan.effects.size) // start, initialize, input, release: never a broad GUI grant
    }

    @Test fun centralLedgerReplayDoesNotReexecuteAndSendsStableJournalOperationId() {
        val f = Fixture()
        f.initialize()
        val call = f.step("tap", "call/with spaces")
        assertConfirmed(f.dispatch(call))
        assertConfirmed(f.dispatch(call))
        val inputs = f.endpoint.requests.filter { it.getString("op") == "input" }
        assertEquals(1, inputs.size)
        assertEquals(f.plan.intent(call.id.value, f.plan.writes.single { it.id == "tap" }).operationId,
            inputs.single().getString("operationId"))
        assertEquals(3, f.journal.claims.size)
    }

    @Test fun journalExistingClaimIsLookupOnlyAndNeverBusinessSuccess() {
        val f = Fixture()
        f.initialize()
        f.journal.lookupOnly = true
        val outcome = f.dispatch(f.step("tap")).single().outcome
        assertTrue(outcome is DispatchOutcome.Unknown)
        assertEquals("receipt", f.endpoint.requests.last().getString("op"))
        assertFalse(f.endpoint.requests.any { it.getString("op") == "input" })
        assertTrue(f.leaf.heldState().uncertain)
    }

    @Test fun receiptOnlyNegativeAndDisconnectedResponsesAreUnknownNeverNotExecuted() {
        for (mode in listOf("receipt", "negative", "disconnect")) {
            val f = Fixture()
            f.initialize()
            f.endpoint.receiptOnly = mode == "receipt"
            f.endpoint.negative = mode == "negative"
            f.endpoint.drop = mode == "disconnect"
            assertTrue(f.dispatch(f.step("tap")).single().outcome is DispatchOutcome.Unknown)
            assertTrue(f.dispatch(f.step("tap")).single().outcome is DispatchOutcome.Unknown)
            assertEquals(1, f.endpoint.requests.count { it.getString("op") == "input" })
            assertTrue(closeResult(f) is R08VirtualDisplayCloseResult.Unknown)
            assertFalse(f.endpoint.requests.any { it.getString("op") == "release" })
        }
    }

    @Test fun centralPersistenceFailureOverridesLocalSuccessAndRetainsEvenPhysicallyDrainedHost() {
        var transactions = 0
        val memory = InMemoryToolLedgerStore()
        val failing = object : ToolLedgerStore {
            override fun <T> transaction(block: (MutableList<LedgerEntry>) -> T): T {
                transactions++
                if (transactions == 2) throw IOException("test central finish failed")
                return memory.transaction(block)
            }
        }
        val f = Fixture(store = failing)
        assertTrue(f.dispatch(f.step("start")).single().outcome is DispatchOutcome.Unknown)
        assertEquals(1, f.endpoint.starts)
        assertTrue(f.leaf.heldState().uncertain)
        assertEquals(R08VirtualDisplayEvidence.UNKNOWN, f.journal.final.last())
        f.endpoint.lifecycle = AndroidVirtualDisplayOwnerHost.Lifecycle.DRAINED
        assertTrue(closeResult(f) is R08VirtualDisplayCloseResult.Unknown)
    }

    @Test fun journalFailureNeverFallsBackToRandomIdOrStartsOwner() {
        val f = Fixture()
        f.journal.failClaim = true
        assertTrue(f.dispatch(f.step("start")).single().outcome is DispatchOutcome.Unknown)
        assertEquals(0, f.endpoint.starts)
        assertTrue(f.journal.claims.isEmpty())
        assertTrue(closeResult(f) is R08VirtualDisplayCloseResult.Unknown)
    }

    private fun rejectedFeedback(block: () -> Unit) {
        try { block(); fail("invalid feedback accepted") }
        catch (_: IllegalArgumentException) { }
        catch (_: IllegalStateException) { }
    }

    @Test fun centralLedgerUnknownShortCircuitWithoutAnyFeedbackCannotDrain() {
        val store = InMemoryToolLedgerStore()
        val f = Fixture(store)
        assertConfirmed(f.dispatch(f.step("start", "earlier-start")))
        // Simulate an already durable ambiguous effect. A different ID hits semantic protection,
        // so neither local execute nor the owner journal gets called on the second dispatch.
        store.transaction { entries -> entries[0] = entries[0].copy(state = LedgerState.UNKNOWN) }
        val calls = listOf(f.step("start", "protected-start"))
        f.leaf.beginBatch(calls)
        assertEquals(1, f.leaf.heldState().pendingFinalOutcomes)
        val results = worker { f.dispatcher.dispatchOwned(f.auth, calls, f.owner) }
        assertEquals(DispatchOutcome.Unknown("unknown_effect_protected"), results.single().outcome)
        assertEquals(1, f.endpoint.starts)
        assertEquals(1, f.journal.claims.size)
        assertEquals(1, f.journal.final.size)
        assertFalse(f.leaf.heldState().uncertain) // No feedback at all, not even UNKNOWN delivery.
        f.endpoint.lifecycle = AndroidVirtualDisplayOwnerHost.Lifecycle.DRAINED
        assertTrue(closeResult(f) is R08VirtualDisplayCloseResult.Unknown)
        assertEquals(0, f.endpoint.observations)
        assertEquals(1, f.leaf.heldState().pendingFinalOutcomes)
    }

    @Test fun localExecuteWithoutFinalFeedbackKeepsItsAccountingAfterReturn() {
        val f = Fixture()
        val calls = listOf(f.step("start"))
        f.leaf.beginBatch(calls)
        assertConfirmed(worker { f.dispatcher.dispatchOwned(f.auth, calls, f.owner) })
        assertEquals(0, f.leaf.heldState().activeExecutions)
        assertEquals(1, f.leaf.heldState().pendingFinalOutcomes)
        assertFalse(f.leaf.heldState().uncertain)
        assertTrue(f.journal.final.isEmpty())
        f.endpoint.lifecycle = AndroidVirtualDisplayOwnerHost.Lifecycle.DRAINED
        assertTrue(closeResult(f) is R08VirtualDisplayCloseResult.Unknown)
    }

    @Test fun dispatcherExceptionWithoutFeedbackKeepsPreRegisteredTicket() {
        val f = Fixture()
        val calls = listOf(f.step("start"))
        f.leaf.beginBatch(calls)
        worker {
            try {
                f.dispatcher.dispatchOwned(f.auth, calls, f.owner) { throw IOException("test dispatcher interruption") }
                fail("dispatcher unexpectedly returned")
            } catch (_: IOException) { }
        }
        assertEquals(0, f.endpoint.starts)
        assertEquals(0, f.leaf.heldState().activeExecutions)
        assertEquals(1, f.leaf.heldState().pendingFinalOutcomes)
        f.endpoint.lifecycle = AndroidVirtualDisplayOwnerHost.Lifecycle.NOT_STARTED
        assertTrue(closeResult(f) is R08VirtualDisplayCloseResult.Unknown)
    }

    @Test fun executeWithoutTicketFailsClosedAndCannotBeRetroactivelyReleased() {
        val f = Fixture()
        val calls = listOf(f.step("start"))
        val results = worker { f.dispatcher.dispatchOwned(f.auth, calls, f.owner) }
        assertEquals(DispatchOutcome.Unknown("virtual_display_batch_not_registered"), results.single().outcome)
        assertEquals(0, f.endpoint.starts)
        assertEquals(1, f.leaf.heldState().pendingFinalOutcomes)
        rejectedFeedback { f.leaf.beginBatch(calls) }
        f.endpoint.lifecycle = AndroidVirtualDisplayOwnerHost.Lifecycle.DRAINED
        assertTrue(closeResult(f) is R08VirtualDisplayCloseResult.Unknown)
    }

    @Test fun missingWholeBatchResultsNeverReleaseEvenWhenAllR08ResultsArePresent() {
        val f = Fixture()
        val calls = mutableListOf(f.step("start"), ToolInvocation(ToolCallId("other"), "unknown_tool", "{}"))
        val ticket = f.leaf.beginBatch(calls)
        val results = worker { f.dispatcher.dispatchOwned(f.auth, calls, f.owner) }
        calls.clear() // The ticket must own the original snapshot, not this caller-owned list.
        rejectedFeedback { f.leaf.finishBatch(ticket, results.take(1)) }
        assertEquals(1, f.leaf.heldState().pendingFinalOutcomes)
        assertTrue(f.leaf.heldState().uncertain)
        assertTrue(f.journal.final.isEmpty())
        f.endpoint.lifecycle = AndroidVirtualDisplayOwnerHost.Lifecycle.NOT_STARTED
        assertTrue(closeResult(f) is R08VirtualDisplayCloseResult.Unknown)
    }

    @Test fun missingDuplicateAndWrongOrderedResultsFailBeforeAnyJournalRelease() {
        for (mode in listOf("missing", "duplicate", "reordered", "extra", "wrong-id")) {
            val f = Fixture()
            val calls = listOf(f.step("start"), f.step("initialize"))
            val ticket = f.leaf.beginBatch(calls)
            val results = worker { f.dispatcher.dispatchOwned(f.auth, calls, f.owner) }
            val invalid = when (mode) {
                "missing" -> emptyList()
                "duplicate" -> listOf(results[0], results[0])
                "reordered" -> results.reversed()
                "extra" -> results + results.last()
                else -> listOf(results[0], results[1].copy(callId = ToolCallId("forged")))
            }
            rejectedFeedback { f.leaf.finishBatch(ticket, invalid) }
            assertEquals(2, f.leaf.heldState().pendingFinalOutcomes)
            assertTrue(f.leaf.heldState().uncertain)
            assertTrue(f.journal.final.isEmpty())
            f.endpoint.lifecycle = AndroidVirtualDisplayOwnerHost.Lifecycle.DRAINED
            assertTrue(closeResult(f) is R08VirtualDisplayCloseResult.Unknown)
        }
    }

    @Test fun forgedForeignAndAlreadyFinishedTicketsCannotReleasePendingBatch() {
        val f = Fixture()
        val firstCalls = listOf(f.step("start"))
        val first = f.leaf.beginBatch(firstCalls)
        val firstResults = worker { f.dispatcher.dispatchOwned(f.auth, firstCalls, f.owner) }
        worker { f.leaf.finishBatch(first, firstResults) }
        assertEquals(0, f.leaf.heldState().pendingFinalOutcomes)
        val secondCalls = listOf(f.step("initialize"))
        f.leaf.beginBatch(secondCalls)
        val secondResults = worker { f.dispatcher.dispatchOwned(f.auth, secondCalls, f.owner) }
        val other = Fixture()
        val foreign = other.leaf.beginBatch(listOf(other.step("initialize")))
        for (ticket in listOf(R08VirtualDisplayCatalogLeaf.BatchTicket(), foreign, first)) {
            rejectedFeedback { f.leaf.finishBatch(ticket, secondResults) }
            assertEquals(1, f.leaf.heldState().pendingFinalOutcomes)
            assertEquals(1, f.journal.final.size)
        }
        assertTrue(f.leaf.heldState().uncertain)
        f.endpoint.lifecycle = AndroidVirtualDisplayOwnerHost.Lifecycle.DRAINED
        assertTrue(closeResult(f) is R08VirtualDisplayCloseResult.Unknown)
    }

    @Test fun normalWholeBatchFeedbackDrainsAndPreservesDuplicateIdRejection() {
        val f = Fixture()
        assertConfirmed(f.dispatch(f.step("start"), f.step("initialize"), f.step("release")))
        assertEquals(0, f.leaf.heldState().activeExecutions)
        assertEquals(0, f.leaf.heldState().pendingFinalOutcomes)
        assertEquals(List(3) { R08VirtualDisplayEvidence.CONFIRMED }, f.journal.final)
        // A duplicate ID in the ORIGINAL batch is a legitimate dispatcher rejection, not a
        // fabricated duplicate result. Exact original order/multiplicity must still be accepted.
        assertDenied(f.dispatch(f.step("tap", "duplicate"), f.step("tap", "duplicate")))
        assertEquals(0, f.leaf.heldState().pendingFinalOutcomes)
        assertFalse(f.leaf.heldState().uncertain)
        f.endpoint.lifecycle = AndroidVirtualDisplayOwnerHost.Lifecycle.DRAINED
        assertEquals(R08VirtualDisplayCloseResult.Drained, closeResult(f))
    }

    @Test fun finalJournalFailureKeepsPendingAndLatchesUnknown() {
        val f = Fixture()
        f.journal.failFinal = true
        assertConfirmed(f.dispatch(f.step("start")))
        assertEquals(1, f.leaf.heldState().pendingFinalOutcomes)
        assertTrue(f.leaf.heldState().uncertain)
        f.endpoint.lifecycle = AndroidVirtualDisplayOwnerHost.Lifecycle.DRAINED
        assertTrue(closeResult(f) is R08VirtualDisplayCloseResult.Unknown)
    }

    @Test fun closeDuringFinalJournalIOIsNonblockingAndRetainsTicketUntilFeedbackCompletes() {
        val f = Fixture()
        val calls = listOf(f.step("start"))
        val ticket = f.leaf.beginBatch(calls)
        val results = worker { f.dispatcher.dispatchOwned(f.auth, calls, f.owner) }
        val unblock = CountDownLatch(1)
        f.journal.finalPause = unblock
        f.endpoint.lifecycle = AndroidVirtualDisplayOwnerHost.Lifecycle.DRAINED
        val executor = Executors.newSingleThreadExecutor()
        try {
            val finishing = executor.submit<Unit> { f.leaf.finishBatch(ticket, results) }
            assertTrue(f.journal.finalEntered.await(5, TimeUnit.SECONDS))
            assertEquals(0, f.leaf.heldState().activeExecutions)
            assertEquals(1, f.leaf.heldState().pendingFinalOutcomes)
            assertTrue(closeResult(f) is R08VirtualDisplayCloseResult.Unknown)
            assertEquals(0, f.endpoint.observations)
            unblock.countDown()
            finishing.get(5, TimeUnit.SECONDS)
            assertEquals(0, f.leaf.heldState().pendingFinalOutcomes)
            assertEquals(R08VirtualDisplayCloseResult.Drained, closeResult(f))
            rejectedFeedback { f.leaf.beginBatch(listOf(f.step("initialize"))) }
        } finally { unblock.countDown(); executor.shutdownNow() }
    }

    @Test fun concurrentDuplicateFinishCannotReleaseTicketDuringFirstCallback() {
        val f = Fixture()
        val calls = listOf(f.step("start"))
        val ticket = f.leaf.beginBatch(calls)
        val results = worker { f.dispatcher.dispatchOwned(f.auth, calls, f.owner) }
        val unblock = CountDownLatch(1)
        f.journal.finalPause = unblock
        val executor = Executors.newSingleThreadExecutor()
        try {
            val finishing = executor.submit<Unit> { f.leaf.finishBatch(ticket, results) }
            assertTrue(f.journal.finalEntered.await(5, TimeUnit.SECONDS))
            rejectedFeedback { f.leaf.finishBatch(ticket, results) }
            assertEquals(1, f.leaf.heldState().pendingFinalOutcomes)
            assertTrue(f.journal.final.isEmpty())
            unblock.countDown()
            finishing.get(5, TimeUnit.SECONDS)
            assertEquals(1, f.journal.final.size)
            assertEquals(0, f.leaf.heldState().pendingFinalOutcomes)
            assertTrue(f.leaf.heldState().uncertain)
            f.endpoint.lifecycle = AndroidVirtualDisplayOwnerHost.Lifecycle.DRAINED
            assertTrue(closeResult(f) is R08VirtualDisplayCloseResult.Unknown)
        } finally { unblock.countDown(); executor.shutdownNow() }
    }

    @Test fun closeIsNonblockingWhileRequestIsInFlightAndNeverSendsCleanup() {
        val f = Fixture()
        assertConfirmed(f.dispatch(f.step("start")))
        val unblock = CountDownLatch(1)
        f.endpoint.pause = unblock
        val worker = Executors.newSingleThreadExecutor()
        try {
            val pending = worker.submit<List<ToolDispatchResult>> { f.dispatch(f.step("initialize")) }
            assertTrue(f.endpoint.entered.await(5, TimeUnit.SECONDS))
            f.leaf.close() // Would deadlock here if close tried to acquire the execution/client lock.
            assertTrue(f.leaf.heldState().revoked)
            assertEquals(1, f.leaf.heldState().activeExecutions)
            assertTrue(closeResult(f) is R08VirtualDisplayCloseResult.Unknown)
            unblock.countDown()
            pending.get(5, TimeUnit.SECONDS)
            assertFalse(f.endpoint.requests.any { it.getString("op") == "release" })
        } finally { unblock.countDown(); worker.shutdownNow() }
    }
}
