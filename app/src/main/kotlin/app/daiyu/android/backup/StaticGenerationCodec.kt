package app.daiyu.android.backup

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import app.daiyu.android.domain.MessageBlock
import app.daiyu.android.model.generation.GenerationKind
import app.daiyu.android.model.generation.GenerationProtocol
import app.daiyu.android.storage.MessageHistoryCodec
import app.daiyu.android.storage.StaticGenerationDestination
import app.daiyu.android.storage.StaticGenerationOutput
import app.daiyu.android.storage.StaticGenerationProvenance
import org.json.JSONArray
import org.json.JSONObject

/** Codec for a distinct read-only kind. Production branch and complete-package v5 use this
 * same codec; v4 runtime_usage_history is unchanged. Schema installation belongs to the host's
 * existing BackupHistoryRows.install, never to a copied helper or a live attempt registration.
 */
internal object StaticGenerationCodec {
    const val KIND = "generation_static_output"
    const val MAX_RECORD_BYTES = 16 * 1024
    private val sourceFields = listOf("account_scope", "operation_id", "item_id", "output_index",
        "conversation_id", "assistant_id", "run_id", "tool_call_id", "tool_name", "fingerprint",
        "protocol", "receipt", "kind", "mime_type", "byte_count", "sha256", "width", "height", "duration_ms")
    private val destinationFields = listOf("account_scope", "conversation_id", "assistant_id", "run_id", "tool_call_id", "tool_name")

