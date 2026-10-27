package app.daiyu.android

import android.app.Application
import android.content.Context
import android.os.Looper
import android.test.InstrumentationTestCase
import app.daiyu.android.context.*
import app.daiyu.android.domain.*
import app.daiyu.android.storage.*
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real parent monitor, Preparation, executor and SQLite. Injected backends below are only
 * cancellation/drain probes; they are NOT evidence of a real paid summary transport. */
@Suppress("DEPRECATION")
class ControllerContextManagementEntryTest : InstrumentationTestCase() {
    private class FixtureApplication(base: Context) : Application() {
        private val directory = File(base.cacheDir, "context-entry-${UUID.randomUUID()}").apply { mkdirs() }
        init { attachBaseContext(base) }
        override fun getApplicationContext(): Context = this
        override fun getFilesDir() = directory
    }
    private data class Fixture(val app: FixtureApplication, val db: ConversationDatabase,
        val controller: ChatController, val calls: AtomicInteger, val conversation: Conversation)
    private val fixtures = mutableListOf<Fixture>()
    private val releases = mutableListOf<CountDownLatch>()
    private fun fresh(factory: (Context, ContextService) -> ContextManagementBackend =
        { context, service -> ContextManagementWorker.production(context, service) }): Fixture {
        val app = FixtureApplication(testContext(instrumentation.targetContext, "context-entry-${UUID.randomUUID()}-"))
        val db = ConversationDatabase(app)
        val calls = AtomicInteger()
        val controller = ChatController(app, db, ModelSettingsStore(app), app.getSharedPreferences("conversation-ui", 0),
            { calls.incrementAndGet(); error("context entry must not resolve chat credentials or send") },
            contextManagementFactory = factory)
        awaitControl("controller ready before seed") { controller.ready }
        assertNull(controller.problem)
        val conversation = db.create("default")
        val user = Message(MessageId("original-user"), conversation.id, RunId("original-run"), "user",
            listOf(MessageBlock.Text("body", "literal local history")), RunStatus.COMPLETED)
        val assistant = user.copy(id = MessageId("original-assistant"), role = "assistant", status = RunStatus.RUNNING)
        db.send(user, assistant); db.save(assistant.copy(status = RunStatus.COMPLETED))
        db.draft(conversation.id, "do not send this draft")
        return Fixture(app, db, controller, calls, conversation).also {
            fixtures.add(it)
            awaitControl("controller ready") { controller.ready }
            assertNull(controller.problem)
        }
    }
    private fun open(f: Fixture, generation: Long = 7): ContextManagementControllerAdapter {
        var adapter: ContextManagementControllerAdapter? = null
        controlMain { adapter = f.controller.openContextManagement(ContextManagementOwner(f.conversation.id, generation)) }
        return checkNotNull(adapter)
    }
    private fun field(controller: ChatController, name: String): Any? =
        ChatController::class.java.getDeclaredField(name).apply { isAccessible = true }.get(controller)
    private fun preparation(f: Fixture) = synchronized(f.controller) { field(f.controller, "preparation") }
    private fun await(latch: CountDownLatch) = assertTrue("worker timed out", latch.await(10, TimeUnit.SECONDS))
    private fun release() = CountDownLatch(1).also { releases.add(it) }
    override fun tearDown() {
        try {
            releases.forEach { it.countDown() }
            fixtures.forEach { f ->
                controlMain { f.controller.stop() }
                f.controller.close()
                f.app.deleteDatabase("conversations.db")
                f.app.filesDir.deleteRecursively()
            }
        } finally { super.tearDown() }
    }

