package app.daiyu.android

import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.test.InstrumentationTestCase
import app.daiyu.android.backup.BackupVideoAssets
import app.daiyu.android.backup.BackupVideoIntegration
import app.daiyu.android.backup.NeutralBackupService
import app.daiyu.android.context.DatabaseTransactions
import app.daiyu.android.context.PrivateBlobs
import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.ExecutionPhase
import app.daiyu.android.domain.runtime.RunCheckpoint
import app.daiyu.android.media.video.MessageVideoOwner
import app.daiyu.android.media.video.MessageVideoReference
import app.daiyu.android.model.videos.MessageVideoInput
import app.daiyu.android.storage.ConversationDatabase
import app.daiyu.android.storage.MessageVideoInputCodec
import app.daiyu.android.storage.SQLiteRuntimeStore
import java.io.File
import java.util.UUID

/** Real SQLiteOpenHelper upgrades and transactions, not direct calls to onUpgrade.
 * Uses the existing testContext isolation and the metadata/asset-capability fixture patterns in
 * MessageVideoDatabaseTest, SQLiteSupplementAdmissionDatabaseTest and NeutralVideoBackupTest.
 * Video bytes below are deliberately fake: these tests prove DB/backup evidence, not decoding,
 * provider capability, model transport, or acceptance of a playable video.
 */
@Suppress("DEPRECATION")
class ControllerVideoWitnessDatabaseTest : InstrumentationTestCase() {
    private class Fixture(val context: Context) {
        var db = ConversationDatabase(context)
        var runtime: SQLiteRuntimeStore? = null
        fun reopen() {
            check(runtime == null)
            db.close()
            db = ConversationDatabase(context)
        }
    }
    private data class Turn(val user: Message, val assistant: Message, val inputs: List<MessageVideoInput>)
    private val fixtures = mutableListOf<Fixture>()
    private val bytes = byteArrayOf(8, 7, 6, 5)
    private val witnessTable = "message_video_witnesses"

    private fun fresh(): Fixture {
        val token = UUID.randomUUID().toString()
        val base = testContext(instrumentation.targetContext, "video-witness-$token-")
        val directory = File(base.cacheDir, "video-witness-$token").apply { check(mkdirs()) }
        return Fixture(object : ContextWrapper(base) {
            override fun getFilesDir(): File = directory
        }).also { fixtures.add(it) }
    }

    override fun tearDown() {
        try {
            fixtures.forEach { f ->
                f.runtime?.close()
                f.db.close()
                f.context.deleteDatabase("conversations.db")
                File(f.context.getDatabasePath("conversations.db").canonicalPath + ".runtime-owner").delete()
                f.context.filesDir.deleteRecursively()
                f.context.getSharedPreferences("daiyu-assistants", 0).edit().clear().commit()
            }
        } finally { super.tearDown() }
    }

    private fun pair(conversation: ConversationId, video: Boolean = false): Turn {
        val token = UUID.randomUUID().toString()
        val user = Message(MessageId("user-$token"), conversation, RunId("run-$token"), Message.USER_ROLE,
            listOf(MessageBlock.Text("question", "retained question")), RunStatus.COMPLETED)
        val assistant = user.copy(id = MessageId("assistant-$token"), role = Message.ASSISTANT_ROLE,
            blocks = emptyList(), status = RunStatus.RUNNING)
        val owner = MessageVideoOwner(conversation.value, user.id.value)
        val inputs = if (!video) emptyList() else listOf(MessageVideoInput(owner, listOf(
            MessageVideoReference(owner, UUID.randomUUID().toString(), "video/mp4", bytes.size.toLong(),
                PrivateBlobs.digest(bytes), 1000L, 32, 24))))
        return Turn(user, assistant, inputs)
    }

    private fun seed(f: Fixture, video: Boolean = false): Turn {
        val turn = pair(f.db.create(f.db.selectedAssistantId()).id, video)
        f.db.send(turn.user, turn.assistant, videoInputs = turn.inputs)
        val completed = turn.assistant.copy(status = RunStatus.COMPLETED)
        f.db.save(completed)
        f.db.draft(turn.user.conversationId, "conversation draft")
        f.db.draft("composer draft")
        return turn.copy(assistant = completed)
    }

    private fun rejects(action: () -> Unit): Exception {
        try { action() } catch (e: Exception) { return e }
        // Do not catch AssertionFailedError: an accepted operation must fail the test.
        throw AssertionError("Expected fail-closed database rejection")
    }

