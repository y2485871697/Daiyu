package app.daiyu.android.storage

import android.database.sqlite.SQLiteDatabase
import app.daiyu.android.domain.ConversationId

/** Local unread events, not a projection of the latest historical terminal status. */
internal object ConversationCompletionMarkers {
    data class Receipt(val conversationId: String, val messageId: String, val runId: String, val token: String)

    /** Additive v19 install; deliberately no history backfill. Runs inside the helper migration. */
    fun installSchema(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS conversation_completion_markers(
            conversation_id TEXT PRIMARY KEY NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
            message_id TEXT NOT NULL, run_id TEXT NOT NULL, token TEXT NOT NULL
        )""".trimIndent())
        // Both legacy save and atomic runtime-checkpoint writes UPDATE the same physical row.
        // INSERTed backup/history COMPLETED rows must never fabricate a new completion event.
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS conversation_completion_event
            AFTER UPDATE OF status ON messages
            WHEN OLD.status='RUNNING' AND NEW.status='COMPLETED' AND NEW.role='assistant'
                AND OLD.id=NEW.id AND OLD.run_id=NEW.run_id AND OLD.conversation_id=NEW.conversation_id
                AND EXISTS(SELECT 1 FROM conversations WHERE id=NEW.conversation_id)
            BEGIN
                INSERT OR REPLACE INTO conversation_completion_markers(conversation_id,message_id,run_id,token)
                VALUES(NEW.conversation_id,NEW.id,NEW.run_id,lower(hex(randomblob(16))));
            END""".trimIndent())
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS conversation_completion_message_deleted
            AFTER DELETE ON messages BEGIN
                DELETE FROM conversation_completion_markers
                WHERE conversation_id=OLD.conversation_id AND message_id=OLD.id AND run_id=OLD.run_id;
            END""".trimIndent())
    }

    fun read(db: SQLiteDatabase, id: ConversationId): Receipt? = db.rawQuery(
        "SELECT message_id,run_id,token FROM conversation_completion_markers WHERE conversation_id=?",
        arrayOf(id.value),
    ).use { c -> if (!c.moveToFirst()) null else Receipt(id.value, c.getString(0), c.getString(1), c.getString(2)) }

    /** A delayed read acknowledgement cannot clear a newer completed run, even with restored IDs. */
    fun acknowledge(db: SQLiteDatabase, receipt: Receipt): Boolean = db.delete(
        "conversation_completion_markers", "conversation_id=? AND message_id=? AND run_id=? AND token=?",
        arrayOf(receipt.conversationId, receipt.messageId, receipt.runId, receipt.token),
    ) == 1
}
