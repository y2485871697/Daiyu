package app.daiyu.android.backup

import app.daiyu.android.context.PrivateBlobs
import app.daiyu.android.context.Utf8Payload
import org.json.JSONArray
import org.json.JSONObject

/** Combines disjoint, already scoped captures before the existing package validation and asset spool. */
internal object BackupConversationMerge {
    class Budget {
        private data class Bounds(var rows: Int = 0, var bytes: Long = 2)
        private val bounds = mutableMapOf<String, Bounds>()
        private var totalBytes = 0L

        fun include(name: String, section: JSONObject, arrayLimit: Long = BackupHistoryRows.MAX_METADATA_BYTES.toLong(),
            sectionLimit: Long? = null) {
            val empty = JSONObject()
            var contents = 0L
            section.keys().forEach { key ->
                if (key == "version") empty.put(key, section.get(key))
                else {
                    val rows = section.getJSONArray(key)
                    val current = bounds.getOrPut("$name.$key") { Bounds() }
                    val limit = if (name == "tables" && key == "context_usage")
                        minOf(arrayLimit, BackupHistoryRows.MAX_METADATA_BYTES.toLong()) else arrayLimit
                    val added = Utf8Payload.byteCount(rows.toString(), limit) - 2
                    val bytes = current.bytes + added + if (current.rows > 0 && rows.length() > 0) 1 else 0
                    require(current.rows.toLong() + rows.length() <= BackupHistoryRows.MAX_ROWS && bytes <= limit) {
                        "备份历史元数据超限"
                    }
                    current.rows += rows.length(); current.bytes = bytes
                    empty.put(key, JSONArray()); contents += bytes - 2
                }
            }
            if (sectionLimit != null) require(Utf8Payload.byteCount(empty.toString(), sectionLimit) + contents <= sectionLimit) {
                "备份历史元数据超限"
            }
            totalBytes += Utf8Payload.byteCount(section.toString(), NeutralBackupService.MAX_PACKAGE)
            require(totalBytes <= NeutralBackupService.MAX_PACKAGE) { "备份元数据超限" }
        }
    }

    fun sections(parts: List<JSONObject>): JSONObject {
        val keys = parts.first().keys().asSequence().toSet()
        require(parts.all { it.keys().asSequence().toSet() == keys })
        return JSONObject().apply {
            keys.forEach { key ->
                if (key == "version") {
                    val version = parts.first().getLong(key)
                    require(parts.all { it.getLong(key) == version })
                    put(key, version)
                } else put(key, JSONArray().apply {
                    parts.forEach { part ->
                        val rows = part.getJSONArray(key)
                        repeat(rows.length()) { put(rows.get(it)) }
                    }
                })
            }
        }
    }

    fun text(parts: List<String>): String {
        val conversations = JSONArray(); val messages = JSONArray()
        parts.forEach { text ->
            val payload = JSONObject(JSONObject(text).getString("payload"))
            require(payload.getString("draft").isEmpty())
            val owners = payload.getJSONArray("conversations")
            val rows = payload.getJSONArray("messages")
            require(conversations.length() + owners.length() <= 1000 && messages.length() + rows.length() <= 50000)
            repeat(owners.length()) { conversations.put(owners.getJSONObject(it)) }
            repeat(rows.length()) { messages.put(rows.getJSONObject(it)) }
        }
        val payload = JSONObject().put("conversations", conversations).put("messages", messages).put("draft", "").toString()
        val result = JSONObject().put("format", "daiyu-neutral-text").put("version", 3)
            .put("conversation_count", conversations.length()).put("message_count", messages.length())
            .put("sha256", PrivateBlobs.digest(payload.toByteArray(Charsets.UTF_8))).put("payload", payload).toString()
        Utf8Payload.byteCount(result, 16L * 1024 * 1024)
        return result
    }
}
