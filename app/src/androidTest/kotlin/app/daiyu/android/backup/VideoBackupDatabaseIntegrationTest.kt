package app.daiyu.android.backup

import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.database.Cursor
import android.test.InstrumentationTestCase
import app.daiyu.android.context.ArchiveCodec
import app.daiyu.android.context.DatabaseTransactions
import app.daiyu.android.context.PrivateBlobs
import app.daiyu.android.domain.*
import app.daiyu.android.domain.context.ContextRevision
import app.daiyu.android.media.MessageImageOwner as ImageOwner
import app.daiyu.android.media.MessageImageReference as ImageReference
import app.daiyu.android.media.video.MessageVideoOwner
import app.daiyu.android.media.video.MessageVideoReference
import app.daiyu.android.model.videos.MessageVideoInput
import app.daiyu.android.storage.ConversationDatabase
import app.daiyu.android.storage.MessageImageReferenceCodec
import app.daiyu.android.storage.SQLiteMessageVideoStore
import app.daiyu.android.testContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Real parent database/complete-backup callbacks and SQLite transactions, unlike the callback
 * doubles in NeutralVideoBackupTest. Only original-asset capabilities are fakes: these byte arrays
 * are NOT playable videos/images, and this is NOT evidence of device codecs or VideoAssetStore.
 * No model, network, clock ordering or thread sleeps are involved.
 */
@Suppress("DEPRECATION")
class VideoBackupDatabaseIntegrationTest : InstrumentationTestCase() {
    private data class Fixture(val context: Context, var db: ConversationDatabase)
    private data class VideoTurn(val conversation: ConversationId, val user: Message,
        val physical: Message, val inputs: List<MessageVideoInput>, val historyInput: MessageVideoInput)
    private data class Source(val fixture: Fixture, val assets: VideoAssets,
        val turns: List<VideoTurn>, val textOnly: ConversationId)
    private val fixtures = mutableListOf<Fixture>()
    private val original = byteArrayOf(8, 7, 6, 5)