    private fun tableExists(sql: SQLiteDatabase, name: String): Boolean = sql.rawQuery(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", arrayOf(name)).use { it.moveToFirst() }

    private fun table(sql: SQLiteDatabase, name: String): List<List<String?>> {
        // sqlite_master is queryable but, unlike ordinary tables, does not list itself.
        if (name != "sqlite_master" && !tableExists(sql, name)) return listOf(listOf("<absent>"))
        return sql.rawQuery("SELECT * FROM $name ORDER BY rowid", null).use { c ->
            buildList {
                while (c.moveToNext()) add((0 until c.columnCount).map { column ->
                    when (c.getType(column)) {
                        Cursor.FIELD_TYPE_NULL -> null
                        Cursor.FIELD_TYPE_BLOB -> "blob:" + PrivateBlobs.digest(c.getBlob(column))
                        else -> "${c.getType(column)}:${c.getString(column)}"
                    }
                })
            }
        }
    }

    private fun snapshot(sql: SQLiteDatabase): Map<String, List<List<String?>>> = listOf(
        "conversations", "composer", "messages", "message_video_inputs", witnessTable,
        "runtime_checkpoints", "generation_attempts", "generation_outputs", "sqlite_sequence",
        "neutral_assets", "neutral_config", "neutral_imports"
    ).associateWith { table(sql, it) }

    private fun assertWitness(f: Fixture, row: Message, inputs: List<MessageVideoInput>) {
        f.db.readableDatabase.rawQuery(
            "SELECT typeof(reference_count),reference_count,typeof(manifest_sha256),manifest_sha256 FROM $witnessTable WHERE message_id=?",
            arrayOf(row.id.value)).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("integer", c.getString(0))
            assertEquals(inputs.sumOf { it.references.size }.toLong(), c.getLong(1))
            assertEquals("text", c.getString(2))
            assertEquals(PrivateBlobs.digest(MessageVideoInputCodec.encode(row, inputs).toByteArray(Charsets.UTF_8)), c.getString(3))
            assertFalse(c.moveToNext())
        }
        DatabaseTransactions { f.db.readableDatabase }.run { sql ->
            ControllerVideoManifestWitness.verify(sql, row, inputs)
        }
    }

    private fun count(f: Fixture, name: String): Int = f.db.readableDatabase.rawQuery(
        "SELECT count(*) FROM $name", null).use { it.moveToFirst(); it.getInt(0) }

    private fun capture(f: Fixture, turn: Turn) = ControllerVideoHistory(f.db).capture(turn.user.conversationId)

    private fun downgrade(f: Fixture, version: Int) {
        // Reconstruct the predecessor schema. v12 had sidecars but no witness table;
        // earlier versions had neither. Reuse the legacy fixture for pre-v10 assistants.
        f.db.writableDatabase.execSQL("DROP TABLE $witnessTable")
        if (version < 12) f.db.writableDatabase.execSQL("DROP TABLE message_video_inputs")
        if (version < 10) removeAssistantSchemaForLegacyFixture(f.db.writableDatabase)
        f.db.writableDatabase.version = version
        f.reopen()
    }

    fun testPreVideoVersionsNineTenAndElevenUpgradeToKnownZeroWitnesses() {
        for (version in listOf(9, 10, 11)) {
            val f = fresh()
            val turn = seed(f)
            // v9 predates per-conversation bindings. Its preserved binding is explicitly NULL,
            // not a silently assigned global assistant during migration.
            if (version < 10) f.db.writableDatabase.execSQL("UPDATE conversations SET assistant_id=NULL")
            val retained = snapshot(f.db.readableDatabase).filterKeys { it != witnessTable && it != "message_video_inputs" }
            downgrade(f, version)
            assertEquals(25, f.db.readableDatabase.version)
            assertEquals(retained, snapshot(f.db.readableDatabase).filterKeys { it != witnessTable && it != "message_video_inputs" })
            assertEquals(0, count(f, "message_video_inputs"))
            assertEquals(2, count(f, witnessTable))
            assertWitness(f, turn.user, emptyList())
            assertWitness(f, turn.assistant, emptyList())
            assertEquals(listOf(turn.user, turn.assistant), capture(f, turn).rows)
            val exported = f.db.exportBackup()
            val before = snapshot(f.db.readableDatabase)
            f.reopen()
            assertEquals(exported, f.db.exportBackup())
            assertEquals(before, snapshot(f.db.readableDatabase))
        }
    }

