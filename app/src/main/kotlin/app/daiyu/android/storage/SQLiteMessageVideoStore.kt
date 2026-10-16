package app.daiyu.android.storage

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import app.daiyu.android.ControllerVideoManifestWitness
import app.daiyu.android.domain.Message
import app.daiyu.android.model.videos.MessageVideoInput

/** Metadata belongs to the physical row; original video bytes remain in VideoAssetStore. */
internal object SQLiteMessageVideoStore {
    private const val TABLE = "message_video_inputs"

    fun installSchema(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS message_video_inputs(
            message_id TEXT PRIMARY KEY NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
            payload TEXT NOT NULL CHECK(typeof(payload)='text' AND length(CAST(payload AS BLOB)) BETWEEN 1 AND 1048576)
        )""".trimIndent())
    }

    fun read(db: SQLiteDatabase, message: Message): List<MessageVideoInput> {
        val present = db.rawQuery("SELECT typeof(payload) FROM $TABLE WHERE message_id=?",
            arrayOf(message.id.value)).use { cursor ->
            if (!cursor.moveToFirst()) false else {
                check(cursor.getString(0) == "text") { "video_sidecar_storage_invalid" }
                true
            }
        }
        if (!present) return emptyList()
        val encoded = checkNotNull(SQLiteText.read(db, TABLE, "payload", "message_id", message.id.value,
            MessageVideoInputCodec.MAX_UTF8_BYTES.toLong())) { "video_sidecar_missing" }
        return MessageVideoInputCodec.decode(message, encoded)
    }

    fun readVerified(db: SQLiteDatabase, message: Message): List<MessageVideoInput> =
        read(db, message).also { ControllerVideoManifestWitness.verify(db, message, it) }

    /** Call in the very same transaction that inserts the two accepted message rows. */
    fun insert(db: SQLiteDatabase, message: Message, inputs: List<MessageVideoInput>) {
        check(db.inTransaction())
        val encoded = MessageVideoInputCodec.encode(message, inputs)
        if (inputs.isNotEmpty()) check(db.insertOrThrow(TABLE, null, ContentValues().apply {
            put("message_id", message.id.value)
            put("payload", encoded)
        }) != -1L) { "video_sidecar_not_inserted" }
        ControllerVideoManifestWitness.insert(db, message, inputs)
        check(MessageVideoInputCodec.encode(message, readVerified(db, message)) == encoded) {
            "video_sidecar_receipt_mismatch"
        }
    }

    fun deleteConversation(db: SQLiteDatabase, conversationId: String) {
        check(db.inTransaction())
        db.execSQL("DELETE FROM $TABLE WHERE message_id IN (SELECT id FROM messages WHERE conversation_id=?)",
            arrayOf(conversationId))
        ControllerVideoManifestWitness.deleteConversation(db, conversationId)
        for (table in listOf(TABLE, ControllerVideoManifestWitness.TABLE)) {
            check(!db.rawQuery("SELECT 1 FROM $table WHERE message_id IN (SELECT id FROM messages WHERE conversation_id=?) LIMIT 1",
                arrayOf(conversationId)).use { it.moveToFirst() }) { "video_metadata_delete_unconfirmed" }
        }
    }

    fun assertNoOrphans(db: SQLiteDatabase) {
        check(db.inTransaction())
        check(!db.rawQuery("SELECT 1 FROM message_video_inputs v LEFT JOIN messages m " +
            "ON m.id=v.message_id WHERE m.id IS NULL LIMIT 1", null).use { it.moveToFirst() }) {
            "video_orphan_sidecar"
        }
    }

    fun hasVideos(db: SQLiteDatabase, conversationId: String?): Boolean {
        assertNoOrphans(db)
        return db.rawQuery("SELECT 1 FROM messages m LEFT JOIN message_video_witnesses w ON w.message_id=m.id " +
            "WHERE (w.message_id IS NULL OR w.reference_count<>0 OR EXISTS(SELECT 1 FROM message_video_inputs v WHERE v.message_id=m.id))" +
            (if (conversationId == null) "" else " AND m.conversation_id=?") + " LIMIT 1",
            conversationId?.let { arrayOf(it) }).use { it.moveToFirst() }
    }
}
