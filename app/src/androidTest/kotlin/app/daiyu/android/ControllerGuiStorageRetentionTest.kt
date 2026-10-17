package app.daiyu.android

import android.app.Application
import android.content.Context
import android.os.Looper
import android.test.InstrumentationTestCase
import app.daiyu.android.context.*
import app.daiyu.android.domain.*
import app.daiyu.android.domain.context.CompressionPolicy
import app.daiyu.android.domain.context.estimateTokens
import app.daiyu.android.gui.*
import app.daiyu.android.integration.tools.RunCatalogCloseResult
import app.daiyu.android.storage.*
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real Controller/sendGui/SQLite/Runner/catalog and production physical GUI close observer.
 * Only the trusted overlay's already-confirmed input is synthetic. No service is enabled,
 * no system gate is changed, and no screenshot, external action or network is attempted. */
@Suppress("DEPRECATION")
class ControllerGuiStorageRetentionTest : InstrumentationTestCase() {
    private class App(base: Context, private val root: File) : Application() {
        init { attachBaseContext(base) }
        override fun getApplicationContext(): Context = this
        override fun getFilesDir() = File(root, "files").apply { mkdirs() }
        override fun getNoBackupFilesDir() = File(root, "no-backup").apply { mkdirs() }
    }

    private inner class Fixture(val window: Long? = null, val input: String? = null,
        private val beforePolicy: () -> Unit = {}) {
        val id = UUID.randomUUID().toString()
        val root = File(instrumentation.targetContext.cacheDir, "gui-storage-$id").apply { mkdirs() }
        val app = App(testContext(instrumentation.targetContext, "gui-storage-$id-"), root)
        val database = ConversationDatabase(app)
        val alias = "app.daiyu.android.test.gui-storage.$id"
        val settings = ModelSettingsStore(app, alias)
        val opens = AtomicInteger()
        val factories = AtomicInteger()
        val modelInput = AtomicReference<List<Message>>()
        lateinit var delivery: GuiConfirmedDelivery
        val draft = "  exact GUI draft\nretained on unknown  "
        val modelEntered = CountDownLatch(1)
        val modelRelease = CountDownLatch(1)
        val ticket = ChatController.TextSendTicket()
        val receipt = AtomicReference<ChatController.TextSendResult>()
        val receiptReady = CountDownLatch(1)
        val controller: ChatController
        val executor: ExecutorService get() = field(controller, "executor") as ExecutorService
        val closeDone: CountDownLatch get() = field(controller, "closeDone") as CountDownLatch

        init {
            settings.save(ModelSettings("https://example.invalid", "gui-storage", "synthetic-key", false))
            controller = ChatController(app, database, settings, app.getSharedPreferences("conversation-ui", 0), {
                factories.incrementAndGet()
                ModelPort { history ->
                    modelInput.set(history)
                    opens.incrementAndGet()
                    object : ModelSession {
                        override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                            modelEntered.countDown()
                            check(modelRelease.await(10, TimeUnit.SECONDS))
                            sink(ModelEvent.TextFinal("body", "done"))
                        }
                        override fun cancel() { modelRelease.countDown() }
                    }
                }
            }, contextPreparationFactory = { context, service ->
                if (window == null) ProductionContextPreparation.create(context, service)
                else ContextPreparationCoordinator(service, {
                    check(Looper.myLooper() !== Looper.getMainLooper())
                    beforePolicy()
                    CompressionSettings(contextWindowTokens = window, reserveTokens = 128)
                }, CompressionPolicyResolver { snapshot ->
                    CompressionPolicy(snapshot.automaticWithKnownWindow, snapshot.contextWindowTokens,
                        snapshot.triggerPercent, snapshot.reserveTokens, 2)
                }, SummaryModelResolver { error("No summary binding in this fixture") })
            })
            awaitControl("controller recovery ready") { controller.ready }
            assertNull(controller.problem)
        }