    /** Stable minimal JSON, including property order and escaping, independent of JSONObject's
     * map iteration order. Raw persisted records must be canonical: duplicates, trailing input,
     * coercions, alternate numeric syntax and unknown fields cannot survive decode.
     */
    fun encode(value: StaticGenerationOutput): String {
        val s = value.source; val d = value.destination
        val source = obj(sourceFields.zip(listOf(s.accountScope, s.operationId, s.itemId, s.outputIndex,
            s.conversationId, s.assistantMessageId, s.runId, s.toolCallId, s.toolName, s.fingerprint,
            s.protocol.name, s.receipt, s.kind.name, s.mimeType, s.bytes, s.sha256, s.width, s.height, s.durationMs)))
        val destination = obj(destinationFields.zip(d.fields()))
        return "{\"version\":1,\"kind\":${quote(KIND)},\"identity\":${quote(value.identity)},\"source\":$source,\"destination\":$destination}"
            .also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_RECORD_BYTES) }
    }

    fun decode(text: String): StaticGenerationOutput {
        require(text.toByteArray(Charsets.UTF_8).size in 1..MAX_RECORD_BYTES)
        return decode(JSONObject(text)).also { require(encode(it) == text) { "Noncanonical static record" } }
    }

    fun decode(row: JSONObject): StaticGenerationOutput = with(BackupHistoryRows) {
        require(row.toString().toByteArray(Charsets.UTF_8).size <= MAX_RECORD_BYTES)
        fields(row, listOf("version", "kind", "identity", "source", "destination"))
        require(number(row, "version") == 1L && text(row, "kind") == KIND)
        val s = row.getJSONObject("source"); val d = row.getJSONObject("destination")
        fields(s, sourceFields); fields(d, destinationFields)
        fun int(key: String): Int = number(s, key).also { require(it in 0..Int.MAX_VALUE.toLong()) }.toInt()
        val source = StaticGenerationProvenance(text(s, "account_scope"), text(s, "operation_id"), text(s, "item_id"),
            int("output_index"), text(s, "conversation_id"), text(s, "assistant_id"), text(s, "run_id"),
            text(s, "tool_call_id"), text(s, "tool_name"), text(s, "fingerprint"),
            GenerationProtocol.valueOf(text(s, "protocol")), text(s, "receipt"), GenerationKind.valueOf(text(s, "kind")),
            text(s, "mime_type"), number(s, "byte_count"), text(s, "sha256"), int("width"), int("height"), number(s, "duration_ms"))
        val destination = StaticGenerationDestination(text(d, "account_scope"), text(d, "conversation_id"),
            text(d, "assistant_id"), text(d, "run_id"), text(d, "tool_call_id"), text(d, "tool_name"))
        StaticGenerationOutput(source, destination).also { require(text(row, "identity") == it.identity) }
    }

    /** Call before any source read/allocation. These are leaf limits, NOT extra allowances:
     * parent must share its overall budget with images, video and other package contents.
     */
    fun validateBatch(outputs: List<StaticGenerationOutput>): Long = validateRecords(outputs).also {
        require(it <= NeutralBackupService.MAX_BYTES)
    }

    // Persisted metadata is bounded too, but the original-byte allowance belongs to ONE
    // operation, not the lifetime collection of independent conversations in this database.
    private fun validateRecords(outputs: List<StaticGenerationOutput>): Long {
        require(outputs.size <= BackupHistoryRows.MAX_ROWS)
        val identities = HashSet<String>(); val hashes = HashMap<String, Long>()
        var metadata = 0L
        outputs.forEach {
            require(identities.add(it.identity)) { "Duplicate static destination slot" }
            metadata += encode(it).toByteArray(Charsets.UTF_8).size + it.identity.length +
                it.destination.assistantMessageId.toByteArray(Charsets.UTF_8).size + KIND.length
            require(metadata <= BackupHistoryRows.MAX_METADATA_BYTES)
            val previous = hashes.put(it.source.sha256, it.source.bytes)
            require(previous == null || previous == it.source.bytes)
        }
        return hashes.values.sum()
    }

    fun encodeSection(outputs: List<StaticGenerationOutput>): JSONObject {
        validateBatch(outputs)
        return JSONObject().put("version", 1).put("outputs", JSONArray(outputs.sortedBy { it.identity }.map { JSONObject(encode(it)) }))
    }

    fun decodeSection(section: JSONObject, messages: JSONArray): List<StaticGenerationOutput> {
        BackupHistoryRows.fields(section, listOf("version", "outputs"))
        require(BackupHistoryRows.number(section, "version") == 1L)
        return BackupHistoryRows.rows(section.getJSONArray("outputs")).map(::decode).also { outputs ->
            validateBatch(outputs)
            outputs.forEach { validateDestination(it, messages) }
        }
    }

    /** Source owner need NOT exist: deletion of source is independent of the destination FK.
     * Only the destination's exact physical assistant + authoritative historical call is checked.
     * Parent additionally rechecks complete closed-run evidence/revision and all side-table CAS.
     */
    fun validateDestination(value: StaticGenerationOutput, messages: JSONArray) = with(BackupHistoryRows) {
        val d = value.destination
        val owners = messageMap(messages)
        require(owners.size == messages.length()) { "Duplicate message identity" }
        val ownerRow = JSONObject().put("conversation_id", d.conversationId).put("assistant_id", d.assistantMessageId).put("run_id", d.runId)
        owner(ownerRow, owners, "conversation_id", "assistant_id", "run_id")
        val message = owners.getValue(d.assistantMessageId)
        val history = if (message.has("history_segments")) MessageHistoryCodec.decode(message.getJSONArray("history_segments")) else emptyList()
        val names = if (history.isNotEmpty()) history.filter { it.role == "assistant" }.flatMap {
            require(it.messageId.value == d.assistantMessageId)
            it.blocks.filterIsInstance<MessageBlock.Tool>()
        }.filter { it.invocation.id.value == d.toolCallId }.map { it.invocation.name }
        else {
            val blocks = message.getJSONArray("blocks")
            (0 until blocks.length()).map { blocks.getJSONObject(it) }.filter {
                it.getString("type") == "tool" && it.getString("id") == d.toolCallId
            }.map { it.getString("name") }
        }
        require(names == listOf(d.toolName)) { "Static destination lacks exact historical tool source" }
    }

    /** Existing neutral_backup_history schema must be provisioned by the host. No helper/version
     * ownership here. Call INSIDE T2 after messages exist, and again verify AFTER the last write.
     * Idempotent insertion requires complete typed equality; never replace an existing slot.
     */
    fun saveInTransaction(db: SQLiteDatabase, value: StaticGenerationOutput, messages: JSONArray) {
        check(db.inTransaction())
        validateDestination(value, messages)
        validatePersistedOwner(db, value)
        // Hosts validate the complete operation with validateBatch BEFORE original I/O.
        // This per-record writer must not charge unrelated durable assets to that budget.
        validateBatch(listOf(value))
        val old = find(db, value.identity)
        if (old != null) { check(old == value); return }
        val values = ContentValues().apply {
            put("kind", KIND); put("identity", value.identity)
            put("owner_message_id", value.destination.assistantMessageId); put("payload", encode(value))
        }
        check(db.insertOrThrow("neutral_backup_history", null, values) != -1L)
        verifyInTransaction(db, value, messages)
    }

    fun verifyInTransaction(db: SQLiteDatabase, value: StaticGenerationOutput, messages: JSONArray) {
        check(db.inTransaction()); validateDestination(value, messages)
        check(find(db, value.identity) == value) { "Static history receipt mismatch" }
    }

    fun find(db: SQLiteDatabase, identity: String): StaticGenerationOutput? {
        require(identity.matches(Regex("genstatic-v1-[0-9a-f]{64}")))
        if (!BackupHistoryRows.exists(db, "neutral_backup_history")) return null
        return db.rawQuery("SELECT owner_message_id,CASE WHEN length(CAST(payload AS BLOB)) BETWEEN 1 AND $MAX_RECORD_BYTES THEN payload ELSE NULL END FROM neutral_backup_history WHERE kind=? AND identity=?",
            arrayOf(KIND, identity)).use { cursor ->
            if (!cursor.moveToFirst()) null else {
                require(cursor.getType(0) == android.database.Cursor.FIELD_TYPE_STRING &&
                    cursor.getType(1) == android.database.Cursor.FIELD_TYPE_STRING)
                decode(cursor.getString(1)).also {
                    require(it.identity == identity && it.destination.assistantMessageId == cursor.getString(0))
                    validatePersistedOwner(db, it)
                    check(!cursor.moveToNext())
                }
            }
        }
    }

    /** Full collection, NOT a UI limit. Bounds are checked by SQL before materializing payloads.
     * Reading metadata does not reserve original bytes; exporting/copying still validates its batch.
     */
    fun read(db: SQLiteDatabase, conversationId: String? = null): List<StaticGenerationOutput> =
        readPersisted(db,
            " WHERE kind=?" + if (conversationId == null) "" else " AND owner_message_id IN (SELECT id FROM messages WHERE conversation_id=?)",
            if (conversationId == null) arrayOf(KIND) else arrayOf(KIND, conversationId)).also { outputs ->
            require(conversationId == null || outputs.all { it.destination.conversationId == conversationId })
        }

    /** Exact physical package scope, including unexpected rows. Apply the owner predicate to
     * BOTH the SQL bounds query and payload query, never decode unrelated rows then filter.
     */
    fun readForOwners(db: SQLiteDatabase, owners: Set<String>): List<StaticGenerationOutput> {
        require(owners.size <= BackupHistoryRows.MAX_ROWS)
        val outputs = mutableListOf<StaticGenerationOutput>()
        // Stay below SQLite's bind-variable limit, even for a 50,000-message package.
        owners.toList().chunked(400).forEach { batch ->
            val rows = readPersisted(db, " WHERE kind=? AND owner_message_id IN (${batch.joinToString(",") { "?" }})",
                (listOf(KIND) + batch).toTypedArray())
            outputs.addAll(rows)
            // Each SQL chunk and the accumulated metadata remain bounded, not just the last one.
            validateRecords(outputs)
        }
        return outputs
    }

    private fun readPersisted(db: SQLiteDatabase, where: String, args: Array<String>): List<StaticGenerationOutput> {
        val rows = BackupHistoryRows.read(db, "neutral_backup_history", listOf("kind", "identity", "owner_message_id", "payload"),
            where, args)
        return rows.map { row ->
            decode(BackupHistoryRows.text(row, "payload")).also {
                require(it.identity == BackupHistoryRows.text(row, "identity") &&
                    it.destination.assistantMessageId == BackupHistoryRows.text(row, "owner_message_id"))
                validatePersistedOwner(db, it)
            }
        }.also { validateRecords(it) }
    }

    private fun validatePersistedOwner(db: SQLiteDatabase, value: StaticGenerationOutput) {
        val limit = BackupHistoryRows.MAX_METADATA_BYTES
        db.rawQuery("SELECT conversation_id,run_id,role," +
            "CASE WHEN length(CAST(blocks AS BLOB)) <= $limit THEN blocks ELSE NULL END," +
            "CASE WHEN length(CAST(history_segments AS BLOB)) <= $limit THEN history_segments ELSE NULL END " +
            "FROM messages WHERE id=?", arrayOf(value.destination.assistantMessageId)).use { cursor ->
            require(cursor.moveToFirst() && !cursor.isNull(3) && !cursor.isNull(4)) { "Missing/bounded static owner" }
            val message = JSONObject().put("id", value.destination.assistantMessageId)
                .put("conversation_id", cursor.getString(0)).put("run_id", cursor.getString(1)).put("role", cursor.getString(2))
                .put("blocks", JSONArray(cursor.getString(3))).put("history_segments", JSONArray(cursor.getString(4)))
            validateDestination(value, JSONArray().put(message))
            check(!cursor.moveToNext())
        }
    }

    private fun obj(fields: List<Pair<String, Any>>): String = fields.joinToString(",", "{", "}") { (key, value) ->
        quote(key) + ":" + when (value) {
            is String -> quote(value)
            is Int, is Long -> value.toString()
            else -> error("Non-scalar static field")
        }
    }
    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { c -> when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            else -> if (c < ' ') append("\\u" + c.code.toString(16).padStart(4, '0')) else append(c)
        } }
        append('"')
    }
}
