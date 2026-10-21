package app.daiyu.android.backup

import android.database.sqlite.SQLiteDatabase
import app.daiyu.android.domain.runtime.*
import app.daiyu.android.storage.RecordedUsageHistory
import app.daiyu.android.storage.RunCheckpointCodec
import app.daiyu.android.storage.SQLiteRuntimeStore
import org.json.JSONArray
import org.json.JSONObject

/** One-way allowlist projection. Checkpoints are decoded locally, NEVER copied into a package.
 * Import writes only conversation-owned inert observations installed by the sole DB helper. */
internal object BackupRuntimeUsageCodec {
    private val parentColumns = RecordedUsageHistory.parentColumns
    private val requestColumns = RecordedUsageHistory.requestColumns
    private val tokens = listOf("input_tokens", "output_tokens", "cached_tokens", "cache_creation_tokens")
    data class Validated(val checkpoints: List<JSONObject>, val requests: List<JSONObject>,
        val conversations: Set<String>)
    private fun key(row: JSONObject) = BackupHistoryRows.text(row, "request_owner") to BackupHistoryRows.text(row, "request_id")

    fun export(db: SQLiteDatabase, conversation: String?): JSONObject = with(BackupHistoryRows) {
        check(db.inTransaction())
        // Include either persisted ownership or physical parent ownership so a corrupt
        // conversation_id cannot hide a row from a conversation-only export.
        val where = if (conversation == null) "" else " WHERE (conversation_id=? OR message_id IN (SELECT id FROM messages WHERE conversation_id=?))"
        val args = conversation?.let { arrayOf(it, it) }
        val parents = read(db, RecordedUsageHistory.PARENTS, parentColumns, where, args, "message_id")
            .associateBy { text(it, "message_id") }.toMutableMap()
        val requests = read(db, RecordedUsageHistory.REQUESTS, requestColumns, where, args, "request_owner,request_id")
            .associateBy(::key).toMutableMap()
        fun addParent(row: JSONObject) {
            val previous = parents.put(text(row, "message_id"), row)
            require(previous == null || same(previous, row)) { "运行用量历史父身份冲突" }
        }
        fun add(row: JSONObject) {
            val previous = requests.put(key(row), row)
            require(previous == null || same(previous, row)) { "运行用量历史请求身份冲突" }
            require(requests.size <= MAX_ROWS)
        }
        val runtimeWhere = if (conversation == null) "" else " WHERE (r.conversation_id=? OR r.message_id IN (SELECT id FROM messages WHERE conversation_id=?))"
        // Bound the entire source, not the report reader's LIMIT or payload budget. Fail, never truncate.
        db.rawQuery("SELECT count(*),coalesce(sum(length(CAST(r.payload AS BLOB))),0) FROM runtime_checkpoints r$runtimeWhere", args).use {
            require(it.moveToFirst() && it.getLong(0) <= MAX_ROWS && it.getLong(1) <= 128L * 1024 * 1024) { "运行用量源快照超限" }
        }
        db.rawQuery("SELECT r.message_id,r.conversation_id,r.run_id,m.conversation_id,m.run_id,m.status,m.role," +
            "length(CAST(r.payload AS BLOB)),typeof(r.payload) FROM runtime_checkpoints r LEFT JOIN messages m ON m.id=r.message_id$runtimeWhere ORDER BY r.message_id", args).use { c ->
            while (c.moveToNext()) {
                require(c.getString(8) == "text" && c.getLong(7) in 1..RunCheckpointCodec.MAX_BYTES.toLong())
                val saved = requireNotNull(SQLiteRuntimeStore.readCheckpointSnapshot(db, c.getString(0)))
                val m = saved.message
                require(m.id.value == c.getString(0) && m.conversationId.value == c.getString(1) && m.runId.value == c.getString(2) &&
                    m.conversationId.value == c.getString(3) && m.runId.value == c.getString(4) && m.status.name == c.getString(5) && c.getString(6) == "assistant")
                addParent(JSONObject().put("message_id", m.id.value).put("conversation_id", m.conversationId.value).put("run_id", m.runId.value))
                val admitted = saved.usageRequests.ifEmpty { listOfNotNull(saved.request?.takeIf { it.operation != RequestOperation.OPEN }) }
                val reports = saved.usage.associateBy { it.request.owner to it.request.requestId }
                admitted.forEach { request ->
                    val report = reports[request.owner to request.requestId]
                    require(report == null || report.request == request && report.source == UsageRequestSource.MAIN)
                    add(observation("CHECKPOINT", m.id.value, m.conversationId.value, m.runId.value, m.id.value,
                        request, UsageRequestSource.MAIN, report?.snapshot))
                }
                saved.usage.forEach { report ->
                    require(report.source in setOf(UsageRequestSource.MAIN, UsageRequestSource.SUMMARY))
                    if (admitted.none { it.owner == report.request.owner && it.requestId == report.request.requestId })
                        add(observation("CHECKPOINT", m.id.value, m.conversationId.value, m.runId.value, m.id.value,
                            report.request, report.source, report.snapshot))
                }
            }
        }
        val preparationColumns = listOf("message_id", "conversation_id", "run_id", "owner", "process_owner", "revision", "state",
            "request_id", "request_owner", "attempt_id", "has_usage") + tokens
        read(db, "runtime_preparations", preparationColumns, where, args, "message_id").forEach { p ->
            val message = id(p, "message_id"); val owner = id(p, "conversation_id"); val run = id(p, "run_id")
            id(p, "owner"); id(p, "process_owner"); require(number(p, "revision") in 1L until Long.MAX_VALUE)
            val state = text(p, "state"); require(state in setOf("ADMITTED", "READY", "TERMINAL", "HANDED"))
            val presence = number(p, "has_usage"); require(presence in 0L..1L)
            tokens.forEach { if (!p.isNull(it)) require(number(p, it) >= 0) }
            require(presence == 1L && state != "HANDED" || tokens.all { p.isNull(it) })
            val request = RequestCheckpoint(id(p, "request_id"), id(p, "attempt_id"), id(p, "request_owner"), RequestOperation.NEXT)
            val parent = db.rawQuery("SELECT conversation_id,run_id,role FROM messages WHERE id=?", arrayOf(message)).use {
                if (!it.moveToFirst()) null else {
                    require(it.getString(0) == owner && it.getString(1) == run && it.getString(2) == "assistant"); message
                }
            }
            if (state == "HANDED" && presence == 1L) {
                // The live ledger also excludes this receipt after its parent was deleted.
                // No retained checkpoint means there are no retained tokens to manufacture.
                if (parent == null) return@forEach
                val report = requireNotNull(requests[request.owner to request.requestId]) { "摘要交接缺少checkpoint用量" }
                require(text(report, "kind") == "CHECKPOINT" && text(report, "message_id") == message &&
                    text(report, "conversation_id") == owner && text(report, "run_id") == run &&
                    text(report, "attempt_id") == request.attemptId && text(report, "operation") == "NEXT" &&
                    text(report, "usage_source") == "SUMMARY" && number(report, "has_usage") == 1L)
            } else {
                val snapshot = if (presence == 0L) null else app.daiyu.android.domain.ModelEvent.Usage(
                    if (p.isNull("input_tokens")) null else number(p, "input_tokens"),
                    if (p.isNull("output_tokens")) null else number(p, "output_tokens"),
                    if (p.isNull("cached_tokens")) null else number(p, "cached_tokens"),
                    if (p.isNull("cache_creation_tokens")) null else number(p, "cache_creation_tokens"))
                add(observation("PREPARATION", message, owner, run, parent, request, UsageRequestSource.SUMMARY, snapshot))
            }
        }
        JSONObject().put("version", 2).put("checkpoints", JSONArray(parents.toSortedMap().values.toList()))
            .put("requests", JSONArray(requests.values.sortedWith(compareBy({ text(it, "request_owner") }, { text(it, "request_id") }))))
    }

