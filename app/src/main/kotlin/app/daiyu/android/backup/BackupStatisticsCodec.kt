package app.daiyu.android.backup

import android.database.sqlite.SQLiteDatabase
import app.daiyu.android.storage.UsageStatisticsStore
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** Static accounting only. Local initialization and launch count are deliberately not portable. */
internal object BackupStatisticsCodec {
    private const val RECEIPTS = "conversation_usage_receipts"
    private val receiptColumns = listOf("conversation_id", "request_owner", "request_id", "input_tokens", "output_tokens",
        "cached_tokens", "cache_creation_tokens", "window_tokens")
    private val columns = linkedMapOf(UsageStatisticsStore.REQUESTS to UsageStatisticsStore.requestColumns,
        UsageStatisticsStore.CONVERSATIONS to UsageStatisticsStore.conversationColumns,
        UsageStatisticsStore.RETIRED_MESSAGES to listOf("message_id", "conversation_id"), RECEIPTS to receiptColumns)
    private val identities = mapOf(UsageStatisticsStore.REQUESTS to listOf("id"),
        UsageStatisticsStore.CONVERSATIONS to listOf("conversation_id"), UsageStatisticsStore.RETIRED_MESSAGES to listOf("message_id"), RECEIPTS to receiptColumns.take(3))
    private val names = mapOf(UsageStatisticsStore.REQUESTS to "requests", UsageStatisticsStore.CONVERSATIONS to "conversations", UsageStatisticsStore.RETIRED_MESSAGES to "retired_messages", RECEIPTS to "receipts")
    data class Validated(val tables: Map<String, List<JSONObject>>, val textConversations: Set<String>)
    data class Receipt(val expected: Map<String, List<JSONObject>>)

    fun export(db: SQLiteDatabase, conversation: String?): JSONObject = JSONObject().put("version", 1).apply {
        columns.forEach { (table, fields) ->
            put(names.getValue(table), JSONArray(BackupHistoryRows.read(db, table, fields,
                if (conversation == null) "" else " WHERE conversation_id=?", conversation?.let { arrayOf(it) },
                identities.getValue(table).joinToString(","))))
        }
    }
    fun validate(section: JSONObject?, textConversations: Set<String>): Validated? {
        if (section == null) return null
        BackupHistoryRows.fields(section, listOf("version", "requests", "conversations", "retired_messages", "receipts"))
        require(BackupHistoryRows.number(section, "version") == 1L)
        val tables = columns.mapValues { (table, fields) ->
            val seen = mutableSetOf<String>()
            BackupHistoryRows.rows(section.getJSONArray(names.getValue(table))).onEach { row ->
                BackupHistoryRows.fields(row, fields)
                fields.forEach { field ->
                    if (!row.isNull(field)) {
                        if (field.endsWith("_tokens") || field in setOf("at_millis", "created_at", "retired_messages")) {
                            val value = BackupHistoryRows.number(row, field)
                            require(field == "window_tokens" || value >= 0)
                            if (field == "retired_messages") require(value <= Int.MAX_VALUE)
                        } else require(BackupHistoryRows.text(row, field).let { it.isNotBlank() && it.length <= 400 })
                    }
                }
                identities.getValue(table).forEach { require(!row.isNull(it)) }
                require(seen.add(key(table, row))) { "统计身份重复" }
                if (table == RECEIPTS) require(BackupHistoryRows.text(row, "conversation_id") in textConversations)
                if (table == UsageStatisticsStore.REQUESTS) {
                    val modelFields = listOf("provider_id", "provider_name", "model_id", "model_name", "at_millis")
                    require(modelFields.all { row.isNull(it) } || modelFields.none { row.isNull(it) })
                }
                if (table == UsageStatisticsStore.CONVERSATIONS) {
                    require(row.isNull("retired_messages") == row.isNull("retired_day"))
                    if (!row.isNull("retired_day")) LocalDate.parse(BackupHistoryRows.text(row, "retired_day"))
                    require(row.isNull("retired_messages") || BackupHistoryRows.text(row, "conversation_id") !in textConversations)
                }
            }
        }
        return Validated(tables, textConversations)
    }

    /** Freeze the target before any import writes. Identity collisions must be equal facts,
     * never max/replace/add: another backup may contain exactly the same retired requests. */
    fun prepare(db: SQLiteDatabase, incoming: Validated?): Receipt? {
        if (incoming == null) return null
        val expected = columns.mapValues { (table, fields) ->
            val rows = BackupHistoryRows.read(db, table, fields).associateByTo(linkedMapOf()) { key(table, it) }
            incoming.tables.getValue(table).forEach { row ->
                val previous = rows[key(table, row)]
                require(previous == null || same(fields, previous, row)) { "统计身份冲突，未覆盖现有账目" }
                rows[key(table, row)] = row
            }
            require(rows.size <= BackupHistoryRows.MAX_ROWS)
            rows.values.toList()
        }
        return Receipt(expected)
    }
    fun restore(db: SQLiteDatabase, incoming: Validated?) {
        if (incoming == null) return
        check(db.inTransaction())
        incoming.tables.forEach { (table, rows) -> rows.forEach { row ->
            val identity = identities.getValue(table)
            val exists = db.rawQuery("SELECT 1 FROM $table WHERE " + identity.joinToString(" AND ") { "$it=?" },
                identity.map { BackupHistoryRows.text(row, it) }.toTypedArray()).use { it.moveToFirst() }
            if (exists) BackupUsageCodec.verifyRow(db, table, row, identity)
            else BackupHistoryRows.insert(db, table, row)
        } }
    }
    fun verify(db: SQLiteDatabase, incoming: Validated?, receipt: Receipt? = null) {
        if (incoming == null) return
        incoming.tables.forEach { (table, rows) -> rows.forEach { BackupUsageCodec.verifyRow(db, table, it, identities.getValue(table)) } }
        // Receipts are conversation-owned, including authoritative empty sets in v8 packages.
        val actualReceipts = BackupHistoryRows.read(db, RECEIPTS, receiptColumns).filter {
            BackupHistoryRows.text(it, "conversation_id") in incoming.textConversations
        }
        check(equalRows(RECEIPTS, incoming.tables.getValue(RECEIPTS), actualReceipts)) { "会话用量收据集合不匹配" }
        if (receipt != null) receipt.expected.forEach { (table, expected) ->
            val actual = BackupHistoryRows.read(db, table, columns.getValue(table))
            check(equalRows(table, expected, actual)) { "统计导入集合不匹配" }
        }
    }
    private fun equalRows(table: String, expected: List<JSONObject>, actual: List<JSONObject>): Boolean {
        if (expected.size != actual.size) return false
        val indexed = actual.associateBy { key(table, it) }
        return expected.all { row -> indexed[key(table, row)]?.let { same(columns.getValue(table), row, it) } == true }
    }
    private fun same(fields: List<String>, a: JSONObject, b: JSONObject) = fields.all { field ->
        val left = a.get(field); val right = b.get(field)
        when {
            left === JSONObject.NULL || right === JSONObject.NULL -> left === JSONObject.NULL && right === JSONObject.NULL
            left is String -> right is String && left == right
            left is Int || left is Long -> (right is Int || right is Long) && (left as Number).toLong() == (right as Number).toLong()
            else -> false
        }
    }
    private fun key(table: String, row: JSONObject): String = JSONArray(identities.getValue(table).map { row.get(it) }).toString()
}
