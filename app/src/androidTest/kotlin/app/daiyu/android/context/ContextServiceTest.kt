package app.daiyu.android.context

import android.content.Context
import android.content.ContextWrapper
import android.test.InstrumentationTestCase
import app.daiyu.android.testContext
import app.daiyu.android.storage.ConversationDatabase
import app.daiyu.android.domain.*
import app.daiyu.android.domain.context.*
import java.io.File
import java.util.UUID

@Suppress("DEPRECATION")
class ContextServiceTest : InstrumentationTestCase() {
    private lateinit var context: Context
    private lateinit var db: ConversationDatabase
    private lateinit var service: ContextService
    override fun setUp() {
        super.setUp()
        val base = testContext(instrumentation.targetContext, "context-${UUID.randomUUID()}-")
        val files = File(base.cacheDir, "context-${UUID.randomUUID()}").apply { mkdirs() }
        context = object : ContextWrapper(base) { override fun getFilesDir(): File = files }
        db = ConversationDatabase(context)
        service = ContextService(context, db)
    }
    override fun tearDown() {
        db.close(); context.deleteDatabase("conversations.db"); context.filesDir.deleteRecursively(); super.tearDown()
    }
    private fun seed(turns: Int = 6): ConversationId {
        val owner = db.create("default").id
        repeat(turns) { n -> val run = RunId("run-$n-${owner.value}")
            val user = Message(MessageId("u-$n-${owner.value}"), owner, run, "user", listOf(MessageBlock.Text("u", "事实$n ".repeat(100))), RunStatus.COMPLETED)
            val reply = Message(MessageId("a-$n-${owner.value}"), owner, run, "assistant", listOf(MessageBlock.Text("a", "已确认$n ".repeat(100))), RunStatus.RUNNING)
            db.send(user, reply); db.save(reply.copy(status = RunStatus.COMPLETED))
        }
        return owner
    }
    private fun reject(block: () -> Unit) { try { block(); fail("must reject") } catch (_: IllegalArgumentException) {} }
    fun testCompressionFailureUsesTheRunningIdentityAndKeepsTheOriginalHistory() {
        val owner = seed(6)
        val original = service.displayHistory(owner)
        val events = mutableListOf<app.daiyu.android.timeline.CompressionActivity>()
        service.onCompression = { events += it }
        var started = false
        val failure = ModelRequestException(ModelFailure.RATE_LIMIT, ModelRequestDiagnostic(429))
        val summarizer = ContextSummarizer { throw failure }
        try {
            service.compress(owner, CompressionPolicy(true, 10000, reserveTokens = 500, tokenTail = true), summarizer,
                onStarted = { started = true })
            fail("must reject")
        } catch (caught: ModelRequestException) { assertSame(failure, caught) }
        assertTrue(started)
        assertEquals(2, events.size)
        assertTrue(events[0].running)
        assertEquals(events[0].id, events[1].id)
        assertEquals(events[0].anchor, events[1].anchor)
        assertEquals(failure.userMessage, events[1].failure)
        assertFalse(events[1].running)
        assertEquals(original, service.displayHistory(owner))
    }

