package app.daiyu.android.backup

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject

/** The existing usage receipts only: never reconstruct a runtime checkpoint or infer a source. */
internal object BackupUsageCodec {
    val legacyColumns = listOf("request_id", "run_id", "branch_id", "input_tokens", "output_tokens", "cached_tokens", "reasoning_tokens")
    private val metadata = listOf("usage_source", "request_owner", "attempt_id", "parent_message_id", "has_usage",
        "process_owner", "usage_state", "usage_revision", "operation_id", "terminal_reason")
    val typedColumns = legacyColumns + metadata
    val columns = typedColumns + "cache_creation_tokens"
    private val tokens = listOf("input_tokens", "output_tokens", "cached_tokens", "reasoning_tokens", "cache_creation_tokens")
    private val reasons = setOf("SUCCEEDED", "FAILED", "CANCELLED", "UNKNOWN")

    fun export(db: SQLiteDatabase, conversation: String?): JSONArray {
        val rows = BackupHistoryRows.read(db, "context_usage", columns,
            if (conversation == null) "" else " WHERE branch_id=?", conversation?.let { arrayOf(it) }, "request_id")
        return JSONArray(rows.map { row ->
            // Close only the serialized observation. Preserve all identities and provider presence,
            // including missing usage versus a present snapshot whose token fields are all NULL.
            if (!row.isNull("usage_state") && BackupHistoryRows.text(row, "usage_state") == "ADMITTED") {
                require(row.isNull("terminal_reason"))
                require(BackupHistoryRows.text(row, "usage_source") in setOf("TITLE", "AUXILIARY_VISION", "MANUAL_SUMMARY"))
                JSONObject(row.toString()).apply {
                    val revision = BackupHistoryRows.number(row, "usage_revision")
                    require(revision >= 1L && revision < Long.MAX_VALUE - 1L)
                    put("usage_state", "TERMINAL"); put("terminal_reason", "UNKNOWN"); put("usage_revision", revision + 1)
                }
            } else row
        })
    }

    fun validate(rows: JSONArray, version: Long, conversations: Set<String>, messages: JSONArray) = with(BackupHistoryRows) {
        val parents = messageMap(messages)
        val requests = mutableSetOf<String>(); val operations = mutableSetOf<Pair<String, String>>()
        require(rows.length() <= MAX_ROWS)
        repeat(rows.length()) { index ->
            val row = rows.getJSONObject(index)
            fields(row, if (version >= 6) columns else if (version >= 3) typedColumns else legacyColumns)
            require(requests.add(id(row, "request_id")))
            require(id(row, "branch_id") in conversations)
            tokens.filter { row.has(it) }.forEach { if (!row.isNull(it)) require(number(row, it) >= 0) }
            if (version >= 3 && text(row, "usage_source") != "LEGACY" &&
                !row.isNull("input_tokens") && !row.isNull("cached_tokens"))
                require(number(row, "cached_tokens") <= number(row, "input_tokens"))
            if (version >= 6 && text(row, "usage_source") != "LEGACY") {
                fun token(key: String): Long? = if (row.isNull(key)) null else number(row, key)
                app.daiyu.android.domain.ModelEvent.Usage(token("input_tokens"), token("output_tokens"),
                    token("cached_tokens"), token("cache_creation_tokens"))
            }
            if (version < 3 || text(row, "usage_source") == "LEGACY") {
                fun token(key: String): Long? = if (row.isNull(key)) null else number(row, key)
                app.daiyu.android.domain.context.UsageRecord(text(row, "request_id"), text(row, "run_id"), text(row, "branch_id"),
                    token("input_tokens"), token("output_tokens"), token("cached_tokens"), token("reasoning_tokens"))
                if (version >= 3) metadata.filter { it != "usage_source" }.forEach { require(row.isNull(it)) }
            } else {
                val source = text(row, "usage_source")
                require(source in setOf("TITLE", "AUXILIARY_VISION", "MANUAL_SUMMARY"))
                id(row, "request_owner"); id(row, "attempt_id"); id(row, "process_owner")
                require(number(row, "has_usage") in 0L..1L && number(row, "usage_revision") in 1L until Long.MAX_VALUE)
                // A complete package is already a closed history; ADMITTED is export input only.
                require(text(row, "usage_state") == "TERMINAL")
                if (number(row, "has_usage") == 0L) require(tokens.all { row.isNull(it) })
                if (source == "MANUAL_SUMMARY") {
                    require(row.isNull("run_id") && row.isNull("parent_message_id"))
                    require(operations.add(id(row, "branch_id") to id(row, "operation_id")))
                    require(text(row, "terminal_reason") in reasons)
                } else {
                    owner(row, parents, "branch_id", "parent_message_id", "run_id")
                    require(row.isNull("operation_id"))
                    require(row.isNull("terminal_reason") || text(row, "terminal_reason") in reasons)
                }
            }
        }
    }

    /** Old packages have no typed attribution; use exactly the helper's LEGACY defaults. */
    fun verify(db: SQLiteDatabase, rows: JSONArray, version: Long) {
        repeat(rows.length()) { index ->
            val expected = JSONObject(rows.getJSONObject(index).toString())
            if (version < 3) metadata.forEach { expected.put(it, if (it == "usage_source") "LEGACY" else JSONObject.NULL) }
            if (version < 6) expected.put("cache_creation_tokens", JSONObject.NULL)
            verifyRow(db, "context_usage", expected, listOf("request_id"))
        }
    }

    /** Compare storage class as well as value: SQL affinity / IS ? alone is insufficient. */
    fun verifyRow(db: SQLiteDatabase, table: String, expected: JSONObject, identity: List<String>) {
        val keys = expected.keys().asSequence().toList()
        val args = identity.map { BackupHistoryRows.text(expected, it) }.toTypedArray()
        db.rawQuery("SELECT ${keys.joinToString(",")} FROM $table WHERE " + identity.joinToString(" AND ") { "$it=?" }, args).use { c ->
            check(c.moveToFirst()) { "usage 历史回执缺失" }
            keys.forEachIndexed { index, key ->
                val value = expected.get(key)
                check(when (value) {
                    JSONObject.NULL -> c.isNull(index)
                    is String -> c.getType(index) == Cursor.FIELD_TYPE_STRING && c.getString(index) == value
                    is Int, is Long -> c.getType(index) == Cursor.FIELD_TYPE_INTEGER && c.getLong(index) == (value as Number).toLong()
                    else -> false
                }) { "usage 历史回执类型或值不匹配" }
            }
            check(!c.moveToNext()) { "usage 历史回执重复" }
        }
    }
}