        fun begin(): ControllerGuiEntry {
            val retained = AtomicReference<ControllerGuiEntry>()
            controlMain {
                val (entry, delivery) = installConfirmedInput(this)
                retained.set(entry)
                this.delivery = delivery
                assertTrue(controller.sendGui(entry, delivery, ticket) {
                    receipt.set(it); receiptReady.countDown()
                })
            }
            return retained.get()
        }

        fun awaitReceipt() {
            assertTrue("sendGui receipt", receiptReady.await(10, TimeUnit.SECONDS))
        }

        fun submit(): ControllerGuiEntry = begin().also { awaitReceipt() }

        fun dispose() {
            modelRelease.countDown()
            controlMain { controller.stop(); controller.close() }
            // Persistence-Unknown intentionally keeps the original entry, executor and SQLite
            // owner until process teardown. Never reset unknown or fake a Controller drain.
            if (closeDone.await(100, TimeUnit.MILLISECONDS)) {
                database.close()
                app.deleteDatabase("conversations.db")
                app.deleteDatabase("questions.db")
                database.runtimeLockFile.delete()
                root.deleteRecursively()
            }
            app.getSharedPreferences("model-settings", 0).edit().clear().commit()
            app.getSharedPreferences("conversation-ui", 0).edit().clear().commit()
            val keys = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            java.util.Collections.list(keys.aliases()).filter { it == alias || it.startsWith("$alias.") }
                .forEach { keys.deleteEntry(it) }
        }
    }

    private fun field(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    /** Test-only injection at the trusted UI handoff, not a replacement send or acceptance path.
     * Like GuiCatalogProductionTest's detached session, this needs no accessibility service.
     * Install the exact frozen write snapshot an overlay would deliver; do not loosen any
     * production send/commit/cleanup predicate or inject persistenceUnknown/cleanup results. */
    private fun installConfirmedInput(f: Fixture): Pair<ControllerGuiEntry, GuiConfirmedDelivery> {
        check(Looper.myLooper() === Looper.getMainLooper())
        val controller = f.controller
        synchronized(controller) {
            check(controller.ready && controller.active == null && controller.guiEntry() == null)
            check(field(controller, "preparation") == null && field(controller, "resources") == null)
            val text = f.draft
            f.database.draft(text)
            val entry = ControllerGuiEntry(null, ChatController.TextSendOwner(null, 7, 11),
                text, if (f.input == null) GuiOperation.HOME else GuiOperation.INPUT, f.input)
            val session = GuiRunSession(GuiLeaseGate.Lease(GuiOwner(entry.sessionId, entry.runId.value)))
            val observation = GuiObservation("confirmed-${f.id}", "test.external.application", 1,
                1, 1, emptyList(), false)
            val action = GuiAction(observation.observationId, entry.operation,
                nodeId = if (f.input == null) null else 4, text = f.input)
            val writes = setOf(action.effect(session.identity))
            GuiRunSession::class.java.getDeclaredField("frozenWrites").apply { isAccessible = true }
                .set(session, writes)
            entry.session = session
            ChatController::class.java.getDeclaredMethod("reservePreparation", ChatController.TextSendTicket::class.java)
                .apply { isAccessible = true }.invoke(controller, null)
            ChatController::class.java.getDeclaredField("guiEntry").apply { isAccessible = true }
                .set(controller, entry)
            return entry to GuiConfirmedDelivery(session, observation, action, writes)
        }
    }

    private fun assertCatalogDrained(entry: ControllerGuiEntry) {
        val result = AtomicReference<RunCatalogCloseResult>()
        val done = CountDownLatch(1)
        checkNotNull(entry.catalog).closeWithResult { result.set(it); done.countDown() }
        assertTrue("physical catalog close receipt", done.await(5, TimeUnit.SECONDS))
        assertEquals(RunCatalogCloseResult.Drained, result.get())
        assertTrue(checkNotNull(entry.session).releaseConfirmed)
    }

    private fun assertRejectedBeforeCatalog(f: Fixture, entry: ControllerGuiEntry) {
        assertTrue(f.receipt.get() is ChatController.TextSendResult.Rejected)
        assertEquals(entry.editor, f.receipt.get().owner)
        assertFalse(entry.accepted)
        assertFalse(entry.persistenceUnknown)
        assertNull(entry.catalog)
        assertSame(f.delivery.session, entry.session)
        awaitControl("original GUI session released and preparation cleared") {
            checkNotNull(entry.session).releaseConfirmed && f.controller.guiEntry() == null &&
                field(f.controller, "preparation") == null
        }
        controlMain {
            assertNull(f.controller.active)
            assertNull(field(f.controller, "resources"))
            assertTrue((field(f.controller, "unacceptedTools") as Map<*, *>).isEmpty())
        }
        assertEquals(entry.text, f.database.draft())
        assertTrue(f.database.conversations().isEmpty())
        for (table in listOf("messages", "context_archives", "runtime_preparations")) {
            f.database.readableDatabase.rawQuery("SELECT COUNT(*) FROM $table", null).use {
                assertTrue(it.moveToFirst()); assertEquals("no durable acceptance or summary claim in $table", 0, it.getInt(0))
            }
        }
        assertEquals(0, f.factories.get())
        assertEquals(0, f.opens.get())
        controlMain { f.controller.close() }
        assertTrue("original preparation cleanup permits storage close", f.closeDone.await(10, TimeUnit.SECONDS))
    }

    fun testPlainDraftFitsButSameDraftWithConfirmedEvidenceRejectsBeforeAcceptanceOrModelOpen() {
        val ordinary = Fixture(window = 1024)
        try {
            ordinary.database.draft(ordinary.draft)
            controlMain {
                assertTrue(ordinary.controller.sendText(null, 7, 11, ordinary.draft, ticket = ordinary.ticket) {
                    ordinary.receipt.set(it); ordinary.receiptReady.countDown()
                })
            }
            ordinary.awaitReceipt()
            assertTrue(ordinary.receipt.get() is ChatController.TextSendResult.Accepted)
            assertTrue(ordinary.modelEntered.await(5, TimeUnit.SECONDS))
            assertTrue(estimateTokens(ordinary.modelInput.get()) <= 1024 - 128)
            assertEquals(1, ordinary.modelInput.get().size)
            assertEquals(listOf(ordinary.draft), ordinary.modelInput.get().single().blocks
                .filterIsInstance<MessageBlock.Text>().map { it.text })
            assertEquals(1, ordinary.opens.get())
            controlMain { ordinary.controller.close() }
            assertTrue("ordinary run drained", ordinary.closeDone.await(10, TimeUnit.SECONDS))
        } finally { ordinary.dispose() }

        // Full exact input is evidence, not part of the short editor draft; never truncate it to fit.
        val fullInput = "  exact\n\"quoted\"\\input  " + "x".repeat(4000)
        val gui = Fixture(window = 1024, input = fullInput)
        try {
            val entry = gui.submit()
            val raw = Message(MessageId("estimate-only"), entry.conversationId, entry.runId, Message.USER_ROLE,
                listOf(MessageBlock.Text("user", entry.text)), RunStatus.COMPLETED)
            assertTrue(estimateTokens(listOf(raw)) <= 1024 - 128)
            assertTrue(estimateTokens(listOf(GuiConfirmedModelPort.project(raw, gui.delivery))) > 1024 - 128)
            assertEquals(fullInput, gui.delivery.action.text)
            controlMain { assertTrue(gui.controller.problem?.contains("上下文超出发送预算") == true) }
            assertRejectedBeforeCatalog(gui, entry)
        } finally { gui.dispose() }
    }

    fun testWithinBudgetSendsOneFrozenEvidenceWithOriginalMessageAndProjectedCheckpoint() {
        val fullInput = "  exact\n\"quoted\"\\input  " + "x".repeat(4000)
        val f = Fixture(window = 8192, input = fullInput)
        try {
            val entry = f.submit()
            assertTrue(f.receipt.get() is ChatController.TextSendResult.Accepted)
            assertTrue(f.modelEntered.await(5, TimeUnit.SECONDS))
            val sent = f.modelInput.get().single()
            val persisted = ContextService(f.app, f.database).displayHistory(entry.conversationId)
            val user = persisted.single { it.role == Message.USER_ROLE }
            assertEquals(2, persisted.size)
            assertEquals(entry.runId, user.runId)
            assertEquals(entry.conversationId, user.conversationId)
            assertEquals(listOf(MessageBlock.Text("user", entry.text)), user.blocks)
            assertEquals(user.copy(blocks = sent.blocks), sent) // no second user/run identity
            assertEquals(2, sent.blocks.size)
            assertEquals(user.blocks.single(), sent.blocks.first())
            val evidence = sent.blocks.filterIsInstance<MessageBlock.Text>().single { it.itemId == "gui_confirmed_action" }
            assertEquals(f.delivery.modelContext(), evidence.text)
            assertEquals(fullInput, org.json.JSONObject(f.delivery.actionArgumentsJson()).getString("text"))
            assertEquals(f.delivery.observation.observationId,
                org.json.JSONObject(f.delivery.actionArgumentsJson()).getString("observation_id"))
            assertTrue(estimateTokens(listOf(sent)) <= 8192 - 128)
            assertEquals("", f.database.draft())
            assertEquals(1, f.factories.get())
            assertEquals(1, f.opens.get())
            f.database.readableDatabase.rawQuery("SELECT payload FROM runtime_checkpoints WHERE message_id=?",
                arrayOf(entry.assistantId.value)).use { rows ->
                assertTrue(rows.moveToFirst())
                val history = RunCheckpointCodec.decode(rows.getString(0)).history
                val retained = history.single { it.id == user.id }
                assertEquals(sent, retained)
                assertEquals(1, retained.blocks.filterIsInstance<MessageBlock.Text>().count { it.itemId == "gui_confirmed_action" })
            }
            assertSame(f.delivery.session, entry.session)
            controlMain { f.controller.close() }
            assertTrue("accepted original GUI owner drains", f.closeDone.await(10, TimeUnit.SECONDS))
            assertCatalogDrained(entry)
        } finally { f.dispose() }
    }

    fun testCancellationDuringGuiContextFreezeKeepsDraftAndReleasesOriginalSession() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val f = Fixture(window = 8192, beforePolicy = {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
        })
        try {
            val entry = f.begin()
            assertTrue("context freeze entered", entered.await(5, TimeUnit.SECONDS))
            controlMain { assertTrue(f.controller.cancelGui(entry)) }
            release.countDown()
            f.awaitReceipt()
            assertRejectedBeforeCatalog(f, entry)
        } finally { release.countDown(); f.dispose() }
    }

    fun testGuiContextPreparationFailureKeepsDraftAndReleasesOriginalSession() {
        val f = Fixture(window = 8192, beforePolicy = { throw IllegalStateException("fixture policy failure") })
        try {
            val entry = f.submit()
            controlMain { assertTrue(f.controller.problem?.contains("上下文或摘要准备失败") == true) }
            assertRejectedBeforeCatalog(f, entry)
        } finally { f.dispose() }
    }

    fun testDatabaseSendExceptionRetainsUnknownEntryAndStorageAfterPhysicalCatalogDrain() {
        val f = Fixture()
        try {
            val sqlite = f.database.writableDatabase
            // Fail the second message INSERT, inside database.send, after the user INSERT and
            // draft updates. Real SQLite rollback is not a confirmed send receipt to Controller.
            sqlite.execSQL("""CREATE TRIGGER fail_gui_assistant BEFORE INSERT ON messages
                WHEN NEW.role='assistant' BEGIN
                SELECT RAISE(ABORT,'gui fixture failure after user insert'); END""")
            val runtime = checkNotNull(field(f.controller, "runtimeStore"))
            val executor = f.executor
            val entry = f.submit()
            assertTrue(f.receipt.get() is ChatController.TextSendResult.Unknown)
            assertEquals(entry.editor, f.receipt.get().owner)
            assertTrue(entry.persistenceUnknown)
            assertFalse(entry.accepted)
            val session = checkNotNull(entry.session)
            val catalog = checkNotNull(entry.catalog)
            assertCatalogDrained(entry)
            awaitControl("preparation finished and unaccepted catalog removed") {
                field(f.controller, "preparation") == null &&
                    (field(f.controller, "unacceptedTools") as Map<*, *>).isEmpty()
            }
            controlMain {
                assertSame(entry, f.controller.guiEntry())
                assertSame(session, entry.session)
                assertSame(catalog, entry.catalog)
                assertNull(field(f.controller, "resources"))
                assertTrue(f.controller.ready)
                assertNull(f.controller.active)
                // Acknowledging the UI receipt must not clear persistenceUnknown or its entry.
                f.controller.acknowledgeTextSend(f.ticket)
                assertNull(f.controller.textSendState())
                assertFalse(f.controller.sendText(null, 8, 12, "ordinary send must reject") {
                    fail("a rejected ordinary send must not deliver a receipt")
                })
            }
            assertNull(f.controller.send(null, "legacy ordinary send must reject"))
            assertEquals(entry.text, f.database.draft())
            assertTrue(f.database.conversations().isEmpty())
            sqlite.rawQuery("SELECT COUNT(*) FROM messages", null).use {
                assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
            }
            assertEquals(0, f.opens.get())

            controlMain { f.controller.close() }
            instrumentation.waitForIdleSync() // observes the closeStorageWhenIdle main callback
            controlMain {
                assertSame(entry, f.controller.guiEntry())
                assertTrue(entry.persistenceUnknown)
                assertSame(session, entry.session)
                assertSame(catalog, entry.catalog)
                assertFalse(field(f.controller, "closingStorage") as Boolean)
                // Also makes the missing-guiEntry regression deterministic: the old predicate
                // starts child close immediately, even before its asynchronous SQLite close.
                assertFalse(field(f.controller, "childrenClosing") as Boolean)
                assertEquals(1L, f.closeDone.count)
                assertSame(runtime, field(f.controller, "runtimeStore"))
            }
            assertFalse("close is not acknowledged for Unknown", f.closeDone.await(100, TimeUnit.MILLISECONDS))
            assertSame(executor, f.executor)
            assertFalse(executor.isShutdown)
            val workerStillHeld = CountDownLatch(1)
            executor.execute { workerStillHeld.countDown() }
            assertTrue("retained executor is still usable", workerStillHeld.await(5, TimeUnit.SECONDS))
            assertTrue("the ORIGINAL SQLite handle must remain open (not a reopened helper)", sqlite.isOpen)
            assertEquals(0, f.opens.get()) // no automatic retry during drain or close
        } finally { f.dispose() }
    }

    fun testConfirmedAcceptanceAndPhysicalGuiDrainAllowControllerStorageClose() {
        val f = Fixture()
        try {
            val sqlite = f.database.writableDatabase
            val executor = f.executor
            val entry = f.submit()
            val session = checkNotNull(entry.session)
            assertTrue(f.receipt.get() is ChatController.TextSendResult.Accepted)
            assertTrue(entry.accepted)
            assertFalse(entry.persistenceUnknown)
            assertTrue(f.modelEntered.await(5, TimeUnit.SECONDS))
            controlMain { assertSame(entry, f.controller.guiEntry()); f.controller.close() }
            assertTrue("confirmed drain must acknowledge close", f.closeDone.await(10, TimeUnit.SECONDS))
            assertCatalogDrained(entry)
            controlMain {
                assertNull(f.controller.guiEntry())
                assertSame(session, entry.session)
                assertNull(field(f.controller, "resources"))
                assertTrue((field(f.controller, "unacceptedTools") as Map<*, *>).isEmpty())
                assertNull(field(f.controller, "runtimeStore"))
            }
            assertTrue(executor.isShutdown)
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            assertFalse(sqlite.isOpen)
            assertEquals(1, f.opens.get())
        } finally { f.dispose() }
    }
}
