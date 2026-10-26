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
    data class RetirementRestore(val before: JSONObject, val after: JSONObject)
    data class Receipt(val expected: Map<String, List<JSONObject>>,
        val retirementRestores: Map<String, RetirementRestore> = emptyMap(),
        val legacyRetirementRestores: List<UsageStatisticsStore.RetirementSnapshot> = emptyList(),
        val legacyRequests: List<JSONObject> = emptyList())

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

    /** Freeze accounting before import writes. Only an explicit restoration of an absent
     * live conversation may reverse retirement; request identities remain immutable. */
    fun prepare(db: SQLiteDatabase, incoming: Validated?, restoringConversations: Set<String> = emptySet()): Receipt? {
        if (incoming == null) {
            if (restoringConversations.isEmpty()) return null
            val restores = UsageStatisticsStore.prepareRetirementRestores(db, restoringConversations)
            val retained = restores.map { it.conversation }.toSet()
            return if (restores.isEmpty()) null else Receipt(emptyMap(), legacyRetirementRestores = restores,
                legacyRequests = BackupHistoryRows.read(db, UsageStatisticsStore.REQUESTS, UsageStatisticsStore.requestColumns)
                    .filter { !it.isNull("conversation_id") && BackupHistoryRows.text(it, "conversation_id") in retained })
        }
        check(db.inTransaction())
        val retirementRestores = linkedMapOf<String, RetirementRestore>()
        val expected = columns.mapValues { (table, fields) ->
            val rows = BackupHistoryRows.read(db, table, fields).associateByTo(linkedMapOf()) { key(table, it) }
            incoming.tables.getValue(table).forEach { row ->
                val identity = key(table, row)
                val previous = rows[identity]
                if (previous != null && !same(fields, previous, row)) {
                    val conversation = if (table == UsageStatisticsStore.CONVERSATIONS)
                        BackupHistoryRows.text(row, "conversation_id") else null
                    require(conversation != null && conversation in incoming.textConversations &&
                        conversation in restoringConversations &&
                        !previous.isNull("retired_messages") && !previous.isNull("retired_day") &&
                        row.isNull("retired_messages") && row.isNull("retired_day") &&
                        same(fields.filterNot { it == "retired_messages" || it == "retired_day" }, previous, row) &&
                        !db.rawQuery("SELECT 1 FROM conversations WHERE id=?", arrayOf(conversation)).use { it.moveToFirst() }
                    ) { "统计身份冲突，未覆盖现有账目" }
                    retirementRestores[identity] = RetirementRestore(previous, row)
                }
                rows[identity] = row
            }
            if (table == UsageStatisticsStore.CONVERSATIONS) {
                val supplied = incoming.tables.getValue(table).map { key(table, it) }.toSet()
                rows.toMap().forEach { (identity, before) ->
                    val conversation = BackupHistoryRows.text(before, "conversation_id")
                    if (identity !in supplied && conversation in incoming.textConversations &&
                        conversation in restoringConversations && !before.isNull("retired_messages")) {
                        require(!before.isNull("retired_day") && !db.rawQuery(
                            "SELECT 1 FROM conversations WHERE id=?", arrayOf(conversation)).use { it.moveToFirst() }
                        ) { "统计身份冲突，未覆盖现有账目" }
                        val after = JSONObject(before.toString()).put("retired_messages", JSONObject.NULL)
                            .put("retired_day", JSONObject.NULL)
                        retirementRestores[identity] = RetirementRestore(before, after)
                        rows[identity] = after
                    }
                }
            }
            require(rows.size <= BackupHistoryRows.MAX_ROWS)
            rows.values.toList()
        }
        return Receipt(expected, retirementRestores)
    }
    fun restore(db: SQLiteDatabase, incoming: Validated?, receipt: Receipt? = null) {
        if (incoming == null) {
            receipt?.legacyRetirementRestores.orEmpty().forEach { UsageStatisticsStore.restoreRetirement(db, it) }
            return
        }
        check(db.inTransaction())
        incoming.tables.forEach { (table, rows) -> rows.forEach { row ->
            val identity = identities.getValue(table)
            val retirement = if (table == UsageStatisticsStore.CONVERSATIONS)
                receipt?.retirementRestores?.get(key(table, row)) else null
            if (retirement != null) BackupUsageCodec.verifyRow(db, table, retirement.before, identity)
            else {
                val exists = db.rawQuery("SELECT 1 FROM $table WHERE " + identity.joinToString(" AND ") { "$it=?" },
                    identity.map { BackupHistoryRows.text(row, it) }.toTypedArray()).use { it.moveToFirst() }
                if (exists) BackupUsageCodec.verifyRow(db, table, row, identity)
                else BackupHistoryRows.insert(db, table, row)
            }
        } }
        receipt?.retirementRestores?.values?.forEach { retirement ->
            val before = retirement.before
            // Later inserts may run triggers. Recheck the frozen row immediately before CAS.
            BackupUsageCodec.verifyRow(db, UsageStatisticsStore.CONVERSATIONS, before,
                identities.getValue(UsageStatisticsStore.CONVERSATIONS))
            UsageStatisticsStore.restoreRetirement(db, BackupHistoryRows.text(before, "conversation_id"),
                if (before.isNull("created_at")) null else BackupHistoryRows.number(before, "created_at"),
                BackupHistoryRows.number(before, "retired_messages"), BackupHistoryRows.text(before, "retired_day"))
            BackupUsageCodec.verifyRow(db, UsageStatisticsStore.CONVERSATIONS, retirement.after,
                identities.getValue(UsageStatisticsStore.CONVERSATIONS))
        }
    }
    fun verify(db: SQLiteDatabase, incoming: Validated?, receipt: Receipt? = null) {
        if (incoming == null) {
            val restored = receipt?.legacyRetirementRestores.orEmpty()
            restored.forEach { UsageStatisticsStore.verifyRetirementRestore(db, it) }
            if (restored.isNotEmpty()) {
                val retained = receipt!!.legacyRequests.map { BackupHistoryRows.text(it, "conversation_id") }.toSet()
                val actual = BackupHistoryRows.read(db, UsageStatisticsStore.REQUESTS, UsageStatisticsStore.requestColumns)
                    .filter { !it.isNull("conversation_id") && BackupHistoryRows.text(it, "conversation_id") in retained }
                check(equalRows(UsageStatisticsStore.REQUESTS, receipt.legacyRequests, actual)) { "统计身份冲突，未覆盖现有账目" }
            }
            return
        }
        incoming.tables.forEach { (table, rows) -> rows.forEach { BackupUsageCodec.verifyRow(db, table, it, identities.getValue(table)) } }
        // Receipts are conversation-owned, including authoritative empty sets in v8 packages.
        val actualReceipts = BackupHistoryRows.read(db, RECEIPTS, receiptColumns).filter {
            BackupHistoryRows.text(it, "conversation_id") in incoming.textConversations
        }
        check(equalRows(RECEIPTS, incoming.tables.getValue(RECEIPTS), actualReceipts)) { "会话用量收据集合不匹配" }
        receipt?.retirementRestores?.values?.forEach { retirement ->
            UsageStatisticsStore.verifyRestoredRetirement(db,
                BackupHistoryRows.text(retirement.before, "conversation_id"),
                BackupHistoryRows.number(retirement.before, "retired_messages"))
        }
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
