package app.daiyu.android.storage

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.util.JsonReader
import android.util.JsonToken
import app.daiyu.android.domain.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.StringReader

/** Disposable display projection. SQL triggers invalidate it even for import/direct SQL writers. */
internal object MessageDisplayStore {
    fun install(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS message_display(message_id TEXT PRIMARY KEY NOT NULL REFERENCES messages(id) ON DELETE CASCADE,payload TEXT NOT NULL)")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS message_display_update BEFORE UPDATE ON messages BEGIN DELETE FROM message_display WHERE message_id=OLD.id OR message_id=NEW.id; END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS message_display_insert AFTER INSERT ON messages BEGIN DELETE FROM message_display WHERE message_id=NEW.id; END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS message_display_delete BEFORE DELETE ON messages BEGIN DELETE FROM message_display WHERE message_id=OLD.id; END")
    }

    fun read(db: SQLiteDatabase, conversation: ConversationId, before: Long, limit: Int): MessageDisplayWindow {
        check(db.inTransaction())
        val ids = db.rawQuery("SELECT seq,id,run_id,role,status,created_at FROM messages WHERE conversation_id=? AND seq<? ORDER BY seq DESC LIMIT ?",
            arrayOf(conversation.value, before.toString(), limit.toString())).use { c ->
            buildList { while (c.moveToNext()) add(Metadata(c.getLong(0), c.getString(1), c.getString(2),
                c.getString(3), c.getString(4), if (c.isNull(5)) null else c.getLong(5))) }.reversed()
        }
        return MessageDisplayWindow(ids.map { row ->
            fun decodePayload(payload: String): MessageDisplayRow {
                val value = JSONObject(payload)
                require(value.keys().asSequence().toSet() == setOf("version", "id", "conversation", "run", "blocks", "history"))
                require(value.getInt("version") == 1 && value.getString("id") == row.id &&
                    value.getString("conversation") == conversation.value && value.getString("run") == row.run)
                val history = value.getJSONArray("history")
                repeat(history.length()) { require(history.getJSONObject(it).getJSONArray("protocol").length() == 0) }
                val segments = MessageHistoryCodec.decode(history)
                return MessageDisplayRow(MessageId(row.id), conversation, RunId(row.run), row.role,
                    decodeBlocks(value.getJSONArray("blocks")), RunStatus.valueOf(row.status),
                    SQLiteText.read(db, "messages", "error", "id", row.id), row.time,
                    segments.map { MessageDisplaySegment(it.messageId, it.role, it.blocks, it.createdAt, it.reportedReasoning) })
            }
            val cached = db.rawQuery("SELECT 1 FROM message_display WHERE message_id=?", arrayOf(row.id)).use { it.moveToFirst() }
            val hit = if (!cached) null else try {
                decodePayload(requireNotNull(SQLiteText.read(db, "message_display", "payload", "message_id", row.id)))
            } catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled }
              catch (cancelled: android.os.OperationCanceledException) { throw cancelled }
              catch (_: Exception) { db.delete("message_display", "message_id=?", arrayOf(row.id)); null }
            row.seq to (hit ?: run {
                // Legacy rows pay a one-time scan, without materializing/validating protocol items.
                val blockText = requireNotNull(SQLiteText.read(db, "messages", "blocks", "id", row.id))
                val history = requireNotNull(SQLiteText.read(db, "messages", "history_segments", "id", row.id))
                val payload = envelope(row.id, conversation.value, row.run, JSONArray(blockText), stripProtocols(history)).toString()
                val projection = decodePayload(payload)
                check(db.insertOrThrow("message_display", null, ContentValues().apply {
                    put("message_id", row.id); put("payload", payload)
                }) != -1L)
                projection
            })
        })
    }

    private fun envelope(id: String, conversation: String, run: String, blocks: JSONArray, history: JSONArray) =
        JSONObject().put("version", 1).put("id", id).put("conversation", conversation).put("run", run)
            .put("blocks", blocks).put("history", history)

    private data class Metadata(val seq: Long, val id: String, val run: String, val role: String,
        val status: String, val time: Long?)

    private fun stripProtocols(source: String): JSONArray = JsonReader(StringReader(source)).use { reader ->
        val result = JSONArray()
        reader.beginArray()
        while (reader.hasNext()) {
            val segment = JSONObject()
            reader.beginObject()
            while (reader.hasNext()) {
                val name = reader.nextName()
                if (name == "protocol") { reader.skipValue(); segment.put(name, JSONArray()) }
                else segment.put(name, jsonValue(reader))
            }
            reader.endObject()
            result.put(segment)
        }
        reader.endArray()
        require(reader.peek() == JsonToken.END_DOCUMENT)
        result
    }

    private fun jsonValue(reader: JsonReader): Any = when (reader.peek()) {
        JsonToken.BEGIN_ARRAY -> JSONArray().apply {
            reader.beginArray(); while (reader.hasNext()) put(jsonValue(reader)); reader.endArray()
        }
        JsonToken.BEGIN_OBJECT -> JSONObject().apply {
            reader.beginObject(); while (reader.hasNext()) put(reader.nextName(), jsonValue(reader)); reader.endObject()
        }
        JsonToken.STRING -> reader.nextString()
        JsonToken.NUMBER -> reader.nextString().let { it.toLongOrNull() ?: it.toDouble() }
        JsonToken.BOOLEAN -> reader.nextBoolean()
        JsonToken.NULL -> { reader.nextNull(); JSONObject.NULL }
        else -> error("Invalid display JSON")
    }

    private fun decodeBlocks(value: JSONArray): List<MessageBlock> = (0 until value.length()).map { index ->
        val block = value.getJSONObject(index)
        fun text(name: String) = block.get(name) as? String ?: error("Invalid display text")
        when (text("type")) {
            "text" -> {
                require(block.keys().asSequence().toSet() == setOf("type", "item", "text"))
                MessageBlock.Text(text("item"), text("text"))
            }
            "image" -> MessageImageReferenceCodec.decodeBlock(block)
            "tool" -> {
                require(block.keys().asSequence().toSet() == setOf("type", "id", "name", "arguments", "result"))
                MessageBlock.Tool(ToolInvocation(ToolCallId(text("id")), text("name"), text("arguments")),
                    if (block.isNull("result")) null else text("result"))
            }
            else -> error("Unknown display block")
        }
    }
}
