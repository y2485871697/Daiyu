package app.daiyu.android.backup

import android.database.sqlite.SQLiteDatabase
import app.daiyu.android.context.PrivateBlobs
import app.daiyu.android.domain.*
import app.daiyu.android.media.video.MessageVideoOwner
import app.daiyu.android.media.video.MessageVideoReference
import app.daiyu.android.storage.MessageHistoryCodec
import app.daiyu.android.storage.MessageImageReferenceCodec
import app.daiyu.android.storage.MessageVideoInputCodec
import app.daiyu.android.storage.SQLiteText
import org.json.JSONArray
import org.json.JSONObject

/** Closed versioned manifest. Physical sidecar order and every per-owner reference order matter.
 * Owners are resolved by the existing strict codec against the actual physical row/history,
 * never against a manifest-supplied replacement message. No metadata grants upload access.
 */
internal object BackupVideoManifest {
    const val MAX_SIDECARS = 50_000
    const val MAX_REFERENCES = 50_000
    const val MAX_MANIFEST_BYTES = 4 * 1024 * 1024

    data class Validated(val sidecars: List<BackupVideoSidecar>, val references: List<MessageVideoReference>)

    fun read(db: SQLiteDatabase, rows: JSONArray): JSONObject {
        val sidecars = JSONArray()
        var size = 0L
        repeat(rows.length()) { index ->
            val id = rows.getJSONObject(index).getString("id")
            db.rawQuery("SELECT typeof(payload),length(CAST(payload AS BLOB)) FROM message_video_inputs WHERE message_id=?",
                arrayOf(id)).use { cursor ->
                if (cursor.moveToFirst()) {
                    require(cursor.getString(0) == "text" && cursor.getLong(1) in 1..MessageVideoInputCodec.MAX_UTF8_BYTES.toLong())
                    size += cursor.getLong(1)
                    require(size <= MAX_MANIFEST_BYTES)
                    sidecars.put(JSONObject().put("message_id", id).put("payload", checkNotNull(SQLiteText.read(db, "message_video_inputs", "payload", "message_id", id,
                        MessageVideoInputCodec.MAX_UTF8_BYTES.toLong()))))
                    require(sidecars.length() <= MAX_SIDECARS)
                }
            }
        }
        return JSONObject().put("version", 1).put("sidecars", sidecars).also { decode(it, rows) }
    }

    fun decode(manifest: JSONObject?, rows: JSONArray): Validated {
        if (manifest == null) return Validated(emptyList(), emptyList())
        require(manifest.toString().toByteArray(Charsets.UTF_8).size <= MAX_MANIFEST_BYTES)
        fields(manifest, setOf("version", "sidecars"))
        require(integer(manifest, "version") == 1L)
        val sidecars = manifest.getJSONArray("sidecars")
        require(sidecars.length() <= MAX_SIDECARS)
        val physical = (0 until rows.length()).associate { index -> rows.getJSONObject(index).getString("id") to index }
        require(physical.size == rows.length())
        var previousIndex = -1
        val owners = linkedMapOf<MessageVideoOwner, List<MessageVideoReference>>()
        var count = 0
        val result = (0 until sidecars.length()).map { index ->
            val row = sidecars.getJSONObject(index)
            fields(row, setOf("message_id", "payload"))
            val id = text(row, "message_id")
            val physicalIndex = requireNotNull(physical[id]) { "视频清单缺物理消息" }
            require(physicalIndex > previousIndex) { "重复或乱序视频物理消息" }
            previousIndex = physicalIndex
            val encoded = text(row, "payload")
            val message = message(rows.getJSONObject(physicalIndex))
            val inputs = MessageVideoInputCodec.decode(message, encoded)
            require(inputs.isNotEmpty()) { "空视频 sidecar" }
            // Canonical codec preserves input and reference order, rejects unknown/duplicate keys.
            require(MessageVideoInputCodec.encode(message, inputs) == encoded)
            inputs.forEach { input ->
                val old = owners.put(input.owner, input.references)
                require(old == null || old == input.references) { "历史视频原件或顺序冲突" }
                if (old == null) {
                    count += input.references.size
                    require(count <= MAX_REFERENCES)
                }
            }
            BackupVideoSidecar(id, encoded)
        }
        return Validated(result, owners.values.flatten())
    }