    fun testVersionTwelveAbsentSidecarIsUnknownNotTextButStillListed() {
        val f = fresh()
        val turn = seed(f)
        downgrade(f, 12)
        assertEquals(25, f.db.readableDatabase.version)
        f.db.readableDatabase.rawQuery("SELECT reference_count,manifest_sha256 FROM $witnessTable WHERE message_id=?",
            arrayOf(turn.user.id.value)).use { c ->
            assertTrue(c.moveToFirst()); assertEquals(-1L, c.getLong(0)); assertTrue(c.isNull(1))
        }
        assertWitness(f, turn.assistant, emptyList())
        val before = snapshot(f.db.readableDatabase)
        repeat(2) {
            assertEquals(turn.user.conversationId, f.db.conversations().single().id)
            assertEquals(turn.user.conversationId, f.db.conversations("retained question").single().id)
            assertEquals(listOf(turn.user, turn.assistant), f.db.messages(turn.user.conversationId).map { it.second })
            assertTrue(rejects { capture(f, turn) }.message.orEmpty().contains("video_witness_unknown"))
            rejects { f.db.videoInputs(listOf(turn.user, turn.assistant)) }
            rejects { f.db.exportBackup() }
            rejects { f.db.exportConversationBackup(turn.user.conversationId) }
            rejects { backup(f, Assets()).exportPackage() }
            assertEquals(before, snapshot(f.db.readableDatabase)) // reads must never repair unknown to zero
            f.reopen()
        }
    }

    fun testVersionTwelveValidVideoMigratesOrderedManifestAndZeroAssistant() {
        val f = fresh()
        val turn = seed(f, video = true)
        val retained = snapshot(f.db.readableDatabase).filterKeys { it != witnessTable }
        downgrade(f, 12)
        assertEquals(25, f.db.readableDatabase.version)
        // Check durable evidence BEFORE first history capture: it must have been installed by upgrade.
        assertWitness(f, turn.user, turn.inputs)
        assertWitness(f, turn.assistant, emptyList())
        assertEquals(retained, snapshot(f.db.readableDatabase).filterKeys { it != witnessTable })
        assertEquals(turn.inputs.single().references, capture(f, turn).inputs.single().references)
        val before = snapshot(f.db.readableDatabase)
        f.reopen()
        assertEquals(turn.inputs.single().references, capture(f, turn).inputs.single().references)
        assertEquals(before, snapshot(f.db.readableDatabase))
    }

    fun testSidecarDeletedBeforeFirstReopenedReadCannotBecomeTextOnly() {
        val f = fresh()
        val turn = seed(f, video = true)
        val witnesses = table(f.db.readableDatabase, witnessTable)
        f.db.close()
        // Simulates offline metadata loss before any capture in the new helper/process.
        f.context.openOrCreateDatabase("conversations.db", 0, null).use { raw ->
            assertEquals(1, raw.delete("message_video_inputs", "message_id=?", arrayOf(turn.user.id.value)))
        }
        f.reopen()
        val before = snapshot(f.db.readableDatabase)
        assertEquals(listOf(turn.user, turn.assistant), f.db.messages(turn.user.conversationId).map { it.second })
        assertTrue(rejects { capture(f, turn) }.message.orEmpty().contains("video_witness_mismatch"))
        rejects { f.db.videoInputs(listOf(turn.user)) }
        rejects { f.db.exportBackup() }
        rejects { backup(f, Assets()).exportPackage() }
        assertEquals(witnesses, table(f.db.readableDatabase, witnessTable))
        assertEquals(before, snapshot(f.db.readableDatabase))
    }

    fun testOrdinaryTextSendAndRuntimeSupplementEachPersistKnownZeroWitness() {
        val f = fresh()
        val runtime = SQLiteRuntimeStore.acquire(f.db).also { f.runtime = it; it.recoverPending() }
        val turn = pair(f.db.create(f.db.selectedAssistantId()).id)
        f.db.send(turn.user, turn.assistant)
        assertWitness(f, turn.user, emptyList())
        assertWitness(f, turn.assistant, emptyList())
        f.db.draft(turn.user.conversationId, "supplement must not consume draft")
        f.db.draft("composer remains")
        val first = RunCheckpoint(turn.assistant, listOf(turn.user), ExecutionPhase.PAUSED,
            owner = "witness-runtime-owner", revision = 1)
        runtime.commitCheckpoint(first)
        val extra = turn.user.copy(id = MessageId("extra-${UUID.randomUUID()}"),
            blocks = listOf(MessageBlock.Text("extra", "追加 text")))
        val next = first.copy(revision = 2, supplements = listOf(extra))
        runtime.commitSupplement(next, extra)
        assertEquals(next, runtime.readCommittedCheckpoint(next))
        assertEquals(3, count(f, witnessTable))
        assertEquals(0, count(f, "message_video_inputs"))
        listOf(turn.user, turn.assistant, extra).forEach { assertWitness(f, it, emptyList()) }
        assertEquals(listOf(turn.user, turn.assistant, extra), capture(f, turn).rows)
        assertEquals("supplement must not consume draft", f.db.conversations().single().draft)
        assertEquals("composer remains", f.db.draft())
        val before = snapshot(f.db.readableDatabase)
        runtime.close(); f.runtime = null
        f.reopen() // opening alone must not rewrite the checkpoint or witnesses
        assertEquals(before, snapshot(f.db.readableDatabase))
        listOf(turn.user, turn.assistant, extra).forEach { assertWitness(f, it, emptyList()) }
        assertTrue(capture(f, turn).inputs.isEmpty())
    }

