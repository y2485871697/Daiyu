package app.daiyu.android.context

import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.database.Cursor
import android.test.InstrumentationTestCase
import app.daiyu.android.domain.*
import app.daiyu.android.domain.context.*
import app.daiyu.android.media.video.MessageVideoOwner
import app.daiyu.android.media.video.MessageVideoReference
import app.daiyu.android.model.videos.MessageVideoInput
import app.daiyu.android.storage.ConversationDatabase
import app.daiyu.android.storage.MessageHistoryCodec
import app.daiyu.android.storage.MessageVideoCodecException
import app.daiyu.android.storage.MessageVideoCodecFailure
import app.daiyu.android.storage.MessageVideoInputCodec
import app.daiyu.android.storage.SQLiteMessageVideoStore
import app.daiyu.android.testContext
import org.json.JSONArray
import java.io.File
import java.util.UUID

/** Real SQLite/codec/service regression tests; no model, media decoder or network is involved. */
@Suppress("DEPRECATION")
class ContextVideoBoundaryTest : InstrumentationTestCase() {
    private lateinit var context: Context
    private lateinit var db: ConversationDatabase
    private lateinit var service: ContextService

    override fun setUp() {
        super.setUp()
        val base = testContext(instrumentation.targetContext, "context-video-${UUID.randomUUID()}-")
        val files = File(base.cacheDir, "context-video-${UUID.randomUUID()}").apply { mkdirs() }
        context = object : ContextWrapper(base) { override fun getFilesDir(): File = files }
        db = ConversationDatabase(context)
        service = ContextService(context, db)
    }

    override fun tearDown() {
        db.close()
        context.deleteDatabase("conversations.db")
        context.filesDir.deleteRecursively()
        super.tearDown()
    }

    private fun reopen() {
        db.close()
        db = ConversationDatabase(context)
        service = ContextService(context, db)
    }

    private fun text(value: String) = listOf(MessageBlock.Text("text", value.repeat(100)))

    private fun row(owner: ConversationId, key: String, run: String, role: String) = Message(
        MessageId("${owner.value}-$key"), owner, RunId("${owner.value}-$run"), role,
        text("事实-$key "), RunStatus.COMPLETED)

    private fun inputs(physical: Message, userId: MessageId = physical.id): List<MessageVideoInput> {
        val owner = MessageVideoOwner(physical.conversationId.value, userId.value)
        val reference = MessageVideoReference(owner, "12345678-1234-1234-1234-123456789abc",
            "video/webm", 987654, "ab".repeat(32), 3210, 640, 480)
        return listOf(MessageVideoInput(owner, listOf(reference)))
    }

    private fun turn(owner: ConversationId, key: String, video: Boolean = false): List<Message> {
        val user = row(owner, "u-$key", key, Message.USER_ROLE)
        val reply = row(owner, "a-$key", key, Message.ASSISTANT_ROLE)
        db.send(user, reply.copy(status = RunStatus.RUNNING),
            videoInputs = if (video) inputs(user) else emptyList())
        db.save(reply)
        return listOf(user, reply)
    }

    private fun addSidecar(physical: Message, userId: MessageId = physical.id) {
        DatabaseTransactions { db.writableDatabase }.run { sql ->
            SQLiteMessageVideoStore.insert(sql, physical, inputs(physical, userId))
        }
    }

    private fun sidecar(physical: Message) = SQLiteMessageVideoStore.read(db.readableDatabase, physical)

    // Historical owner/supplement shapes cannot be produced by the ordinary two-row send API.
    // Match ContextHistoryServiceTest's physical-row fixture and use the production codecs.
    private fun insertHistoricalRows(rows: List<Message>, videoRow: Message, userId: MessageId) {
        DatabaseTransactions { db.writableDatabase }.run { sql ->
            rows.forEach { message ->
                MessageHistoryCodec.validate(message)
                sql.insertOrThrow("messages", null, ContentValues().apply {
                    put("id", message.id.value); put("conversation_id", message.conversationId.value)
                    put("run_id", message.runId.value); put("role", message.role)
                    put("blocks", ArchiveCodec.blocks(message.blocks).toString())
                    put("status", message.status.name); put("error", message.error)
                    put("protocol", JSONArray(message.protocolItems).toString())
                    put("history_segments", MessageHistoryCodec.encode(message.historySegments).toString())
                })
            }
            MessageHistory.expand(rows)
            SQLiteMessageVideoStore.insert(sql, videoRow, inputs(videoRow, userId))
        }
    }

