package app.daiyu.android.integration.tools

import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.RunExecutionOwner
import app.daiyu.android.domain.tools.*
import app.daiyu.android.skills.SkillRunSession
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real central registry/dispatcher/ledger and approval, with the leaf's scripted v2 endpoint.
 * No su, process, socket, display, production journal, or Android UI is used. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
class RunCatalogVirtualDisplayTest {
    private val message = Message(MessageId("assistant"), ConversationId("conversation"), RunId("run"),
        Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING)

    private class Journal : R08VirtualDisplayJournal {
        val claims = linkedMapOf<String, R08VirtualDisplayIntent>()
        val finals = mutableListOf<Pair<String, R08VirtualDisplayEvidence>>()
        val finalEntered = CountDownLatch(1)
        var finalPause: CountDownLatch? = null
        var failFinal = false
        override fun claim(intent: R08VirtualDisplayIntent): R08VirtualDisplayPermit {
            val previous = claims.putIfAbsent(intent.operationId, intent)
            check(previous == null || previous == intent)
            return if (previous == null) R08VirtualDisplayPermit.EXECUTE_ONCE else R08VirtualDisplayPermit.LOOKUP_ONLY
        }
        override fun complete(intent: R08VirtualDisplayIntent, evidence: R08VirtualDisplayEvidence) = Unit
        override fun finalOutcome(bindingKey: String, generation: String, invocationId: String, evidence: R08VirtualDisplayEvidence) {
            finalEntered.countDown()
            finalPause?.let { check(it.await(5, TimeUnit.SECONDS)) }
            if (failFinal) throw IOException("test final journal unavailable")
            finals.add(invocationId to evidence)
        }
    }

    /** Same known-good wire shapes as R08VirtualDisplayCatalogLeafTest: exercise the real client
     * normalizer, digest and response validator rather than manufacture OwnerReply success. */
    private class Endpoint(private val plan: R08VirtualDisplayPlan) : R08VirtualDisplayEndpoint {
        val starts = AtomicInteger()
        val observations = AtomicInteger()
        val requests = mutableListOf<JSONObject>()
        @Volatile var lifecycle = AndroidVirtualDisplayOwnerHost.Lifecycle.NOT_STARTED
        var drop = false
        val entered = CountDownLatch(1)
        var pause: CountDownLatch? = null
        private val client = VirtualDisplayOwnerClient(plan.generation, plan.binding,
            OwnerCredential("00000000000000000000000000000000"), object : OwnerTransport {
                override fun exchange(line: String): String {
                    val request = JSONObject(line)
                    requests.add(request)
                    entered.countDown()
                    pause?.let { check(it.await(5, TimeUnit.SECONDS)) }
                    if (drop) throw IOException("test EOF")
                    return response(request).toString()
                }
                override fun disconnect() = Unit
            })
        override fun start(generation: String, binding: OwnerRunBinding): Boolean {
            assertEquals(plan.generation, generation)
            assertEquals(plan.binding, binding)
            starts.incrementAndGet()
            lifecycle = AndroidVirtualDisplayOwnerHost.Lifecycle.UNKNOWN
            return true
        }
        override fun initialize() = client.initialize()
        override fun execute(id: String, operation: String, parameters: JSONObject) = client.execute(id, operation, parameters)
        override fun lookup(id: String, operation: String, parameters: JSONObject) = client.lookup(id, operation, parameters)
        override fun lookupGeneration() = client.lookupGeneration()
        override fun observe(): AndroidVirtualDisplayOwnerHost.Lifecycle { observations.incrementAndGet(); return lifecycle }