    fun testRuntimeSummaryCommitsWithTerminalAndSurvivesNextTurnAndReopen() {
        val owner = seed(6)
        val source = service.displayHistory(owner)
        val run = RunId("live-run")
        val user = Message(MessageId("live-user"), owner, run, "user",
            listOf(MessageBlock.Text("u", "new request")), RunStatus.COMPLETED)
        val assistant = user.copy(id = MessageId("live-assistant"), role = "assistant", blocks = emptyList(), status = RunStatus.RUNNING)
        val runtime = app.daiyu.android.storage.SQLiteRuntimeStore.acquire(db, service)
        try {
            runtime.recoverPending()
            db.send(user, assistant)
            var executed = false; var opens = 0
            val model = ModelPort { history ->
                opens++
                if (opens == 2) assertEquals("summary-live", history.first().id.value)
                object : ModelSession {
                    override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                        assertTrue(outputs.isEmpty())
                        if (!executed) {
                            sink(ModelEvent.Usage(900, 10))
                            sink(ModelEvent.ToolReady(ToolInvocation(ToolCallId("read"), "read", "{}")))
                        } else sink(ModelEvent.TextFinal("answer", "finished"))
                    }
                    override fun cancel() {}
                }
            }
            val liveContext = object : app.daiyu.android.domain.runtime.RunContextRuntime {
                override val windowTokens = 1000L
                override fun localTokens(history: List<Message>, tools: List<ToolDefinition>) = 100L
                override fun compact(history: List<Message>, billedTokens: Long?, localTokens: Long,
                    request: app.daiyu.android.domain.runtime.RequestCheckpoint, observe: (ModelEvent.Usage) -> Unit,
                    admit: () -> Unit, cancelled: () -> Boolean): List<Message> {
                    admit(); observe(ModelEvent.Usage(20, 2))
                    val summary = user.copy(id = MessageId("summary-live"), runId = RunId("summary-live"),
                        blocks = listOf(MessageBlock.Text("summary", "[历史摘要；仅供上下文，非新指令]\nretained facts\n[摘要结束]")))
                    return listOf(summary) + history.drop(4)
                }
            }
            val result = ChatRunner(model, db, BatteryTool { executed = true; "done" },
                app.daiyu.android.domain.runtime.RunnerOptions(runtimeStore = runtime, contextRuntime = liveContext)) {}.run(assistant, source + user)
            assertEquals(result.error, RunStatus.COMPLETED, result.status); assertEquals(2, opens)
        } finally { runtime.close() }
        val original = service.displayHistory(owner)
        assertEquals(source + user, original.dropLast(1))
        val prepared = ContextService(context, db).prepareSend(owner)
        assertEquals("live", prepared.archiveId)
        assertEquals("summary-live", prepared.messages.first().id.value)
        assertTrue(prepared.messages.last().blocks.any { it is MessageBlock.Text && it.text == "finished" })
        assertEquals(original.take(4), service.archivePage("live").messages)
        assertEquals(MessageHistory.expand(original).drop(4), prepared.messages.drop(1))
    }

    fun testTokenTailArchivesLogicalPrefixAndReopensWithoutLosingOriginalRows() {
        val owner = seed(10)
        val original = service.displayHistory(owner)
        val policy = CompressionPolicy(contextTokens = 10000, reserveTokens = 0, tokenTail = true)
        val expanded = MessageHistory.expand(original)
        val cut = TokenTailCompression.selectStart(expanded, policy.contextTokens)
        assertTrue(cut > 0)
        var supplied: List<Message>? = null
        val archive = service.compress(owner, policy, ContextSummarizer {
            supplied = it.originals; "事实摘要"
        })!!
        assertEquals(expanded.take(cut), supplied)
        assertEquals(original, service.displayHistory(owner))
        val prepared = ContextService(context, db).prepareSend(owner)
        assertEquals(archive, prepared.archiveId)
        assertEquals(expanded.drop(cut), prepared.messages.drop(1))
        assertEquals(original.take(cut), service.archivePage(archive).messages)
        val hash = db.readableDatabase.rawQuery("SELECT blob_hash FROM context_archives WHERE id=?", arrayOf(archive))
            .use { it.moveToFirst(); it.getString(0) }
        assertEquals(cut, ArchiveCodec.decodeDocument(PrivateBlobs(context).read(hash)).logicalPrefixCount)
    }

    fun testBranchAndPhysicalReviseDriveDisplayAndSendIdentically() {
        val owner = seed(); val old = service.displayHistory(owner); val copy = service.branch(owner, old[3].id)
        assertEquals(old, service.displayHistory(owner)); assertEquals(4, db.messages(copy).size)
        assertEquals(old.take(4).map { it.blocks }, service.prepareSend(copy).messages.map { it.blocks })
        assertTrue(service.displayHistory(copy).none { m -> old.any { it.id == m.id } })
        service.revise(copy, service.displayHistory(copy)[0].id, "更正事实")
        assertEquals(1, db.messages(copy).size); assertEquals(service.displayHistory(copy), service.prepareSend(copy).messages)
        assertEquals("更正事实", (service.prepareSend(copy).messages.single().blocks.single() as MessageBlock.Text).text)
        assertEquals(old, service.displayHistory(owner))
    }
    fun testArchiveIsExactPageableAndMissingArchiveRefusesRevision() {
        val owner = seed(); val old = service.displayHistory(owner)
        val archive = service.compress(owner, CompressionPolicy(keepTurns = 2), ContextSummarizer { "事实0至3已确认，保留后续原文" })!!
        assertEquals(old, service.displayHistory(owner)); assertEquals(8, service.archivePage(archive).total)
        val first = service.archivePage(archive, 0, 3); assertEquals(3, first.nextOffset)
        assertEquals(old.take(3), first.messages)
        assertEquals(old.drop(6).take(2), service.archivePage(archive, 6, 3).messages)
        val prepared = service.prepareSend(owner); assertEquals(archive, prepared.archiveId); assertEquals(5, prepared.messages.size)
        assertEquals(old.takeLast(4), prepared.messages.takeLast(4))
        val hash = db.readableDatabase.rawQuery("SELECT blob_hash FROM context_archives WHERE id=?", arrayOf(archive)).use { it.moveToFirst(); it.getString(0) }
        File(context.filesDir, "neutral-context-blobs/$hash").delete()
        reject { service.revise(owner, old[1].id) }
        assertEquals(old, service.displayHistory(owner))
    }
    fun testFailureNoShrinkAndHistoryRaceNeverApplySummary() {
        val owner = seed(); val old = service.displayHistory(owner)
        reject { service.compress(owner, CompressionPolicy(), ContextSummarizer { "X".repeat(100000) }) }
        assertNull(service.prepareSend(owner).archiveId)
        try { service.compress(owner, CompressionPolicy(), ContextSummarizer { throw IllegalStateException("failure") }); fail() } catch (_: IllegalStateException) {}
        assertEquals(old, service.displayHistory(owner))
        reject { service.compress(owner, CompressionPolicy(), ContextSummarizer {
            service.revise(owner, old.last().id, null)
            // Same cutoff is not a change; now revise earlier to provoke snapshot mismatch.
            service.revise(owner, old[3].id)
            "简短摘要"
        }) }
        assertNull(service.prepareSend(owner).archiveId); assertEquals(4, service.displayHistory(owner).size)
    }
    fun testAutomaticUnknownWindowAndProviderUsageIsolation() {
        val owner = seed(); var calls = 0
        val summarizer = ContextSummarizer { calls++; "已确认事实摘要" }
        service.prepareSend(owner, CompressionPolicy(enabled = true), summarizer); assertEquals(0, calls)
        service.prepareSend(owner, CompressionPolicy(true, 10000, 10, 1000, 2), summarizer); assertEquals(1, calls)
        val other = seed(1)
        val row = UsageRecord("receipt", "task", owner.value, 100, 20, 30, 4)
        service.recordUsage(row); service.recordUsage(row)
        reject { service.recordUsage(row.copy(branchId = other.value)) }
        // Legacy receipt durability remains tested; it neither authorizes a run nor supplies
        // authoritative counters to the production query.
        reject { service.usage(owner.value, "task") }
        assertEquals(0L, service.usage(owner.value).requests)
        assertNull(service.usage(owner.value).totalTokens)
        assertNull(service.usage(other.value).totalTokens)
        service.recordUsage(UsageRecord("unknown", "task", owner.value, null, null))
        assertTrue(service.usage(owner.value).incomplete)
    }

    fun testKnownOversizeContextIsRejectedEvenWithoutAutomaticCompression() {
        val owner = seed(); val original = service.displayHistory(owner)
        val before = service.prepareSend(owner)
        for (enabled in listOf(false, true)) {
            val error = compressionFailure {
                service.prepareSend(owner, CompressionPolicy(enabled, 1024, reserveTokens = 0))
            }
            assertTrue(error is ContextCapacityExceededException)
            assertEquals(estimateTokens(original), (error as ContextCapacityExceededException).estimatedTokens)
            assertEquals(1024L, error.budgetTokens)
            assertCompressionState(owner, original, before, emptyList())
        }
        // Unknown provider capacity is not fabricated, and the estimate is never a usage receipt.
        assertEquals(before, service.prepareSend(owner, CompressionPolicy(enabled = true)))
        assertEquals(0L, service.usage(owner.value).requests)
    }

    fun testPendingInputCountsTowardsBudgetButIsNotReturnedOrPersisted() {
        val owner = seed(1); val original = service.displayHistory(owner)
        val pending = Message(MessageId("pending"), owner, RunId("pending-run"), "user",
            listOf(MessageBlock.Text("input", "new input")), RunStatus.COMPLETED)
        val budget = estimateTokens(original + pending)
        val policy = CompressionPolicy(contextTokens = budget + 17, reserveTokens = 17)
        val prepared = service.prepareSend(owner, policy, pendingMessages = listOf(pending))
        assertEquals(original, prepared.messages)
        assertEquals(estimateTokens(original), prepared.estimatedTokens)
        assertEquals(original, service.displayHistory(owner))
        val larger = pending.copy(blocks = listOf(MessageBlock.Text("input", "new input!")))
        assertTrue(compressionFailure {
            service.prepareSend(owner, policy, pendingMessages = listOf(larger))
        } is ContextCapacityExceededException)
        assertEquals(original, service.displayHistory(owner))
        assertTrue(compressionRows(owner).isEmpty())
    }

    fun testPendingInputTriggersCompressionAndMatchesSubsequentPreparation() {
        val owner = seed(3); val original = service.displayHistory(owner)
        val pending = Message(MessageId("pending"), owner, RunId("pending-run"), "user",
            listOf(MessageBlock.Text("input", "x".repeat(2000))), RunStatus.COMPLETED)
        val policy = CompressionPolicy(true, 10000, 80, 1000, 1)
        assertFalse(policy.shouldCompress(estimateTokens(original)))
        assertTrue(policy.shouldCompress(estimateTokens(original + pending)))
        var calls = 0
        val prepared = service.prepareSend(owner, policy, ContextSummarizer { calls++; "已确认事实" },
            pendingMessages = listOf(pending))
        assertEquals(1, calls)
        assertNotNull(prepared.archiveId)
        assertEquals(original.takeLast(2), prepared.messages.takeLast(2))
        assertEquals(estimateTokens(prepared.messages), prepared.estimatedTokens)
        assertTrue(policy.fitsSendBudget(estimateTokens(prepared.messages + pending)))
        assertEquals(prepared, service.prepareSend(owner, policy, pendingMessages = listOf(pending)))
        assertEquals(original, service.displayHistory(owner))
        assertEquals(original.take(4), service.archivePage(prepared.archiveId!!).messages)
    }

    fun testStillOversizeSummaryDoesNotReplaceActiveArchive() {
        val owner = seed(); val original = service.displayHistory(owner)
        service.compress(owner, CompressionPolicy(keepTurns = 2), ContextSummarizer { "旧有效摘要" })!!
        val prepared = service.prepareSend(owner); val rows = compressionRows(owner)
        var calls = 0
        val summary = "更短的新摘要"
        // Raw summary + tail fits exactly; the real untrusted-summary wrapper must also count.
        val budget = estimateTokens(original.takeLast(2)) + summary.toByteArray(Charsets.UTF_8).size + 16L
        val error = compressionFailure {
            service.prepareSend(owner, CompressionPolicy(true, budget, 80, 0, 1), ContextSummarizer {
                calls++; summary
            })
        }
        assertEquals(1, calls)
        assertTrue(error is ContextCapacityExceededException)
        assertTrue((error as ContextCapacityExceededException).estimatedTokens > budget)
        assertCompressionState(owner, original, prepared, rows)
    }

    fun testNoSafeCompressionCutCannotReturnOversizePreparation() {
        val owner = seed(1); val original = service.displayHistory(owner)
        val before = service.prepareSend(owner)
        assertTrue(compressionFailure {
            service.prepareSend(owner, CompressionPolicy(true, 1024, 80, 0, 1), ContextSummarizer {
                throw AssertionError("protected tail cannot be summarized")
            })
        } is ContextCapacityExceededException)
        assertCompressionState(owner, original, before, emptyList())
    }

    fun testCancellationBeforeAndDuringSummaryKeepsPriorArchiveAfterReopen() {
        val owner = seed(); val original = service.displayHistory(owner)
        service.compress(owner, CompressionPolicy(keepTurns = 2), ContextSummarizer { "旧有效摘要" })!!
        val prepared = service.prepareSend(owner); val rows = compressionRows(owner)
        var cancelled = true
        var calls = 0
        val policy = CompressionPolicy(true, 10000, 10, 1000, 2)
        val summarizer = ContextSummarizer { calls++; cancelled = true; "取消后不得发布的摘要" }
        assertTrue(compressionFailure {
            service.prepareSend(owner, policy, summarizer, cancelled = { cancelled })
        } is java.util.concurrent.CancellationException)
        assertEquals(0, calls)
        cancelled = false
        assertTrue(compressionFailure {
            service.prepareSend(owner, policy, summarizer, cancelled = { cancelled })
        } is java.util.concurrent.CancellationException)
        assertEquals(1, calls)
        assertCompressionState(owner, original, prepared, rows)
    }

    fun testCancellationAfterInsertRollsBackNewArchiveAndOldDeactivation() {
        val owner = seed(); val original = service.displayHistory(owner)
        service.compress(owner, CompressionPolicy(keepTurns = 2), ContextSummarizer { "旧有效摘要" })!!
        val prepared = service.prepareSend(owner); val rows = compressionRows(owner)
        assertTrue(compressionFailure {
            service.compress(owner, CompressionPolicy(keepTurns = 2), ContextSummarizer { "不得提交的新摘要" },
                cancelled = { compressionRows(owner).size > rows.size })
        } is java.util.concurrent.CancellationException)
        assertCompressionState(owner, original, prepared, rows)
    }

    fun testInterruptedThreadNeverPreparesOrCallsSummarizer() {
        val owner = seed(); val original = service.displayHistory(owner)
        Thread.currentThread().interrupt()
        try {
            assertTrue(compressionFailure {
                service.prepareSend(owner, CompressionPolicy(true, 10000, 10, 1000, 2), ContextSummarizer {
                    throw AssertionError("interrupted preparation cannot call model")
                })
            } is java.util.concurrent.CancellationException)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
        assertCompressionState(owner, original, PreparedContext(original, estimateTokens(original), null), emptyList())
    }

    fun testPendingInputCannotIntroduceAnotherConversationIntoPreparation() {
        val owner = seed(); val other = seed(1); val original = service.displayHistory(owner)
        val foreign = service.displayHistory(other).first()
        assertTrue(compressionFailure {
            service.prepareSend(owner, CompressionPolicy(true, 10000, 10, 1000, 2), ContextSummarizer {
                throw AssertionError("scope must be checked before model")
            }, pendingMessages = listOf(foreign))
        } is IllegalArgumentException)
        assertEquals(original, service.displayHistory(owner))
        assertTrue(compressionRows(owner).isEmpty())
    }

    private fun compressionRows(owner: ConversationId): List<List<String>> =
        db.readableDatabase.rawQuery(
            "SELECT id,prefix_count,revision,summary,blob_hash,created,active " +
                "FROM context_archives WHERE conversation_id=? ORDER BY id", arrayOf(owner.value)).use { c ->
            buildList { while (c.moveToNext()) add((0..6).map { c.getString(it) }) }
        }
    private fun compressionFailure(block: () -> Unit): Exception {
        try { block() } catch (e: Exception) { return e }
        throw AssertionError("must reject failed compression or unavailable archive")
    }
    private fun assertCompressionState(owner: ConversationId, original: List<Message>,
        prepared: PreparedContext, rows: List<List<String>>) {
        db.close(); db = ConversationDatabase(context); service = ContextService(context, db)
        assertEquals(original, service.displayHistory(owner))
        assertEquals(rows, compressionRows(owner))
        assertEquals(prepared, service.prepareSend(owner))
    }

    fun testActiveArchiveSurvivesThrowingEmptyAndNonshrinkingSummarizers() {
        val owner = seed(); val original = service.displayHistory(owner)
        service.compress(owner, CompressionPolicy(keepTurns = 2), ContextSummarizer { "旧有效摘要" })!!
        val prepared = service.prepareSend(owner); val rows = compressionRows(owner)
        val failures = listOf(ContextSummarizer { throw IllegalStateException("model failed") },
            ContextSummarizer { "" }, ContextSummarizer { "x".repeat(100000) })
        failures.forEach { summarizer ->
            compressionFailure { service.compress(owner, CompressionPolicy(keepTurns = 2), ContextSummarizer { input ->
                assertEquals(original, service.displayHistory(owner))
                assertEquals(rows, compressionRows(owner))
                assertEquals(prepared, service.prepareSend(owner))
                summarizer.summarize(input)
            }) }
            assertCompressionState(owner, original, prepared, rows)
            assertEquals(original.take(8), service.archivePage(prepared.archiveId!!).messages)
        }
    }

    fun testActiveArchiveWriteAbortAndIgnoredInsertRollBackDeactivation() {
        val owner = seed(); val original = service.displayHistory(owner)
        service.compress(owner, CompressionPolicy(keepTurns = 2), ContextSummarizer { "旧有效摘要" })!!
        val prepared = service.prepareSend(owner); val rows = compressionRows(owner)
        listOf("ABORT", "IGNORE").forEach { mode ->
            val raised = if (mode == "IGNORE") "RAISE(IGNORE)" else "RAISE(ABORT, 'archive write failed')"
            db.writableDatabase.execSQL("CREATE TRIGGER reject_archive BEFORE INSERT ON context_archives " +
                "BEGIN SELECT $raised; END")
            val error = compressionFailure {
                service.compress(owner, CompressionPolicy(keepTurns = 2), ContextSummarizer { "不得提交的新摘要" })
            }
            assertTrue(if (mode == "ABORT") error is android.database.sqlite.SQLiteException
                else error is IllegalStateException)
            assertCompressionState(owner, original, prepared, rows)
            assertEquals(original.take(8), service.archivePage(prepared.archiveId!!).messages)
            db.writableDatabase.execSQL("DROP TRIGGER reject_archive")
        }
    }

    fun testActiveArchiveSnapshotCasChecksTailAndPrefixRevisionChanges() {
        listOf(false, true).forEach { changePrefix ->
            val owner = seed(); val original = service.displayHistory(owner)
            service.compress(owner, CompressionPolicy(keepTurns = 2), ContextSummarizer { "旧有效摘要" })!!
            val prepared = service.prepareSend(owner); val rows = compressionRows(owner)
            val index = if (changePrefix) 0 else original.lastIndex
            val changed = original[index].copy(blocks = listOf(MessageBlock.Text("concurrent", "并发更正原文")))
            val error = compressionFailure { service.compress(owner, CompressionPolicy(keepTurns = 2), ContextSummarizer {
                db.writableDatabase.execSQL("UPDATE messages SET blocks=? WHERE id=?",
                    arrayOf(ArchiveCodec.blocks(changed.blocks).toString(), changed.id.value))
                "不得应用的竞态摘要"
            }) }
            assertTrue(error is IllegalArgumentException)
            assertEquals("历史已改变；摘要未应用", error.message)
            val after = original.toMutableList().apply { this[index] = changed }
            val expectedMessages = if (changePrefix) after else prepared.messages.dropLast(1) + changed
            val expected = PreparedContext(expectedMessages, estimateTokens(expectedMessages),
                if (changePrefix) null else prepared.archiveId)
            assertCompressionState(owner, after, expected, rows)
            assertEquals(original.take(8), service.archivePage(prepared.archiveId!!).messages)
        }
    }

    fun testMissingCorruptAndDigestMismatchedActiveArchiveRefuseSendWithoutReplacement() {
        listOf("missing", "corrupt", "digest").forEach { mode ->
            val owner = seed(); val original = service.displayHistory(owner)
            val archive = service.compress(owner, CompressionPolicy(keepTurns = 2),
                ContextSummarizer { "旧有效摘要" })!!
            val hash = compressionRows(owner).single()[4]
            val file = File(context.filesDir, "neutral-context-blobs/$hash")
            when (mode) {
                "missing" -> assertTrue(file.delete())
                "corrupt" -> file.writeBytes("not an archive".toByteArray(Charsets.UTF_8))
                "digest" -> {
                    val hashOfDifferentArchive = PrivateBlobs(context).put(ArchiveCodec.encode(original.drop(2).take(8)))
                    db.writableDatabase.execSQL("UPDATE context_archives SET blob_hash=? WHERE id=?",
                        arrayOf(hashOfDifferentArchive, archive))
                }
            }
            val rows = compressionRows(owner)
            repeat(2) {
                compressionFailure {
                    service.prepareSend(owner, CompressionPolicy(true, 10000, 10, 1000, 2),
                        ContextSummarizer { throw AssertionError("unavailable archive must not invoke summarizer") })
                }
                assertEquals(original, service.displayHistory(owner))
                assertEquals(rows, compressionRows(owner))
                db.close(); db = ConversationDatabase(context); service = ContextService(context, db)
            }
        }
    }
}
