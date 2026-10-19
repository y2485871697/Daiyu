package app.daiyu.android.context

import android.content.Context
import android.content.ContextWrapper
import android.test.InstrumentationTestCase
import app.daiyu.android.domain.*
import app.daiyu.android.domain.context.*
import app.daiyu.android.storage.ConversationDatabase
import app.daiyu.android.testContext
import java.io.File
import java.util.UUID
import java.util.concurrent.CancellationException

@Suppress("DEPRECATION")
class ContextPreparationCoordinatorTest : InstrumentationTestCase() {
    private lateinit var context: Context
    private lateinit var db: ConversationDatabase
    private lateinit var service: ContextService
    private val binding = SummaryModelBinding("summary-provider", "summary-only")
    // Test-only explicit mapping of a fixture's message preference to its turn policy.
    // Not a production claim that legacy messages and turns are interchangeable.
    private val policies = CompressionPolicyResolver { settings ->
        require(settings.keepRecentMessages >= 1)
        CompressionPolicy(settings.automaticWithKnownWindow, settings.contextWindowTokens,
            settings.triggerPercent, settings.reserveTokens, settings.keepRecentMessages)
    }

    override fun setUp() {
        super.setUp()
        val base = testContext(instrumentation.targetContext, "preparation-${UUID.randomUUID()}-")
        val files = File(base.cacheDir, "preparation-${UUID.randomUUID()}").apply { mkdirs() }
        context = object : ContextWrapper(base) { override fun getFilesDir(): File = files }
        db = ConversationDatabase(context)
        service = ContextService(context, db)
    }
    override fun tearDown() {
        db.close(); context.deleteDatabase("conversations.db"); context.filesDir.deleteRecursively()
        super.tearDown()
    }
    private fun seed(turns: Int = 6): ConversationId {
        val owner = db.create("default").id
        repeat(turns) { n ->
            val run = RunId("r-$n")
            val user = Message(MessageId("u-$n"), owner, run, "user",
                listOf(MessageBlock.Text("u", "事实$n ".repeat(100))), RunStatus.COMPLETED)
            val assistant = Message(MessageId("a-$n"), owner, run, "assistant",
                listOf(MessageBlock.Text("a", "已确认$n ".repeat(100))), RunStatus.RUNNING)
            db.send(user, assistant); db.save(assistant.copy(status = RunStatus.COMPLETED))
        }
        return owner
    }
    private fun pending(owner: ConversationId) = Message(MessageId("pending"), owner, RunId("new-run"),
        "user", listOf(MessageBlock.Text("input", "new input")), RunStatus.COMPLETED)
    private fun settings() = CompressionSettings(true, 10000, 1000, 10, 2, binding)
    private fun bound(onRequest: () -> Unit = {}): BoundSummaryModel = BoundSummaryModel(binding,
        SummaryModelTransport { _, _ ->
            object : ModelSession {
                override fun cancel() { }
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                    onRequest()
                    sink(ModelEvent.TextFinal("summary", "已确认的事实摘要"))
                }
            }
        }, 100000, 90000, { SummaryRequestDeadline(60000) })
    private fun coordinator(settings: () -> CompressionSettings,
        models: SummaryModelResolver = SummaryModelResolver { bound() }) =
        ContextPreparationCoordinator(service, settings, policies, models)

    fun testRunWindowOverridesSavedCapacityWithoutMutatingPreferences() {
        val owner = seed(1)
        val saved = settings().copy(automatic = false, contextWindowTokens = 100000)
        val coordinator = coordinator({ saved }, SummaryModelResolver { error("automatic disabled") })
        val input = pending(owner).copy(blocks = listOf(MessageBlock.Text("input", "x".repeat(20000))))
        val frozen = coordinator.freeze(owner, listOf(input), contextWindowOverride = 2048)
        assertEquals(2048L, frozen.settings.contextWindowTokens)
        assertEquals(2048L, frozen.policy.contextTokens)
        assertEquals(100000L, saved.contextWindowTokens)
        val before = service.displayHistory(owner)
        var rejected = false
        try { coordinator.prepare(frozen) } catch (_: ContextCapacityExceededException) { rejected = true }
        assertTrue(rejected)
        assertEquals(before, service.displayHistory(owner))
        val automatic = coordinator.freeze(owner, listOf(pending(owner)))
        assertEquals(saved, automatic.settings)
    }

    fun testFrozenSettingsBindingAndPendingAreUsedOnce() {
        val owner = seed(); val original = service.displayHistory(owner)
        var current = settings(); var resolved: SummaryModelBinding? = null; var requests = 0
        val coordinator = coordinator({ current }, SummaryModelResolver { resolved = it; bound { requests++ } })
        val blocks = mutableListOf<MessageBlock>(MessageBlock.Text("input", "frozen input"))
        val input = mutableListOf(pending(owner).copy(blocks = blocks))
        val frozen = coordinator.freeze(owner, input)
        current = CompressionSettings(); blocks.clear(); input.clear()
        val prepared = coordinator.prepare(frozen)
        assertEquals(binding, resolved)
        assertEquals(settings(), frozen.settings)
        assertEquals(1, requests)
        assertNotNull(prepared.archiveId)
        assertEquals(original.takeLast(4), prepared.history.messages.takeLast(4))
        assertEquals(1, prepared.messages.count { it.id == MessageId("pending") })
        assertFalse(prepared.history.messages.any { it.id == MessageId("pending") })
        assertEquals("frozen input", (prepared.messages.last().blocks.single() as MessageBlock.Text).text)
        assertEquals(original, service.displayHistory(owner))
        assertEquals(ContextRevision.digest(original), prepared.expectedHistoryRevision)
        assertEquals(estimateTokens(prepared.history.messages), prepared.history.estimatedTokens)
        try { coordinator.prepare(frozen); fail("must not reuse") } catch (_: IllegalStateException) { }
        assertEquals(1, requests)
    }

    fun testDisabledOrUnknownWindowNeverResolvesSummaryModel() {
        val owner = seed(1); val original = service.displayHistory(owner)
        for (config in listOf(settings().copy(automatic = false), settings().copy(contextWindowTokens = null))) {
            val coordinator = coordinator({ config }, SummaryModelResolver { error("must not resolve") })
            val prepared = coordinator.prepare(coordinator.freeze(owner, listOf(pending(owner))))
            assertEquals(original, prepared.history.messages)
            assertNull(prepared.archiveId)
        }
    }

    fun testMissingOrUnavailableBindingNeverFallsBackAndStillEnforcesBudget() {
        val owner = seed(); val original = service.displayHistory(owner)
        for (missing in listOf(false, true)) {
            var resolved = 0
            val config = settings().copy(summaryModel = if (missing) null else binding)
            val coordinator = coordinator({ config }, SummaryModelResolver { resolved++; null })
            try { coordinator.prepare(coordinator.freeze(owner, listOf(pending(owner)))); fail() }
            catch (_: ContextCapacityExceededException) { }
            assertEquals(if (missing) 0 else 1, resolved)
            assertEquals(original, service.displayHistory(owner))
            assertNull(service.prepareSend(owner).archiveId)
        }
    }

    fun testKnownBudgetIsEnforcedEvenWhenAutomaticCompressionIsDisabled() {
        val owner = seed()
        val coordinator = coordinator({ settings().copy(automatic = false) }, SummaryModelResolver { error("must not resolve") })
        try { coordinator.prepare(coordinator.freeze(owner, listOf(pending(owner)))); fail() }
        catch (_: ContextCapacityExceededException) { }
        assertNull(service.prepareSend(owner).archiveId)
    }

    fun testWrongBindingAndUnsupportedLegacyPolicyAreRejectedBeforeRequest() {
        val owner = seed()
        val wrong = coordinator({ settings() }, SummaryModelResolver {
            bound().copy(binding = SummaryModelBinding("main", "not-the-summary"))
        })
        try { wrong.freeze(owner, listOf(pending(owner))); fail() } catch (_: IllegalArgumentException) { }
        val zero = coordinator({ settings().copy(keepRecentMessages = 0) }, SummaryModelResolver { error("must not resolve") })
        try { zero.freeze(owner, listOf(pending(owner))); fail() } catch (_: IllegalArgumentException) { }
        assertNull(service.prepareSend(owner).archiveId)
    }

    fun testSummaryFailureCancellationAndInsufficientModelBudgetPreserveOldArchive() {
        val owner = seed(); val original = service.displayHistory(owner)
        service.compress(owner, CompressionPolicy(keepTurns = 2), ContextSummarizer { "原有事实摘要" })
        val before = service.prepareSend(owner)
        for (mode in listOf("failure", "cancel", "budget")) {
            val token = ContextPreparationCancellation()
            val model = if (mode == "budget") bound().copy(inputBudgetTokens = 1) else bound {
                if (mode == "cancel") token.cancel() else error("summary failed")
            }
            val coordinator = coordinator({ settings() }, SummaryModelResolver { model })
            val frozen = coordinator.freeze(owner, listOf(pending(owner)), token)
            val failure = try { coordinator.prepare(frozen); null } catch (failure: Exception) { failure }
            assertNotNull(failure)
            if (mode == "cancel") assertTrue(failure is CancellationException)
            assertEquals(before, service.prepareSend(owner))
            assertEquals(original, service.displayHistory(owner))
            db.readableDatabase.rawQuery("SELECT count(*) FROM context_archives WHERE conversation_id=?",
                arrayOf(owner.value)).use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        }
    }

    fun testPendingCountsTowardBudgetWithoutBeingSavedOrDuplicated() {
        val owner = seed(1); val input = pending(owner); val history = service.displayHistory(owner)
        val exact = estimateTokens(history + input)
        val config = CompressionSettings(false, exact + 100, 100, 80, 2)
        val coordinator = coordinator({ config })
        val result = coordinator.prepare(coordinator.freeze(owner, listOf(input)))
        assertEquals(history + input, result.messages)
        assertEquals(history, service.displayHistory(owner))
        val bigger = input.copy(blocks = listOf(MessageBlock.Text("input", "new input!")))
        try { coordinator.prepare(coordinator.freeze(owner, listOf(bigger))); fail() }
        catch (_: ContextCapacityExceededException) { }
    }

    fun testPreparationRejectsCrossOwnerDuplicateAndForeignCoordinatorSnapshots() {
        val owner = seed(1); val coordinator = coordinator({ CompressionSettings() })
        try { coordinator.freeze(owner, listOf(pending(ConversationId("other")))); fail() }
        catch (_: IllegalArgumentException) { }
        try { coordinator.freeze(owner, listOf(pending(owner), pending(owner))); fail() }
        catch (_: IllegalArgumentException) { }
        val frozen = coordinator.freeze(owner, listOf(pending(owner)))
        try { this.coordinator({ CompressionSettings() }).prepare(frozen); fail() }
        catch (_: IllegalArgumentException) { }
        assertEquals(3, coordinator.prepare(frozen).messages.size)
    }

    fun testNewOwnerBudgetsPendingWithoutTryingToSummarizeMissingHistory() {
        val owner = ConversationId("not-created-yet")
        val model = bound().copy(transport = SummaryModelTransport { _, _ -> error("no evidence to summarize") })
        val coordinator = coordinator({ settings() }, SummaryModelResolver { model })
        val input = pending(owner).copy(blocks = listOf(MessageBlock.Text("input", "x".repeat(2000))))
        val result = coordinator.prepare(coordinator.freeze(owner, listOf(input)))
        assertEquals(listOf(input), result.messages)
        assertNull(result.archiveId)
        val huge = input.copy(blocks = listOf(MessageBlock.Text("input", "x".repeat(10000))))
        try { coordinator.prepare(coordinator.freeze(owner, listOf(huge))); fail("budget required") }
        catch (_: ContextCapacityExceededException) { }
        assertTrue(db.conversations().isEmpty())
    }

    fun testFinalRevisionUsesAllOriginalsEvenBehindArchiveAndOutsideUiPage() {
        val owner = seed(32)
        val original = service.displayHistory(owner)
        service.compress(owner, CompressionPolicy(keepTurns = 2), ContextSummarizer { "历史摘要" })
        val coordinator = coordinator({ CompressionSettings() })
        val prepared = coordinator.prepare(coordinator.freeze(owner, listOf(pending(owner))))
        assertNotNull(prepared.archiveId)
        assertEquals(ContextRevision.digest(original), prepared.expectedHistoryRevision)
        DatabaseTransactions { db.writableDatabase }.run { transaction ->
            assertTrue(service.matchesHistoryRevision(transaction, owner, prepared.expectedHistoryRevision))
        }
        val firstReply = original.first { it.role == "assistant" }
        DatabaseTransactions { db.writableDatabase }.run { sql ->
            assertEquals(1, sql.update("messages", android.content.ContentValues().apply {
                put("error", "corrected old evidence")
            }, "id=? AND conversation_id=? AND run_id=? AND status='COMPLETED'",
                arrayOf(firstReply.id.value, firstReply.conversationId.value, firstReply.runId.value)))
        }
        DatabaseTransactions { db.writableDatabase }.run { transaction ->
            assertFalse(service.matchesHistoryRevision(transaction, owner, prepared.expectedHistoryRevision))
        }
        assertEquals(64, service.displayHistory(owner).size)
    }

    fun testFinalRevisionRequiresTheOwnersOpenTransaction() {
        val owner = seed(1)
        val revision = ContextRevision.digest(service.displayHistory(owner))
        try { service.matchesHistoryRevision(db.writableDatabase, owner, revision); fail("transaction required") }
        catch (_: IllegalStateException) { }
        val other = ConversationDatabase(context)
        try {
            DatabaseTransactions { other.writableDatabase }.run { transaction ->
                try { service.matchesHistoryRevision(transaction, owner, revision); fail("same connection required") }
                catch (_: IllegalStateException) { }
            }
        } finally { other.close() }
    }

    fun testPreCancelledPreparationDoesNotResolveOrPublish() {
        val owner = seed(); val token = ContextPreparationCancellation().apply { cancel() }
        val coordinator = coordinator({ error("must not load") }, SummaryModelResolver { error("must not resolve") })
        try { coordinator.freeze(owner, listOf(pending(owner)), token); fail() }
        catch (_: CancellationException) { }
        assertNull(service.prepareSend(owner).archiveId)
    }
}
