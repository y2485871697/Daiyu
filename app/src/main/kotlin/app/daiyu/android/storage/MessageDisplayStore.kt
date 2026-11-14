package app.daiyu.android.storage

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.util.JsonReader
import android.util.JsonToken
import app.daiyu.android.domain.*
import app.daiyu.android.model.LegacySearchDisplay
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

    fun read(db: SQLiteDatabase, conversation: ConversationId, before: Long, limit: Int, rebuildWindow: Boolean = true,
        latestRunOnly: Boolean = false): MessageDisplayWindow {
        check(db.inTransaction())
        val metadata = db.rawQuery("SELECT seq,id,run_id,role,status,created_at FROM messages WHERE conversation_id=? AND seq<? ORDER BY seq DESC LIMIT ?",
            arrayOf(conversation.value, before.toString(), limit.toString())).use { c ->
            buildList { while (c.moveToNext()) add(Metadata(c.getLong(0), c.getString(1), c.getString(2),
                c.getString(3), c.getString(4), if (c.isNull(5)) null else c.getLong(5))) }.reversed()
        }
        // Keep the newest contiguous run, including supplements, within the requested tail.
        val tail = if (latestRunOnly) metadata.takeLastWhile { it.run == metadata.last().run } else metadata
        val ids = if (latestRunOnly && tail.any { it.role == Message.ASSISTANT_ROLE }) tail else metadata
        val rows = ids.map { row ->
            fun decodePayload(payload: String): MessageDisplayRow {
                val value = JSONObject(payload)
                require(value.keys().asSequence().toSet() == setOf("version", "id", "conversation", "run", "blocks", "history", "projections"))
                require(value.getInt("version") == 3 && value.getString("id") == row.id &&
                    value.getString("conversation") == conversation.value && value.getString("run") == row.run)
                val history = value.getJSONArray("history")
                repeat(history.length()) { require(history.getJSONObject(it).getJSONArray("protocol").length() == 0) }
                val segments = MessageHistoryCodec.decode(history)
                val blocks = decodeBlocks(value.getJSONArray("blocks"))
                val projections = value.getJSONArray("projections")
                val addresses = mutableSetOf<Pair<Int, Int>>()
                repeat(projections.length()) { index ->
                    val projection = projections.getJSONObject(index)
                    require(projection.keys().asSequence().toSet() == setOf("segment", "block", "text", "boundaries"))
                    val segment = projection.getInt("segment")
                    val ordinal = projection.getInt("block")
                    require(addresses.add(segment to ordinal))
                    val source = if (segment == -1) blocks else segments[segment].blocks
                    require((if (segment == -1) row.role else segments[segment].role) == Message.ASSISTANT_ROLE)
                    val raw = source[ordinal] as MessageBlock.Text
                    require(LegacySearchDisplay.candidate(raw.text))
                    val text = projection.getString("text")
                    val offsets = projection.getJSONArray("boundaries")
                    val coordinates = app.daiyu.android.MarkdownSourceCoordinates.from(
                        (0 until offsets.length()).map { offsets.getInt(it) }, text.length)
                    LegacySearchDisplay.remember(raw, text, coordinates)
                }
                return MessageDisplayRow(MessageId(row.id), conversation, RunId(row.run), row.role,
                    blocks.map(LegacySearchDisplay::display), RunStatus.valueOf(row.status),
                    SQLiteText.read(db, "messages", "error", "id", row.id), row.time,
                    segments.map { MessageDisplaySegment(it.messageId, it.role, it.blocks.map(LegacySearchDisplay::display),
                        it.createdAt, it.reportedReasoning, coordinateBlocks = it.blocks) }, coordinateBlocks = blocks)
            }
            val cached = db.rawQuery("SELECT 1 FROM message_display WHERE message_id=?", arrayOf(row.id)).use { it.moveToFirst() }
            val hit = if (!cached) null else try {
                decodePayload(requireNotNull(SQLiteText.read(db, "message_display", "payload", "message_id", row.id)))
            } catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled }
              catch (cancelled: android.os.OperationCanceledException) { throw cancelled }
              catch (_: Exception) { db.delete("message_display", "message_id=?", arrayOf(row.id)); null }
            row.seq to (hit ?: run {
                // Ordinary rows still skip protocols. Only exact legacy candidates need raw evidence.
                val blockText = requireNotNull(SQLiteText.read(db, "messages", "blocks", "id", row.id))
                val history = requireNotNull(SQLiteText.read(db, "messages", "history_segments", "id", row.id))
                val blocks = JSONArray(blockText)
                val projections = JSONArray()
                if (hasCandidate(row.role, blocks)) {
                    val raw = SQLiteText.read(db, "messages", "protocol", "id", row.id)
                    val protocol = try { raw?.let(::JSONArray) } catch (_: Exception) { null }
                    if (protocol != null) repairBlocks(row.role, blocks, protocol, projections, -1)
                }
                val segments = stripProtocols(history)
                repairHistory(history, segments, projections)
                val payload = envelope(row.id, conversation.value, row.run, blocks, segments, projections).toString()
                val projection = decodePayload(payload)
                check(db.insertOrThrow("message_display", null, ContentValues().apply {
                    put("message_id", row.id); put("payload", payload)
                }) != -1L)
                projection
            })
        }
        return try { MessageDisplayWindow(rows, deferredRows = metadata.size - ids.size) } catch (invalid: IllegalArgumentException) {
            if (!rebuildWindow) throw invalid
            ids.forEach { db.delete("message_display", "message_id=?", arrayOf(it.id)) }
            read(db, conversation, before, limit, rebuildWindow = false, latestRunOnly = latestRunOnly)
        }
    }

    private fun envelope(id: String, conversation: String, run: String, blocks: JSONArray, history: JSONArray,
        projections: JSONArray) = JSONObject().put("version", 3).put("id", id).put("conversation", conversation).put("run", run)
            .put("blocks", blocks).put("history", history).put("projections", projections)

    private data class Metadata(val seq: Long, val id: String, val run: String, val role: String,
        val status: String, val time: Long?)

    private fun hasCandidate(role: String, blocks: JSONArray): Boolean = role == Message.ASSISTANT_ROLE &&
        (0 until blocks.length()).any { index -> blocks.getJSONObject(index).let {
            it.optString("type") == "text" && LegacySearchDisplay.candidate(it.optString("text"))
        } }

    private fun repairBlocks(role: String, blocks: JSONArray, protocol: JSONArray, projections: JSONArray, segment: Int) {
        try {
            val original = decodeBlocks(blocks)
            LegacySearchDisplay.project(role, original, (0 until protocol.length()).map { protocol.getString(it) })
            original.forEachIndexed { index, block ->
                LegacySearchDisplay.projection(block)?.let { projection ->
                    projections.put(JSONObject().put("segment", segment).put("block", index)
                        .put("text", projection.display.text).put("boundaries", JSONArray(projection.coordinates.values())))
                }
            }
        } catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled }
          catch (_: Exception) { /* Unprovable legacy records retain their stored display. */ }
    }

    private fun repairHistory(source: String, segments: JSONArray, projections: JSONArray) {
        val candidates = (0 until segments.length()).filter { index -> segments.getJSONObject(index).let {
            hasCandidate(it.getString("role"), it.getJSONArray("blocks"))
        } }.toSet()
        if (candidates.isEmpty()) return
        JsonReader(StringReader(source)).use { reader ->
            reader.beginArray()
            var index = 0
            while (reader.hasNext()) {
                if (index !in candidates) reader.skipValue() else {
                    reader.beginObject()
                    while (reader.hasNext()) {
                        if (reader.nextName() == "protocol") {
                            val protocol = jsonValue(reader) as? JSONArray
                            val segment = segments.getJSONObject(index)
                            if (protocol != null) repairBlocks(segment.getString("role"), segment.getJSONArray("blocks"), protocol, projections, index)
                        } else reader.skipValue()
                    }
                    reader.endObject()
                }
                index++
            }
            reader.endArray()
        }
    }

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
