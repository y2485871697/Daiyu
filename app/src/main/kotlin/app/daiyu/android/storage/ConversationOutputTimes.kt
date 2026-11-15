package app.daiyu.android.storage

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.util.JsonReader
import android.util.JsonToken
import app.daiyu.android.domain.Message
import java.io.StringReader

/** Disposable sidebar projection; direct SQL/import writers invalidate it too. */
internal object ConversationOutputTimes {
    fun install(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS conversation_output_times(conversation_id TEXT PRIMARY KEY NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,created_at INTEGER)")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS conversation_output_insert AFTER INSERT ON messages BEGIN DELETE FROM conversation_output_times WHERE conversation_id=NEW.conversation_id; END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS conversation_output_update AFTER UPDATE ON messages BEGIN DELETE FROM conversation_output_times WHERE conversation_id=OLD.conversation_id OR conversation_id=NEW.conversation_id; END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS conversation_output_delete AFTER DELETE ON messages BEGIN DELETE FROM conversation_output_times WHERE conversation_id=OLD.conversation_id; END")
    }

    fun read(db: SQLiteDatabase): Map<String, Long?> {
        check(db.inTransaction())
        val cached = db.rawQuery("SELECT c.id,t.conversation_id,t.created_at FROM conversations c " +
            "LEFT JOIN conversation_output_times t ON t.conversation_id=c.id WHERE c.id NOT GLOB 'voice-call-*'", null).use { rows ->
            buildList { while (rows.moveToNext()) add(Triple(rows.getString(0), !rows.isNull(1),
                if (rows.isNull(2)) null else rows.getLong(2))) }
        }
        return buildMap {
            for ((id, hit, value) in cached) {
                val time = if (hit) value else tailTime(db, id).also { time ->
                    db.insertOrThrow("conversation_output_times", null, ContentValues().apply {
                        put("conversation_id", id); put("created_at", time)
                    })
                }
                put(id, time)
            }
        }
    }

    private data class Tail(val seq: Long, val id: String, val run: String, val role: String,
        val status: String, val time: Long?, val hasHistory: Boolean)

    private fun metadata(db: SQLiteDatabase, where: String, args: Array<String>): Tail? =
        db.rawQuery("SELECT seq,id,run_id,role,status,created_at," +
            "length(history_segments)>2 AND substr(history_segments,1,1)='[' FROM messages WHERE $where ORDER BY seq DESC LIMIT 1", args).use { row ->
            if (!row.moveToFirst()) null else Tail(row.getLong(0), row.getString(1), row.getString(2),
                row.getString(3), row.getString(4), if (row.isNull(5)) null else row.getLong(5), row.getInt(6) != 0)
        }

    private data class Segment(val id: String, val role: String, val time: Long?)
    private data class HistoryTail(val users: Set<String>, val last: Segment?, val lastUser: Segment?)

    private fun tailTime(db: SQLiteDatabase, conversation: String): Long? {
        val histories = mutableMapOf<String, HistoryTail>()
        fun history(owner: Tail): HistoryTail = histories.getOrPut(owner.id) { readHistory(db, owner) }
        val coveredByRun = mutableMapOf<String, Set<String>>()
        fun coveredUsers(run: String): Set<String> = coveredByRun.getOrPut(run) {
            db.rawQuery("SELECT seq,id,run_id,role,status,created_at,1 FROM messages WHERE conversation_id=? " +
                "AND run_id=? AND role='assistant' AND length(history_segments)>2 AND substr(history_segments,1,1)='['",
                arrayOf(conversation, run)).use { owners -> buildSet {
                while (owners.moveToNext()) addAll(history(tail(owners)).users)
            } }
        }
        return db.rawQuery("SELECT seq,id,run_id,role,status,created_at," +
            "length(history_segments)>2 AND substr(history_segments,1,1)='[' FROM messages " +
            "WHERE conversation_id=? ORDER BY seq DESC", arrayOf(conversation)).use { rows ->
            while (rows.moveToNext()) {
                val row = tail(rows)
                if (row.role == Message.USER_ROLE && row.id in coveredUsers(row.run)) continue
                if (!row.hasHistory) {
                    if (row.status != "RUNNING") return@use row.time
                    continue
                }
                val projected = history(row)
                val last = if (row.status == "RUNNING") projected.lastUser else projected.last
                if (last == null) {
                    if (row.status != "RUNNING") return@use row.time
                    continue
                }
                return@use last.time ?: metadata(db, "conversation_id=? AND run_id=? AND id=?",
                    arrayOf(conversation, row.run, last.id))?.time
            }
            null
        }
    }

    private fun tail(row: android.database.Cursor) = Tail(row.getLong(0), row.getString(1), row.getString(2),
        row.getString(3), row.getString(4), if (row.isNull(5)) null else row.getLong(5), row.getInt(6) != 0)

    private fun readHistory(db: SQLiteDatabase, owner: Tail): HistoryTail {
        val raw = checkNotNull(SQLiteText.read(db, "messages", "history_segments", "id", owner.id))
        val users = mutableSetOf<String>()
        var last: Segment? = null
        var lastUser: Segment? = null
        JsonReader(StringReader(raw)).use { reader ->
            reader.beginArray()
            while (reader.hasNext()) {
                var id: String? = null
                var role: String? = null
                var time: Long? = null
                reader.beginObject()
                while (reader.hasNext()) when (reader.nextName()) {
                    "message_id" -> id = reader.nextString()
                    "role" -> role = reader.nextString()
                    MessageTimeCodec.FIELD -> if (reader.peek() == JsonToken.NULL) reader.nextNull() else time = reader.nextLong()
                    else -> reader.skipValue()
                }
                reader.endObject()
                last = Segment(checkNotNull(id), checkNotNull(role), time)
                if (role == Message.USER_ROLE) { users += checkNotNull(id); lastUser = last }
            }
            reader.endArray()
        }
        return HistoryTail(users, last, lastUser)
    }
}
