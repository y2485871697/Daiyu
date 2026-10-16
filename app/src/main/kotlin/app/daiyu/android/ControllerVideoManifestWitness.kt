package app.daiyu.android

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import app.daiyu.android.domain.Message
import app.daiyu.android.domain.MessageHistory
import app.daiyu.android.model.videos.MessageVideoInput
import app.daiyu.android.storage.MessageVideoInputCodec
import app.daiyu.android.storage.SQLiteMessageVideoStore
import java.security.MessageDigest

/** Independent evidence of expected metadata, NOT a send/receipt ledger or an asset index.
 * One row per physical message (including known text-only rows), so deleting a sidecar before
 * the first capture cannot silently remove capability checks. No read repairs/backfills evidence.
 * Parent MUST install/migrate and write this in the same DB transaction as messages + sidecars.
 */
internal object ControllerVideoManifestWitness {
    const val TABLE = "message_video_witnesses"

    fun installSchema(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS message_video_witnesses(
            message_id TEXT PRIMARY KEY NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
            reference_count INTEGER NOT NULL CHECK(typeof(reference_count)='integer' AND reference_count BETWEEN -1 AND 1024),
            manifest_sha256 TEXT,
            CHECK((reference_count=-1 AND manifest_sha256 IS NULL) OR
              (reference_count>=0 AND typeof(manifest_sha256)='text' AND length(manifest_sha256)=64
               AND manifest_sha256 NOT GLOB '*[^0-9a-f]*'))
        )""".trimIndent())
    }

    /** Insert-or-verify, never replace/upsert: sends cannot overwrite a conflicting witness.
     * Inputs must be the authoritative accepted/validated import manifest, not an inferred empty
     * result from reading a possibly lost sidecar. Call even for zero-video user AND assistant.
     */
    fun insert(db: SQLiteDatabase, row: Message, inputs: List<MessageVideoInput>) {
        check(db.inTransaction()) { "video_witness_requires_transaction" }
        val count = inputs.sumOf { it.references.size }
        val hash = digest(MessageVideoInputCodec.encode(row, inputs))
        val existing = db.rawQuery("SELECT 1 FROM $TABLE WHERE message_id=?", arrayOf(row.id.value)).use { it.moveToFirst() }
        if (existing) {
            verify(db, row, inputs)
            verify(db, row, SQLiteMessageVideoStore.read(db, row))
            return
        }
        db.insertOrThrow(TABLE, null, ContentValues().apply {
            put("message_id", row.id.value)
            put("reference_count", count)
            put("manifest_sha256", hash)
        })
        verify(db, row, SQLiteMessageVideoStore.read(db, row))
    }

    /** No fallback for a missing table/row, invalid witness, unknown legacy row or mismatch.
     * Hash canonical codec output (not JSON whitespace). The codec binds physical + expanded
     * owner/run identities and ordered references; text remains the physical-history CAS's job.
     */
    fun verify(db: SQLiteDatabase, row: Message, inputs: List<MessageVideoInput>) {
        check(db.inTransaction()) { "video_witness_requires_transaction" }
        db.rawQuery("SELECT typeof(reference_count),reference_count,typeof(manifest_sha256),manifest_sha256 FROM $TABLE WHERE message_id=?",
            arrayOf(row.id.value)).use { c ->
            check(c.moveToFirst()) { "video_witness_missing" }
            check(c.getString(0) == "integer" && c.getLong(1) in 0L..1024L && c.getString(2) == "text") {
                "video_witness_unknown_or_invalid"
            }
            check(c.getLong(1) == inputs.sumOf { it.references.size }.toLong() &&
                c.getString(3) == digest(MessageVideoInputCodec.encode(row, inputs))) { "video_witness_mismatch" }
            check(!c.moveToNext()) { "video_witness_duplicate" }
        }
    }

    /** Only inside the versioned migration, never from capture/read/onOpen. v12 absent sidecars
     * cannot prove old text: persist -1 (quarantine), retaining every original row. Older schemas
     * known never to have supported original video may explicitly pass emptyIsProven=true.
     * Parent supplies ALL physical rows via storedMessage, not display/expanded projections.
     */
    fun migrateLegacy(db: SQLiteDatabase, rows: List<Message>, emptyIsProven: Boolean = false) {
        check(db.inTransaction()) { "video_witness_requires_transaction" }
        rows.forEach { row ->
            val present = db.rawQuery("SELECT 1 FROM message_video_inputs WHERE message_id=?",
                arrayOf(row.id.value)).use { it.moveToFirst() }
            // A physical assistant with no expanded user cannot own video by codec contract.
            val cannotOwnVideos = MessageHistory.expand(listOf(row)).none { it.role == Message.USER_ROLE }
            if (present || emptyIsProven || cannotOwnVideos) {
                insert(db, row, SQLiteMessageVideoStore.read(db, row))
            } else {
                db.insertOrThrow(TABLE, null, ContentValues().apply {
                    put("message_id", row.id.value)
                    put("reference_count", -1)
                    putNull("manifest_sha256")
                })
            }
        }
    }

    /** Use for explicit conversation deletion BEFORE deleting messages, in that transaction.
     * Foreign-key cascade also covers individual message deletion. NEVER tie deletion to a
     * sidecar delete trigger: a vanished sidecar must leave its evidence behind.
     */
    fun deleteConversation(db: SQLiteDatabase, conversationId: String) {
        check(db.inTransaction()) { "video_witness_requires_transaction" }
        db.execSQL("DELETE FROM $TABLE WHERE message_id IN (SELECT id FROM messages WHERE conversation_id=?)",
            arrayOf(conversationId))
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
}
