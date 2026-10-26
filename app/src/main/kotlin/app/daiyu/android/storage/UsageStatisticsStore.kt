package app.daiyu.android.storage

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import app.daiyu.android.data.repository.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Paid-request facts have no runtime or message foreign keys. Removing a conversation cannot
 * erase its bill, and importing these numbers cannot restore an executable request. */
internal object UsageStatisticsStore {
    const val REQUESTS = "usage_statistics_requests"
    const val CONVERSATIONS = "usage_statistics_conversations"
    const val RETIRED_MESSAGES = "usage_statistics_retired_messages"
    const val META = "usage_statistics_meta"
    val requestColumns = listOf("id", "conversation_id", "provider_id", "provider_name", "model_id", "model_name",
        "at_millis", "input_tokens", "output_tokens", "cached_tokens", "cache_creation_tokens")
    val conversationColumns = listOf("conversation_id", "created_at", "retired_messages", "retired_day")

    fun install(db: SQLiteDatabase) {
        check(db.inTransaction())
        db.execSQL("CREATE TABLE IF NOT EXISTS $META(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS $CONVERSATIONS(conversation_id TEXT PRIMARY KEY," +
            "created_at INTEGER CHECK(created_at IS NULL OR (typeof(created_at)='integer' AND created_at>=0))," +
            "retired_messages INTEGER CHECK(retired_messages IS NULL OR (typeof(retired_messages)='integer' AND retired_messages>=0)),retired_day TEXT)")
        db.execSQL("CREATE TABLE IF NOT EXISTS $REQUESTS(id TEXT PRIMARY KEY,conversation_id TEXT," +
            "provider_id TEXT,provider_name TEXT,model_id TEXT,model_name TEXT,at_millis INTEGER," +
            "input_tokens INTEGER,output_tokens INTEGER,cached_tokens INTEGER,cache_creation_tokens INTEGER)")
        db.execSQL("CREATE TABLE IF NOT EXISTS $RETIRED_MESSAGES(message_id TEXT PRIMARY KEY,conversation_id TEXT NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS usage_statistics_retired_owner ON $RETIRED_MESSAGES(conversation_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS usage_statistics_model_time ON $REQUESTS(provider_id,model_id,at_millis)")
        db.execSQL("CREATE INDEX IF NOT EXISTS usage_statistics_owner ON $REQUESTS(conversation_id)")
        val installed = db.rawQuery("SELECT 1 FROM $META WHERE key='initialized'", null).use { it.moveToFirst() }
        if (installed) return
        // The old database has no conversation creation timestamp or historical model binding.
        db.execSQL("INSERT OR IGNORE INTO $CONVERSATIONS(conversation_id) SELECT id FROM conversations")
        importLegacyReceipts(db)
        check(db.insertOrThrow(META, null, ContentValues().apply { put("key", "initialized"); put("value", "1") }) != -1L)
    }

    fun importLegacyReceipts(db: SQLiteDatabase, conversations: Set<String>? = null) {
        check(db.inTransaction())
        db.rawQuery("SELECT conversation_id,request_owner,request_id,input_tokens,output_tokens,cached_tokens,cache_creation_tokens,window_tokens " +
            "FROM conversation_usage_receipts", null).use { c -> while (c.moveToNext()) {
            if (conversations != null && c.getString(0) !in conversations) continue
            val id = UUID.nameUUIDFromBytes((c.getString(0) + "\u0000" + c.getString(1) + "\u0000" + c.getString(2)).toByteArray(Charsets.UTF_8)).toString()
            val normalized = normalized(Fields(token(c, 3), token(c, 4), token(c, 5), token(c, 6)),
                if (c.isNull(7)) null else c.getLong(7))
            val values = ContentValues().apply {
                put("id", "legacy:$id"); put("conversation_id", c.getString(0))
                put("input_tokens", normalized.input); put("output_tokens", normalized.output)
                put("cached_tokens", normalized.cached); put("cache_creation_tokens", normalized.creation)
            }
            if (!db.rawQuery("SELECT 1 FROM $REQUESTS WHERE id=?", arrayOf("legacy:$id")).use { it.moveToFirst() })
                check(db.insertOrThrow(REQUESTS, null, values) != -1L)
        } }
    }

    fun created(db: SQLiteDatabase, conversation: String, at: Long) {
        check(db.inTransaction()); require(at >= 0)
        check(db.insertOrThrow(CONVERSATIONS, null, ContentValues().apply {
            put("conversation_id", conversation); put("created_at", at)
        }) != -1L)
    }

