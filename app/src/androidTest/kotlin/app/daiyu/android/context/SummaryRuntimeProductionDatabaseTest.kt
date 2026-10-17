package app.daiyu.android.context

import android.content.Context
import android.content.ContextWrapper
import android.test.InstrumentationTestCase
import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.*
import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.model.ProviderSnapshot
import app.daiyu.android.storage.*
import app.daiyu.android.testContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import java.security.cert.Certificate
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

/** Real ContextService compaction + provider parser + SQLite lease + real Runner. All HTTPS is
 * an in-memory connection. These are source fixtures, not evidence of executed device tests. */
@Suppress("DEPRECATION")
class SummaryRuntimeProductionDatabaseTest : InstrumentationTestCase() {
    private lateinit var context: Context
    private lateinit var database: ConversationDatabase
    private lateinit var runtime: SQLiteRuntimeStore
    private val binding = SummaryModelBinding("summary-provider", "summary-model")
    private val deadlines = object : SummaryDeadlineScheduler {
        override fun nanoTime() = 0L
        override fun schedule(delayMillis: Long, expire: () -> Unit) = AutoCloseable {}
    }
    override fun setUp() {
        super.setUp()
        val base = testContext(instrumentation.targetContext, "summary-runtime-${UUID.randomUUID()}-")
        val root = File(base.cacheDir, "summary-runtime-${UUID.randomUUID()}").apply { mkdirs() }
        context = object : ContextWrapper(base) { override fun getFilesDir() = root }
        database = ConversationDatabase(context)
        runtime = SQLiteRuntimeStore.acquire(database).also { it.recoverPending() }
    }
    override fun tearDown() {
        try { runtime.close(); database.close(); context.deleteDatabase("conversations.db"); context.filesDir.deleteRecursively() }
        finally { super.tearDown() }
    }
    private fun seed(): ConversationId {
        val owner = database.create("default").id
        repeat(4) { n ->
            val user = Message(MessageId("old-u-$n-${owner.value}"), owner, RunId("old-$n-${owner.value}"), "user",
                listOf(MessageBlock.Text("u", "fact $n ".repeat(120))), RunStatus.COMPLETED)
            val assistant = user.copy(id = MessageId("old-a-$n-${owner.value}"), role = "assistant", status = RunStatus.RUNNING)
            database.send(user, assistant); database.save(assistant.copy(status = RunStatus.COMPLETED))
        }
        return owner
    }
    private data class Submission(val user: Message, val assistant: Message, val cancellation: ContextPreparationCancellation,
        val usage: RuntimeSummaryPreparation, val coordinator: ContextPreparationCoordinator, val frozen: FrozenContextPreparation,
        val wire: Wire)
    private fun submission(owner: ConversationId): Submission {
        val run = RunId(UUID.randomUUID().toString())
        val user = Message(MessageId("new-u-${run.value}"), owner, run, "user",
            listOf(MessageBlock.Text("body", "pending once")), RunStatus.COMPLETED)
        val assistant = user.copy(id = MessageId("new-a-${run.value}"), role = "assistant", status = RunStatus.RUNNING, blocks = emptyList())
        val cancellation = ContextPreparationCancellation()
        val usage = runtime.summaryPreparation(owner, run, assistant.id, cancellation)
        val wire = Wire {
            // Observe durable admission from the actual transport boundary, not a memory flag.
            assertEquals("ADMITTED", state(assistant.id))
            assertEquals(0L, scalar("SELECT COUNT(*) FROM messages WHERE id=?", assistant.id.value))
            assertEquals(0L, scalar("SELECT COUNT(*) FROM runtime_checkpoints WHERE message_id=?", assistant.id.value))
        }
        val provider = ProviderSnapshot.capture(ModelSettings("https://example.invalid/v1", binding.modelId,
            "offline-fixture-only", false, protocol = ProviderProtocol.RESPONSES))
        val config = SummaryProviderConfiguration(binding, provider, binding.modelId, 128000, 127104, 256, 128, 512, 60000)
        val coordinator = ContextPreparationCoordinator(ContextService(context, database),
            { ProductionCompressionSettings(true, 20000, 1000, 10, 1, binding).coordinatorSettings() },
            ProductionCompressionPolicyResolver,
            ProviderSummaryModelResolver({ config }, { wire }, deadlines))
        return Submission(user, assistant, cancellation, usage, coordinator,
            coordinator.freeze(owner, listOf(user), cancellation, usage.usagePort), wire)
    }
    private fun scalar(sql: String, vararg args: String): Long = database.readableDatabase.rawQuery(sql, args).use {
        it.moveToFirst(); it.getLong(0)
    }
    private fun state(id: MessageId): String = database.readableDatabase.rawQuery(
        "SELECT state FROM runtime_preparations WHERE message_id=?", arrayOf(id.value)).use { it.moveToFirst(); it.getString(0) }
    private fun read(owner: ConversationId) = DatabaseTransactions { database.writableDatabase }.run {
        RecordedUsageReader.readAttributed(it, owner)
    }
    private fun checkpoint(id: MessageId) = DatabaseTransactions { database.writableDatabase }.run {
        requireNotNull(SQLiteRuntimeStore.readCheckpointSnapshot(it, id.value))
    }
    private fun reject(block: () -> Unit) {
        try { block(); fail("stale/unadmitted operation accepted") } catch (_: IllegalStateException) { }
    }
    private fun restart() {
        runtime.close(); database.close()
        database = ConversationDatabase(context)
        runtime = SQLiteRuntimeStore.acquire(database).also { it.recoverPending() }
    }