    fun testOpenIsMemoryOnlyThenProductionReadUsesExistingOwnerAndLeavesDraftAndMessagesIntact() {
        val creations = AtomicInteger()
        val f = fresh { context, service ->
            check(Looper.myLooper() != Looper.getMainLooper()) { "Management backend created on UI" }
            creations.incrementAndGet()
            ContextManagementWorker.production(context, service)
        }
        val before = f.db.exportBackup()
        // Recovery already opens the shared service on its worker; opening this visit must
        // neither construct its backend nor change archive storage.
        val blobRoot = File(f.app.filesDir, "neutral-context-blobs")
        assertTrue(blobRoot.isDirectory)
        val archiveFiles = blobRoot.walkTopDown().filter { it.isFile }.associate {
            it.relativeTo(blobRoot).path to it.readBytes().toList()
        }
        assertEquals(0, creations.get())
        val adapter = open(f)
        assertEquals(archiveFiles, blobRoot.walkTopDown().filter { it.isFile }.associate {
            it.relativeTo(blobRoot).path to it.readBytes().toList()
        })
        assertEquals(0, creations.get())
        assertNull(preparation(f)) // Opening registers a visit, not a reservation.
        controlMain { adapter.refresh() }
        awaitControl("production snapshot") { adapter.state.value.operation == null && adapter.state.value.snapshot != null }
        assertEquals(1, creations.get())
        assertEquals(f.conversation.id, adapter.state.value.snapshot!!.conversationId)
        assertEquals(2, adapter.state.value.snapshot!!.originals.size)
        assertEquals(before, f.db.exportBackup())
        assertEquals("do not send this draft", f.db.conversations().single().draft)
        assertEquals(0, f.calls.get())
        controlMain { f.controller.invalidateContextManagement(adapter) }
    }

    fun testSameIdNewVisitRejectsOldInvalidationAndOldCommands() {
        val creations = AtomicInteger()
        val f = fresh { c, s -> creations.incrementAndGet(); ContextManagementWorker.production(c, s) }
        val old = open(f)
        controlMain { f.controller.invalidateContextManagement(old) }
        val next = open(f, 8)
        assertNotSame(old, next)
        controlMain {
            f.controller.invalidateContextManagement(old) // Must not close next.
            old.refresh(); old.requestCompression(); old.confirmCompression()
            assertNull(f.controller.openContextManagement(ContextManagementOwner(f.conversation.id, 9)))
            next.refresh()
        }
        awaitControl("new visit snapshot") { next.state.value.snapshot != null && next.state.value.operation == null }
        assertEquals(1, creations.get())
        assertNull(old.state.value.snapshot)
        assertEquals(8L, next.state.value.owner.generation)
    }

    fun testQueuedAdmissionRetainsOriginalTokenUntilWorkerExitAndSkipsBackendAfterInvalidation() {
        val creations = AtomicInteger()
        val f = fresh { c, s -> creations.incrementAndGet(); ContextManagementWorker.production(c, s) }
        val adapter = open(f)
        val queued = CountDownLatch(1); val unblock = release()
        (field(f.controller, "contextManagementExecutor") as Executor).execute { queued.countDown(); await(unblock) }
        await(queued)
        var token: Any? = null
        controlMain {
            adapter.refresh()
            token = preparation(f)
            assertNotNull(token)
            assertFalse(f.controller.sendText(f.conversation, 7, 0, "do not send this draft") {})
            f.controller.invalidateContextManagement(adapter)
            assertSame(token, preparation(f))
            assertNull(f.controller.openContextManagement(ContextManagementOwner(f.conversation.id, 8)))
        }
        unblock.countDown()
        awaitControl("queued worker drained") { preparation(f) == null }
        assertEquals(0, creations.get())
        assertNull(adapter.state.value.snapshot)
        assertEquals(2, f.db.messages(f.conversation.id).size)
        assertEquals(0, f.calls.get())
        open(f, 8)
    }

