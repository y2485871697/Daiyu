package app.daiyu.android

import android.app.Application
import android.content.Context
import android.test.InstrumentationTestCase
import app.daiyu.android.domain.*
import app.daiyu.android.integration.tools.*
import app.daiyu.android.storage.*
import app.daiyu.android.tasksurface.*
import app.daiyu.android.vd.*
import app.daiyu.android.vd.runtime.OwnerV2Contract
import java.io.IOException
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.*

/** Real Controller + SQLite acceptance + Runner + unique RunCatalogTools + durable Runtime store.
 * Host and explicit-review policy are fixture-injected: no root, socket, network, or alternate catalog/ledger.
 * These are test sources, not a claim that an emulator/privileged backend was exercised. */
@Suppress("DEPRECATION")
class ControllerVirtualDisplayParentTest : InstrumentationTestCase() {
    private class App(base: Context) : Application() {
        val balances = fixtureProviderBalances()
        init { attachBaseContext(base) }
        override fun getApplicationContext(): Context = this
    }
    private class Host(val initializedCleanup: Boolean = false, val loseCleanupReply: Boolean = false) : ControllerVirtualDisplayRuntime.Host {
        val starts = AtomicInteger()
        val observations = AtomicInteger()
        val cleanupSends = AtomicInteger()
        override var client: VirtualDisplayOwnerClient? = null
        override fun start(generation: String, binding: OwnerRunBinding): AndroidVirtualDisplayOwnerHost.Start {
            starts.incrementAndGet()
            if (!initializedCleanup) throw IOException("test ambiguous launch")
            // Scripted, authenticated original v2 client; no socket, root, or replacement endpoint.
            client = VirtualDisplayOwnerClient(generation, binding,
                OwnerCredential("0123456789abcdef0123456789abcdef"), object : OwnerTransport {
                    override fun exchange(line: String): String {
                        app.daiyu.android.vd.runtime.OwnerProtocol.parseWireRequest(line)
                        val request = org.json.JSONObject(line)
                        val operation = request.getString("op")
                        val initialize = operation == "initialize"
                        if (operation == "release") {
                            cleanupSends.incrementAndGet()
                            if (loseCleanupReply) throw IOException("test lost cleanup reply")
                        }
                        val id = if (initialize) "" else request.getString("operationId")
                        val receipt = org.json.JSONObject().put("generation", generation).put("operationId", id)
                            .put("operation", if (initialize) "" else operation).put("generationReceipt", initialize)
                            .put("digest", if (initialize) OwnerV2Contract.generationDigest(generation, binding.trusted)
                                else OwnerV2Contract.identity(generation, id, operation, binding.trusted, request.getJSONObject("parameters")))
                            .put("bindingDigest", OwnerV2Contract.bindingDigest(binding.trusted))
                            .put("state", if (initialize) "STARTED" else "SUCCEEDED")
                        val body = app.daiyu.android.vd.runtime.OwnerProtocol.ok(operation, null)
                            .put("generation", generation).put("receipt", receipt)
                            .put("displayId", 7).put("uniqueId", "virtual:parent-test")
                        if (initialize) body.put("initialized", true)
                        else {
                            receipt.put("result", org.json.JSONObject().put("ok", true).put("released", true))
                            body.put("receiptOnly", false).put("released", true)
                        }
                        return body.toString()
                    }
                    override fun disconnect() = Unit
                })
            return AndroidVirtualDisplayOwnerHost.Start.READY
        }
        override fun observe(): AndroidVirtualDisplayOwnerHost.Observation {
            observations.incrementAndGet()
            return AndroidVirtualDisplayOwnerHost.Observation(
                if (starts.get() == 0) AndroidVirtualDisplayOwnerHost.Lifecycle.NOT_STARTED
                else AndroidVirtualDisplayOwnerHost.Lifecycle.UNKNOWN, false, null, null, false)
        }
    }
    private inner class Fixture(val failRelease: Boolean = false, val late: Boolean = false,
                                val executeStart: Boolean = false, val throwAfterRetain: Boolean = false,
                                val askUser: Boolean = false, val initializedCleanup: Boolean = false,
                                val loseCleanupReply: Boolean = false, val holdRunnerOnCancel: Boolean = false,
                                val locationPreference: TaskSurfacePreference = TaskSurfacePreference.BACKGROUND) {
        val id = UUID.randomUUID().toString()
        val app = App(testContext(instrumentation.targetContext, "vd-parent-$id-"))
        val database = ConversationDatabase(app)
        val alias = "app.daiyu.android.test.vd.$id"
        val settings = ModelSettingsStore(app, alias)
        val taskSurfaces = isolatedTaskSurfaceStore(locationPreference)
        val host = Host(initializedCleanup, loseCleanupReply)
        val root = app.cacheDir.canonicalFile.toPath().resolve("vd-parent-leases-$id")
        val gate = Semaphore(1)
        val allocations = AtomicInteger()
        val releases = AtomicInteger()
        val factoryEntered = CountDownLatch(1)
        val factoryRelease = CountDownLatch(if (late) 1 else 0)
        val modelEntered = CountDownLatch(1)
        val initializedBatchReturned = CountDownLatch(1)
        val cancellationEntered = CountDownLatch(1)
        val modelRelease = CountDownLatch(1)
        val opens = AtomicInteger()
        val ticket = ChatController.TextSendTicket()
        val receipt = AtomicReference<ChatController.TextSendResult>()
        val receiptReady = CountDownLatch(1)
        val controller: ChatController
        init {
            settings.save(ModelSettings("https://example.invalid", "vd-parent", "synthetic-key", false))
            if (askUser) check(app.getSharedPreferences("conversation-ui", 0).edit()
                .putBoolean("interactive_mode", true).commit())
            controller = ChatController(app, database, settings, app.getSharedPreferences("conversation-ui", 0), {
                CatalogFixturePort {
                    opens.incrementAndGet()
                    object : ModelSession {
                        var round = 0
                        override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                            modelEntered.countDown()
                            if ((executeStart || askUser || initializedCleanup) && round++ == 0) {
                                sink(ModelEvent.ToolReady(if (askUser) ToolInvocation(ToolCallId("question-call"),
                                    "ask_user", """{"title":"Choose","question":"Which?","options":[{"id":"a","label":"A"},{"id":"b","label":"B"}]}""")
                                else ToolInvocation(ToolCallId("start-call"),
                                    R08VirtualDisplayCatalogLeaf.WRITE_TOOL, """{"step_id":"start"}""")))
                                if (initializedCleanup) sink(ModelEvent.ToolReady(ToolInvocation(ToolCallId("initialize-call"),
                                    R08VirtualDisplayCatalogLeaf.WRITE_TOOL, """{"step_id":"initialize"}""")))
                            } else {
                                if (initializedCleanup) initializedBatchReturned.countDown()
                                check(modelRelease.await(10, TimeUnit.SECONDS))
                                sink(ModelEvent.TextFinal("body", "done"))
                            }
                        }
                        override fun cancel() {
                            cancellationEntered.countDown()
                            if (!holdRunnerOnCancel) modelRelease.countDown()
                        }
                    }
                }
            }, taskSurfaceStore = taskSurfaces, virtualDisplayFactory = ControllerVirtualDisplayFactory { _, owner, caps, writes, reads, parent, retention ->
                allocations.incrementAndGet()
                factoryEntered.countDown()
                check(factoryRelease.await(10, TimeUnit.SECONDS))
                val store = ControllerVirtualDisplayRuntime.LeaseStore({ root }, gate) { mutation, point ->
                    if (mutation == ControllerVirtualDisplayRuntime.Mutation.RELEASE &&
                        point == ControllerVirtualDisplayRuntime.WritePoint.BEFORE_UNLOCK) {
                        releases.incrementAndGet()
                        if (failRelease) throw IOException("test lost release receipt")
                    }
                }
                val reviewParent = object : ControllerVirtualDisplayRuntime.Parent by parent {
                    override val requiresExplicitApproval = true
                }
                val session = ControllerVirtualDisplayRuntime.assemble(owner, caps, writes, reads, reviewParent,
                    retention, store) { host }
                if (throwAfterRetain) throw IOException("test late factory loss")
                session
            }, providerBalanceHost = { app.balances })
            awaitControl("controller ready") { controller.ready }
        }
        fun submit(): ControllerVirtualDisplayEntry {
            val entry = AtomicReference<ControllerVirtualDisplayEntry>()
            controlMain {
                database.draft("accepted virtual display message")
                entry.set(checkNotNull(controller.prepareVirtualDisplaySend(null,
                    ChatController.TextSendOwner(null, 7, 11), "accepted virtual display message",
                    listOf(R08VirtualDisplayWrite("start", R08VirtualDisplayAction.Start)) +
                        if (initializedCleanup) listOf(R08VirtualDisplayWrite("initialize", R08VirtualDisplayAction.Initialize))
                        else emptyList(),
                    listOf(R08VirtualDisplayRead("status")))))
                assertNull(entry.get().session)
                assertEquals(0, allocations.get())
                assertFalse("direct send before frozen location cannot reach DB/model/factory",
                    controller.sendVirtualDisplay(entry.get(), ticket) { error("unprepared callback") })
            }
            val location = entry.get().taskSurface
            val decision = location.freezeOnWorker()
            if (locationPreference == TaskSurfacePreference.ASK) {
                assertSame(TaskSurfaceDecision.AskRequired, decision)
                val adapter = checkNotNull(location.adapter)
                val question = checkNotNull(adapter.askFromUser(location.request))
                assertSame(entry.get(), location.request.owner)
                assertNull(entry.get().owner); assertNull(entry.get().session)
                assertEquals(1, gate.availablePermits()); assertEquals(0, allocations.get()); assertEquals(0, opens.get())
                assertTrue(database.conversations().isEmpty())
                assertTrue(adapter.answerFromUser(location.request, question, TaskSurface.BACKGROUND))
            }
            assertEquals(TaskSurface.BACKGROUND, location.ready(TaskSurface.BACKGROUND)?.surface)
            controlMain {
                assertTrue(controller.sendVirtualDisplay(entry.get(), ticket) {
                    receipt.set(it); receiptReady.countDown()
                })
            }
            assertTrue(receiptReady.await(10, TimeUnit.SECONDS))
            assertTrue(receipt.get() is ChatController.TextSendResult.Accepted)
            return entry.get()
        }
        fun approval(entry: ControllerVirtualDisplayEntry): app.daiyu.android.vdapproval.VirtualDisplayApprovalRequest {
            awaitControl("full owner approval") { controller.virtualDisplayApproval(entry) != null }
            return checkNotNull(controller.virtualDisplayApproval(entry))
        }
        fun dispose() {
            factoryRelease.countDown(); modelRelease.countDown()
            // UNKNOWN intentionally pins the Controller/lease. Never fake a production drain in teardown.
            controlMain { controller.stop(); controller.close() }
            app.balances.close()
            val keys = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            java.util.Collections.list(keys.aliases()).filter { it == alias || it.startsWith("$alias.") }
                .forEach { keys.deleteEntry(it) }
        }
    }

    fun testLocationAnswerRetainsOriginalEntryAndDoesNotApproveWholePlan() {
        val f = Fixture(locationPreference = TaskSurfacePreference.ASK)
        try {
            val entry = f.submit()
            val adapter = entry.taskSurface.adapter
            val request = f.approval(entry)
            assertSame(entry, entry.taskSurface.request.owner)
            assertSame(entry.owner, request.plan.owner)
            assertNull(entry.authorization) // location choice is not the independent exact-plan grant
            assertEquals(0, f.opens.get()); assertEquals(0, f.host.starts.get())
            check(f.taskSurfaces.save(TaskSurfacePreference.FOREGROUND))
            assertSame(adapter, entry.taskSurface.adapter)
            assertEquals(TaskSurface.BACKGROUND, entry.taskSurface.ready(TaskSurface.BACKGROUND)?.surface)
            controlMain { f.controller.cancelVirtualDisplay(entry) }
            awaitControl("original location run ends only after catalog drain") { f.controller.virtualDisplayEntry() == null }
            assertTrue(entry.taskSurface.decision() is TaskSurfaceDecision.NotExecuted)
            assertEquals(1, f.gate.availablePermits())
        } finally { f.dispose() }
    }

    fun testAcceptedCancellationKeepsReceiptAndReleasesOnlyAfterObservedDrain() {
        val f = Fixture()
        try {
            val entry = f.submit()
            val request = f.approval(entry)
            assertSame(entry.owner, request.plan.owner)
            assertSame(entry.capabilities, request.plan.capabilities)
            assertEquals(entry.assistantId, request.plan.owner.assistantMessageId)
            assertEquals(entry.runId, request.plan.owner.runId)
            assertEquals(entry.conversationId, request.plan.owner.conversationId)
            assertEquals(0, f.host.starts.get()); assertEquals(0, f.opens.get())
            assertEquals(0, f.gate.availablePermits())
            val retained = entry.session
            controlMain {
                assertTrue(f.controller.cancelTextSend(f.ticket))
                assertSame(retained, entry.session)
                assertFalse(f.controller.answerVirtualDisplay(entry, request, true))
            }
            awaitControl("unstarted owner physically drained") { f.controller.virtualDisplayEntry() == null }
            assertEquals(1, f.gate.availablePermits())
            assertEquals(1, f.releases.get())
            assertEquals(0, f.host.starts.get()); assertEquals(0, f.opens.get())
            val saved = (f.receipt.get() as ChatController.TextSendResult.Accepted).conversation
            assertEquals("", f.database.conversations().single().draft)
            assertEquals(2, f.database.messages(saved.id).size)
        } finally { f.dispose() }
    }

    fun testApprovalBindingAndDefinitionsWithoutExecutionNeverStartOwner() {
        val f = Fixture()
        try {
            val entry = f.submit()
            val request = f.approval(entry)
            controlMain { assertTrue(f.controller.answerVirtualDisplay(entry, request, true)) }
            assertTrue(f.modelEntered.await(10, TimeUnit.SECONDS)) // catalog definitions already captured
            assertEquals(0, f.host.starts.get())
            assertNotNull(entry.authorization)
            assertSame(entry.owner, request.plan.owner)
            controlMain { f.controller.cancelVirtualDisplay(entry) }
            awaitControl("approved but never started drain") { f.controller.virtualDisplayEntry() == null }
            assertEquals(1, f.gate.availablePermits())
        } finally { f.dispose() }
    }

    fun testPureAskUserSuspensionAndCancellationNeverStartOwner() {
        val f = Fixture(askUser = true)
        try {
            val entry = f.submit()
            val request = f.approval(entry)
            controlMain { assertTrue(f.controller.answerVirtualDisplay(entry, request, true)) }
            awaitControl("pure question barrier") {
                f.controller.active?.let { f.controller.runState(it)?.phase } ==
                    app.daiyu.android.domain.runtime.ExecutionPhase.WAITING_USER
            }
            assertEquals(0, f.host.starts.get())
            assertSame(entry, f.controller.virtualDisplayEntry())
            assertEquals(0, f.gate.availablePermits())
            controlMain { f.controller.cancelVirtualDisplay(entry) }
            awaitControl("question cancellation and lease drain") { f.controller.virtualDisplayEntry() == null }
            assertEquals(0, f.host.starts.get())
        } finally { f.dispose() }
    }

    fun testApprovalAndDefinitionsDoNotStartOwnerButRealApprovedExecutionDoes() {
        val f = Fixture(executeStart = true)
        try {
            val entry = f.submit()
            val request = f.approval(entry)
            assertEquals(0, f.host.starts.get())
            controlMain { assertTrue(f.controller.answerVirtualDisplay(entry, request, true)) }
            assertTrue(f.modelEntered.await(10, TimeUnit.SECONDS))
            awaitControl("one actual approved Start") { f.host.starts.get() == 1 }
            assertNotNull(entry.authorization)
            assertEquals(request.plan.effects, entry.authorization!!.allowedWrites)
            f.modelRelease.countDown()
            awaitControl("ambiguous launch retained") { f.controller.problem?.contains("UNKNOWN") == true }
            assertSame(entry, f.controller.virtualDisplayEntry())
            assertNotNull(entry.session); assertEquals(0, f.gate.availablePermits())
            controlMain { f.controller.cancelVirtualDisplay(entry); f.controller.observeVirtualDisplayClose(entry) }
            assertEquals(1, f.host.starts.get()) // observing/stop never retries Start
        } finally { f.dispose() }
    }

    fun testReleaseReceiptUnknownRetainsSameSessionRunIdentityAndExclusiveLease() {
        val f = Fixture(failRelease = true)
        try {
            val entry = f.submit()
            val request = f.approval(entry)
            val retained = entry.session
            controlMain { f.controller.cancelVirtualDisplay(entry) }
            awaitControl("lost release receipt") { f.releases.get() == 1 }
            awaitControl("unknown published") { f.controller.problem?.contains("UNKNOWN") == true }
            assertSame(entry, f.controller.virtualDisplayEntry())
            assertSame(retained, entry.session)
            assertSame(request.plan.owner, entry.owner)
            assertEquals(0, f.gate.availablePermits())
            assertEquals(0, f.host.starts.get())
            controlMain { assertTrue(f.controller.observeVirtualDisplayClose(entry)) }
            // Drain observation never retries the uncertain durable release mutation.
            awaitControl("still retained") { f.controller.virtualDisplayEntry() === entry }
            assertEquals(1, f.releases.get())
        } finally { f.dispose() }
    }

    fun testProductionCleanupParentEntryUsesOriginalSessionAndNeverResendsUnknown() {
        for (loseReply in listOf(false, true)) {
            val f = Fixture(initializedCleanup = true, loseCleanupReply = loseReply, holdRunnerOnCancel = true)
            val worker = Executors.newSingleThreadExecutor()
            try {
                val entry = f.submit()
                val approval = f.approval(entry)
                val owner = app.daiyu.android.vdapproval.R08CleanupUiOwner()
                val current = AtomicReference(owner)
                controlMain {
                    assertNull(f.controller.openVirtualDisplayCleanup(entry, owner) { current.get() }) // not revoked
                    assertTrue(f.controller.answerVirtualDisplay(entry, approval, true))
                }
                assertTrue(f.initializedBatchReturned.await(10, TimeUnit.SECONDS))
                val session = checkNotNull(entry.session)
                val authorization = entry.authorization
                val catalog = checkNotNull(entry.catalog)
                controlMain { assertTrue(f.controller.cancelVirtualDisplay(entry)) }
                assertTrue(f.cancellationEntered.await(5, TimeUnit.SECONDS))
                val premature = checkNotNull(f.controller.openVirtualDisplayCleanup(entry, owner) { current.get() })
                assertNull(worker.submit<app.daiyu.android.vdapproval.R08CleanupApprovalRequest?> {
                    premature.prepareOnWorker()
                }.get(5, TimeUnit.SECONDS)) // revoked/Stop requested is NOT Runner returned/drained
                assertEquals(0, f.host.cleanupSends.get())
                premature.close()
                val drainedOwner = app.daiyu.android.vdapproval.R08CleanupUiOwner()
                current.set(drainedOwner)
                f.modelRelease.countDown()
                awaitControl("returned Runner and retained locally drained catalog") {
                    catalog.virtualDisplayCleanupLocallyDrained(session) &&
                        f.controller.problem?.contains("UNKNOWN") == true
                }
                val beforeObserve = f.host.observations.get()
                lateinit var adapter: ControllerVirtualCleanupAdapter
                controlMain {
                    val foreign = ControllerVirtualDisplayEntry(entry.conversation, entry.editor, entry.text, entry.writes, entry.reads)
                    foreign.revoked = true; foreign.catalog = catalog; foreign.session = session
                    assertNull(f.controller.openVirtualDisplayCleanup(foreign, drainedOwner) { current.get() })
                    adapter = checkNotNull(f.controller.openVirtualDisplayCleanup(entry, drainedOwner) { current.get() })
                }
                val request = checkNotNull(worker.submit<app.daiyu.android.vdapproval.R08CleanupApprovalRequest?> {
                    adapter.prepareOnWorker()
                }.get(5, TimeUnit.SECONDS))
                assertSame(approval.plan.owner, request.review.plan.owner)
                assertSame(approval.plan.capabilities, request.review.plan.capabilities)
                assertEquals(approval.plan.generation, request.review.plan.generation)
                assertEquals(0, f.host.cleanupSends.get())
                controlMain {
                    // Mutate retained pointers only as a test fault: callbacks must hold the
                    // ORIGINAL catalog/session, not silently follow the entry's later pointers.
                    synchronized(f.controller) {
                        entry.catalog = null
                        assertFalse(adapter.canPublish(request)); assertFalse(adapter.answerFromUi(request, true))
                        entry.catalog = catalog
                        entry.session = null
                        assertFalse(adapter.canPublish(request)); assertFalse(adapter.answerFromUi(request, true))
                        entry.session = session
                    }
                    assertTrue(adapter.canPublish(request))
                    assertTrue(adapter.answerFromUi(request, true))
                    assertFalse(f.controller.answerVirtualDisplay(entry, approval, true))
                }
                val expected = if (loseReply) ControllerVirtualDisplaySession.CleanupResult.UNKNOWN
                    else ControllerVirtualDisplaySession.CleanupResult.CONFIRMED
                assertEquals(expected, worker.submit<ControllerVirtualDisplaySession.CleanupResult> {
                    adapter.releaseOnWorker(request)
                }.get(5, TimeUnit.SECONDS))
                repeat(2) {
                    assertEquals(ControllerVirtualDisplaySession.CleanupResult.UNAVAILABLE,
                        worker.submit<ControllerVirtualDisplaySession.CleanupResult> { adapter.releaseOnWorker(request) }
                            .get(5, TimeUnit.SECONDS))
                    assertNull(worker.submit<app.daiyu.android.vdapproval.R08CleanupApprovalRequest?> {
                        adapter.prepareOnWorker()
                    }.get(5, TimeUnit.SECONDS))
                }
                adapter.close()
                assertEquals(1, f.host.starts.get()); assertEquals(1, f.host.cleanupSends.get())
                assertEquals(beforeObserve, f.host.observations.get()) // cleanup never probes physical drain
                assertSame(entry, f.controller.virtualDisplayEntry()); assertSame(session, entry.session)
                assertSame(catalog, entry.catalog); assertSame(authorization, entry.authorization)
                assertEquals(0, f.releases.get()); assertEquals(0, f.gate.availablePermits())
                assertEquals(2, f.database.messages(entry.conversationId).size) // SQLite Accepted, not a second send
            } finally { worker.shutdownNow(); f.dispose() }
        }
    }

    fun testProductionCleanupCancellationBeforeQueuedWorkerDoesNotClaimOrRelease() {
        val f = Fixture(initializedCleanup = true)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val entry = f.submit()
            val approval = f.approval(entry)
            controlMain { assertTrue(f.controller.answerVirtualDisplay(entry, approval, true)) }
            assertTrue(f.initializedBatchReturned.await(10, TimeUnit.SECONDS))
            controlMain { f.controller.cancelVirtualDisplay(entry) }
            awaitControl("cleanup parent drain") {
                entry.catalog?.virtualDisplayCleanupLocallyDrained(checkNotNull(entry.session)) == true &&
                    f.controller.problem?.contains("UNKNOWN") == true
            }
            val owner = app.daiyu.android.vdapproval.R08CleanupUiOwner()
            val current = AtomicReference(owner)
            val adapter = checkNotNull(f.controller.openVirtualDisplayCleanup(entry, owner) { current.get() })
            val request = checkNotNull(worker.submit<app.daiyu.android.vdapproval.R08CleanupApprovalRequest?> {
                adapter.prepareOnWorker()
            }.get(5, TimeUnit.SECONDS))
            controlMain { assertTrue(adapter.canPublish(request)); assertTrue(adapter.answerFromUi(request, true)) }
            // Equivalent to navigation before the accepted worker runs, not an undo of admitted IPC.
            adapter.close()
            current.set(app.daiyu.android.vdapproval.R08CleanupUiOwner())
            assertEquals(ControllerVirtualDisplaySession.CleanupResult.UNAVAILABLE,
                worker.submit<ControllerVirtualDisplaySession.CleanupResult> { adapter.releaseOnWorker(request) }
                    .get(5, TimeUnit.SECONDS))
            assertEquals(0, f.host.cleanupSends.get()); assertEquals(0, f.releases.get())
            assertSame(entry, f.controller.virtualDisplayEntry()); assertTrue(current.get().isActive)
        } finally { worker.shutdownNow(); f.dispose() }
    }

    fun testLateThrowingFactoryAfterCancellationStillRetainsItsOriginalSession() {
        val f = Fixture(late = true, throwAfterRetain = true)
        try {
            val entry = f.submit()
            assertTrue(f.factoryEntered.await(10, TimeUnit.SECONDS))
            controlMain { assertTrue(f.controller.cancelVirtualDisplay(entry)) }
            assertNull(entry.session)
            f.factoryRelease.countDown()
            awaitControl("late session retained") { entry.session != null && f.controller.problem?.contains("UNKNOWN") == true }
            assertSame(entry, f.controller.virtualDisplayEntry())
            assertEquals(1, f.allocations.get())
            assertEquals(0, f.host.starts.get()); assertEquals(0, f.opens.get())
            assertEquals(1, f.gate.availablePermits()) // closed before reservation; still no invented success receipt
        } finally { f.dispose() }
    }
}
