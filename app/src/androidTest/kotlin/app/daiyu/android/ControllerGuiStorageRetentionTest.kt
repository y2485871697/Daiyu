package app.daiyu.android

import android.app.Application
import android.content.Context
import android.os.Looper
import android.test.InstrumentationTestCase
import app.daiyu.android.context.*
import app.daiyu.android.domain.*
import app.daiyu.android.domain.context.CompressionPolicy
import app.daiyu.android.domain.context.estimateTokens
import app.daiyu.android.domain.runtime.ExecutionPhase
import app.daiyu.android.domain.runtime.UsageRequestSource
import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.model.ProviderSnapshot
import app.daiyu.android.model.ResponsesModel
import java.io.ByteArrayOutputStream
import java.net.URL
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection
import app.daiyu.android.gui.*
import app.daiyu.android.tasksurface.*
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
        val balances = fixtureProviderBalances()
        init { attachBaseContext(base) }
        override fun getApplicationContext(): Context = this
        override fun getFilesDir() = File(root, "files").apply { mkdirs() }
        override fun getNoBackupFilesDir() = File(root, "no-backup").apply { mkdirs() }
    }

    private inner class Fixture(val window: Long? = null, val input: String? = null,
        val automatic: Boolean = false, holdSummary: Boolean = false,
        private val beforePolicy: () -> Unit = {}) {
        val id = UUID.randomUUID().toString()
        val root = File(instrumentation.targetContext.cacheDir, "gui-storage-$id").apply { mkdirs() }
        val app = App(testContext(instrumentation.targetContext, "gui-storage-$id-"), root)
        val database = ConversationDatabase(app)
        val alias = "app.daiyu.android.test.gui-storage.$id"
        val settings = ModelSettingsStore(app, alias)
        val binding = SummaryModelBinding("gui-summary-provider", "gui-summary-model")
        val summary = InMemoryWire(holdSummary)
        val conversation: Conversation? = if (automatic) seedHistory() else null
        val originalHistory = conversation?.let { database.messages(it.id).map { row -> row.second } }.orEmpty()
        val opens = AtomicInteger()
        val factories = AtomicInteger()
        val modelInput = AtomicReference<List<Message>>()
        lateinit var delivery: GuiConfirmedDelivery
        val draft = "  exact GUI draft\nretained on unknown  "
        val chat = InMemoryWire(hold = true, main = true)
        val modelEntered = if (automatic) chat.entered else CountDownLatch(1)
        val modelRelease = if (automatic) chat.release else CountDownLatch(1)
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
                object : ModelPort {
                    override fun open(history: List<Message>) = error("GUI requires catalog definitions")
                    override fun open(history: List<Message>, definitions: List<ToolDefinition>): ModelSession {
                        modelInput.set(history)
                        opens.incrementAndGet()
                        if (automatic) return ResponsesModel(settings.load(), { chat }).open(history, definitions)
                        else return object : ModelSession {
                            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                                modelEntered.countDown()
                                check(modelRelease.await(10, TimeUnit.SECONDS))
                                sink(ModelEvent.TextFinal("body", "done"))
                            }
                            override fun cancel() { modelRelease.countDown() }
                        }
                    }
                }
            }, taskSurfaceStore = isolatedTaskSurfaceStore(TaskSurfacePreference.FOREGROUND),
                contextPreparationFactory = { context, service ->
                if (window == null) ProductionContextPreparation.create(context, service)
                else ContextPreparationCoordinator(service, {
                    check(Looper.myLooper() !== Looper.getMainLooper())
                    beforePolicy()
                    CompressionSettings(automatic = automatic, contextWindowTokens = window, reserveTokens = 128,
                        triggerPercent = if (automatic) 50 else 80, keepTurns = if (automatic) 1 else 2,
                        summaryModel = binding.takeIf { automatic })
                }, CompressionPolicyResolver { snapshot ->
                    CompressionPolicy(snapshot.automaticWithKnownWindow, snapshot.contextWindowTokens,
                        snapshot.triggerPercent, snapshot.reserveTokens, checkNotNull(snapshot.keepTurns))
                }, if (!automatic) SummaryModelResolver { error("No summary binding in this fixture") }
                else ProviderSummaryModelResolver({ requested ->
                    check(requested == binding)
                    val provider = ProviderSnapshot.capture(ModelSettings("https://example.invalid/v1", binding.modelId,
                        "offline-summary-only", false, protocol = ProviderProtocol.RESPONSES))
                    SummaryProviderConfiguration(binding, provider, binding.modelId,
                        128000, 127104, 256, 128, 512, 60000)
                }, { summary }, object : SummaryDeadlineScheduler {
                    // Only the synthetic summary transport's timer; GUI clocks/expiry are untouched.
                    override fun schedule(delayMillis: Long, expire: () -> Unit) = AutoCloseable {}
                }))
            }, providerBalanceHost = { app.balances })
            awaitControl("controller recovery ready") { controller.ready }
            assertNull(controller.problem)
        }

        private fun seedHistory(): Conversation {
            val owner = database.create("default")
            repeat(4) { n ->
                val user = Message(MessageId("old-u-$n-$id"), owner.id, RunId("old-$n-$id"), Message.USER_ROLE,
                    listOf(MessageBlock.Text("old", "fact $n ".repeat(36))), RunStatus.COMPLETED)
                val assistant = user.copy(id = MessageId("old-a-$n-$id"), role = Message.ASSISTANT_ROLE,
                    status = RunStatus.RUNNING)
                database.send(user, assistant)
                database.save(assistant.copy(status = RunStatus.COMPLETED))
            }
            return owner
        }

        fun saveDraft() {
            val owner = conversation
            if (owner == null) database.draft(draft) else database.draft(owner.id, draft)
        }

        fun retainedDraft(): String = conversation?.let { owner ->
            database.readableDatabase.rawQuery("SELECT draft FROM conversations WHERE id=?", arrayOf(owner.id.value)).use {
                check(it.moveToFirst()); it.getString(0)
            }
        } ?: database.draft()

        fun begin(): ControllerGuiEntry {
            val retained = AtomicReference<ControllerGuiEntry>()
            controlMain {
                saveDraft()
                retained.set(checkNotNull(controller.prepareGuiSend(conversation,
                    ChatController.TextSendOwner(conversation?.id, 7, 11), draft,
                    if (input == null) GuiOperation.HOME else GuiOperation.INPUT, input)))
            }
            val entry = retained.get()
            assertEquals(TaskSurface.FOREGROUND,
                (entry.taskSurface.freezeOnWorker() as TaskSurfaceDecision.Ready).surface)
            controlMain {
                val delivery = installConfirmedInput(this, entry)
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
            summary.release.countDown()
            modelRelease.countDown()
            controlMain { controller.stop(); controller.close() }
            app.balances.close()
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

    /** Production Responses serializer/parser and cancellation, with HTTPS replaced only at
     * the connection boundary. disconnect deliberately cannot unblock a held platform read. */
    private companion object {
        val summaryText = app.daiyu.android.context.SummaryCheckpoint.marker + "\n" +
            app.daiyu.android.context.SummaryCheckpoint.sections.joinToString("\n") { section ->
                "## $section\n" + if (section == "Current Work") "summary text" else "(none)"
            }
    }

    private class InMemoryWire(private val hold: Boolean, private val main: Boolean = false) :
        HttpsURLConnection(URL("https://example.invalid/fixture")) {
        val posts = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val body = ByteArrayOutputStream()
        override fun connect() {}
        override fun disconnect() { disconnected.countDown(); if (main) release.countDown() }
        override fun usingProxy() = false
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getOutputStream(): ByteArrayOutputStream {
            check(requestMethod == "POST")
            posts.incrementAndGet()
            return body
        }
        override fun getResponseCode() = 200
        override fun getContentType() = "text/event-stream"
        override fun getInputStream(): java.io.InputStream {
            entered.countDown()
            if (hold) check(release.await(10, TimeUnit.SECONDS))
            val text = if (main) "done" else summaryText
            val input = if (main) 100 else 10
            val output = if (main) 40 else 20
            val cached = if (main) 10 else 5
            return ("event: response.completed\n" +
                "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"id\":\"final\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":${org.json.JSONObject.quote(text)}}]}],\"usage\":{\"input_tokens\":$input,\"output_tokens\":$output,\"input_tokens_details\":{\"cached_tokens\":$cached}}}}\n\n").byteInputStream()
        }
    }

    private fun scalar(f: Fixture, sql: String, vararg args: String): Long =
        f.database.readableDatabase.rawQuery(sql, args).use { check(it.moveToFirst()); it.getLong(0) }

    private fun checkpoint(f: Fixture, entry: ControllerGuiEntry) = checkpoint(f, entry.assistantId)

    private fun checkpoint(f: Fixture, assistantId: MessageId) =
        f.database.readableDatabase.rawQuery("SELECT payload FROM runtime_checkpoints WHERE message_id=?",
            arrayOf(assistantId.value)).use { check(it.moveToFirst()); RunCheckpointCodec.decode(it.getString(0)) }

    private fun usage(f: Fixture, owner: ConversationId) = DatabaseTransactions { f.database.writableDatabase }.run {
        RecordedUsageReader.readAttributed(it, owner)
    }

    private fun field(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    /** Test-only injection at the trusted UI handoff, not a replacement send or acceptance path.
     * Like GuiCatalogProductionTest's detached session, this needs no accessibility service.
     * Install the exact frozen write snapshot an overlay would deliver; do not loosen any
     * production send/commit/cleanup predicate or inject persistenceUnknown/cleanup results. */
    private fun installConfirmedInput(f: Fixture, entry: ControllerGuiEntry): GuiConfirmedDelivery {
        check(Looper.myLooper() === Looper.getMainLooper())
        val controller = f.controller
        synchronized(controller) {
            check(controller.ready && controller.active == null && controller.guiEntry() === entry)
            check(field(controller, "preparation") != null && field(controller, "resources") == null)
            check(entry.session == null && entry.taskSurface.ready(TaskSurface.FOREGROUND) != null)
            val session = GuiRunSession(GuiLeaseGate.Lease(GuiOwner(entry.sessionId, entry.runId.value)))
            val observation = GuiObservation("confirmed-${f.id}", "test.external.application", 1,
                1, 1, emptyList(), false)
            val action = GuiAction(observation.observationId, entry.operation,
                nodeId = if (f.input == null) null else 4, text = f.input)
            val writes = setOf(action.effect(session.identity))
            GuiRunSession::class.java.getDeclaredField("frozenWrites").apply { isAccessible = true }
                .set(session, writes)
            entry.session = session
            return GuiConfirmedDelivery(session, observation, action, writes)
        }
    }

    private fun assertCatalogDrained(entry: ControllerGuiEntry) {
        val catalog = checkNotNull(entry.catalog)
        val lock = field(catalog, "closeLock")!!
        awaitControl("production closes original catalog without test assistance") {
            synchronized(lock) { field(catalog, "closeResult") == RunCatalogCloseResult.Drained }
        }
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

    fun testSmallWindowPlainDraftCapacityPausesButConfirmedGuiRejectsBeforeAcceptanceOrModelOpen() {
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
            val owner = ordinary.database.conversations().single()
            val rows = ordinary.database.messages(owner.id).map { it.second }
            val user = rows.single { it.role == Message.USER_ROLE }
            val assistant = rows.single { it.role == Message.ASSISTANT_ROLE }
            assertTrue(estimateTokens(listOf(user)) <= 1024 - 128)
            assertEquals(listOf(ordinary.draft), user.blocks.filterIsInstance<MessageBlock.Text>().map { it.text })
            awaitControl("ordinary request durably capacity-paused before model open") {
                scalar(ordinary, "SELECT COUNT(*) FROM runtime_checkpoints WHERE message_id=?", assistant.id.value) == 1L &&
                    checkpoint(ordinary, assistant.id).phase == ExecutionPhase.PAUSED
            }
            val paused = checkpoint(ordinary, assistant.id)
            val capacity = checkNotNull(paused.context?.capacity)
            assertEquals(RunStatus.RUNNING, paused.message.status)
            assertEquals(1024L, capacity.windowTokens)
            assertEquals(896L, capacity.budgetTokens)
            assertTrue(capacity.estimatedTokens > capacity.budgetTokens)
            assertEquals(user, paused.history.single { it.id == user.id })
            assertNull(paused.request)
            assertFalse(paused.requestUnconfirmed)
            assertTrue(paused.usageRequests.isEmpty())
            assertEquals(0, ordinary.opens.get())
            assertNull(ordinary.modelInput.get())
            assertEquals(1L, ordinary.modelEntered.count)
            assertEquals("", ordinary.database.draft())
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

    fun testOnlyConfirmedGuiEvidenceCrossesAutomaticTriggerAndOneSummaryFeedsOnePendingGuiAndUsageCheckpoint() {
        val policy = CompressionPolicy(true, 8192, 50, 128, 1)
        // Negative control: same real Controller, history, policy and exact short draft, no GUI.
        val plain = Fixture(window = 8192, automatic = true)
        try {
            val owner = checkNotNull(plain.conversation)
            plain.saveDraft()
            controlMain {
                assertTrue(plain.controller.sendText(owner, 7, 11, plain.draft, ticket = plain.ticket) {
                    plain.receipt.set(it); plain.receiptReady.countDown()
                })
            }
            plain.awaitReceipt()
            assertTrue(plain.receipt.get() is ChatController.TextSendResult.Accepted)
            assertTrue(plain.modelEntered.await(5, TimeUnit.SECONDS))
            assertFalse(policy.shouldCompress(estimateTokens(plain.modelInput.get())))
            assertEquals(plain.originalHistory, plain.modelInput.get().dropLast(1))
            assertEquals(listOf(MessageBlock.Text("user", plain.draft)), plain.modelInput.get().last().blocks)
            assertEquals(0, plain.summary.posts.get())
            assertEquals(1, plain.chat.posts.get())
            assertEquals(0L, scalar(plain, "SELECT COUNT(*) FROM context_archives"))
            plain.modelRelease.countDown()
            awaitControl("plain control Runner drained") { plain.controller.active == null && field(plain.controller, "resources") == null }
            controlMain { plain.controller.close() }
            assertTrue(plain.closeDone.await(10, TimeUnit.SECONDS))
        } finally { plain.dispose() }

        val fullInput = "  exact\n\"quoted\"\\input  " + "x".repeat(4000)
        val f = Fixture(window = 8192, input = fullInput, automatic = true)
        try {
            val entry = f.submit()
            assertTrue(f.receipt.get() is ChatController.TextSendResult.Accepted)
            assertTrue("actual chat POST entered after summary", f.modelEntered.await(5, TimeUnit.SECONDS))
            val sentHistory = f.modelInput.get()
            val sent = sentHistory.last()
            val displayed = ContextService(f.app, f.database).displayHistory(entry.conversationId)
            val user = displayed.single { it.role == Message.USER_ROLE && it.runId == entry.runId }
            assertFalse(policy.shouldCompress(estimateTokens(f.originalHistory + user)))
            assertTrue(policy.shouldCompress(estimateTokens(f.originalHistory + GuiConfirmedModelPort.project(user, f.delivery))))
            assertEquals(f.originalHistory, displayed.filter { it.runId != entry.runId })
            assertEquals(f.originalHistory.size + 2, displayed.size)
            assertEquals(listOf(MessageBlock.Text("user", entry.text)), user.blocks)
            assertEquals(user.copy(blocks = sent.blocks), sent)
            assertEquals(1, sentHistory.count { it.runId == entry.runId })
            assertEquals(1, sentHistory.count { it.id == user.id })
            assertEquals(2, sent.blocks.size)
            assertEquals(user.blocks.single(), sent.blocks.first())
            val evidence = sent.blocks.filterIsInstance<MessageBlock.Text>().single { it.itemId == "gui_confirmed_action" }
            assertEquals(f.delivery.modelContext(), evidence.text)
            assertEquals(fullInput, org.json.JSONObject(f.delivery.actionArgumentsJson()).getString("text"))
            assertTrue(policy.fitsSendBudget(estimateTokens(sentHistory)))
            assertEquals("", f.retainedDraft())
            assertEquals(1, f.summary.posts.get())
            assertEquals(1, f.chat.posts.get())
            val wireInput = org.json.JSONObject(f.chat.body.toString("UTF-8")).getJSONArray("input")
            val wireTexts = (0 until wireInput.length()).map { wireInput.getJSONObject(it).getString("content") }
            assertEquals(sentHistory.size, wireInput.length())
            assertEquals(entry.text + "\n" + evidence.text, wireTexts.last())
            assertEquals(1, wireTexts.count { it.contains(f.delivery.observation.observationId) })
            assertEquals(1, f.factories.get())
            assertEquals(1, f.opens.get())
            assertEquals(1L, scalar(f, "SELECT COUNT(*) FROM context_archives WHERE active=1"))
            f.database.readableDatabase.rawQuery("SELECT id,prefix_count,summary FROM context_archives WHERE active=1", null).use {
                assertTrue(it.moveToFirst())
                assertEquals(6, it.getInt(1)) // four complete old turns, keep one
                assertEquals(summaryText, it.getString(2))
                assertEquals("summary-${it.getString(0)}", sentHistory.first().id.value)
            }
            assertEquals(f.originalHistory.takeLast(2), sentHistory.drop(1).dropLast(1))
            val summaryBody = f.summary.body.toString("UTF-8")
            assertEquals(f.binding.modelId, org.json.JSONObject(summaryBody).getString("model"))
            assertEquals(0, org.json.JSONObject(summaryBody).optJSONArray("tools")?.length() ?: 0)
            assertTrue(summaryBody.contains("fact 0"))
            assertFalse("pending GUI is not old summary evidence", summaryBody.contains("gui_confirmed_action"))
            assertFalse(summaryBody.contains("x".repeat(4000)))
            val stagedCheckpoint = checkpoint(f, entry)
            val emptyAssistant = Message(entry.assistantId, entry.conversationId, entry.runId,
                Message.ASSISTANT_ROLE, emptyList(), RunStatus.COMPLETED, createdAt = user.createdAt)
            assertEquals(emptyAssistant.copy(status = RunStatus.RUNNING), stagedCheckpoint.message)
            assertEquals(sentHistory + emptyAssistant, stagedCheckpoint.history)
            val staged = usage(f, entry.conversationId)
            val summaryRequest = staged.requests.single { it.source == UsageRequestSource.SUMMARY }
            assertEquals(entry.runId.value, summaryRequest.parentRunId)
            assertEquals(entry.assistantId.value, summaryRequest.parentMessageId)
            assertEquals(10L, staged.summary.main.input.reportedTokens)
            assertEquals(20L, staged.summary.main.output.reportedTokens)
            assertEquals(5L, staged.summary.main.cached.reportedTokens)
            assertEquals(summaryRequest.snapshot, checkpoint(f, entry).usage.single {
                it.source == UsageRequestSource.SUMMARY }.snapshot)

            f.modelRelease.countDown()
            awaitControl("real Runner and original GUI catalog drain") {
                f.controller.active == null && field(f.controller, "resources") == null && f.controller.guiEntry() == null
            }
            assertCatalogDrained(entry)
            val final = checkpoint(f, entry)
            assertEquals(RunStatus.COMPLETED, final.message.status)
            assertEquals(entry.assistantId, final.message.id)
            assertEquals(entry.conversationId, final.message.conversationId)
            assertEquals(entry.runId, final.message.runId)
            assertNull(final.message.error)
            assertEquals("done", final.message.blocks.filterIsInstance<MessageBlock.Text>().single().text)
            assertEquals(sentHistory + MessageHistory.expand(listOf(
                final.message.copy(status = RunStatus.COMPLETED, error = null))), final.history)
            assertEquals(1, final.usage.count { it.source == UsageRequestSource.SUMMARY })
            assertEquals(1, final.usage.count { it.source == UsageRequestSource.MAIN })
            val total = usage(f, entry.conversationId)
            assertEquals(110L, total.summary.combined.input.reportedTokens)
            assertEquals(60L, total.summary.combined.output.reportedTokens)
            assertEquals(15L, total.summary.combined.cached.reportedTokens)
            assertEquals(1, f.summary.posts.get())
            assertEquals(1, f.chat.posts.get())
            controlMain { f.controller.close() }
            assertTrue(f.closeDone.await(10, TimeUnit.SECONDS))
        } finally { f.dispose() }
    }

    fun testCancellationWhileActualSummaryReadIsHeldKeepsPreparationGateUntilWorkerReturnsAndNeverPostsChat() {
        val f = Fixture(window = 8192, input = "x".repeat(4000), automatic = true, holdSummary = true)
        try {
            val sqlite = f.database.writableDatabase
            val entry = f.begin()
            val session = checkNotNull(entry.session)
            val token = checkNotNull(field(f.controller, "preparation"))
            assertTrue("actual summary POST/read in flight", f.summary.entered.await(5, TimeUnit.SECONDS))
            assertEquals(1, f.summary.posts.get())
            f.database.readableDatabase.rawQuery("SELECT state FROM runtime_preparations WHERE message_id=?",
                arrayOf(entry.assistantId.value)).use { assertTrue(it.moveToFirst()); assertEquals("ADMITTED", it.getString(0)) }
            controlMain { assertTrue(f.controller.cancelGui(entry)) }
            assertTrue("cancel reaches actual summary connection", f.summary.disconnected.await(5, TimeUnit.SECONDS))
            controlMain {
                assertTrue(entry.revoked)
                assertSame(entry, f.controller.guiEntry())
                assertSame(token, field(f.controller, "preparation"))
                assertSame(session, entry.session)
                assertTrue("no captures: original session's physical main-loop drain is real", session.releaseConfirmed)
                assertFalse(session.lease.valid)
                assertNull(entry.catalog)
                assertFalse(f.controller.sendText(f.conversation, 8, 12, "must stay gated") {
                    fail("busy rejection must not deliver a receipt")
                })
                f.controller.close()
                assertFalse(field(f.controller, "closingStorage") as Boolean)
                assertFalse(field(f.controller, "childrenClosing") as Boolean)
            }
            assertEquals("send worker has not returned", 1L, f.receiptReady.count)
            assertFalse(f.closeDone.await(100, TimeUnit.MILLISECONDS))
            assertFalse(f.executor.isShutdown)
            assertTrue("original SQLite remains open, not a reopened helper", sqlite.isOpen)
            assertEquals(f.draft, f.retainedDraft())
            assertEquals(f.originalHistory, ContextService(f.app, f.database).displayHistory(entry.conversationId))
            assertEquals(0L, scalar(f, "SELECT COUNT(*) FROM context_archives"))
            assertEquals(0L, scalar(f, "SELECT COUNT(*) FROM runtime_checkpoints WHERE message_id=?", entry.assistantId.value))
            assertEquals(0, f.factories.get())
            assertEquals(0, f.opens.get())
            assertEquals(0, f.chat.posts.get())

            // A late success-shaped response after real disconnect is not a new authorization.
            f.summary.release.countDown()
            f.awaitReceipt()
            assertTrue(f.receipt.get() is ChatController.TextSendResult.Rejected)
            assertEquals(entry.editor, f.receipt.get().owner)
            assertFalse(entry.accepted)
            assertFalse(entry.persistenceUnknown)
            assertTrue("worker exit and original session drain permit storage close", f.closeDone.await(10, TimeUnit.SECONDS))
            controlMain {
                assertNull(f.controller.guiEntry())
                assertNull(field(f.controller, "preparation"))
                assertNull(field(f.controller, "resources"))
                assertSame(session, entry.session)
                assertTrue(session.releaseConfirmed)
            }
            assertFalse(sqlite.isOpen)
            assertEquals(1, f.summary.posts.get())
            assertEquals(0, f.chat.posts.get())
            assertEquals(0, f.opens.get())
            assertEquals(0, f.factories.get())
            // Inspect durable rejection only after proving the ORIGINAL handle really closed.
            val reopened = ConversationDatabase(f.app)
            try {
                assertEquals(f.originalHistory, ContextService(f.app, reopened).displayHistory(entry.conversationId))
                reopened.readableDatabase.rawQuery("SELECT draft FROM conversations WHERE id=?",
                    arrayOf(entry.conversationId.value)).use { assertTrue(it.moveToFirst()); assertEquals(f.draft, it.getString(0)) }
                for (table in listOf("context_archives", "runtime_checkpoints")) reopened.readableDatabase.rawQuery(
                    "SELECT COUNT(*) FROM $table", null).use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
                reopened.readableDatabase.rawQuery("SELECT state FROM runtime_preparations WHERE message_id=?",
                    arrayOf(entry.assistantId.value)).use { assertTrue(it.moveToFirst()); assertEquals("TERMINAL", it.getString(0)) }
            } finally { reopened.close() }
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