    private class Assets : BackupVideoAssets {
        private val originals = mutableMapOf<Pair<MessageVideoOwner, String>, Pair<MessageVideoReference, ByteArray>>()
        override fun read(owner: MessageVideoOwner, reference: MessageVideoReference): ByteArray {
            val saved = requireNotNull(originals[owner to reference.id])
            require(saved.first == reference)
            return saved.second.copyOf()
        }
        override fun restore(owner: MessageVideoOwner, references: List<MessageVideoReference>, contents: List<ByteArray>) {
            require(references.size == contents.size)
            references.zip(contents).forEach { (reference, content) ->
                require(reference.owner == owner)
                val previous = originals[owner to reference.id]
                require(previous == null || previous.first == reference && previous.second.contentEquals(content))
                originals[owner to reference.id] = reference to content.copyOf()
            }
        }
    }

    private fun backup(f: Fixture, assets: Assets) = NeutralBackupService(f.context, f.db, null,
        BackupVideoIntegration(assets,
            exportText = { sql, scope -> f.db.exportBackupForVideoAssets(sql, scope, assets) },
            restoreSidecars = { sql, sidecars -> f.db.restoreVideoBackupSidecars(sql, sidecars) }))

    fun testCompleteVideoBackupRestoresWitnessesForVideoAndTextRowsWithoutBackfill() {
        val source = fresh()
        val turn = seed(source, video = true)
        val sourceAssets = Assets().apply {
            val input = turn.inputs.single()
            restore(input.owner, input.references, listOf(bytes))
        }
        val sourceBefore = snapshot(source.db.readableDatabase)
        val wire = backup(source, sourceAssets).exportPackage()
        val target = fresh()
        val restoredAssets = Assets()
        val service = backup(target, restoredAssets)
        assertEquals(1, service.preflight(wire).attachments)
        assertFalse(service.importPackage(wire).alreadyImported)
        // Inspect SQL directly before the first capture; import, not reading, must write evidence.
        assertEquals(2, count(target, witnessTable))
        assertWitness(target, turn.user, turn.inputs)
        assertWitness(target, turn.assistant, emptyList())
        assertEquals(table(source.db.readableDatabase, witnessTable), table(target.db.readableDatabase, witnessTable))
        assertEquals(table(source.db.readableDatabase, "message_video_inputs"), table(target.db.readableDatabase, "message_video_inputs"))
        val reference = turn.inputs.single().references.single()
        assertTrue(bytes.contentEquals(restoredAssets.read(reference.owner, reference)))
        val restoredBefore = snapshot(target.db.readableDatabase)
        assertTrue(service.importPackage(wire).alreadyImported)
        assertEquals(restoredBefore, snapshot(target.db.readableDatabase))
        assertEquals(sourceBefore, snapshot(source.db.readableDatabase))
        target.reopen()
        assertEquals(turn.inputs.single().references, capture(target, turn).inputs.single().references)
        assertEquals(restoredBefore, snapshot(target.db.readableDatabase))
        assertWitness(target, turn.user, turn.inputs)
    }

