package app.daiyu.android.context

import android.content.Context
import android.content.ContextWrapper
import android.test.InstrumentationTestCase
import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.*
import app.daiyu.android.domain.usage.UsageOrigin
import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.model.usage.SQLiteAdditionalUsageStore
import app.daiyu.android.storage.*
import app.daiyu.android.testContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import java.security.cert.Certificate
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

/** Real worker + revision-retained profiles + resolver + Responses parser + same-helper SQLite.
 * All HTTPS is in memory. Source fixtures only; no test execution is claimed. */
@Suppress("DEPRECATION")
class ManualSummaryProductionDatabaseTest : InstrumentationTestCase() {
    private lateinit var context: Context
    private lateinit var database: ConversationDatabase
    private lateinit var service: ContextService
    private lateinit var profiles: SummaryProfileStore
    private val binding = SummaryModelBinding("manual-summary", "independent-model")
    private val deadlines = object : SummaryDeadlineScheduler {
        override fun nanoTime() = 0L
        override fun schedule(delayMillis: Long, expire: () -> Unit) = AutoCloseable {}
    }
    private class Lease(override val owner: ContextManagementOwner) : ContextManagementLease {
        override val cancellation = ContextPreparationCancellation()
        override fun checkCurrent() { cancellation.check() }
        override fun finish() = Unit // Host releases only after compress() has drained its finally.
    }
    private lateinit var lease: Lease
    private lateinit var retained: BoundSummaryUsagePort

    override fun setUp() {
        super.setUp()
        val base = testContext(instrumentation.targetContext, "manual-summary-${UUID.randomUUID()}-")
        val root = File(base.cacheDir, "manual-summary-${UUID.randomUUID()}").apply { mkdirs() }
        context = object : ContextWrapper(base) { override fun getFilesDir() = root }
        database = ConversationDatabase(context)
        service = ContextService(context, database)
        val provider = ModelSettings("https://example.invalid/v1", binding.modelId, "offline-fixture-only", false,
            protocol = ProviderProtocol.RESPONSES)
        val prefs = context.getSharedPreferences("manual-profile", Context.MODE_PRIVATE)
        profiles = SummaryProfileStore(prefs, { provider }, { _, _, _ -> }, "manual-profile")
        profiles.save(SummaryProfileSettings(binding, provider,
            SummaryProfileBudgets(2_000_000, 1_900_000, 97_000, 1000, 2000, 60_000)))
        val id = database.create("default").id
        repeat(4) { n ->
            val user = Message(MessageId("u-$n"), id, RunId("history-$n"), "user",
                listOf(MessageBlock.Text("u", "retained evidence $n ".repeat(100))), RunStatus.COMPLETED)
            val assistant = user.copy(id = MessageId("a-$n"), role = "assistant", status = RunStatus.RUNNING)
            database.send(user, assistant); database.save(assistant.copy(status = RunStatus.COMPLETED))
        }
        lease = Lease(ContextManagementOwner(id, 7))
    }
    override fun tearDown() {
        try { database.close(); context.deleteDatabase("conversations.db"); context.filesDir.deleteRecursively() }
        finally { super.tearDown() }
    }
    private fun worker(wire: Wire): ContextManagementWorker = ContextManagementWorker(service,
        { ProductionCompressionSettings(automatic = false, contextWindowTokens = 1_000_000, reserveTokens = 1000,
            summaryModel = binding).coordinatorSettings() }, ProductionCompressionPolicyResolver,
        object : ContextManagementSummaryProfiles {
            override fun describe(binding: SummaryModelBinding) = profiles.descriptor(binding)
            override fun freeze(expected: SummaryProfileDescriptor, usagePort: BoundSummaryUsagePort): BoundSummaryModel? {
                val frozen = profiles.configuration(expected) // Exact revision compare + capture, like production.
                retained = usagePort
                return ProviderSummaryModelResolver({ requested ->
                    require(requested == expected.binding); frozen
                }, { wire }, deadlines, usagePort).resolve(expected.binding)
            }
        })
    private fun <T> tx(block: (android.database.sqlite.SQLiteDatabase) -> T): T =
        DatabaseTransactions { database.writableDatabase }.run(block)
    private fun scalar(sql: String): Long = database.readableDatabase.rawQuery(sql, null).use { it.moveToFirst(); it.getLong(0) }
    private fun text(sql: String): String = database.readableDatabase.rawQuery(sql, null).use { it.moveToFirst(); it.getString(0) }
    private fun read(budget: RecordedUsageBudget = RecordedUsageBudget()) = tx {
        RecordedUsageReader.readAttributed(it, lease.owner.conversationId, budget)
    }
    private fun rejected(block: () -> Unit) {
        var failure: Exception? = null
        try { block() } catch (caught: Exception) { failure = caught }
        assertNotNull("must reject", failure)
    }
    private fun historyRows(): List<List<String?>> = database.readableDatabase.rawQuery(
        "SELECT id,run_id,status,blocks,protocol,history_segments FROM messages ORDER BY seq", null).use { c ->
        buildList { while (c.moveToNext()) add((0..5).map { c.getString(it) }) }
    }
    private fun assertAdmission() {
        assertEquals("ADMITTED", text("SELECT usage_state FROM context_usage WHERE usage_source='MANUAL_SUMMARY'"))
        assertEquals(1L, scalar("SELECT COUNT(*) FROM context_usage WHERE operation_id IS NOT NULL AND " +
            "run_id IS NULL AND parent_message_id IS NULL AND request_owner IS NOT NULL AND attempt_id IS NOT NULL " +
            "AND usage_revision=1 AND has_usage=0"))
        assertEquals(0L, scalar("SELECT COUNT(*) FROM runtime_checkpoints"))
        assertEquals(0L, scalar("SELECT COUNT(*) FROM runtime_preparations"))
    }

