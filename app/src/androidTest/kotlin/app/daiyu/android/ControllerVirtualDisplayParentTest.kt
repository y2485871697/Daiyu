package app.daiyu.android

import android.app.Application
import android.content.Context
import android.test.InstrumentationTestCase
import app.daiyu.android.domain.*
import app.daiyu.android.integration.tools.*
import app.daiyu.android.storage.*
import app.daiyu.android.vd.*
import java.io.IOException
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.*

/** Real Controller + SQLite acceptance + Runner + unique RunCatalogTools + durable Runtime store.
 * Only the process host is substituted: no root, socket, network, or alternate catalog/ledger.
 * These are test sources, not a claim that an emulator/privileged backend was exercised. */
@Suppress("DEPRECATION")
class ControllerVirtualDisplayParentTest : InstrumentationTestCase() {
    private class App(base: Context) : Application() {
        init { attachBaseContext(base) }
        override fun getApplicationContext(): Context = this
    }
    private class Host : ControllerVirtualDisplayRuntime.Host {
        val starts = AtomicInteger()
        override val client: VirtualDisplayOwnerClient? = null
        override fun start(generation: String, binding: OwnerRunBinding): AndroidVirtualDisplayOwnerHost.Start {
            starts.incrementAndGet()
            throw IOException("test ambiguous launch")
        }
        override fun observe() = AndroidVirtualDisplayOwnerHost.Observation(
            if (starts.get() == 0) AndroidVirtualDisplayOwnerHost.Lifecycle.NOT_STARTED
            else AndroidVirtualDisplayOwnerHost.Lifecycle.UNKNOWN, false, null, null, false)
    }
    private inner class Fixture(val failRelease: Boolean = false, val late: Boolean = false,
                                val executeStart: Boolean = false, val throwAfterRetain: Boolean = false,
                                val askUser: Boolean = false) {
        val id = UUID.randomUUID().toString()
        val app = App(testContext(instrumentation.targetContext, "vd-parent-$id-"))
        val database = ConversationDatabase(app)
        val alias = "app.daiyu.android.test.vd.$id"
        val settings = ModelSettingsStore(app, alias)
        val host = Host()
        val root = app.cacheDir.canonicalFile.toPath().resolve("vd-parent-leases-$id")
        val gate = Semaphore(1)
        val allocations = AtomicInteger()
        val releases = AtomicInteger()
        val factoryEntered = CountDownLatch(1)
        val factoryRelease = CountDownLatch(if (late) 1 else 0)
        val modelEntered = CountDownLatch(1)
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
                ModelPort {
                    opens.incrementAndGet()
                    object : ModelSession {
                        var round = 0
                        override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                            modelEntered.countDown()
                            if ((executeStart || askUser) && round++ == 0) {
                                sink(ModelEvent.ToolReady(if (askUser) ToolInvocation(ToolCallId("question-call"),
                                    "ask_user", """{"title":"Choose","question":"Which?","options":[{"id":"a","label":"A"},{"id":"b","label":"B"}]}""")
                                else ToolInvocation(ToolCallId("start-call"),
                                    R08VirtualDisplayCatalogLeaf.WRITE_TOOL, """{"step_id":"start"}""")))
                            } else {
                                check(modelRelease.await(10, TimeUnit.SECONDS))
                                sink(ModelEvent.TextFinal("body", "done"))
                            }
                        }
                        override fun cancel() { modelRelease.countDown() }
                    }
                }
            }, virtualDisplayFactory = ControllerVirtualDisplayFactory { _, owner, caps, writes, reads, parent, retention ->
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
                val session = ControllerVirtualDisplayRuntime.assemble(owner, caps, writes, reads, parent,
                    retention, store) { host }
                if (throwAfterRetain) throw IOException("test late factory loss")
                session
            })
            awaitControl("controller ready") { controller.ready }
        }
        fun submit(): ControllerVirtualDisplayEntry {
            val entry = AtomicReference<ControllerVirtualDisplayEntry>()
            controlMain {
                database.draft("accepted virtual display message")
                entry.set(checkNotNull(controller.prepareVirtualDisplaySend(null,
                    ChatController.TextSendOwner(null, 7, 11), "accepted virtual display message",
                    listOf(R08VirtualDisplayWrite("start", R08VirtualDisplayAction.Start)),
                    listOf(R08VirtualDisplayRead("status")))))
                assertNull(entry.get().session)
                assertEquals(0, allocations.get())
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
            val keys = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            java.util.Collections.list(keys.aliases()).filter { it == alias || it.startsWith("$alias.") }
                .forEach { keys.deleteEntry(it) }
        }
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