        private fun response(q: JSONObject): JSONObject {
            val wire = q.getString("op")
            val id = q.optString("operationId", "")
            val op = if (wire == "receipt") q.optString("operation", "") else wire
            val state = if (wire == "initialize") "STARTED" else "SUCCEEDED"
            val digest = if (id.isEmpty()) OwnerV2Contract.generationDigest(plan.generation, plan.binding.trusted)
            else OwnerV2Contract.identity(plan.generation, id, op, plan.binding.trusted, q.getJSONObject("parameters"))
            val receipt = JSONObject().put("generation", plan.generation).put("operationId", id)
                .put("operation", if (id.isEmpty()) "" else op).put("generationReceipt", id.isEmpty())
                .put("digest", digest).put("bindingDigest", plan.bindingKey).put("state", state)
            if (state != "STARTED") receipt.put("result", JSONObject().put("ok", true)
                .put("released", id.isEmpty() || op == "release"))
            val result = JSONObject().put("v", 2).put("op", wire).put("ok", true)
                .put("generation", plan.generation).put("receipt", receipt)
            if (wire == "receipt") return result.put("receiptOnly", true).put("found", true).put("executed", false)
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

    private inner class Fixture(
        val store: ToolLedgerStore = InMemoryToolLedgerStore(),
        enabled: Boolean = true,
        barrier: Boolean = false,
        val factoryGate: CountDownLatch? = null,
        bind: Boolean = true
    ) {
        val owner = runnerOwner(message)
        val capabilities = SessionCapabilities("session", setOf("audit") +
            (if (enabled) setOf(R08VirtualDisplayCatalogLeaf.CAPABILITY) else emptySet()) +
            (if (barrier) setOf(AskUserPlanTool.CAPABILITY) else emptySet()))
        val plan = R08VirtualDisplayPlan.prepare(owner, capabilities, "stable_generation", listOf(
            R08VirtualDisplayWrite("start", R08VirtualDisplayAction.Start),
            R08VirtualDisplayWrite("initialize", R08VirtualDisplayAction.Initialize),
            R08VirtualDisplayWrite("tap", R08VirtualDisplayAction.Tap(12, 34)),
            R08VirtualDisplayWrite("release", R08VirtualDisplayAction.Release)
        ), listOf(R08VirtualDisplayRead("frame", snapshot = true, include = false)))
        val approval: VirtualDisplayApproval = run {
            var result: VirtualDisplayApproval? = null
            VirtualDisplayApprovalRequest(plan).answerFromUi(true) { result = it }
            checkNotNull(result)
        }
        val authorization = TaskAuthorization("session", "run", approval.requiredEffects)
        val endpoint = Endpoint(plan)
        val journal = Journal()
        val contexts = mutableListOf<ToolExecutionContext>()
        val authCalls = AtomicInteger()
        val factoryCalls = AtomicInteger()
        val captures = AtomicInteger()
        val factoryEntered = CountDownLatch(1)
        val created = AtomicReference<R08VirtualDisplayCatalogLeaf?>()
        val registry = ToolRegistry().register(object : RegisteredTool {
            override val definition = app.daiyu.android.domain.tools.ToolDefinition(
                "audit", 1, "read", "audit", ToolEffect.READ_ONLY, ToolSchema())
            override fun prepare(arguments: ToolArguments) = PreparedTool.contextual {
                contexts.add(it)
                ExecutionReceipt.Confirmed("audit")
            }
        }).also { if (barrier) it.register(AskUserPlanTool) }
        val tools = RunCatalogTools(registry, capabilities, ToolLedger(store), message.conversationId, message.runId,
            authorizationFactory = {
                assertSame(owner, it)
                authCalls.incrementAndGet()
                authorization
            }, virtualDisplayFactory = { actualOwner, caps, auth ->
                assertSame(owner, actualOwner); assertSame(capabilities, caps); assertSame(authorization, auth)
                factoryCalls.incrementAndGet()
                val leaf = approval.bind(actualOwner, caps, auth, endpoint, journal)
                created.set(leaf)
                factoryEntered.countDown()
                factoryGate?.let { check(it.await(5, TimeUnit.SECONDS)) }
                leaf
            }, capture = { caps, auth ->
                assertSame(capabilities, caps); assertSame(authorization, auth)
                captures.incrementAndGet()
                SkillRunSession.create(auth, emptySet(), emptyList())
            })
        init { if (bind) tools.bindOwner(owner) }
        val leaf get() = checkNotNull(created.get())
        fun step(id: String, call: String = id) = ToolInvocation(ToolCallId(call), R08VirtualDisplayCatalogLeaf.WRITE_TOOL,
            JSONObject().put("step_id", id).toString())
        fun audit(id: String = "audit") = ToolInvocation(ToolCallId(id), "audit", "{}")
        fun frame() = ToolInvocation(ToolCallId("frame-call"), R08VirtualDisplayCatalogLeaf.READ_TOOL, "{\"read_id\":\"frame\"}")
        fun dispatch(vararg calls: ToolInvocation, start: Boolean = false, cancelled: () -> Boolean = { false }) = worker {
            if (start) (tools.start(message, calls.toList(), cancelled) as ReceiptBatchStart.Completed).receipts
            else tools.execute(message, calls.toList(), cancelled)
        }
        fun prepare() { tools.definitions }
        fun closeResult(): RunCatalogCloseResult = this@RunCatalogVirtualDisplayTest.closeResult(tools)
    }

    private fun <T> worker(block: () -> T): T {
        val executor = Executors.newSingleThreadExecutor()
        return try { executor.submit<T> { block() }.get(10, TimeUnit.SECONDS) } finally { executor.shutdownNow() }
    }
    private fun closeResult(tools: RunCatalogTools): RunCatalogCloseResult {
        val closed = CountDownLatch(1)
        val result = AtomicReference<RunCatalogCloseResult?>()
        tools.closeWithResult { result.set(it); closed.countDown() }
        assertTrue("central close callback missing", closed.await(5, TimeUnit.SECONDS))
        return checkNotNull(result.get())
    }
    private fun rejected(block: () -> Unit) {
        try { block(); fail("unexpected acceptance") }
        catch (_: IllegalArgumentException) { }
        catch (_: IllegalStateException) { }
    }
    private fun confirmed(results: List<ToolDispatchResult>) = assertTrue(results.all { it.outcome is DispatchOutcome.Confirmed })
    private fun denied(results: List<ToolDispatchResult>) = assertTrue(results.all { it.outcome is DispatchOutcome.NotExecuted })

    @Test fun oneCentralCatalogUsesExactOwnerAuthorizationAndOriginalCallsWithoutSetupIO() {
        val f = Fixture()
        assertEquals(0, f.captures.get()); assertEquals(0, f.endpoint.starts.get()); assertTrue(f.endpoint.requests.isEmpty())
        assertEquals(setOf("audit", R08VirtualDisplayCatalogLeaf.WRITE_TOOL, R08VirtualDisplayCatalogLeaf.READ_TOOL),
            f.tools.definitions.map { it.name }.toSet())
        f.tools.bindOwner(f.owner); f.tools.definitions
        assertEquals(1, f.authCalls.get()); assertEquals(1, f.factoryCalls.get()); assertEquals(1, f.captures.get())
        assertTrue(f.store.transaction { it.isEmpty() })
        val audit = f.audit()
        confirmed(f.dispatch(f.step("start"), audit, start = true))
        assertSame(f.owner, f.contexts.single().owner)
        assertSame(f.authorization, f.contexts.single().authorization)
        assertSame(audit, f.contexts.single().invocation)
        assertEquals(listOf("start" to R08VirtualDisplayEvidence.CONFIRMED), f.journal.finals)
        confirmed(f.dispatch(f.step("initialize")))
        confirmed(f.dispatch(f.step("tap"), f.frame(), start = true))
        confirmed(f.dispatch(f.step("tap"))) // Central ledger replay, not another input operation.
        assertEquals(1, f.endpoint.starts.get())
        assertEquals(listOf("initialize", "input", "snapshot"), f.endpoint.requests.map { it.getString("op") })
        assertEquals(setOf("start", "audit", "initialize", "tap", "frame-call"), f.store.transaction { rows -> rows.map { it.callId }.toSet() })
        assertEquals(0, f.leaf.heldState().pendingFinalOutcomes)
        assertTrue(f.closeResult() is RunCatalogCloseResult.Unknown)
        assertTrue(f.endpoint.requests.none { it.getString("op") == "release" })
    }

    @Test fun absentFactoryNeverAdvertisesVirtualDisplayEvenWhenCapabilityExists() {
        val caps = SessionCapabilities("session", setOf(R08VirtualDisplayCatalogLeaf.CAPABILITY))
        val tools = RunCatalogTools(ToolRegistry(), caps, ToolLedger(InMemoryToolLedgerStore()), message.conversationId, message.runId) { _, auth ->
            assertTrue(auth.allowedWrites.isEmpty())
            SkillRunSession.create(auth, emptySet(), emptyList())
        }
        tools.bindOwner(runnerOwner(message))
        assertTrue(tools.definitions.isEmpty())
        assertEquals(RunCatalogCloseResult.Drained, closeResult(tools))
    }

    @Test fun missingInvalidOrThrowingAuthorizationNeverReachesFactory() {
        val caps = SessionCapabilities("session", setOf(R08VirtualDisplayCatalogLeaf.CAPABILITY))
        val calls = AtomicInteger()
        fun create(auth: TaskAuthorization? = null, factory: ((RunExecutionOwner) -> TaskAuthorization)? = null) =
            RunCatalogTools(ToolRegistry(), caps, ToolLedger(InMemoryToolLedgerStore()), message.conversationId, message.runId,
                authorization = auth, authorizationFactory = factory,
                virtualDisplayFactory = { _, _, _ -> calls.incrementAndGet(); error("must not construct leaf") },
                capture = { _, _ -> error("must not capture") })
        rejected { create() }
        for (auth in listOf(TaskAuthorization("foreign", "run"), TaskAuthorization("session", "foreign"))) {
            val tools = create(factory = { auth })
            rejected { tools.bindOwner(runnerOwner(message)) }
            assertEquals(RunCatalogCloseResult.Drained, closeResult(tools))
        }
        val fixed = TaskAuthorization("session", "run")
        val copied = create(fixed) { TaskAuthorization("session", "run") }
        rejected { copied.bindOwner(runnerOwner(message)) }
        assertEquals(RunCatalogCloseResult.Drained, closeResult(copied))
        val throwing = create(fixed) { throw IllegalStateException("approval unavailable") }
        rejected { throwing.bindOwner(runnerOwner(message)) }
        assertEquals(RunCatalogCloseResult.Drained, closeResult(throwing))
        assertEquals(0, calls.get())
    }

    @Test fun returnedLeafCannotSubstituteAnotherOwnerAndIsRevokedOnBindFailure() {
        val f = Fixture(bind = false)
        val foreignLeaf = f.approval.bind(f.owner, f.capabilities, f.authorization, f.endpoint, f.journal)
        val otherOwner = runnerOwner(message)
        val tools = RunCatalogTools(ToolRegistry(), f.capabilities, ToolLedger(f.store), message.conversationId, message.runId,
            authorization = f.authorization,
            virtualDisplayFactory = { owner, caps, auth ->
                assertSame(otherOwner, owner); assertSame(f.capabilities, caps); assertSame(f.authorization, auth)
                foreignLeaf
            }, capture = { _, _ -> error("must not capture") })
        rejected { tools.bindOwner(otherOwner) }
        assertTrue(foreignLeaf.heldState().revoked)
        assertEquals(RunCatalogCloseResult.Drained, closeResult(tools))
        assertEquals(0, f.endpoint.starts.get()); assertTrue(f.endpoint.requests.isEmpty())
        assertEquals(RunCatalogCloseResult.Drained, f.closeResult())
    }

    @Test fun realRunnerTokenAndForeignMessageAreNotInterchangeable() {
        val f = Fixture(); f.prepare()
        rejected { f.tools.bindOwner(runnerOwner(message)) }
        rejected { f.tools.execute(message.copy(id = MessageId("foreign")), listOf(f.step("start"))) { false } }
        rejected { f.tools.start(message.copy(runId = RunId("foreign")), listOf(f.step("start"))) { false } }
        assertTrue(f.journal.finals.isEmpty()); assertTrue(f.store.transaction { it.isEmpty() })
        assertEquals(0, f.endpoint.starts.get())
        assertEquals(RunCatalogCloseResult.Drained, f.closeResult())
    }

    @Test fun shortCircuitsCancellationDisabledAndDuplicateBatchesFinishWholeOriginalTicket() {
        for (start in listOf(false, true)) {
            val f = Fixture(); f.prepare()
            val unknown = ToolInvocation(ToolCallId("unknown"), "missing", "{}")
            val batch = listOf(f.step("start"), unknown)
            val results = f.dispatch(*batch.toTypedArray(), start = start)
            assertEquals(batch.map { it.id }, results.map { it.callId }); denied(results)
            assertEquals(listOf("start" to R08VirtualDisplayEvidence.NOT_EXECUTED), f.journal.finals)
            denied(f.dispatch(f.step("start", "cancelled"), start = start, cancelled = { true }))
            val duplicate = f.step("start", "duplicate")
            denied(f.dispatch(duplicate, duplicate, start = start))
            denied(f.dispatch(f.step("start", "invalid").copy(arguments = "{}"), start = start))
            val large = listOf(f.step("start", "large")) + (1..64).map { f.audit("audit-$it") }
            denied(f.dispatch(*large.toTypedArray(), start = start))
            assertEquals(listOf("start", "cancelled", "duplicate", "duplicate", "invalid", "large"), f.journal.finals.map { it.first })
            assertTrue(f.journal.finals.all { it.second == R08VirtualDisplayEvidence.NOT_EXECUTED })
            assertEquals(0, f.leaf.heldState().pendingFinalOutcomes)
            assertEquals(0, f.endpoint.starts.get()); assertTrue(f.endpoint.requests.isEmpty())
            assertTrue(f.store.transaction { it.isEmpty() })
            assertEquals(RunCatalogCloseResult.Drained, f.closeResult())
        }
        val disabled = Fixture(enabled = false); disabled.prepare()
        assertEquals(listOf("audit"), disabled.tools.definitions.map { it.name })
        denied(disabled.dispatch(disabled.step("start")))
        assertEquals(0, disabled.leaf.heldState().pendingFinalOutcomes)
        assertEquals(RunCatalogCloseResult.Drained, disabled.closeResult())
    }

    private fun question(id: String = "question") = ToolInvocation(ToolCallId(id), "ask_user",
        """{"title":"Choose","question":"Which?","options":[{"id":"a","label":"A"},{"id":"b","label":"B"}]}""")

    @Test fun mixedUserBarrierIsRejectedWholeAndStillFinishesTheOriginalR08Ticket() {
        for (start in listOf(false, true)) {
            val f = Fixture(barrier = true); f.prepare()
            for (batch in listOf(listOf(question(), f.step("start", "before")), listOf(f.step("start", "after"), question()))) {
                val results = f.dispatch(*batch.toTypedArray(), start = start)
                assertEquals(batch.map { it.id }, results.map { it.callId })
                assertTrue(results.all { it.outcome == DispatchOutcome.NotExecuted("ask_user_batch_barrier") })
                assertEquals(0, f.leaf.heldState().pendingFinalOutcomes)
            }
            assertEquals(listOf("before" to R08VirtualDisplayEvidence.NOT_EXECUTED,
                "after" to R08VirtualDisplayEvidence.NOT_EXECUTED), f.journal.finals)
            assertTrue(f.store.transaction { it.isEmpty() })
            assertEquals(0, f.endpoint.starts.get()); assertTrue(f.endpoint.requests.isEmpty())
            assertEquals(RunCatalogCloseResult.Drained, f.closeResult())
        }
    }

    @Test fun pureUserBarrierSuspensionOwnsNoR08TicketAndDoesNotDelayR08Close() {
        val f = Fixture(barrier = true); f.prepare()
        val call = question()
        val result = f.tools.start(message, listOf(call)) { false }
        assertTrue(result is ReceiptBatchStart.Suspended)
        val offer = (result as ReceiptBatchStart.Suspended).offer
        assertSame(f.owner, offer.context.owner)
        assertSame(f.authorization, offer.context.authorization)
        assertSame(call, offer.context.invocation)
        assertFalse(offer.context.cancelled())
        assertTrue(f.journal.finals.isEmpty())
        assertEquals(0, f.leaf.heldState().pendingFinalOutcomes)
        assertFalse(f.leaf.heldState().uncertain)
        assertEquals(LedgerState.UNKNOWN, f.store.transaction { it.single().state })
        assertEquals(RunCatalogCloseResult.Drained, f.closeResult())
        assertTrue(offer.context.cancelled())
        assertEquals(0, f.endpoint.starts.get()); assertTrue(f.endpoint.requests.isEmpty())
    }

    @Test fun centralPersistenceUnknownReachesLeafEvenAfterConfirmedStartAndPhysicalDrain() {
        for (start in listOf(false, true)) {
            val backing = InMemoryToolLedgerStore()
            val transactions = AtomicInteger()
            val failing = object : ToolLedgerStore {
                override fun <T> transaction(block: (MutableList<LedgerEntry>) -> T): T {
                    if (transactions.incrementAndGet() == 2) throw IOException("test ledger finish unavailable")
                    return backing.transaction(block)
                }
            }
            val f = Fixture(failing); f.prepare()
            assertTrue(f.dispatch(f.step("start"), start = start).single().outcome is DispatchOutcome.Unknown)
            assertEquals(listOf("start" to R08VirtualDisplayEvidence.UNKNOWN), f.journal.finals)
            assertTrue(f.leaf.heldState().uncertain)
            f.endpoint.lifecycle = AndroidVirtualDisplayOwnerHost.Lifecycle.DRAINED
            assertTrue(f.closeResult() is RunCatalogCloseResult.Unknown)
            assertEquals(1, f.endpoint.starts.get()); assertTrue(f.endpoint.requests.isEmpty())
        }
    }

    @Test fun exceptionNeverFabricatesSuccessfulFinalFeedback() {
        for (start in listOf(false, true)) {
            val f = Fixture(); f.prepare()
            worker {
                rejected {
                    if (start) f.tools.start(message, listOf(f.step("start"))) { throw IllegalStateException("cancel check failed") }
                    else f.tools.execute(message, listOf(f.step("start"))) { throw IllegalStateException("cancel check failed") }
                }
            }
            assertTrue(f.journal.finals.isEmpty())
            assertEquals(1, f.leaf.heldState().pendingFinalOutcomes)
            assertEquals(0, f.endpoint.starts.get())
            assertTrue(f.closeResult() is RunCatalogCloseResult.Unknown)
        }
    }

    @Test fun closeWaitsForFinalJournalIOAndDoesNotObserveOrReleaseEarly() {
        for (start in listOf(false, true)) {
            val f = Fixture(); f.prepare()
            val release = CountDownLatch(1)
            f.journal.finalPause = release
            val failure = AtomicReference<Throwable?>()
            val dispatch = Thread {
                try { confirmed(f.dispatch(f.step("start"), start = start)) } catch (caught: Throwable) { failure.set(caught) }
            }
            dispatch.start()
            assertTrue(f.journal.finalEntered.await(5, TimeUnit.SECONDS))
            val closed = CountDownLatch(1)
            val result = AtomicReference<RunCatalogCloseResult?>()
            try {
                f.tools.closeWithResult { result.set(it); closed.countDown() }
                assertEquals(1, f.leaf.heldState().pendingFinalOutcomes)
                assertEquals(0, f.endpoint.observations.get())
                assertNull(result.get())
                assertTrue(f.endpoint.requests.isEmpty())
            } finally { release.countDown() }
            dispatch.join(5000); assertFalse(dispatch.isAlive); failure.get()?.let { throw it }
            assertTrue(closed.await(5, TimeUnit.SECONDS))
            assertTrue(result.get() is RunCatalogCloseResult.Unknown)
            assertEquals(0, f.leaf.heldState().pendingFinalOutcomes)
            assertEquals(1, f.endpoint.starts.get()); assertTrue(f.endpoint.requests.isEmpty())
        }
    }

    @Test fun closeDuringOwnerIORevokesImmediatelyButWaitsForCentralAndLeafAccounting() {
        val f = Fixture(); f.prepare(); confirmed(f.dispatch(f.step("start")))
        val release = CountDownLatch(1)
        f.endpoint.pause = release
        val failure = AtomicReference<Throwable?>()
        val dispatch = Thread {
            try { confirmed(f.dispatch(f.step("initialize"), start = true)) } catch (caught: Throwable) { failure.set(caught) }
        }
        dispatch.start()
        assertTrue(f.endpoint.entered.await(5, TimeUnit.SECONDS))
        val done = CountDownLatch(1)
        val result = AtomicReference<RunCatalogCloseResult?>()
        try {
            f.tools.closeWithResult { result.set(it); done.countDown() }
            assertTrue(f.leaf.heldState().revoked)
            assertEquals(1, f.leaf.heldState().activeExecutions)
            assertEquals(1, f.leaf.heldState().pendingFinalOutcomes)
            assertEquals(0, f.endpoint.observations.get())
            assertNull(result.get())
        } finally { release.countDown() }
        dispatch.join(5000); assertFalse(dispatch.isAlive); failure.get()?.let { throw it }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertTrue(result.get() is RunCatalogCloseResult.Unknown)
        assertEquals(0, f.leaf.heldState().activeExecutions)
        assertEquals(0, f.leaf.heldState().pendingFinalOutcomes)
        assertEquals(listOf("initialize"), f.endpoint.requests.map { it.getString("op") })
    }

    @Test fun closedCatalogNeverCallsFactoriesAndClosedDispatchNeverOpensANewTicket() {
        val f = Fixture(bind = false)
        assertEquals(RunCatalogCloseResult.Drained, f.closeResult())
        rejected { f.tools.bindOwner(f.owner) }
        assertEquals(0, f.authCalls.get()); assertEquals(0, f.factoryCalls.get()); assertEquals(0, f.captures.get())
        val g = Fixture(); g.prepare()
        assertEquals(RunCatalogCloseResult.Drained, g.closeResult())
        for (start in listOf(false, true)) {
            assertEquals(DispatchOutcome.NotExecuted("run_closed"), g.dispatch(g.step("start"), start = start).single().outcome)
        }
        assertTrue(g.journal.finals.isEmpty()); assertEquals(0, g.leaf.heldState().pendingFinalOutcomes)
    }

    @Test fun lateFactoryIsRetainedRevokedAndObservedOnlyAfterItReturns() {
        val release = CountDownLatch(1)
        val f = Fixture(factoryGate = release, bind = false)
        val failure = AtomicReference<Throwable?>()
        val bind = Thread {
            try { f.tools.bindOwner(f.owner); failure.set(AssertionError("late binding published")) }
            catch (expected: IllegalStateException) { if (expected.message != "run_closed") failure.set(expected) }
            catch (other: Throwable) { failure.set(other) }
        }
        bind.start()
        assertTrue(f.factoryEntered.await(5, TimeUnit.SECONDS))
        val done = CountDownLatch(1)
        val result = AtomicReference<RunCatalogCloseResult?>()
        try {
            f.tools.closeWithResult { result.set(it); done.countDown() }
            assertNull(result.get()); assertEquals(0, f.endpoint.observations.get())
        } finally { release.countDown() }
        bind.join(5000); assertFalse(bind.isAlive); failure.get()?.let { throw it }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(RunCatalogCloseResult.Drained, result.get())
        assertTrue(f.leaf.heldState().revoked)
        assertEquals(1, f.endpoint.observations.get()); assertEquals(0, f.endpoint.starts.get())
        assertEquals(0, f.captures.get())
        rejected { f.tools.definitions }
    }

    @Test fun explicitReleaseNeedsItsOwnDispatchAndPhysicalProofUnknownIsCached() {
        val f = Fixture(); f.prepare()
        confirmed(f.dispatch(f.step("start"))); confirmed(f.dispatch(f.step("initialize")))
        confirmed(f.dispatch(f.step("release")))
        assertEquals(listOf("initialize", "release"), f.endpoint.requests.map { it.getString("op") })
        val first = f.closeResult()
        assertEquals(RunCatalogCloseResult.Unknown("virtual_display_owner_retained"), first)
        f.endpoint.lifecycle = AndroidVirtualDisplayOwnerHost.Lifecycle.DRAINED
        assertSame(first, f.closeResult()) // No implicit retry/adoption after terminal Unknown.
        assertEquals(1, f.endpoint.observations.get())
        val g = Fixture(); g.prepare()
        confirmed(g.dispatch(g.step("start"))); confirmed(g.dispatch(g.step("initialize"))); confirmed(g.dispatch(g.step("release")))
        g.endpoint.lifecycle = AndroidVirtualDisplayOwnerHost.Lifecycle.DRAINED
        assertEquals(RunCatalogCloseResult.Drained, g.closeResult())
    }

    @Test fun failedFinalJournalRetainsPendingTicketEvenWhenOwnerLooksDrained() {
        val f = Fixture(); f.prepare(); f.journal.failFinal = true
        confirmed(f.dispatch(f.step("start")))
        assertTrue(f.journal.finals.isEmpty())
        assertEquals(1, f.leaf.heldState().pendingFinalOutcomes)
        assertTrue(f.leaf.heldState().uncertain)
        f.endpoint.lifecycle = AndroidVirtualDisplayOwnerHost.Lifecycle.DRAINED
        assertTrue(f.closeResult() is RunCatalogCloseResult.Unknown)
        assertEquals(0, f.endpoint.observations.get())
    }
}