    fun retire(db: SQLiteDatabase, conversation: String) {
        check(db.inTransaction())
        val updated = db.rawQuery("SELECT updated FROM conversations WHERE id=?", arrayOf(conversation)).use {
            if (!it.moveToFirst()) return else it.getLong(0)
        }
        val count = messageCount(db, conversation)
        db.insertWithOnConflict(CONVERSATIONS, null, ContentValues().apply { put("conversation_id", conversation) }, SQLiteDatabase.CONFLICT_IGNORE)
        check(db.update(CONVERSATIONS, ContentValues().apply {
            put("retired_messages", count)
            // Original retired heatmap uses last update, not a guessed creation timestamp.
            put("retired_day", day(updated).toString())
        }, "conversation_id=? AND retired_messages IS NULL", arrayOf(conversation)) == 1)
    }

    /** Restore only lossless retirement transitions after live text is installed. */
    fun restoreRetirement(db: SQLiteDatabase, conversation: String, createdAt: Long?,
        retiredMessages: Long, retiredDay: String) {
        check(db.inTransaction())
        verifyRestoredRetirement(db, conversation, retiredMessages)
        val createdClause = if (createdAt == null) "created_at IS NULL" else "created_at=?"
        val args = buildList {
            add(conversation)
            if (createdAt != null) add(createdAt.toString())
            add(retiredMessages.toString()); add(retiredDay); add(conversation)
        }.toTypedArray()
        check(db.update(CONVERSATIONS, ContentValues().apply {
            putNull("retired_messages"); putNull("retired_day")
        }, "conversation_id=? AND $createdClause AND retired_messages=? AND retired_day=? " +
            "AND EXISTS(SELECT 1 FROM conversations WHERE id=?)", args) == 1) { "退役恢复前值已变化" }
    }

    fun verifyRestoredRetirement(db: SQLiteDatabase, conversation: String, retiredMessages: Long) {
        check(db.inTransaction())
        check(db.rawQuery("SELECT 1 FROM conversations WHERE id=?", arrayOf(conversation)).use { it.moveToFirst() }) {
            "退役恢复缺少已恢复会话"
        }
        require(messageCount(db, conversation).toLong() == retiredMessages) {
            "恢复消息数与退役账目不同，未覆盖现有账目"
        }
        require(!db.rawQuery("SELECT 1 FROM messages m JOIN $RETIRED_MESSAGES r ON r.message_id=m.id " +
            "WHERE m.conversation_id=? LIMIT 1", arrayOf(conversation)).use { it.moveToFirst() }) {
            "恢复消息与退役消息账目重叠，未覆盖现有账目"
        }
    }

    data class ModelIdentity(val providerId: String, val providerName: String, val modelId: String, val modelName: String) {
        init { require(listOf(providerId, providerName, modelId, modelName).all { it.isNotBlank() && it.length <= 400 }) }
    }
    data class Fields(val input: Long?, val output: Long?, val cached: Long?, val creation: Long?) {
        init { require(listOf(input, output, cached, creation).all { it == null || it >= 0 }) }
        fun merge(next: Fields) = Fields(next.input ?: input, next.output ?: output, next.cached ?: cached, next.creation ?: creation)
        val empty get() = input == null && output == null && cached == null && creation == null
    }