    fun testOpenAndRefreshRepeatDataBusyGateWithoutInitializingBackendOrRetrying() {
        val creations = AtomicInteger()
        val f = fresh { c, s -> creations.incrementAndGet(); ContextManagementWorker.production(c, s) }
        val entered = CountDownLatch(1); val unblock = release()
        controlMain {
            f.controller.dataTask(work = { entered.countDown(); await(unblock); "fixture local work" })
            assertTrue(f.controller.dataBusy)
            assertNull(f.controller.openContextManagement(ContextManagementOwner(f.conversation.id, 7)))
        }
        await(entered)
        assertEquals(0, creations.get())
        unblock.countDown()
        awaitControl("data work drained") { !f.controller.dataBusy }
        val adapter = open(f)
        val secondEntered = CountDownLatch(1); val secondUnblock = release()
        controlMain {
            f.controller.dataTask(work = { secondEntered.countDown(); await(secondUnblock); "fixture local work" })
            assertTrue(f.controller.dataBusy)
            adapter.refresh()
            assertNull(preparation(f))
            assertNull(adapter.state.value.operation)
            assertNull(adapter.state.value.snapshot)
            assertNotNull(adapter.state.value.notice)
            assertEquals(0, creations.get())
        }
        await(secondEntered)
        secondUnblock.countDown()
        awaitControl("second data work drained") { !f.controller.dataBusy }
        assertEquals(0, creations.get()) // No hidden retry when idle returns.
        controlMain {
            assertNull(preparation(f))
            assertNull(adapter.state.value.operation)
            assertNull(adapter.state.value.snapshot)
        }
        assertEquals(0, f.calls.get())
        controlMain { adapter.refresh() }
        awaitControl("explicit local refresh") { adapter.state.value.snapshot != null && adapter.state.value.operation == null }
        assertEquals(1, creations.get())
    }

    fun testMissingPersistedOwnerStaysFailedOnExactVisitAndDoesNotCreateConversation() {
        val f = fresh()
        val missing = ContextManagementOwner(ConversationId("missing-persisted-owner"), 44)
        lateinit var adapter: ContextManagementControllerAdapter
        controlMain { adapter = checkNotNull(f.controller.openContextManagement(missing)); adapter.refresh() }
        awaitControl("missing owner failure") { adapter.state.value.operation == null && adapter.state.value.requiresRefresh }
        assertEquals(missing, adapter.state.value.owner)
        assertNull(adapter.state.value.snapshot)
        assertNotNull(adapter.state.value.notice)
        assertEquals(listOf(f.conversation.id), f.db.conversations().map { it.id })
        assertEquals(0, f.calls.get())
    }

    fun testOneShotSelectionUsesParentPreparationAndNeedsSeparateConfirmation() {
        val entered = CountDownLatch(1); val unblock = release()
        val selections = AtomicInteger(); val compressions = AtomicInteger()
        val selectedBinding = SummaryModelBinding("selected-summary", "selected-model")
        val saved = CompressionSettings(automatic = false, contextWindowTokens = 10000,
            reserveTokens = 1000, keepTurns = 1)
        val f = fresh { context, service ->
            val production = ContextManagementWorker.production(context, service)
            object : ContextManagementBackend by production {
                override fun snapshot(lease: ContextManagementLease) = production.snapshot(lease)
                    .copy(configuration = saved, savedConfiguration = saved)
                override fun selectCompression(lease: ContextManagementLease, expected: ContextManagementSnapshot,
                    selection: ContextManagementSelection): ContextManagementSnapshot {
                    assertTrue(Looper.myLooper() != Looper.getMainLooper())
                    selections.incrementAndGet()
                    assertEquals(selectedBinding, selection.binding)
                    entered.countDown(); await(unblock)
                    lease.checkCurrent()
                    return expected.copy(configuration = saved.copy(summaryModel = selectedBinding),
                        summaryTarget = SummaryProfileDescriptor(selectedBinding, "selected-revision",
                            "https://selected.invalid/v1/responses", "selected-api-model"))
                }
                override fun compress(lease: ContextManagementLease, expected: ContextManagementSnapshot): ContextCompressionReceipt {
                    compressions.incrementAndGet()
                    error("selection alone must never compress")
                }
            }
        }
        val before = f.db.exportBackup()
        val adapter = open(f)
        controlMain { adapter.refresh() }
        awaitControl("selection baseline") { adapter.state.value.snapshot != null && adapter.state.value.operation == null }
        controlMain {
            assertTrue(adapter.selectCompression(ContextManagementSelection(selectedBinding,
                app.daiyu.android.model.ProviderProtocol.RESPONSES)))
        }
        await(entered)
        controlMain {
            assertNotNull(preparation(f))
            assertEquals(ContextManagementOperation.SELECT, adapter.state.value.operation)
            assertFalse(adapter.state.value.confirmingCompression)
            assertFalse(f.controller.sendText(f.conversation, 7, 0, "do not send this draft") {})
        }
        unblock.countDown()
        awaitControl("selected target awaiting new consent") {
            preparation(f) == null && adapter.state.value.confirmingCompression
        }
        assertEquals(saved, adapter.state.value.snapshot!!.savedConfiguration)
        assertEquals(selectedBinding, adapter.state.value.snapshot!!.configuration!!.summaryModel)
        assertEquals(1, selections.get())
        assertEquals(0, compressions.get())
        assertEquals(0, f.calls.get())
        assertEquals(before, f.db.exportBackup())
        controlMain {
            f.controller.invalidateContextManagement(adapter)
            adapter.confirmCompression()
        }
        assertEquals(0, compressions.get())
    }

