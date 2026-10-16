package app.daiyu.android

import android.database.sqlite.SQLiteDatabase
import app.daiyu.android.domain.*
import app.daiyu.android.media.video.MessageVideoOwner
import app.daiyu.android.media.video.MessageVideoReference
import app.daiyu.android.model.videos.MessageVideoInput
import app.daiyu.android.storage.MessageVideoInputCodec
import app.daiyu.android.storage.SQLiteMessageVideoStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Small real-SQLite tests, independent of the parent's still-required DB migration hooks. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [26])
class ControllerVideoManifestWitnessTest {
    private lateinit var db: SQLiteDatabase
    private val row = Message(MessageId("user"), ConversationId("conversation"), RunId("run"),
        Message.USER_ROLE, listOf(MessageBlock.Text("user", "body")), RunStatus.COMPLETED)
    private val owner = MessageVideoOwner("conversation", "user")
    private val inputs = listOf(MessageVideoInput(owner, listOf(MessageVideoReference(owner,
        "00000000-0000-0000-0000-000000000001", "video/mp4", 3, "a".repeat(64), 1000, 32, 24))))

    @Before fun setUp() {
        db = SQLiteDatabase.create(null)
        db.setForeignKeyConstraintsEnabled(true)
        db.execSQL("CREATE TABLE messages(id TEXT PRIMARY KEY,conversation_id TEXT NOT NULL)")
        SQLiteMessageVideoStore.installSchema(db)
        ControllerVideoManifestWitness.installSchema(db)
    }
    @After fun tearDown() { db.close() }
    private fun transaction(block: () -> Unit) {
        db.beginTransaction()
        try { block(); db.setTransactionSuccessful() } finally { db.endTransaction() }
    }
    private fun addRow() = db.execSQL("INSERT INTO messages(id,conversation_id) VALUES(?,?)",
        arrayOf(row.id.value, row.conversationId.value))
    private fun putSidecar(values: List<MessageVideoInput> = inputs) {
        db.execSQL("INSERT INTO message_video_inputs(message_id,payload) VALUES(?,?)",
            arrayOf(row.id.value, MessageVideoInputCodec.encode(row, values)))
    }
    private fun fails(block: () -> Unit) = assertNotNull(runCatching(block).exceptionOrNull())
    private fun count(table: String): Long = db.rawQuery("SELECT count(*) FROM $table", null).use {
        it.moveToFirst(); it.getLong(0)
    }

    @Test fun witnessSurvivesSidecarDeletionBeforeAnyReadAndRejectsMissingEvidence() {
        transaction {
            addRow(); putSidecar(); ControllerVideoManifestWitness.insert(db, row, inputs)
        }
        db.delete("message_video_inputs", null, null)
        transaction {
            fails { ControllerVideoManifestWitness.verify(db, row, SQLiteMessageVideoStore.read(db, row)) }
        }
        assertEquals(1L, count(ControllerVideoManifestWitness.TABLE))
        db.delete(ControllerVideoManifestWitness.TABLE, null, null)
        transaction { fails { ControllerVideoManifestWitness.verify(db, row, emptyList()) } }
    }

    @Test fun zeroIsExplicitEvidenceAndCannotBeReplacedByVideoOrLostWitness() {
        transaction {
            addRow(); ControllerVideoManifestWitness.insert(db, row, emptyList())
            ControllerVideoManifestWitness.verify(db, row, emptyList())
            ControllerVideoManifestWitness.insert(db, row, emptyList()) // verified idempotence
            putSidecar()
            fails { ControllerVideoManifestWitness.insert(db, row, inputs) }
            fails { ControllerVideoManifestWitness.verify(db, row, inputs) }
        }
        assertEquals(1L, count(ControllerVideoManifestWitness.TABLE))
    }

    @Test fun exactCanonicalManifestHashDetectsChangedMetadataButNotWhitespace() {
        transaction {
            addRow(); putSidecar(); ControllerVideoManifestWitness.insert(db, row, inputs)
            db.execSQL("UPDATE message_video_inputs SET payload='  ' || payload || '  '")
            ControllerVideoManifestWitness.verify(db, row, SQLiteMessageVideoStore.read(db, row))
            db.execSQL("UPDATE message_video_inputs SET payload=replace(payload,?,?)", arrayOf("a".repeat(64), "b".repeat(64)))
            fails { ControllerVideoManifestWitness.verify(db, row, SQLiteMessageVideoStore.read(db, row)) }
        }
    }

    @Test fun rowsSidecarsAndWitnessRollBackTogetherAndMessageDeletionCascades() {
        db.beginTransaction()
        try { addRow(); putSidecar(); ControllerVideoManifestWitness.insert(db, row, inputs) }
        finally { db.endTransaction() } // Simulate failed acceptance/import: no commit.
        assertEquals(0L, count("messages"))
        assertEquals(0L, count("message_video_inputs"))
        assertEquals(0L, count(ControllerVideoManifestWitness.TABLE))
        transaction { addRow(); putSidecar(); ControllerVideoManifestWitness.insert(db, row, inputs) }
        db.delete("messages", null, null)
        assertEquals(0L, count("message_video_inputs"))
        assertEquals(0L, count(ControllerVideoManifestWitness.TABLE))
    }

    @Test fun v12AbsentSidecarIsQuarantinedNotGuessedTextAndPresentSidecarIsBackfilled() {
        transaction {
            addRow()
            ControllerVideoManifestWitness.migrateLegacy(db, listOf(row))
            fails { ControllerVideoManifestWitness.verify(db, row, emptyList()) }
        }
        db.rawQuery("SELECT reference_count,manifest_sha256 FROM message_video_witnesses", null).use {
            assertTrue(it.moveToFirst()); assertEquals(-1, it.getInt(0)); assertTrue(it.isNull(1))
        }
        db.delete(ControllerVideoManifestWitness.TABLE, null, null)
        transaction {
            putSidecar()
            ControllerVideoManifestWitness.migrateLegacy(db, listOf(row))
            ControllerVideoManifestWitness.verify(db, row, inputs)
        }
    }

    @Test fun legacyPlainAssistantIsProvablyZeroButExpandedUserStillRequiresEvidence() {
        val assistant = row.copy(id = MessageId("assistant"), role = Message.ASSISTANT_ROLE, blocks = emptyList())
        val combined = assistant.copy(id = MessageId("combined"), historySegments = listOf(
            MessageHistorySegment(row.id, Message.USER_ROLE, row.blocks),
            MessageHistorySegment(MessageId("combined"), Message.ASSISTANT_ROLE, emptyList())))
        transaction {
            for (message in listOf(assistant, combined)) {
                db.execSQL("INSERT INTO messages(id,conversation_id) VALUES(?,?)",
                    arrayOf(message.id.value, message.conversationId.value))
            }
            ControllerVideoManifestWitness.migrateLegacy(db, listOf(assistant, combined))
            ControllerVideoManifestWitness.verify(db, assistant, emptyList())
            fails { ControllerVideoManifestWitness.verify(db, combined, emptyList()) }
        }
    }

    @Test fun schemaRejectsInvalidWitnessAndProvenPreVideoTextCanMigrate() {
        transaction {
            addRow()
            fails { db.execSQL("INSERT INTO message_video_witnesses VALUES('user',2,'not-a-hash')") }
            ControllerVideoManifestWitness.migrateLegacy(db, listOf(row), emptyIsProven = true)
            ControllerVideoManifestWitness.verify(db, row, emptyList())
        }
    }
}