    private fun observation(kind: String, message: String, conversation: String, run: String, parent: String?,
        request: RequestCheckpoint, source: UsageRequestSource, usage: app.daiyu.android.domain.ModelEvent.Usage?) = JSONObject()
        .put("kind", kind).put("message_id", message).put("conversation_id", conversation).put("run_id", run)
        .put("parent_message_id", parent ?: JSONObject.NULL).put("request_owner", request.owner).put("request_id", request.requestId)
        .put("attempt_id", request.attemptId).put("operation", request.operation.name).put("usage_source", source.name)
        .put("has_usage", if (usage == null) 0 else 1).put("input_tokens", usage?.inputTokens ?: JSONObject.NULL)
        .put("output_tokens", usage?.outputTokens ?: JSONObject.NULL).put("cached_tokens", usage?.cachedTokens ?: JSONObject.NULL)
        .put("cache_creation_tokens", usage?.cacheCreationTokens ?: JSONObject.NULL)

    fun validate(value: JSONObject?, conversations: Set<String>, messages: JSONArray): Validated = with(BackupHistoryRows) {
        if (value == null) return Validated(emptyList(), emptyList(), conversations.toSet())
        fields(value, listOf("version", "checkpoints", "requests")); val version = number(value, "version"); require(version in 1L..2L)
        require(value.toString().toByteArray(Charsets.UTF_8).size <= MAX_METADATA_BYTES)
        val parents = rows(value.getJSONArray("checkpoints")); val requests = rows(value.getJSONArray("requests")).map { row ->
            fields(row, if (version == 1L) requestColumns - "cache_creation_tokens" else requestColumns)
            JSONObject().apply {
                row.keys().forEach { put(it, row.get(it)) }
                if (version == 1L) put("cache_creation_tokens", JSONObject.NULL)
            }
        }
        val physical = messageMap(messages); val parentMap = mutableMapOf<String, JSONObject>()
        parents.forEach { p ->
            fields(p, parentColumns); require(id(p, "conversation_id") in conversations)
            owner(p, physical, "conversation_id", "message_id", "run_id")
            require(parentMap.put(id(p, "message_id"), p) == null)
        }
        val identities = mutableSetOf<Pair<String, String>>()
        val preparations = mutableSetOf<String>()
        requests.forEach { r ->
            fields(r, requestColumns)
            val message = id(r, "message_id"); val conversation = id(r, "conversation_id"); val run = id(r, "run_id")
            require(conversation in conversations && identities.add(id(r, "request_owner") to id(r, "request_id")))
            id(r, "attempt_id"); require(text(r, "operation") in setOf("NEXT", "RESUME"))
            val kind = text(r, "kind"); require(kind in setOf("CHECKPOINT", "PREPARATION"))
            val source = text(r, "usage_source"); require(source in setOf("MAIN", "SUMMARY"))
            val presence = number(r, "has_usage"); require(presence in 0L..1L)
            tokens.forEach { if (!r.isNull(it)) require(number(r, it) >= 0) }
            if (!r.isNull("input_tokens") && !r.isNull("cached_tokens")) require(number(r, "cached_tokens") <= number(r, "input_tokens"))
            fun token(key: String): Long? = if (r.isNull(key)) null else number(r, key)
            app.daiyu.android.domain.ModelEvent.Usage(token("input_tokens"), token("output_tokens"),
                token("cached_tokens"), token("cache_creation_tokens"))
            require(presence == 1L || tokens.all { r.isNull(it) })
            if (physical.containsKey(message)) {
                owner(r, physical, "conversation_id", "message_id", "run_id")
                require(id(r, "parent_message_id") == message)
            } else require(r.isNull("parent_message_id"))
            if (kind == "CHECKPOINT") {
                val p = requireNotNull(parentMap[message]); require(text(p, "conversation_id") == conversation && text(p, "run_id") == run)
                require(!r.isNull("parent_message_id"))
            } else {
                require(source == "SUMMARY" && text(r, "operation") == "NEXT" && preparations.add(message))
            }
        }
        Validated(parents, requests, conversations.toSet())
    }
    fun restore(db: SQLiteDatabase, value: Validated) {
        value.checkpoints.forEach { BackupHistoryRows.insert(db, RecordedUsageHistory.PARENTS, it) }
        value.requests.forEach { BackupHistoryRows.insert(db, RecordedUsageHistory.REQUESTS, it) }
        verify(db, value)
    }
    fun verify(db: SQLiteDatabase, value: Validated) = with(BackupHistoryRows) {
        check(db.inTransaction())
        fun exact(table: String, columns: List<String>, expected: List<JSONObject>, parent: Boolean) {
            value.conversations.forEach { conversation ->
                val physical = if (parent) "message_id" else "parent_message_id"
                val actual = read(db, table, columns,
                    " WHERE conversation_id=? OR message_id IN (SELECT id FROM messages WHERE conversation_id=?)" +
                        (if (parent) "" else " OR $physical IN (SELECT id FROM messages WHERE conversation_id=?)"),
                    if (parent) arrayOf(conversation, conversation) else arrayOf(conversation, conversation, conversation))
                val wanted = expected.filter { text(it, "conversation_id") == conversation }
                require(actual.size == wanted.size) { "运行用量历史集合回执不匹配" }
                actual.forEach { row ->
                    val match = if (parent) wanted.singleOrNull { text(it, "message_id") == text(row, "message_id") }
                        else wanted.singleOrNull { key(it) == key(row) }
                    require(match != null && same(row, match)) { "运行用量历史集合回执不匹配" }
                }
            }
        }
        exact(RecordedUsageHistory.PARENTS, parentColumns, value.checkpoints, true)
        exact(RecordedUsageHistory.REQUESTS, requestColumns, value.requests, false)
        value.checkpoints.forEach { verify(db, RecordedUsageHistory.PARENTS, it) }
        value.requests.forEach { verify(db, RecordedUsageHistory.REQUESTS, it) }
    }
}