    fun validate(manifest: JSONObject?, rows: JSONArray, bytes: Map<String, ByteArray>): Validated =
        decode(manifest, rows).also { parsed ->
            parsed.references.forEach { reference ->
                verify(reference, requireNotNull(bytes[reference.sha256]) { "备份缺少原视频" })
            }
        }

    /** Receipt is checked even for duplicate imports: the marker alone is not proof. */
    fun verifySidecars(db: SQLiteDatabase, sidecars: List<BackupVideoSidecar>) {
        check(db.inTransaction())
        sidecars.forEach { sidecar ->
            db.rawQuery("SELECT typeof(payload),length(CAST(payload AS BLOB)) FROM message_video_inputs WHERE message_id=?",
                arrayOf(sidecar.messageId)).use { cursor ->
                require(cursor.moveToFirst() && cursor.getString(0) == "text") { "视频 sidecar 未恢复" }
                require(cursor.getLong(1) in 1..MessageVideoInputCodec.MAX_UTF8_BYTES.toLong())
                require(SQLiteText.read(db, "message_video_inputs", "payload", "message_id", sidecar.messageId,
                    MessageVideoInputCodec.MAX_UTF8_BYTES.toLong()) == sidecar.payload) { "视频 sidecar 恢复冲突" }
            }
            val row = db.rawQuery("SELECT conversation_id,run_id,role,status FROM messages WHERE id=?",
                arrayOf(sidecar.messageId)).use { cursor ->
                require(cursor.moveToFirst()) { "视频物理消息缺失" }
                JSONObject().put("id", sidecar.messageId).put("conversation_id", cursor.getString(0))
                    .put("run_id", cursor.getString(1)).put("role", cursor.getString(2)).put("status", cursor.getString(3))
            }
            // Keep the existing chunked large-text path; a video must not make otherwise valid
            // multi-MiB text/history fail merely because a CursorWindow cannot hold one row.
            fun field(column: String) = SQLiteText.read(db, "messages", column, "id", sidecar.messageId)
            row.put("blocks", JSONArray(requireNotNull(field("blocks"))))
                .put("error", field("error") ?: JSONObject.NULL)
                .put("protocol", JSONArray(requireNotNull(field("protocol"))))
            val segments = JSONArray(requireNotNull(field("history_segments")))
            if (segments.length() != 0) row.put("history_segments", segments)
            val current = message(row)
            require(MessageVideoInputCodec.encode(current, MessageVideoInputCodec.decode(current, sidecar.payload)) == sidecar.payload)
        }
    }

    fun verify(reference: MessageVideoReference, bytes: ByteArray) {
        require(reference.bytes == bytes.size.toLong() && reference.sha256 == PrivateBlobs.digest(bytes)) {
            "原视频大小或摘要不匹配"
        }
    }

    /** Text preflight is authoritative for the text protocol; do not impose archive-size limits
     * on ordinary text. This projection is only used to bind the strict sidecar codec. */
    private fun message(row: JSONObject): Message {
        val blocks = row.getJSONArray("blocks")
        val decoded = (0 until blocks.length()).map { index ->
            val block = blocks.getJSONObject(index)
            when (block.getString("type")) {
                "text" -> MessageBlock.Text(block.getString("item"), block.getString("text"))
                "image" -> MessageImageReferenceCodec.decodeBlock(block)
                "tool" -> MessageBlock.Tool(ToolInvocation(ToolCallId(block.getString("id")),
                    block.getString("name"), block.getString("arguments")),
                    if (block.isNull("result")) null else block.getString("result"))
                else -> error("未知消息块")
            }
        }
        val protocol = row.getJSONArray("protocol")
        return Message(MessageId(row.getString("id")), ConversationId(row.getString("conversation_id")),
            RunId(row.getString("run_id")), row.getString("role"), decoded,
            RunStatus.valueOf(row.getString("status")), if (row.isNull("error")) null else row.getString("error"),
            (0 until protocol.length()).map { protocol.getString(it) },
            historySegments = if (row.has("history_segments")) MessageHistoryCodec.decode(row.getJSONArray("history_segments")) else emptyList())
    }

    private fun fields(value: JSONObject, keys: Set<String>) = require(value.keys().asSequence().toSet() == keys)
    private fun text(value: JSONObject, key: String): String = value.get(key) as? String ?: error("视频字段须为文本")
    private fun integer(value: JSONObject, key: String): Long {
        val number = value.get(key)
        require(number is Int || number is Long)
        return (number as Number).toLong()
    }
}