    private fun fresh(): Fixture {
        val base = testContext(instrumentation.targetContext, "video-parent-backup-${UUID.randomUUID()}-")
        val files = File(base.cacheDir, "video-parent-backup-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getFilesDir(): File = files }
        return Fixture(context, ConversationDatabase(context)).also { fixtures.add(it) }
    }

    override fun tearDown() {
        try {
            fixtures.forEach {
                it.db.close()
                it.context.deleteDatabase("conversations.db")
                it.context.filesDir.deleteRecursively()
            }
        } finally { super.tearDown() }
    }

    private fun reopen(f: Fixture) {
        f.db.close()
        f.db = ConversationDatabase(f.context)
    }

    /** Immutable-byte publication double; deliberately does not validate a media container. */
    private class VideoAssets : BackupVideoAssets {
        val originals = linkedMapOf<Pair<MessageVideoOwner, String>, Pair<MessageVideoReference, ByteArray>>()
        var restoreCalls = 0
        var beforeRestore: (() -> Unit)? = null
        var failRestore = false
        override fun read(owner: MessageVideoOwner, reference: MessageVideoReference): ByteArray {
            require(owner == reference.owner)
            val saved = originals.getValue(owner to reference.id)
            require(saved.first == reference)
            // Leave byte verification to the production backup service, including duplicate import.
            return saved.second.copyOf()
        }
        override fun restore(owner: MessageVideoOwner, references: List<MessageVideoReference>, contents: List<ByteArray>) {
            restoreCalls++
            beforeRestore?.invoke()
            check(!failRestore) { "injected original restore failure" }
            require(references.size == contents.size && references.all { it.owner == owner })
            references.zip(contents).forEach { (ref, bytes) ->
                require(ref.bytes == bytes.size.toLong() && ref.sha256 == PrivateBlobs.digest(bytes))
                val old = originals[owner to ref.id]
                require(old == null || old.first == ref && old.second.contentEquals(bytes))
            }
            references.zip(contents).forEach { (ref, bytes) -> originals[owner to ref.id] = ref to bytes.copyOf() }
        }
    }

    // These are the production ChatController callback bindings, not a saved pre-video text
    // snapshot and not a test implementation of physical-sidecar restore.
    private fun service(f: Fixture, assets: VideoAssets, images: BackupImageAssets? = null) =
        NeutralBackupService(f.context, f.db, images, BackupVideoIntegration(assets,
            exportText = { sql, scope -> f.db.exportBackupForVideoAssets(sql, scope, assets) },
            restoreSidecars = { sql, sidecars -> f.db.restoreVideoBackupSidecars(sql, sidecars) }))

    private fun textTurn(f: Fixture, owner: ConversationId, id: String, block: MessageBlock = MessageBlock.Text("t", "question")) {
        val user = Message(MessageId(id), owner, RunId("run-$id"), Message.USER_ROLE, listOf(block), RunStatus.COMPLETED)
        val assistant = user.copy(id = MessageId("answer-$id"), role = Message.ASSISTANT_ROLE,
            blocks = listOf(MessageBlock.Text("t", "answer")), status = RunStatus.RUNNING)
        f.db.send(user, assistant)
        f.db.save(assistant.copy(status = RunStatus.COMPLETED))
    }

    private fun reference(owner: MessageVideoOwner) = MessageVideoReference(owner, UUID.randomUUID().toString(),
        "video/mp4", original.size.toLong(), PrivateBlobs.digest(original), 1000, 32, 24)

    private fun videoTurn(f: Fixture, assets: VideoAssets, id: String): VideoTurn {
        val conversation = f.db.create("default").id
        val user = Message(MessageId("user-$id"), conversation, RunId("run-$id"), Message.USER_ROLE,
            listOf(MessageBlock.Text("t", "video question")), RunStatus.COMPLETED)
        val userOwner = MessageVideoOwner(conversation.value, user.id.value)
        // Two references prove that per-owner reference order survives, even with deduplicated bytes.
        val inputs = listOf(MessageVideoInput(userOwner, listOf(reference(userOwner), reference(userOwner))))
        val answerId = MessageId("answer-$id")
        val historyId = MessageId("history-only-$id")
        val physical = user.copy(id = answerId, role = Message.ASSISTANT_ROLE,
            blocks = listOf(MessageBlock.Text("t", "legacy projection")),
            historySegments = listOf(
                MessageHistorySegment(answerId, Message.ASSISTANT_ROLE, listOf(MessageBlock.Text("t", "first answer"))),
                MessageHistorySegment(historyId, Message.USER_ROLE, listOf(MessageBlock.Text("t", "video supplement"))),
                MessageHistorySegment(answerId, Message.ASSISTANT_ROLE, listOf(MessageBlock.Text("t", "last answer")))))
        f.db.send(user, physical.copy(status = RunStatus.RUNNING), videoInputs = inputs)
        f.db.save(physical)
        val historyOwner = MessageVideoOwner(conversation.value, historyId.value)
        val historyInput = MessageVideoInput(historyOwner, listOf(reference(historyOwner)))
        // As in ContextVideoBoundaryTest: a segment-only user's sidecar belongs to its physical
        // assistant row. The production backup restore must resolve this row, not call send().
        DatabaseTransactions { f.db.writableDatabase }.run { sql ->
            // Build a pre-v13 segment-only fixture, then use the production metadata writer.
            // Runtime code never discards or replaces an existing durable witness.
            sql.execSQL("DELETE FROM message_video_witnesses WHERE message_id=?", arrayOf(physical.id.value))
            SQLiteMessageVideoStore.insert(sql, physical, listOf(historyInput))
        }
        (inputs + historyInput).forEach { input ->
            assets.restore(input.owner, input.references, input.references.map { original })
        }
        return VideoTurn(conversation, user, physical, inputs, historyInput)
    }

    private fun source(): Source {
        val f = fresh()
        val assets = VideoAssets()
        val turns = listOf(videoTurn(f, assets, "one"), videoTurn(f, assets, "two"))
        val textOnly = f.db.create("default").id
        textTurn(f, textOnly, "text-only")
        service(f, assets).apply {
            attach("attachment", turns.first().user.id.value, "application/octet-stream", byteArrayOf(3, 2, 1))
            setNonSecretConfiguration(mapOf("model" to "backup-model"))
        }
        return Source(f, assets, turns, textOnly)
    }

    private fun reject(block: () -> Unit) {
        try { block() } catch (_: Exception) { return }
        fail("Expected fail-closed rejection") // AssertionFailedError must not be swallowed.
    }
    private fun payload(text: String) = JSONObject(JSONObject(text).getString("payload"))
    private fun textPayload(text: String) = payload(payload(text).getString("text_backup"))
    private fun sidecars(f: Fixture): Map<String, String> = f.db.readableDatabase.rawQuery(
        "SELECT message_id,payload FROM message_video_inputs ORDER BY message_id", null).use { c ->
        buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) }
    }
    private fun count(f: Fixture, table: String) = f.db.readableDatabase.rawQuery("SELECT count(*) FROM $table", null).use {
        it.moveToFirst(); it.getInt(0)
    }

    /** All persisted tables, including sqlite_sequence, without hard-coding the DB version. */
    private fun state(f: Fixture): Map<String, List<List<String?>>> {
        val tables = f.db.readableDatabase.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        return tables.associateWith { table ->
            val quoted = table.replace("\"", "\"\"")
            f.db.readableDatabase.rawQuery("SELECT * FROM \"$quoted\"", null).use { c ->
                buildList {
                    while (c.moveToNext()) add((0 until c.columnCount).map { column ->
                        when (c.getType(column)) {
                            Cursor.FIELD_TYPE_NULL -> null
                            Cursor.FIELD_TYPE_BLOB -> "blob:" + c.getBlob(column).joinToString("") { "%02x".format(it.toInt() and 255) }
                            else -> "${c.getType(column)}:${c.getString(column)}"
                        }
                    })
                }.sortedBy { it.toString() }
            }
        }
    }

    fun testOrdinaryTextAndUnwiredCompleteExportsRejectVideoWithoutMutation() {
        val source = source()
        val f = source.fixture
        val before = state(f)
        reject { f.db.exportBackup() }
        source.turns.forEach { turn ->
            reject { f.db.exportConversationBackup(turn.conversation) }
            reject { NeutralBackupService(f.context, f.db).exportConversation(turn.conversation) }
        }
        reject { NeutralBackupService(f.context, f.db).exportPackage() }
        // A text-only conversation is still exportable despite videos elsewhere in the DB.
        f.db.preflightBackup(f.db.exportConversationBackup(source.textOnly))
        assertEquals(before, state(f))
    }

    private fun roundTrip(singleConversation: Boolean) {
        val source = source()
        val from = source.fixture
        val exporter = service(from, source.assets)
        val selected = if (singleConversation) source.turns.take(1) else source.turns
        val before = state(from)
        val text = if (singleConversation) exporter.exportConversation(selected.single().conversation) else exporter.exportPackage()
        assertEquals(before, state(from))
        assertEquals(if (singleConversation) 1 else 3, textPayload(text).getJSONArray("conversations").length())
        assertEquals(if (singleConversation) 2 else 6, textPayload(text).getJSONArray("messages").length())
        assertEquals(selected.size * 2, payload(text).getJSONObject("message_videos").getJSONArray("sidecars").length())
        assertEquals(selected.size * 3 + 1, exporter.preflight(text).attachments) // plus generic attachment
        assertEquals(2, payload(text).getJSONArray("blobs").length()) // deduplicated video + attachment
        assertEquals(singleConversation, payload(text).getJSONObject("config").length() == 0)
        val to = fresh()
        val assets = VideoAssets()
        var importer = service(to, assets)
        assertFalse(importer.importPackage(text).alreadyImported)
        val expectedSidecars = sidecars(from).filterKeys { id -> selected.any { id == it.user.id.value || id == it.physical.id.value } }
        assertEquals(expectedSidecars, sidecars(to))
        selected.forEach { turn ->
            val rows = to.db.messages(turn.conversation).map { it.second }
            assertEquals(listOf(turn.user, turn.physical), rows)
            assertFalse(rows.any { it.id.value == turn.historyInput.owner.messageId })
            assertTrue(MessageHistory.expand(rows).any { it.id.value == turn.historyInput.owner.messageId })
            // MessageVideoInput has identity equality: compare owners and ordered references.
            assertEquals(turn.inputs.map { it.owner to it.references },
                SQLiteMessageVideoStore.read(to.db.readableDatabase, rows.first()).map { it.owner to it.references })
            assertEquals(listOf(turn.historyInput.owner to turn.historyInput.references),
                SQLiteMessageVideoStore.read(to.db.readableDatabase, rows.last()).map { it.owner to it.references })
            (turn.inputs + turn.historyInput).flatMap { it.references }.forEach { ref ->
                assertTrue(original.contentEquals(assets.read(ref.owner, ref)))
            }
        }
        if (singleConversation) {
            assertEquals(0, to.db.messages(source.turns.last().conversation).size)
            assertEquals(0, to.db.messages(source.textOnly).size)
        }
        assertTrue(byteArrayOf(3, 2, 1).contentEquals(importer.attachment("attachment")))
        assertEquals(1, count(to, "neutral_imports"))
        val committed = state(to)
        val calls = assets.restoreCalls
        assertTrue(importer.importPackage(text).alreadyImported)
        assertEquals(calls, assets.restoreCalls)
        assertEquals(committed, state(to))
        reopen(to)
        importer = service(to, assets)
        assertEquals(committed, state(to))
        assertTrue(importer.importPackage(text).alreadyImported)
        assertEquals(text, if (singleConversation) importer.exportConversation(selected.single().conversation) else importer.exportPackage())
    }

    fun testFullExportImportRoundTripUsesPhysicalHistorySidecars() = roundTrip(false)
    fun testSingleConversationExportImportRoundTripExcludesOtherConversations() = roundTrip(true)

    private fun rollbackAtOriginalPublication(lateMarkerFailure: Boolean) {
        val source = source()
        val text = service(source.fixture, source.assets).exportPackage()
        val to = fresh()
        val existing = to.db.create("default").id
        textTurn(to, existing, "existing")
        val assets = VideoAssets()
        // Existing originals must never be erased by either rollback path.
        val preexisting = source.turns.first().inputs.single()
        assets.restore(preexisting.owner, preexisting.references, preexisting.references.map { original })
        assets.restoreCalls = 0
        val importer = service(to, assets)
        importer.setNonSecretConfiguration(mapOf("battery" to true))
        val before = state(to)
        var sawUncommittedRows = false
        assets.beforeRestore = {
            assertTrue(to.db.writableDatabase.inTransaction())
            assertEquals(8, count(to, "messages")) // two old + six imported physical rows
            assertEquals(4, count(to, "message_video_inputs"))
            assertEquals(sidecars(source.fixture), sidecars(to))
            assertEquals(1, count(to, "neutral_assets"))
            assertEquals("backup-model", importer.importedConfiguration().getString("model"))
            assertEquals(0, count(to, "neutral_imports"))
            sawUncommittedRows = true
        }
        if (lateMarkerFailure) {
            to.db.writableDatabase.execSQL("""CREATE TRIGGER reject_video_backup_marker BEFORE INSERT ON neutral_imports
                BEGIN SELECT RAISE(ABORT,'injected final marker failure'); END""")
        } else assets.failRestore = true
        reject { importer.importPackage(text) }
        assertTrue(sawUncommittedRows)
        assertEquals(if (lateMarkerFailure) 4 else 1, assets.restoreCalls)
        assertEquals(before, state(to))
        preexisting.references.forEach { assertTrue(original.contentEquals(assets.read(it.owner, it))) }
        if (lateMarkerFailure) {
            // SQL rollback may leave immutable orphans. This is allowed; never assert file rollback.
            source.turns.flatMap { it.inputs + it.historyInput }.flatMap { it.references }.forEach {
                assertTrue(original.contentEquals(assets.read(it.owner, it)))
            }
        }
        reopen(to)
        assertEquals(before, state(to))
        if (lateMarkerFailure) to.db.writableDatabase.execSQL("DROP TRIGGER reject_video_backup_marker")
        assets.failRestore = false
        assets.beforeRestore = null
        assertFalse(service(to, assets).importPackage(text).alreadyImported)
        assertEquals(sidecars(source.fixture), sidecars(to))
        assertEquals(1, count(to, "neutral_imports"))
    }

    fun testOriginalRestoreFailureRollsBackAllSqlAndKeepsExistingOriginals() = rollbackAtOriginalPublication(false)
    fun testFinalImportMarkerTriggerFailureRollsBackAllSqlAfterOriginalPublication() = rollbackAtOriginalPublication(true)

    fun testDuplicateMarkerDoesNotHideMissingOrCorruptSidecarOrOriginal() {
        val source = source()
        val text = service(source.fixture, source.assets).exportPackage()
        val to = fresh()
        val assets = VideoAssets()
        var importer = service(to, assets)
        assertFalse(importer.importPackage(text).alreadyImported)
        reopen(to)
        importer = service(to, assets)
        assertTrue(importer.importPackage(text).alreadyImported)
        val expected = state(to)
        val calls = assets.restoreCalls
        val physicalId = source.turns.first().physical.id.value
        val savedPayload = sidecars(to).getValue(physicalId)
        fun rejectedWithoutRepair() {
            val damaged = state(to)
            reject { importer.importPackage(text) }
            assertEquals(damaged, state(to))
            assertEquals(1, count(to, "neutral_imports"))
            assertEquals(calls, assets.restoreCalls)
        }
        assertEquals(1, to.db.writableDatabase.update("message_video_inputs", ContentValues().apply { put("payload", "{}") },
            "message_id=?", arrayOf(physicalId)))
        rejectedWithoutRepair()
        assertEquals(1, to.db.writableDatabase.delete("message_video_inputs", "message_id=?", arrayOf(physicalId)))
        rejectedWithoutRepair()
        to.db.writableDatabase.insertOrThrow("message_video_inputs", null, ContentValues().apply {
            put("message_id", physicalId); put("payload", savedPayload)
        })
        val ref = source.turns.first().historyInput.references.single()
        val key = ref.owner to ref.id
        val saved = assets.originals.remove(key)!!
        rejectedWithoutRepair()
        assets.originals[key] = ref to ByteArray(original.size) // same length, wrong digest
        rejectedWithoutRepair()
        assets.originals[key] = saved
        assertEquals(expected, state(to))
        assertTrue(importer.importPackage(text).alreadyImported)
        assertEquals(calls, assets.restoreCalls)
    }

    /** Populate every legacy extension table with valid rows, using the same archive codec as
     * NeutralImageBackupTest. Kept separate so the video-only inventory assertions stay focused.
     */
    private fun addContextRows(source: Source) {
        val f = source.fixture
        val rows = f.db.messages(source.textOnly).map { it.second }
        val hash = PrivateBlobs(f.context).put(ArchiveCodec.encode(rows))
        val revision = ContextRevision.digest(rows)
        DatabaseTransactions { f.db.writableDatabase }.run { sql ->
            sql.insertOrThrow("context_archives", null, ContentValues().apply {
                put("id", "receipt-archive"); put("conversation_id", source.textOnly.value)
                put("prefix_count", rows.size); put("revision", revision); put("summary", "saved summary")
                put("blob_hash", hash); put("created", 1); put("active", 0)
            })
            sql.insertOrThrow("context_branches", null, ContentValues().apply {
                put("conversation_id", source.turns.first().conversation.value)
                put("parent_id", source.textOnly.value); put("through_message", rows.last().id.value)
                put("parent_revision", revision)
            })
            sql.insertOrThrow("context_usage", null, ContentValues().apply {
                put("request_id", "receipt-usage"); put("run_id", source.turns.first().user.runId.value)
                put("branch_id", source.turns.first().conversation.value)
                put("input_tokens", 11); put("output_tokens", 7)
                putNull("cached_tokens"); putNull("reasoning_tokens")
            })
        }
    }

    /** SQLite RAISE(IGNORE) makes insertOrThrow return -1 without throwing. Successful package
     * parsing and successful ordinary round trips above are controls: each missing receipt here
     * must fail closed, not be mistaken for a completed import merely because SQLite did not throw.
     */
    private fun ignoredRestoreInserts(tables: List<String>) {
        val source = source()
        addContextRows(source)
        val text = service(source.fixture, source.assets).exportPackage()
        tables.forEach { table ->
            val to = fresh()
            val existing = to.db.create("default").id
            textTurn(to, existing, "existing")
            val assets = VideoAssets()
            val preexisting = source.turns.first().inputs.single()
            assets.restore(preexisting.owner, preexisting.references, preexisting.references.map { original })
            assets.restoreCalls = 0
            val importer = service(to, assets)
            importer.setNonSecretConfiguration(mapOf("battery" to true))
            val before = state(to)
            // Installed after seeding, so the trigger can only ignore an import's insert.
            to.db.writableDatabase.execSQL("""CREATE TRIGGER ignore_restore_insert BEFORE INSERT ON $table
                BEGIN SELECT RAISE(IGNORE); END""")
            reject { importer.importPackage(text) }
            assertEquals("Ignored $table insert must roll back every SQL table", before, state(to))
            assertEquals(0, count(to, "neutral_imports"))
            preexisting.references.forEach { assertTrue(original.contentEquals(assets.read(it.owner, it))) }
            if (table == "neutral_imports") {
                // This is the last insert, after all real parent sidecar callbacks and originals.
                assertEquals(4, assets.restoreCalls)
                source.turns.flatMap { it.inputs + it.historyInput }.flatMap { it.references }.forEach {
                    assertTrue(original.contentEquals(assets.read(it.owner, it)))
                }
            }
            reopen(to)
            assertEquals(before, state(to))
            to.db.writableDatabase.execSQL("DROP TRIGGER ignore_restore_insert")
            assertFalse(service(to, assets).importPackage(text).alreadyImported)
            assertEquals(sidecars(source.fixture), sidecars(to))
            assertEquals(1, count(to, "neutral_imports"))
        }
    }

    fun testIgnoredConversationAndMessageRestoreInsertsCannotProduceSuccessfulReceipt() =
        ignoredRestoreInserts(listOf("conversations", "messages"))

    fun testIgnoredAttachmentAndConfigurationRestoreInsertsRollBackAllSql() =
        ignoredRestoreInserts(listOf("neutral_assets", "neutral_config"))

    fun testIgnoredBranchArchiveAndUsageRestoreInsertsRollBackAllSql() =
        ignoredRestoreInserts(listOf("context_branches", "context_archives", "context_usage"))

    fun testIgnoredVideoSidecarRestoreInsertRollsBackAllSql() =
        ignoredRestoreInserts(listOf("message_video_inputs"))

    fun testIgnoredFinalImportMarkerInsertRollsBackAfterOriginalPublication() =
        ignoredRestoreInserts(listOf("neutral_imports"))

    fun testFinalMarkerTriggerCannotHideDeletedOrCorruptPhysicalHistorySidecar() {
        val source = source()
        val text = service(source.fixture, source.assets).exportPackage()
        val physicalId = source.turns.first().physical.id.value
        // IDs are fixed local fixtures, not external strings. Mutate specifically the sidecar
        // bound to an assistant's physical row rather than the ordinary user sidecar.
        val edits = listOf(
            "DELETE FROM message_video_inputs WHERE message_id='$physicalId'",
            "UPDATE message_video_inputs SET payload='{}' WHERE message_id='$physicalId'",
            "UPDATE messages SET history_segments='[]' WHERE id='$physicalId'")
        edits.forEach { edit ->
            val to = fresh()
            val existing = to.db.create("default").id
            textTurn(to, existing, "existing")
            val assets = VideoAssets()
            val before = state(to)
            to.db.writableDatabase.execSQL("""CREATE TRIGGER alter_sidecar_after_marker AFTER INSERT ON neutral_imports
                BEGIN $edit; END""")
            reject { service(to, assets).importPackage(text) }
            assertEquals(4, assets.restoreCalls) // Trigger runs strictly after original publication.
            assertEquals(before, state(to)) // Includes the newly inserted marker and all sidecars.
            source.turns.flatMap { it.inputs + it.historyInput }.flatMap { it.references }.forEach {
                assertTrue(original.contentEquals(assets.read(it.owner, it))) // harmless immutable orphans
            }
            reopen(to)
            assertEquals(before, state(to))
            to.db.writableDatabase.execSQL("DROP TRIGGER alter_sidecar_after_marker")
            assertFalse(service(to, assets).importPackage(text).alreadyImported)
            assertEquals(sidecars(source.fixture), sidecars(to))
            assertEquals(1, count(to, "neutral_imports"))
        }
    }

    private class ImageAssets : BackupImageAssets {
        val originals = linkedMapOf<Pair<ImageOwner, String>, Pair<ImageReference, ByteArray>>()
        override fun read(owner: ImageOwner, reference: ImageReference): ByteArray {
            val saved = originals.getValue(owner to reference.id)
            require(reference.owner == owner && reference == saved.first)
            return saved.second.copyOf()
        }
        override fun restore(owner: ImageOwner, references: List<ImageReference>, contents: List<ByteArray>) {
            require(references.size == contents.size && references.all { it.owner == owner })
            references.zip(contents).forEach { (ref, bytes) ->
                val old = originals[owner to ref.id]
                require(old == null || old.first == ref && old.second.contentEquals(bytes))
                originals[owner to ref.id] = ref to bytes.copyOf()
            }
        }
    }

    private fun legacyPackageRoundTrip(withImage: Boolean) {
        val from = fresh()
        val conversation = from.db.create("default").id
        val images = ImageAssets()
        val bytes = byteArrayOf(1, 3, 5, 7)
        val ref = ImageReference(ImageOwner(conversation.value, "legacy"), UUID.randomUUID().toString(),
            "image/png", bytes.size, PrivateBlobs.digest(bytes), 4, 3)
        val block = if (withImage) {
            images.restore(ref.owner, listOf(ref), listOf(bytes))
            MessageBlock.Image(MessageImageReferenceCodec.fromAsset(ref))
        } else MessageBlock.Text("t", "old text package")
        textTurn(from, conversation, "legacy", block)
        // Build the old package through the pre-existing no-video entry point.
        val legacy = NeutralBackupService(from.context, from.db, images).exportPackage()
        assertFalse(payload(legacy).has("message_videos"))
        assertEquals(withImage, payload(legacy).has("message_images"))
        assertEquals(legacy, service(from, VideoAssets(), images).exportPackage())
        val to = fresh()
        val restoredImages = ImageAssets()
        val videoAssets = VideoAssets()
        val importer = service(to, videoAssets, restoredImages)
        assertFalse(importer.importPackage(legacy).alreadyImported)
        assertEquals(from.db.exportBackup(), to.db.exportBackup())
        assertEquals(legacy, importer.exportPackage())
        assertEquals(0, count(to, "message_video_inputs"))
        assertEquals(0, videoAssets.restoreCalls)
        assertTrue(videoAssets.originals.isEmpty())
        if (withImage) assertTrue(bytes.contentEquals(restoredImages.read(ref.owner, ref)))
        assertTrue(importer.importPackage(legacy).alreadyImported)
        // It must remain importable by the old entry point too.
        val oldDestination = fresh()
        NeutralBackupService(oldDestination.context, oldDestination.db, ImageAssets()).importPackage(legacy)
        assertEquals(from.db.exportBackup(), oldDestination.db.exportBackup())
    }

    fun testExistingNoVideoTextPackagesRemainUnchanged() = legacyPackageRoundTrip(false)
    fun testExistingImageOnlyPackagesRemainUnchanged() = legacyPackageRoundTrip(true)
}
