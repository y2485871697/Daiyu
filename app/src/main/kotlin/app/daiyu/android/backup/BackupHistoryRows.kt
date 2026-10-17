package app.daiyu.android.backup

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject

/** Backup-owned history only. Does not own the application helper/version or runtime schema. */
internal object BackupHistoryRows {
    const val MAX_ROWS = 50_000
    const val MAX_METADATA_BYTES = 8 * 1024 * 1024
    fun fields(row: JSONObject, keys: Collection<String>) {
        require(row.keys().asSequence().toSet() == keys.toSet()) { "备份历史字段不完整或未知" }
    }
    fun text(row: JSONObject, key: String): String = row.get(key) as? String ?: error("历史字段须为文本")
    fun number(row: JSONObject, key: String): Long {
        val n = row.get(key); require(n is Int || n is Long); return (n as Number).toLong()
    }
    fun id(row: JSONObject, key: String): String = text(row, key).also {
        require(it.isNotBlank() && it.length <= 200 && !it.contains('/') && !it.contains('\\') && it !in setOf(".", ".."))
    }
    fun hash(row: JSONObject, key: String): String = text(row, key).also { require(it.matches(Regex("[0-9a-f]{64}"))) }
    fun rows(array: JSONArray): List<JSONObject> {
        require(array.length() <= MAX_ROWS)
        require(array.toString().toByteArray(Charsets.UTF_8).size <= MAX_METADATA_BYTES)
        return (0 until array.length()).map { array.getJSONObject(it) }
    }
    fun exists(db: SQLiteDatabase, table: String): Boolean = db.rawQuery(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)).use { it.moveToFirst() }
    fun read(db: SQLiteDatabase, table: String, columns: List<String>, where: String = "", args: Array<String>? = null,
        order: String = columns.joinToString(",")): List<JSONObject> {
        if (!exists(db, table)) return emptyList()
        val byteExpression = columns.joinToString("+") { "coalesce(length(CAST($it AS BLOB)),0)" }
        db.rawQuery("SELECT count(*),coalesce(sum($byteExpression),0) FROM $table$where", args).use { bounds ->
            check(bounds.moveToFirst() && bounds.getLong(0) <= MAX_ROWS && bounds.getLong(1) <= MAX_METADATA_BYTES) {
                "备份历史元数据超限"
            }
        }
        return db.rawQuery("SELECT ${columns.joinToString(",")} FROM $table$where ORDER BY $order LIMIT ${MAX_ROWS + 1}", args).use { c ->
            buildList {
                while (c.moveToNext()) {
                    require(size < MAX_ROWS) { "备份历史数量超限" }
                    val row = JSONObject()
                    columns.forEachIndexed { i, key -> row.put(key, when {
                        c.isNull(i) -> JSONObject.NULL
                        c.getType(i) == Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                        c.getType(i) == Cursor.FIELD_TYPE_STRING -> c.getString(i)
                        else -> error("历史字段类型无效")
                    }) }
                    add(row)
                }
            }
        }
    }
    fun same(a: JSONObject, b: JSONObject): Boolean = a.keys().asSequence().toSet() == b.keys().asSequence().toSet() &&
        a.keys().asSequence().all { key -> a.get(key).toString() == b.get(key).toString() }
    fun verify(db: SQLiteDatabase, table: String, row: JSONObject) {
        val keys = row.keys().asSequence().toList()
        val args = keys.map { if (row.isNull(it)) null else row.get(it).toString() }.toTypedArray()
        check(db.rawQuery("SELECT 1 FROM $table WHERE " + keys.joinToString(" AND ") { "$it IS ?" } + " LIMIT 1", args)
            .use { it.moveToFirst() }) { "备份历史回执不匹配" }
    }
    fun insert(db: SQLiteDatabase, table: String, row: JSONObject) {
        val values = ContentValues()
        row.keys().forEach { key -> when (val value = row.get(key)) {
            JSONObject.NULL -> values.putNull(key)
            is String -> values.put(key, value)
            is Int -> values.put(key, value)
            is Long -> values.put(key, value)
            else -> error("历史字段类型无效")
        } }
        check(db.insertOrThrow(table, null, values) != -1L) { "备份历史未写入" }
        verify(db, table, row)
    }
    private fun install(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS neutral_backup_history(kind TEXT NOT NULL,identity TEXT NOT NULL," +
            "owner_message_id TEXT NOT NULL REFERENCES messages(id) ON DELETE CASCADE,payload TEXT NOT NULL,PRIMARY KEY(kind,identity))")
    }
    fun historyBudget(kind: String, rows: List<JSONObject>, identity: (JSONObject) -> String) {
        val bytes = rows.sumOf { row ->
            row.toString().toByteArray(Charsets.UTF_8).size.toLong() + identity(row).toByteArray(Charsets.UTF_8).size +
                historyOwner(kind, row).toByteArray(Charsets.UTF_8).size + kind.length
        }
        require(bytes <= MAX_METADATA_BYTES) { "备份只读历史元数据超限" }
    }
    fun saveHistory(db: SQLiteDatabase, kind: String, identity: String, row: JSONObject) {
        install(db)
        insert(db, "neutral_backup_history", historyRow(kind, identity, row))
    }
    private fun historyOwner(kind: String, row: JSONObject): String = when (kind) {
        "generation_output" -> id(row, "assistant_id")
        "child_task" -> id(row, "parent_message_id")
        else -> error("未知只读历史种类")
    }
    private fun historyRow(kind: String, identity: String, row: JSONObject) = JSONObject().put("kind", kind)
        .put("identity", identity).put("owner_message_id", historyOwner(kind, row)).put("payload", row.toString())
    fun history(db: SQLiteDatabase, kind: String, conversation: String? = null): Map<String, JSONObject> = read(db, "neutral_backup_history",
        listOf("kind", "identity", "owner_message_id", "payload"),
        " WHERE kind=?" + if (conversation == null) "" else " AND owner_message_id IN (SELECT id FROM messages WHERE conversation_id=?)",
        if (conversation == null) arrayOf(kind) else arrayOf(kind, conversation)).associate {
        val row = JSONObject(text(it, "payload"))
        require(text(it, "owner_message_id") == historyOwner(kind, row)) { "只读历史所有者引用不匹配" }
        text(it, "identity") to row
    }
    fun verifyHistory(db: SQLiteDatabase, kind: String, identity: String, row: JSONObject) {
        verify(db, "neutral_backup_history", historyRow(kind, identity, row))
    }
    fun owner(row: JSONObject, messages: Map<String, JSONObject>, conversationKey: String, messageKey: String, runKey: String) {
        val message = messages[id(row, messageKey)] ?: error("备份历史缺少准确父消息")
        require(id(row, conversationKey) == message.getString("conversation_id") && id(row, runKey) == message.getString("run_id") &&
            message.getString("role") == "assistant") { "备份历史父消息归属不匹配" }
    }
    fun messageMap(messages: JSONArray): Map<String, JSONObject> {
        require(messages.length() <= MAX_ROWS)
        return (0 until messages.length()).map { messages.getJSONObject(it) }.associateBy { text(it, "id") }
    }
}