    fun record(db: SQLiteDatabase, requestId: String, conversation: String?, model: ModelIdentity,
        startedAt: Long, fields: Fields, window: Long?) {
        check(db.inTransaction()); require(startedAt >= 0 && requestId.isNotBlank())
        if (fields.empty) return
        val prior = db.rawQuery("SELECT conversation_id,provider_id,model_id,at_millis FROM $REQUESTS WHERE id=?", arrayOf(requestId)).use { c ->
            if (!c.moveToFirst()) null else listOf(if (c.isNull(0)) null else c.getString(0), c.getString(1), c.getString(2), c.getLong(3).toString())
        }
        check(prior == null || prior == listOf(conversation, model.providerId, model.modelId, startedAt.toString())) { "用量请求归属冲突" }
        val normalized = normalized(fields, window)
        val values = ContentValues().apply {
            put("id", requestId); put("conversation_id", conversation)
            put("provider_id", model.providerId); put("provider_name", model.providerName)
            put("model_id", model.modelId); put("model_name", model.modelName); put("at_millis", startedAt)
            put("input_tokens", normalized.input)
            put("output_tokens", normalized.output)
            put("cached_tokens", normalized.cached)
            put("cache_creation_tokens", normalized.creation)
        }
        check(db.insertWithOnConflict(REQUESTS, null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L)
    }

    data class Totals(val input: Long?, val output: Long?, val cached: Long?, val creation: Long?) {
        val metrics get() = UsageTokenMetrics(input, output, cached, creation,
            if (input == null || output == null || input > Long.MAX_VALUE - output) null else input + output)
    }
    private fun totals(db: SQLiteDatabase, live: Boolean): Totals {
        var input: Long? = 0; var output: Long? = 0; var cached: Long? = 0; var creation: Long? = 0
        fun plus(a: Long?, b: Long?): Long? = if (a == null || b == null || a > Long.MAX_VALUE - b) null else a + b
        val where = if (live) " WHERE EXISTS(SELECT 1 FROM conversations c WHERE c.id=r.conversation_id)" else ""
        db.rawQuery("SELECT input_tokens,output_tokens,cached_tokens,cache_creation_tokens FROM $REQUESTS r$where", null).use { c ->
            while (c.moveToNext()) {
                input = plus(input, token(c, 0)); output = plus(output, token(c, 1))
                cached = plus(cached, token(c, 2)); creation = plus(creation, token(c, 3))
            }
        }
        return Totals(input, output, cached, creation)
    }

    fun snapshot(db: SQLiteDatabase, launchCount: Int): UsageStatsSnapshot {
        check(db.inTransaction())
        val currentConversations = db.rawQuery("SELECT COUNT(*) FROM conversations", null).use { it.moveToFirst(); it.getInt(0) }
        val currentMessages = messageCount(db)
        var retiredConversations = 0; var retiredMessages = 0; var unknownDates = 0
        val heatmap = linkedMapOf<LocalDate, Int>()
        db.rawQuery("SELECT s.created_at,s.retired_messages,s.retired_day," +
            "EXISTS(SELECT 1 FROM conversations c WHERE c.id=s.conversation_id) FROM $CONVERSATIONS s", null).use { c ->
            while (c.moveToNext()) {
                val live = c.getInt(3) == 1
                if (!live && c.isNull(1)) continue
                val date = if (!live) {
                    retiredConversations++; retiredMessages = Math.addExact(retiredMessages, c.getInt(1))
                    if (c.isNull(2)) null else LocalDate.parse(c.getString(2))
                } else if (c.isNull(0)) null else day(c.getLong(0))
                if (date == null) unknownDates++ else heatmap[date] = Math.addExact(heatmap[date] ?: 0, 1)
            }
        }
        val missingLive = db.rawQuery("SELECT COUNT(*) FROM conversations c WHERE NOT EXISTS(" +
            "SELECT 1 FROM $CONVERSATIONS s WHERE s.conversation_id=c.id)", null).use { it.moveToFirst(); it.getInt(0) }
        unknownDates += missingLive
        val current = totals(db, true); val lifetime = totals(db, false)
        val unknownModels = db.rawQuery("SELECT COUNT(*) FROM $REQUESTS WHERE provider_id IS NULL OR model_id IS NULL OR at_millis IS NULL", null)
            .use { it.moveToFirst(); it.getInt(0) }
        return UsageStatsSnapshot(heatmapAvailable = unknownDates == 0 || heatmap.isNotEmpty(), currentDataAvailable = true,
            tokenAccountingAvailable = true, lifetimeAccountingAvailable = true,
            currentConversations = currentConversations, lifetimeConversations = Math.addExact(currentConversations, retiredConversations),
            currentMessages = currentMessages, lifetimeMessages = Math.addExact(Math.addExact(currentMessages, retiredMessages),
                db.rawQuery("SELECT COUNT(*) FROM $RETIRED_MESSAGES", null).use { it.moveToFirst(); it.getInt(0) }),
            currentInputTokens = current.input ?: 0, lifetimeInputTokens = lifetime.input ?: 0,
            currentOutputTokens = current.output ?: 0, lifetimeOutputTokens = lifetime.output ?: 0,
            currentCachedTokens = current.cached ?: 0, lifetimeCachedTokens = lifetime.cached ?: 0,
            currentCacheCreationTokens = current.creation ?: 0, lifetimeCacheCreationTokens = lifetime.creation ?: 0,
            conversationsPerDay = heatmap, launchCount = launchCount, modelUsage = models(db),
            currentAccountingTokens = current.metrics, lifetimeAccountingTokens = lifetime.metrics,
            unknownConversationDates = unknownDates, unattributedUsageRequests = unknownModels)
    }

    private fun models(db: SQLiteDatabase): ModelUsageSnapshot {
        data class ModelRows(val providerName: String, val modelName: String, val events: MutableList<ModelUsageEvent>)
        val groups = linkedMapOf<Pair<String, String>, ModelRows>()
        db.rawQuery("SELECT ${requestColumns.joinToString(",")} FROM $REQUESTS WHERE provider_id IS NOT NULL AND model_id IS NOT NULL " +
            "AND at_millis IS NOT NULL ORDER BY at_millis,id", null).use { c -> while (c.moveToNext()) {
            val key = c.getString(2) to c.getString(4)
            val group = groups.getOrPut(key) { ModelRows(c.getString(3), c.getString(5), mutableListOf()) }
            group.events += ModelUsageEvent(c.getLong(6), token(c, 7) ?: 0, token(c, 8) ?: 0, token(c, 9) ?: 0,
                token(c, 10) ?: 0, if (c.isNull(1)) null else c.getString(1), requestId = c.getString(0),
                inputReported = !c.isNull(7), outputReported = !c.isNull(8), cachedReported = !c.isNull(9), creationReported = !c.isNull(10))
        } }
        return ModelUsageSnapshot(groups.entries.groupBy { it.key.first }.map { (providerId, rows) ->
            ModelUsageProviderUi(providerId, rows.last().value.providerName, rows.map { (key, value) ->
                val events = value.events
                ModelUsageModelUi(key.second, value.modelName,
                    events.safeTokenSum { it.inputTokens },
                    events.safeTokenSum { it.outputTokens },
                    events.mapNotNull { it.conversationId }.toSet().size, events.map { day(it.atMillis) }.toSet().size,
                    events = events, cachedTokens = events.safeTokenSum { it.cachedTokens },
                    cacheCreationTokens = events.safeTokenSum { it.cacheCreationTokens },
                    inputComplete = events.all { it.inputReported } && events.tokenSumAvailable { it.inputTokens }, outputComplete = events.all { it.outputReported } && events.tokenSumAvailable { it.outputTokens },
                    cachedComplete = events.all { it.cachedReported } && events.tokenSumAvailable { it.cachedTokens }, creationComplete = events.all { it.creationReported } && events.tokenSumAvailable { it.cacheCreationTokens })
            })
        })
    }
    fun retireMessages(db: SQLiteDatabase, messages: List<app.daiyu.android.domain.Message>) {
        check(db.inTransaction())
        messages.filter { row -> row.role == "user" || row.role == "assistant" &&
            row.blocks.filterIsInstance<app.daiyu.android.domain.MessageBlock.Text>().any { it.text.isNotBlank() }
        }.forEach { row ->
            check(db.insertOrThrow(RETIRED_MESSAGES, null, ContentValues().apply {
                put("message_id", row.id.value); put("conversation_id", row.conversationId.value)
            }) != -1L)
        }
    }
    internal fun normalized(fields: Fields, window: Long?): Fields {
        val input = fields.input?.takeIf { it > 0 && (window == null || window <= 0 || it.toDouble() <= window.toDouble() * 1.3) }
        val rejected = fields.input != null && input == null
        return Fields(if (fields.input == null) null else input ?: 0L, fields.output,
            fields.cached?.let { if (rejected) 0L else it }, fields.creation?.let { if (rejected) 0L else it })
    }
    private fun messageCount(db: SQLiteDatabase, conversation: String? = null): Int {
        val where = if (conversation == null) "" else " AND conversation_id=?"
        val args = conversation?.let { arrayOf(it) }
        var count = db.rawQuery("SELECT COUNT(*) FROM messages WHERE role='user'$where", args).use { it.moveToFirst(); it.getInt(0) }
        val ids = db.rawQuery("SELECT id FROM messages WHERE role='assistant'$where", args).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        ids.forEach { id -> if (visibleAssistant(checkNotNull(SQLiteText.read(db, "messages", "blocks", "id", id)))) count = Math.addExact(count, 1) }
        return count
    }
    private fun visibleAssistant(raw: String): Boolean {
        val blocks = org.json.JSONArray(raw)
        return (0 until blocks.length()).any { index ->
            val item = blocks.getJSONObject(index)
            item.optString("type") == "text" && item.optString("text").isNotBlank()
        }
    }
    private fun day(at: Long) = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate()
    private fun token(c: Cursor, i: Int): Long? = if (c.isNull(i)) null else {
        check(c.getType(i) == Cursor.FIELD_TYPE_INTEGER); c.getLong(i).also { check(it >= 0) }
    }
}