    /** Snapshot every SQLite table, including sidecars, archives and sqlite_sequence. */
    private fun databaseState(): Map<String, List<List<String?>>> {
        val tables = db.readableDatabase.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        return tables.associateWith { table ->
            val quoted = table.replace("\"", "\"\"")
            db.readableDatabase.rawQuery("SELECT * FROM \"$quoted\"", null).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add((0 until cursor.columnCount).map { column ->
                        when (cursor.getType(column)) {
                            Cursor.FIELD_TYPE_NULL -> null
                            Cursor.FIELD_TYPE_BLOB -> "blob:" + cursor.getBlob(column).joinToString("") {
                                "%02x".format(it.toInt() and 255)
                            }
                            else -> "${cursor.getType(column)}:${cursor.getString(column)}"
                        }
                    })
                }.sortedBy { it.toString() }
            }
        }
    }

    private fun archiveRows(owner: ConversationId): List<List<String>> = db.readableDatabase.rawQuery(
        "SELECT id,prefix_count,revision,summary,blob_hash,created,active FROM context_archives " +
            "WHERE conversation_id=? ORDER BY id", arrayOf(owner.value)).use { cursor ->
        buildList { while (cursor.moveToNext()) add((0 until cursor.columnCount).map { cursor.getString(it) }) }
    }

    private fun reject(block: () -> Unit): Exception {
        try { block() } catch (failure: Exception) { return failure }
        fail("Unsafe context operation must reject")
        throw AssertionError("unreachable")
    }

    private fun triggeringPolicy(original: List<Message>) = CompressionPolicy(
        enabled = true, contextTokens = estimateTokens(original) + 1024,
        triggerPercent = 10, reserveTokens = 0, keepTurns = 1)

    private fun assertRaw(owner: ConversationId, original: List<Message>) {
        val prepared = service.prepareSend(owner)
        assertNull(prepared.archiveId)
        assertEquals(original, prepared.messages)
        assertEquals(estimateTokens(original), prepared.estimatedTokens)
        assertEquals(original, service.displayHistory(owner))
    }

    private fun assertArchived(owner: ConversationId, original: List<Message>, count: Int, archive: String) {
        assertTrue(isCompleteContextPrefix(original, count))
        assertEquals(original.take(count), service.archivePage(archive).messages)
        val prepared = service.prepareSend(owner)
        assertEquals(archive, prepared.archiveId)
        assertEquals(original.drop(count), prepared.messages.drop(1))
        assertEquals(1 + original.size - count, prepared.messages.size)
        assertEquals(estimateTokens(prepared.messages), prepared.estimatedTokens)
        assertEquals(original, service.displayHistory(owner))
    }

    fun testFirstTurnVideoNeverCallsManualOrAutomaticSummarizer() {
        val owner = db.create("default").id
        val original = turn(owner, "first", video = true) +
            turn(owner, "second") + turn(owner, "third") + turn(owner, "fourth")
        assertEquals(inputs(original.first()), sidecar(original.first()))
        val before = databaseState()
        var calls = 0
        val summarizer = ContextSummarizer { calls++; "must not summarize video history" }
        assertNull(service.compress(owner, CompressionPolicy(keepTurns = 1), summarizer))
        val policy = triggeringPolicy(original)
        assertTrue(policy.shouldCompress(estimateTokens(original)))
        val prepared = service.prepareSend(owner, policy, summarizer)
        assertNull(prepared.archiveId)
        assertEquals(original, prepared.messages)
        assertEquals(0, calls)
        assertTrue(archiveRows(owner).isEmpty())
        assertEquals(before, databaseState())
        reopen()
        assertRaw(owner, original)
        assertEquals(inputs(original.first()), sidecar(original.first()))
        assertEquals(before, databaseState())
    }

    fun testTextPrefixBeforeVideoArchivesOnlyCompleteRuns() {
        val owner = db.create("default").id
        val prefix = turn(owner, "first") + turn(owner, "second")
        val video = turn(owner, "video", video = true)
        val original = prefix + video + turn(owner, "tail-one") + turn(owner, "tail-two")
        var calls = 0
        val archive = service.compress(owner, CompressionPolicy(keepTurns = 1), ContextSummarizer { input ->
            calls++
            assertEquals(prefix, input.originals)
            assertEquals(ContextRevision.digest(prefix), input.revision)
            assertTrue(input.originals.none { it.runId == video.first().runId })
            "text-only factual summary"
        })!!
        assertEquals(1, calls)
        assertArchived(owner, original, prefix.size, archive)
        assertEquals(inputs(video.first()), sidecar(video.first()))
        reopen()
        assertArchived(owner, original, prefix.size, archive)
        assertEquals(inputs(video.first()), sidecar(video.first()))
    }

    private fun assertHistoricalVideoBoundary(standalone: Boolean) {
        val owner = db.create("default").id
        val prefix = turn(owner, "prefix")
        val user = row(owner, "history-user", "history", Message.USER_ROLE)
        val answer = row(owner, "history-answer", "history", Message.ASSISTANT_ROLE)
        val supplement = row(owner, "history-supplement", "history", Message.USER_ROLE)
        val physical = answer.copy(historySegments = listOf(
            MessageHistorySegment(answer.id, Message.ASSISTANT_ROLE, text("authoritative first ")),
            MessageHistorySegment(supplement.id, Message.USER_ROLE, supplement.blocks),
            MessageHistorySegment(answer.id, Message.ASSISTANT_ROLE, text("authoritative last "))
        ))
        val history = listOf(user, physical) + if (standalone) listOf(supplement) else emptyList()
        val videoRow = if (standalone) supplement else physical
        insertHistoricalRows(history, videoRow, supplement.id)
        val original = prefix + history + turn(owner, "tail-one") + turn(owner, "tail-two")
        assertEquals(original, service.displayHistory(owner))
        assertEquals(inputs(videoRow, supplement.id), sidecar(videoRow))
        assertTrue(MessageHistory.expand(original).any { it.id == supplement.id })
        assertEquals(standalone, original.any { it.id == supplement.id })
        // The video-bearing row is after the initial user (and, for standalone, its owner).
        // Taking every physical row before it would split a run/owner. Retreat to prefix.
        val naiveCut = original.indexOf(videoRow)
        assertFalse(isCompleteContextPrefix(original, naiveCut))
        var calls = 0
        val archive = service.compress(owner, CompressionPolicy(keepTurns = 1), ContextSummarizer { input ->
            calls++
            assertEquals(MessageHistory.expand(prefix), input.originals)
            assertEquals(ContextRevision.digest(prefix), input.revision)
            "only earlier complete run"
        })!!
        assertEquals(1, calls)
        assertArchived(owner, original, prefix.size, archive)
        reopen()
        assertArchived(owner, original, prefix.size, archive)
        assertEquals(inputs(videoRow, supplement.id), sidecar(videoRow))
    }

    fun testAssistantSidecarForSegmentOnlyUserKeepsWholeRun() = assertHistoricalVideoBoundary(false)

    fun testStandaloneVideoSupplementKeepsAssistantOwnerAndWholeRun() = assertHistoricalVideoBoundary(true)

    fun testOldActiveArchiveCoveringVideoFallsBackToOriginals() {
        val owner = db.create("default").id
        val original = turn(owner, "first") + turn(owner, "second") + turn(owner, "tail")
        val archive = service.compress(owner, CompressionPolicy(keepTurns = 1),
            ContextSummarizer { "previous text summary" })!!
        assertArchived(owner, original, 4, archive)
        // Simulate a persisted pre-video archive: its text revision still matches exactly.
        addSidecar(original.first())
        val before = databaseState()
        val archives = archiveRows(owner)
        assertEquals("1", archives.single().last())
        assertRaw(owner, original)
        assertEquals(archives, archiveRows(owner)) // Fallback is read-only, not archive deletion.
        assertEquals(before, databaseState())
        reopen()
        assertRaw(owner, original)
        assertEquals(inputs(original.first()), sidecar(original.first()))
        assertEquals(before, databaseState())
    }

    private fun assertBadSidecarsRejected(withArchive: Boolean) {
        for (wrongOwner in listOf(false, true)) {
            val owner = db.create("default").id
            val original = turn(owner, "first") + turn(owner, "second") + turn(owner, "tail")
            if (withArchive) {
                val archive = service.compress(owner, CompressionPolicy(keepTurns = 1),
                    ContextSummarizer { "valid earlier text summary" })!!
                assertArchived(owner, original, 4, archive)
            }
            val target = original[4] // A retained tail row, not an archived-prefix row.
            addSidecar(target)
            val foreign = target.copy(conversationId = ConversationId("wrong-${owner.value}"))
            val badPayload = if (wrongOwner) MessageVideoInputCodec.encode(foreign, inputs(foreign)) else "{"
            assertEquals(1, db.writableDatabase.update("message_video_inputs", ContentValues().apply {
                put("payload", badPayload)
            }, "message_id=?", arrayOf(target.id.value)))
            val expected = if (wrongOwner) MessageVideoCodecFailure.OWNER_MISMATCH else MessageVideoCodecFailure.INVALID_JSON
            val before = databaseState()
            var calls = 0
            repeat(2) { pass ->
                if (pass == 1) reopen()
                val displayError = reject { service.displayHistory(owner) }
                assertTrue(displayError is MessageVideoCodecException)
                assertEquals(expected, (displayError as MessageVideoCodecException).failure)
                val sendError = reject {
                    service.prepareSend(owner, triggeringPolicy(original), ContextSummarizer {
                        calls++; "must not summarize invalid metadata"
                    })
                }
                assertTrue(sendError is MessageVideoCodecException)
                assertEquals(expected, (sendError as MessageVideoCodecException).failure)
                assertEquals(0, calls)
                assertEquals(before, databaseState())
            }
        }
    }

    fun testCorruptAndWrongOwnerSidecarsRejectWithoutArchive() = assertBadSidecarsRejected(false)

    fun testCorruptAndWrongOwnerTailSidecarsRejectEvenWithActiveArchive() = assertBadSidecarsRejected(true)

    fun testBranchAndReviseWithVideoLeaveEveryDatabaseTableUnchanged() {
        val owner = db.create("default").id
        val original = turn(owner, "first") + turn(owner, "second") +
            turn(owner, "video", video = true) + turn(owner, "tail")
        val archive = service.compress(owner, CompressionPolicy(keepTurns = 2),
            ContextSummarizer { "earlier text summary" })!!
        db.draft(owner, "keep this draft")
        val before = databaseState()
        // Even choosing an earlier non-video user must not bypass conversation-wide protection.
        assertTrue(reject { service.branch(owner, original.first().id) } is IllegalStateException)
        assertEquals(before, databaseState())
        assertTrue(reject { service.revise(owner, original.first().id, "must not replace text") } is IllegalStateException)
        assertEquals(before, databaseState())
        assertTrue(reject { service.revise(owner, original.first().id) } is IllegalStateException)
        assertEquals(before, databaseState())
        reopen()
        assertEquals(before, databaseState())
        assertArchived(owner, original, 4, archive)
        assertEquals(inputs(original[4]), sidecar(original[4]))
    }

    fun testSidecarAddedDuringSummaryRejectsPublicationAndKeepsPreviousArchiveActive() {
        val owner = db.create("default").id
        val original = turn(owner, "first") + turn(owner, "second") +
            turn(owner, "new-prefix") + turn(owner, "tail")
        val oldArchive = service.compress(owner, CompressionPolicy(keepTurns = 2),
            ContextSummarizer { "previous valid summary" })!!
        val archives = archiveRows(owner)
        val before = databaseState()
        var afterSidecar: Map<String, List<List<String?>>>? = null
        var calls = 0
        val failure = reject {
            service.compress(owner, CompressionPolicy(keepTurns = 1), ContextSummarizer { input ->
                calls++
                assertEquals(original.take(6), input.originals)
                assertEquals(ContextRevision.digest(original.take(6)), input.revision)
                // Sidecars are outside the text/history digest. Insert while the model is outside
                // the transaction; only the commit-time video recheck can catch this race.
                addSidecar(original[4])
                assertEquals(ContextRevision.digest(original), ContextRevision.digest(service.displayHistory(owner)))
                afterSidecar = databaseState()
                "new summary must not be published"
            })
        }
        assertTrue(failure is IllegalArgumentException)
        assertEquals("视频历史已改变；摘要未应用", failure.message)
        assertEquals(1, calls)
        assertNotNull(afterSidecar)
        assertFalse(before == afterSidecar)
        assertEquals(afterSidecar, databaseState())
        assertEquals(archives, archiveRows(owner))
        assertArchived(owner, original, 4, oldArchive)
        reopen()
        assertEquals(afterSidecar, databaseState())
        assertEquals(archives, archiveRows(owner))
        assertArchived(owner, original, 4, oldArchive)
        assertEquals(inputs(original[4]), sidecar(original[4]))
    }
}