    fun testActualPostHasDurableOperationNoParentAndFinallyKeepsParserUsageWithoutCheckpoint() {
        val before = historyRows()
        val wire = Wire(beforePost = ::assertAdmission)
        val worker = worker(wire)
        assertNotNull(worker.compress(lease, worker.snapshot(lease)).archiveId)
        assertEquals(1, wire.posts); assertTrue(wire.disconnected)
        assertEquals(before, historyRows())
        assertEquals("TERMINAL", text("SELECT usage_state FROM context_usage"))
        assertEquals("SUCCEEDED", text("SELECT terminal_reason FROM context_usage"))
        val usage = read()
        assertEquals(4, usage.parentCoverage.totalAssistantMessages)
        assertEquals(1, usage.parentCoverage.totalManualSummaryRequests)
        assertEquals(0, usage.parentCoverage.manualSummaryWithoutUsage)
        val row = usage.requests.single()
        assertTrue(row.origin is UsageOrigin.ConversationPreparation)
        assertNull(row.parentMessageId); assertNull(row.parentRunId); assertNull(row.runId); assertNull(row.messageId)
        assertEquals(UsageRequestSource.MANUAL_SUMMARY, row.source)
        assertEquals(ModelEvent.Usage(10, 20, 5), row.snapshot)
        assertEquals(0L, usage.summary.main.requestSnapshotCount)
        assertEquals(1L, usage.summary.combined.requestSnapshotCount)
        assertEquals(1, service.usage(lease.owner.conversationId.value).toJson().getJSONObject("request_sources").getInt("manual_summary"))
        rejected { retained.observe(row.snapshot) }
        rejected { retained.claim() }
        database.close(); database = ConversationDatabase(context); service = ContextService(context, database)
        assertEquals(row, read().requests.single())
        assertEquals(1, wire.posts) // Reopening/querying cannot replay a summary POST.
        database.delete(lease.owner.conversationId)
        assertEquals(0L, scalar("SELECT COUNT(*) FROM context_usage"))
    }