    fun testSummaryIsDurableBeforeSendThenSameAuthorityHandsToRunnerAndKeepsLaterMainUsage() {
        val owner = seed(); val s = submission(owner)
        val prepared = s.coordinator.prepare(s.frozen)
        assertNotNull(prepared.archiveId); assertEquals(1, s.wire.posts)
        val staged = read(owner)
        assertEquals(10L, staged.summary.main.input.reportedTokens)
        assertEquals(UsageRequestSource.SUMMARY, staged.requests.single().source)
        assertEquals(s.assistant.runId.value, staged.requests.single().parentRunId)
        assertEquals(s.assistant.id.value, staged.requests.single().parentMessageId)
        s.usage.seal()
        database.send(s.user, s.assistant)
        var mainOpened = 0
        val model = ModelPort {
            mainOpened++
            assertEquals("HANDED", state(s.assistant.id))
            val beforeMain = checkpoint(s.assistant.id)
            assertEquals(staged.requests.single().snapshot, beforeMain.usage.single().snapshot)
            assertEquals(1L, scalar("SELECT has_usage FROM runtime_preparations WHERE message_id=?", s.assistant.id.value))
            reject { runtime.commitCheckpoint(beforeMain.copy(revision = beforeMain.revision + 1, usage = emptyList())) }
            object : ModelSession {
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                    sink(ModelEvent.Usage(100, 40, 10)); sink(ModelEvent.TextFinal("main", "done"))
                }
                override fun cancel() {}
            }
        }
        val result = ChatRunner(model, object : ConversationStore { override fun save(message: Message) {} },
            BatteryTool { error("no tools") }, RunnerOptions(runtimeStore = runtime)) {}.run(s.assistant, prepared.messages)
        assertEquals(RunStatus.COMPLETED, result.status); assertEquals(1, mainOpened)
        val final = checkpoint(s.assistant.id)
        assertEquals(2, final.usage.size)
        assertEquals(1, final.usage.count { it.source == UsageRequestSource.SUMMARY })
        assertEquals(110L, read(owner).summary.combined.input.reportedTokens)
        assertEquals(60L, read(owner).summary.combined.output.reportedTokens)
        assertEquals(15L, read(owner).summary.combined.cached.reportedTokens) // cached not added to input
        val sources = ContextService(context, database).usage(owner.value, s.assistant.runId.value).toJson().getJSONObject("request_sources")
        assertEquals(1, sources.getInt("main")); assertEquals(0, sources.getInt("child")); assertEquals(1, sources.getInt("summary"))
        reject { s.usage.usagePort.observe(ModelEvent.Usage(10, 20, 5)) } // identical late callback is fenced too
        s.usage.close()
        restart()
        assertEquals(2L, read(owner).summary.combined.requestSnapshotCount)
        assertEquals(110L, ContextService(context, database).usage(owner.value).inputTokens)
        assertEquals(1, s.wire.posts) // recovery never reposts
    }

    fun testCancelledPreparationKeepsReportedSnapshotButRotatesOwnerAndRejectsLateDuplicateAndSeal() {
        val owner = seed(); val s = submission(owner)
        s.coordinator.prepare(s.frozen)
        val admittedOwner = s.usage.usagePort.parent.request.owner
        s.cancellation.cancel()
        assertEquals("TERMINAL", state(s.assistant.id))
        database.readableDatabase.rawQuery("SELECT owner FROM runtime_preparations WHERE message_id=?", arrayOf(s.assistant.id.value)).use {
            it.moveToFirst(); assertFalse(admittedOwner == it.getString(0))
        }
        reject { s.usage.usagePort.observe(ModelEvent.Usage(10, 20, 5)) }
        reject { s.usage.usagePort.observe(ModelEvent.Usage(999, 999, 999)) }
        reject { s.usage.seal() }
        assertEquals(10L, read(owner).summary.combined.input.reportedTokens)
        assertEquals(0L, scalar("SELECT COUNT(*) FROM messages WHERE id=?", s.assistant.id.value))
        s.usage.close(); restart()
        assertEquals(10L, read(owner).summary.combined.input.reportedTokens)
        assertEquals(1, s.wire.posts)
    }

    fun testRestartBeforeAndAfterSendDoesNotRecoverAnAttemptAsPermissionForAnotherPost() {
        for (accepted in listOf(false, true)) {
            val owner = seed(); val s = submission(owner)
            s.coordinator.prepare(s.frozen)
            if (accepted) { s.usage.seal(); database.send(s.user, s.assistant) }
            restart() // intentionally no close(): simulate lost preparation objects/process death
            assertEquals("TERMINAL", state(s.assistant.id))
            assertEquals(10L, read(owner).summary.combined.input.reportedTokens)
            reject { runtime.preparationUsage(s.assistant) }
            reject { s.usage.usagePort.observe(ModelEvent.Usage(null, 0, 0)) }
            assertEquals(1, s.wire.posts)
            if (accepted) assertEquals(RunStatus.INTERRUPTED,
                database.messages(owner).map { it.second }.single { it.id == s.assistant.id }.status)
            database.delete(owner)
            assertEquals(0L, scalar("SELECT COUNT(*) FROM runtime_preparations WHERE message_id=?", s.assistant.id.value))
        }
    }

    fun testDurableNullCorrectionReplacesSnapshotAndFailedCasDoesNotAcknowledgeOrRepost() {
        val owner = seed(); val s = submission(owner)
        s.coordinator.prepare(s.frozen)
        val before = scalar("SELECT revision FROM runtime_preparations WHERE message_id=?", s.assistant.id.value)
        s.usage.usagePort.observe(ModelEvent.Usage(10, 20, 5))
        assertEquals(before, scalar("SELECT revision FROM runtime_preparations WHERE message_id=?", s.assistant.id.value))
        database.writableDatabase.execSQL("CREATE TRIGGER reject_summary BEFORE UPDATE ON runtime_preparations BEGIN SELECT RAISE(ABORT,'fixture'); END")
        try { s.usage.usagePort.observe(ModelEvent.Usage(null, 0, 0)); fail("write failure swallowed") }
        catch (_: android.database.sqlite.SQLiteException) { }
        assertEquals(10L, read(owner).summary.combined.input.reportedTokens)
        database.writableDatabase.execSQL("DROP TRIGGER reject_summary")
        s.usage.usagePort.observe(ModelEvent.Usage(null, 0, 0)) // retry local observation, never provider POST
        assertNull(read(owner).summary.combined.input.reportedTokens)
        assertEquals(0L, read(owner).summary.combined.output.reportedTokens)
        assertEquals(1, s.wire.posts)
        s.usage.close(); restart()
        assertNull(read(owner).summary.combined.input.reportedTokens)
        assertEquals(0L, read(owner).summary.combined.cached.reportedTokens)
    }

    fun testIgnoredAdmissionNeverOpensSummaryTransport() {
        val owner = seed(); val s = submission(owner)
        database.writableDatabase.execSQL("CREATE TRIGGER ignore_summary BEFORE INSERT ON runtime_preparations BEGIN SELECT RAISE(IGNORE); END")
        try {
            var failed = false
            try { s.coordinator.prepare(s.frozen) } catch (_: Exception) { failed = true }
            assertTrue("ignored admission accepted", failed)
            assertEquals(0, s.wire.posts)
            assertEquals(0L, scalar("SELECT COUNT(*) FROM runtime_preparations WHERE message_id=?", s.assistant.id.value))
            assertEquals(0L, scalar("SELECT COUNT(*) FROM messages WHERE id=?", s.assistant.id.value))
        } finally {
            database.writableDatabase.execSQL("DROP TRIGGER ignore_summary")
            s.usage.close()
        }
    }

    private class Wire(private val beforePost: () -> Unit) : HttpsURLConnection(URL("https://example.invalid/fixture")) {
        var posts = 0
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getOutputStream(): ByteArrayOutputStream { beforePost(); posts++; return ByteArrayOutputStream() }
        override fun getResponseCode() = 200
        override fun getContentType() = "text/event-stream"
        override fun getInputStream() = ("event: response.completed\n" +
            "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"id\":\"final\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"summary text\"}]}],\"usage\":{\"input_tokens\":10,\"output_tokens\":20,\"input_tokens_details\":{\"cached_tokens\":5}}}}\n\n").byteInputStream()
    }
}