    fun testMigrationTriggerFailureRollsBackEarlierWitnessesSchemaVersionAndDrafts() {
        for (version in listOf(11, 12)) {
            val f = fresh()
            val turn = seed(f, video = version == 12)
            val sql = f.db.writableDatabase
            sql.execSQL("DROP TABLE $witnessTable")
            if (version < 12) sql.execSQL("DROP TABLE message_video_inputs")
            // Fault injection only: an empty destination table lets a real SQLite trigger fail
            // the SECOND migrated row. The predecessor retains no already-generated witness.
            ControllerVideoManifestWitness.installSchema(sql)
            sql.execSQL("""CREATE TRIGGER witness_migration_fault BEFORE INSERT ON $witnessTable
                WHEN NEW.message_id='${turn.assistant.id.value}' BEGIN
                UPDATE composer SET draft='trigger changed draft';
                SELECT RAISE(ABORT,'injected second witness failure'); END""")
            sql.version = version
            val before = snapshot(sql)
            val schema = table(sql, "sqlite_master")
            f.reopen()
            val failure = rejects { f.db.writableDatabase }
            assertTrue(failure.message.orEmpty().contains("injected second witness failure"))
            f.db.close()
            f.context.openOrCreateDatabase("conversations.db", 0, null).use { raw ->
                assertEquals(version, raw.version)
                assertEquals(before, snapshot(raw))
                assertEquals(schema, table(raw, "sqlite_master"))
                assertTrue(table(raw, witnessTable).isEmpty()) // first row's insert rolled back too
                raw.execSQL("DROP TRIGGER witness_migration_fault")
            }
            f.reopen()
            assertEquals(25, f.db.readableDatabase.version)
            assertWitness(f, turn.user, turn.inputs)
            assertWitness(f, turn.assistant, emptyList())
            assertEquals("composer draft", f.db.draft())
            assertEquals("conversation draft", f.db.conversations().single().draft)
        }
    }

    fun testVersionThirteenOrphanSidecarRejectsCaptureAndEveryExportWithoutRepair() {
        val f = fresh()
        val turn = seed(f)
        assertEquals(listOf(turn.user, turn.assistant), capture(f, turn).rows)
        assertEquals(0, count(f, "message_video_inputs"))
        f.db.exportBackup() // Positive control: no legitimate video/unknown row can explain rejection.
        val orphan = pair(turn.user.conversationId, video = true)
        val sql = f.db.writableDatabase
        // Model externally corrupted storage; production connections always enable FK checks.
        sql.setForeignKeyConstraintsEnabled(false)
        try {
            sql.insertOrThrow("message_video_inputs", null, ContentValues().apply {
                put("message_id", orphan.user.id.value)
                put("payload", MessageVideoInputCodec.encode(orphan.user, orphan.inputs))
            })
        } finally { sql.setForeignKeyConstraintsEnabled(true) }
        sql.rawQuery("PRAGMA foreign_key_check(message_video_inputs)", null).use { assertTrue(it.moveToFirst()) }
        val before = snapshot(sql)
        repeat(2) {
            assertEquals(25, f.db.readableDatabase.version)
            // No physical row owns this valid payload; silently skipping it is not acceptable.
            rejects { capture(f, turn) }
            rejects { f.db.exportBackup() }
            rejects { f.db.exportConversationBackup(turn.user.conversationId) }
            rejects { backup(f, Assets()).exportPackage() }
            assertEquals(before, snapshot(f.db.readableDatabase))
            assertEquals(listOf(turn.user, turn.assistant), f.db.messages(turn.user.conversationId).map { it.second })
            f.reopen()
        }
    }

    fun testNewAssistantTriggerDeletingOldSidecarRollsBackEntireSendAndDrafts() {
        val f = fresh()
        val old = seed(f, video = true)
        val next = pair(old.user.conversationId)
        val original = capture(f, old)
        f.db.writableDatabase.execSQL("""CREATE TRIGGER witness_send_fault AFTER INSERT ON messages
            WHEN NEW.id='${next.assistant.id.value}' BEGIN
            DELETE FROM message_video_inputs WHERE message_id='${old.user.id.value}'; END""")
        val before = snapshot(f.db.readableDatabase)
        rejects { f.db.send(next.user, next.assistant) }
        // Includes exact old payload/witness, both drafts, title/update metadata, all physical rows,
        // runtime/generation state and sequence counter. No half-pair or Accepted-like return.
        assertEquals(before, snapshot(f.db.readableDatabase))
        assertTrue(original.same(capture(f, old)))
        assertEquals("conversation draft", f.db.conversations().single().draft)
        assertEquals("composer draft", f.db.draft())
        f.reopen()
        assertEquals(before, snapshot(f.db.readableDatabase))
        assertWitness(f, old.user, old.inputs)
        assertTrue(original.same(capture(f, old)))
        f.db.writableDatabase.execSQL("DROP TRIGGER witness_send_fault")
        f.db.send(next.user, next.assistant)
        assertEquals(listOf(old.user, old.assistant, next.user, next.assistant), capture(f, old).rows)
        assertEquals(old.inputs.single().references, capture(f, old).inputs.single().references)
        assertWitness(f, next.user, emptyList())
        assertWitness(f, next.assistant, emptyList())
        assertEquals("", f.db.conversations().single().draft)
        assertEquals("composer draft", f.db.draft())
    }
}