    fun testExistingTerminalRunnerCheckpointIsNotBorrowedOrChangedByManualSummary() {
        val runtime = SQLiteRuntimeStore.acquire(database).also { it.recoverPending() }
        try {
            val owner = lease.owner.conversationId
            val user = Message(MessageId("terminal-user"), owner, RunId("terminal-run"), "user",
                listOf(MessageBlock.Text("u", "another retained turn")), RunStatus.COMPLETED)
            val assistant = user.copy(id = MessageId("terminal-assistant"), role = "assistant", blocks = emptyList(), status = RunStatus.RUNNING)
            database.send(user, assistant)
            val model = ModelPort { object : ModelSession {
                override fun cancel() {}
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                    sink(ModelEvent.Usage(100, 40, 10)); sink(ModelEvent.TextFinal("done", "terminal answer"))
                }
            } }
            val done = ChatRunner(model, database, BatteryTool { error("no tools") }, RunnerOptions(runtimeStore = runtime)) {}.run(assistant, listOf(user))
            assertEquals(RunStatus.COMPLETED, done.status)
            val payload = text("SELECT payload FROM runtime_checkpoints WHERE message_id='terminal-assistant'")
            val wire = Wire(); val worker = worker(wire)
            worker.compress(lease, worker.snapshot(lease))
            assertEquals(payload, text("SELECT payload FROM runtime_checkpoints WHERE message_id='terminal-assistant'"))
            assertEquals(1L, scalar("SELECT COUNT(*) FROM runtime_checkpoints"))
            assertEquals(1L, scalar("SELECT COUNT(*) FROM context_usage WHERE run_id IS NULL AND parent_message_id IS NULL"))
            assertEquals(100L, read().summary.main.input.reportedTokens)
            assertEquals(10L, read().summary.conversationPreparation.input.reportedTokens)
            assertEquals(110L, read().summary.combined.input.reportedTokens)
            assertEquals(1, wire.posts)
        } finally { runtime.close() }
    }

    fun testNoUsageIsIndependentMissingCoverageAndFailureAlsoTerminatesWithoutRetry() {
        val missing = Wire(usage = null)
        val worker = worker(missing)
        worker.compress(lease, worker.snapshot(lease))
        assertEquals(1, read().parentCoverage.manualSummaryWithoutUsage)
        assertEquals(0L, read().summary.combined.requestSnapshotCount)
        assertEquals(0L, scalar("SELECT has_usage FROM context_usage"))
        assertEquals("SUCCEEDED", text("SELECT terminal_reason FROM context_usage"))
        val failed = Wire(bodyOverride = "data: {\"type\":\"response.failed\"}\n\n")
        val failingWorker = worker(failed)
        rejected { failingWorker.compress(lease, failingWorker.snapshot(lease)) }
        assertEquals(2, read().parentCoverage.manualSummaryWithoutUsage)
        assertEquals(1, failed.posts); assertTrue(failed.disconnected)
        assertEquals(1L, scalar("SELECT COUNT(*) FROM context_usage WHERE terminal_reason='FAILED'"))
        assertEquals(0L, scalar("SELECT COUNT(*) FROM runtime_checkpoints"))
    }

    fun testAdmissionFailurePreventsActualPostAndDoesNotCreateUsageOrArchive() {
        tx { it.execSQL("CREATE TRIGGER reject_manual BEFORE INSERT ON context_usage WHEN NEW.usage_source='MANUAL_SUMMARY' " +
            "BEGIN SELECT RAISE(ABORT,'offline-admission-failure'); END") }
        val wire = Wire(); val worker = worker(wire)
        rejected { worker.compress(lease, worker.snapshot(lease)) }
        assertEquals(0, wire.posts)
        assertEquals(0L, scalar("SELECT COUNT(*) FROM context_usage"))
        assertEquals(0L, scalar("SELECT COUNT(*) FROM context_archives"))
        tx {
            it.execSQL("DROP TRIGGER reject_manual")
            it.execSQL("CREATE TRIGGER ignore_manual BEFORE INSERT ON context_usage WHEN NEW.usage_source='MANUAL_SUMMARY' BEGIN SELECT RAISE(IGNORE); END")
        }
        rejected { worker.compress(lease, worker.snapshot(lease)) }
        assertEquals(0, wire.posts)
        assertEquals(0L, scalar("SELECT COUNT(*) FROM context_usage"))
    }

    fun testCancellationRetainsAcceptedSnapshotRejectsLateValueAndFinallyTerminates() {
        val wire = Wire(beforeInput = {
            retained.observe(ModelEvent.Usage(8, 3, 0))
            lease.cancellation.cancel()
            rejected { retained.observe(ModelEvent.Usage(999, 999, 999)) }
            rejected { retained.observe(ModelEvent.Usage(8, 3, 0)) }
        })
        val worker = worker(wire)
        assertFalse("runner thread was already interrupted", Thread.currentThread().isInterrupted)
        try {
            rejected { worker.compress(lease, worker.snapshot(lease)) }
            assertTrue("cancelled worker must preserve its interrupt", Thread.currentThread().isInterrupted)
        } finally {
            // This fixture ran the worker on the runner thread; consume only its deliberate interrupt.
            Thread.interrupted()
        }
        assertEquals("CANCELLED", text("SELECT terminal_reason FROM context_usage"))
        assertEquals(ModelEvent.Usage(8, 3, 0), read().requests.single().snapshot)
        assertEquals(1, wire.posts); assertTrue(wire.disconnected)
        assertEquals(0L, scalar("SELECT COUNT(*) FROM context_archives"))
        rejected { retained.observe(ModelEvent.Usage(8, 3, 0)) }
    }

    fun testWholeSnapshotReplacementTrueZeroNullAndNoUsageAreNotAddedTogether() {
        val op = service.manualSummaryUsage(lease)
        op.port.claim()
        op.port.observe(ModelEvent.Usage(10, 20, 5))
        op.port.observe(ModelEvent.Usage(null, 0, 0))
        val revision = scalar("SELECT usage_revision FROM context_usage")
        op.port.observe(ModelEvent.Usage(null, 0, 0))
        assertEquals(revision, scalar("SELECT usage_revision FROM context_usage"))
        op.port.observe(ModelEvent.Usage(null, null, null)) // Actual event, not a synthetic missing report.
        assertEquals(1L, scalar("SELECT has_usage FROM context_usage"))
        assertEquals(ModelEvent.Usage(null, null, null), read().requests.single().snapshot)
        op.finish(true)
        rejected { op.port.observe(ModelEvent.Usage(null, null, null)) }
        assertEquals(0, read().parentCoverage.manualSummaryWithoutUsage)
    }

    fun testBadPresenceLongsAreIsolatedNotTruncatedAndObserveCannotAcknowledgeThem() {
        val source = UsageRequestSource.MANUAL_SUMMARY
        val owner = lease.owner.conversationId
        val good = service.manualSummaryUsage(lease)
        good.port.claim(); good.port.observe(ModelEvent.Usage(7, 2, 0)); good.finish(true)
        val request = RequestCheckpoint("corrupt", "attempt", "owner", RequestOperation.NEXT)
        tx { SQLiteAdditionalUsageStore.admit(it, owner.value, null, null, request, source, "process", "bad-op") }
        for (value in listOf("4294967296", "4294967297", "-4294967296", "0.5")) {
            tx { it.execSQL("UPDATE context_usage SET has_usage=$value WHERE request_id='corrupt'") }
            val result = read()
            assertEquals(ModelEvent.Usage(7, 2, 0), result.requests.single().snapshot)
            assertEquals(1, result.parentCoverage.unreadableManualSummaryRequests)
            assertEquals(0, result.parentCoverage.manualSummaryWithoutUsage)
            assertFalse(result.scanComplete)
            rejected { tx { SQLiteAdditionalUsageStore.observe(it, owner.value, null, null, request, source,
                "process", null, false, "bad-op") } }
        }
        val bounded = read(RecordedUsageBudget(maxMessages = 1, maxUsageRecords = 1))
        assertEquals(2, bounded.parentCoverage.totalManualSummaryRequests)
        assertEquals(1, bounded.parentCoverage.inspectedManualSummaryRequests)
        assertFalse(bounded.scanComplete)
    }

    fun testUnknownAdmissionOnReopenIsClosedNotReplayedAndOldAttemptIsRejected() {
        val owner = lease.owner.conversationId.value
        val request = RequestCheckpoint("unknown", "attempt", "old-owner", RequestOperation.NEXT)
        tx { SQLiteAdditionalUsageStore.admit(it, owner, null, null, request, UsageRequestSource.MANUAL_SUMMARY,
            "departed-process", "unknown-operation") }
        database.close(); database = ConversationDatabase(context)
        // Same production recovery primitive called by existing runtime recovery; it only updates metadata.
        tx { SQLiteAdditionalUsageStore.recoverManual(it) }
        assertEquals("UNKNOWN", text("SELECT terminal_reason FROM context_usage"))
        assertEquals("TERMINAL", text("SELECT usage_state FROM context_usage"))
        assertEquals(1, read().parentCoverage.manualSummaryWithoutUsage)
        rejected { tx { SQLiteAdditionalUsageStore.observe(it, owner, null, null, request,
            UsageRequestSource.MANUAL_SUMMARY, "departed-process", ModelEvent.Usage(1, 1), false, "unknown-operation") } }
        assertEquals(0L, scalar("SELECT COUNT(*) FROM runtime_checkpoints"))
    }

    fun testRetainedProfileRevisionRejectsResaveBeforeAdmissionAndNoCutDoesNotCreateRequest() {
        val wire = Wire(); val worker = worker(wire)
        val old = worker.snapshot(lease)
        profiles.save(profiles.load(binding)) // Even identical content has a non-reusable revision.
        rejected { worker.compress(lease, old) }
        assertEquals(0, wire.posts)
        assertEquals(0L, scalar("SELECT COUNT(*) FROM context_usage"))
        val first = service.displayHistory(lease.owner.conversationId).first()
        tx { sql -> sql.delete("messages", "conversation_id=? AND id<>?",
            arrayOf(lease.owner.conversationId.value, first.id.value)) }
        assertEquals(listOf(first), service.displayHistory(lease.owner.conversationId))
        val noCut = worker(wire)
        assertNull(noCut.compress(lease, noCut.snapshot(lease)).archiveId)
        assertEquals(0, wire.posts)
        assertEquals(0, read().parentCoverage.totalManualSummaryRequests)
    }

    fun testAuxiliaryAndTitleAlsoRejectOversizedIntegerPresenceInReadAndObserve() {
        for (source in listOf(UsageRequestSource.AUXILIARY_VISION, UsageRequestSource.TITLE)) {
            val request = RequestCheckpoint(source.name, "attempt", "owner", RequestOperation.NEXT)
            val owner = lease.owner.conversationId
            tx { SQLiteAdditionalUsageStore.admit(it, owner.value, "history-0", "a-0", request, source, "process") }
            for (value in listOf("4294967296", "4294967297")) {
                tx { it.execSQL("UPDATE context_usage SET has_usage=$value WHERE request_id=?", arrayOf(source.name)) }
                val scanned = tx { SQLiteAdditionalUsageStore.read(it, owner, source, 10) }
                assertEquals(1, scanned.unreadable); assertEquals(0, scanned.missing); assertTrue(scanned.requests.isEmpty())
                rejected { tx { SQLiteAdditionalUsageStore.observe(it, owner.value, "history-0", "a-0", request,
                    source, "process", null, false) } }
                assertEquals(value.toLong(), scalar("SELECT has_usage FROM context_usage WHERE request_id='${source.name}'"))
            }
        }
    }

    fun testSameV17UpgradeRetainsLegacyUnknownAndZeroAndAllowsNullRunOperation() {
        val owner = lease.owner.conversationId.value
        val db = database.writableDatabase
        db.execSQL("DROP TABLE context_usage")
        db.execSQL("CREATE TABLE context_usage(request_id TEXT PRIMARY KEY,run_id TEXT NOT NULL,branch_id TEXT NOT NULL," +
            "input_tokens INTEGER,output_tokens INTEGER,cached_tokens INTEGER,reasoning_tokens INTEGER)")
        db.execSQL("INSERT INTO context_usage(request_id,run_id,branch_id,input_tokens,output_tokens) VALUES('legacy','history-0',?,NULL,0)", arrayOf(owner))
        db.version = 16
        database.close(); database = ConversationDatabase(context); service = ContextService(context, database)
        assertEquals(25, database.writableDatabase.version)
        assertEquals("LEGACY", text("SELECT usage_source FROM context_usage"))
        assertEquals(1L, scalar("SELECT COUNT(*) FROM context_usage WHERE request_id='legacy' AND input_tokens IS NULL AND output_tokens=0 AND run_id='history-0'"))
        val wire = Wire(); val worker = worker(wire)
        worker.compress(lease, worker.snapshot(lease))
        assertEquals(1, wire.posts)
        assertEquals(1L, scalar("SELECT COUNT(*) FROM context_usage WHERE usage_source='MANUAL_SUMMARY' AND run_id IS NULL"))
        assertEquals(1L, read().summary.combined.requestSnapshotCount) // Legacy is never promoted to authority.
    }

    private class Wire(
        val usage: String? = "\"usage\":{\"input_tokens\":10,\"output_tokens\":20,\"input_tokens_details\":{\"cached_tokens\":5}}",
        val bodyOverride: String? = null,
        val beforePost: () -> Unit = {},
        val beforeInput: () -> Unit = {},
    ) : HttpsURLConnection(URL("https://example.invalid/offline-fixture")) {
        var posts = 0
        var disconnected = false
        override fun connect() {}
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getOutputStream(): ByteArrayOutputStream { beforePost(); posts++; return ByteArrayOutputStream() }
        override fun getResponseCode() = 200
        override fun getContentType() = "text/event-stream"
        override fun getInputStream(): java.io.InputStream {
            beforeInput()
            val optionalUsage = usage?.let { ",$it" } ?: ""
            return (bodyOverride ?: "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"id\":\"final\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"retained summary\"}]}]$optionalUsage}}\n\n").byteInputStream()
        }
    }
}