    private class Probe(private val delegate: ContextManagementBackend, private val entered: CountDownLatch,
        private val unblock: CountDownLatch, private val cancelled: CountDownLatch) : ContextManagementBackend by delegate {
        val lease = AtomicReference<ContextManagementLease>()
        val compressions = AtomicInteger()
        override fun snapshot(lease: ContextManagementLease): ContextManagementSnapshot {
            val binding = SummaryModelBinding("fixture-summary", "fixture-model")
            return delegate.snapshot(lease).copy(configuration = CompressionSettings(automatic = false,
                contextWindowTokens = 10000, reserveTokens = 1000, keepTurns = 1, summaryModel = binding),
                summaryTarget = SummaryProfileDescriptor(binding, "fixture-revision",
                    "https://summary.invalid/v1/responses", "fixture-api-model"))
        }
        override fun compress(lease: ContextManagementLease, expected: ContextManagementSnapshot): ContextCompressionReceipt {
            check(Looper.myLooper() != Looper.getMainLooper())
            this.lease.set(lease); compressions.incrementAndGet()
            lease.cancellation.register { cancelled.countDown() }.use {
                entered.countDown()
                check(unblock.await(10, TimeUnit.SECONDS))
                lease.checkCurrent()
                lease.cancellation.check()
            }
            error("a cancelled probe must not publish")
        }
    }

    private fun cancellationScenario(closeController: Boolean) {
        val entered = CountDownLatch(1); val cancelled = CountDownLatch(1); val unblock = release()
        val probe = AtomicReference<Probe>()
        val f = fresh { c, s ->
            assertTrue(Looper.myLooper() != Looper.getMainLooper())
            Probe(ContextManagementWorker.production(c, s), entered, unblock, cancelled).also { probe.set(it) }
        }
        val before = f.db.exportBackup()
        val adapter = open(f)
        controlMain { adapter.refresh() }
        awaitControl("read before manual consent") { adapter.state.value.snapshot != null && adapter.state.value.operation == null }
        controlMain {
            adapter.confirmCompression() // No prior explicit consent: no task.
            adapter.requestCompression()
            assertTrue(adapter.state.value.confirmingCompression)
            assertEquals(0, probe.get().compressions.get())
            adapter.confirmCompression()
        }
        await(entered)
        controlMain {
            val token = checkNotNull(preparation(f))
            val cancellation = token.javaClass.getDeclaredField("cancellation").apply { isAccessible = true }.get(token)
            assertSame(cancellation, probe.get().lease.get().cancellation)
            if (closeController) f.controller.close() else f.controller.stop()
            assertSame(token, preparation(f))
            assertTrue(probe.get().lease.get().cancellation.isCancelled)
            assertNull(f.controller.openContextManagement(ContextManagementOwner(f.conversation.id, 8)))
            adapter.refresh(); adapter.confirmCompression()
        }
        await(cancelled)
        // Even close has not closed SQLite while the admitted worker still owns Preparation.
        assertEquals(before, f.db.exportBackup())
        unblock.countDown()
        awaitControl("manual worker drained") { preparation(f) == null }
        assertEquals(1, probe.get().compressions.get())
        assertNull(adapter.state.value.snapshot)
        assertEquals(0, f.calls.get())
        if (!closeController) open(f, 8)
    }
    fun testStopCancelsSamePreparationWithoutEarlyReleaseOrRetry() = cancellationScenario(false)
    fun testCloseWaitsForAdmittedWorkerBeforeStorageDrain() = cancellationScenario(true)
}
