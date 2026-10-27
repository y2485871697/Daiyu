package app.daiyu.android.storage

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import app.daiyu.android.domain.ModelEvent
import app.daiyu.android.domain.runtime.RunCheckpoint
import app.daiyu.android.ui.model.ConversationTokenUsageUi

/** Conversation-owned, idempotent UI accounting. Message pruning never removes paid requests.
 * Null provider fields merge within a request only; the authoritative checkpoint is unchanged. */
internal object ConversationUsageStore {
    internal val revision = java.util.concurrent.atomic.AtomicLong()
    // Partial corrections can lower input without repeating earlier cache fields. Keep these
    // independently reported fields without imposing a synthetic cross-snapshot domain Usage.
    private data class Fields(val inputTokens: Long?, val outputTokens: Long?, val cachedTokens: Long?, val cacheCreationTokens: Long?)
    private const val TABLE = "conversation_usage_receipts"
    fun installSchema(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS $TABLE(conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE," +
            "request_owner TEXT NOT NULL,request_id TEXT NOT NULL,input_tokens INTEGER,output_tokens INTEGER," +
            "cached_tokens INTEGER,cache_creation_tokens INTEGER,window_tokens INTEGER,PRIMARY KEY(conversation_id,request_owner,request_id))")
    }

    fun record(db: SQLiteDatabase, conversation: String, owner: String, request: String, usage: ModelEvent.Usage, window: Long? = null) {
        check(db.inTransaction())
        if (listOf(usage.inputTokens, usage.outputTokens, usage.cachedTokens, usage.cacheCreationTokens).all { it == null }) return
        // A cancelled first-send preparation need not have created its conversation yet.
        if (!db.rawQuery("SELECT 1 FROM conversations WHERE id=?", arrayOf(conversation)).use { it.moveToFirst() }) return
        val args = arrayOf(conversation, owner, request)
        val prior = db.rawQuery("SELECT input_tokens,output_tokens,cached_tokens,cache_creation_tokens,window_tokens FROM $TABLE " +
            "WHERE conversation_id=? AND request_owner=? AND request_id=?", args).use { c ->
            if (!c.moveToFirst()) null else readUsage(c, 0) to if (c.isNull(4)) null else c.getLong(4)
        }
        val old = prior?.first
        val effectiveWindow = window ?: prior?.second
        val merged = Fields(usage.inputTokens ?: old?.inputTokens, usage.outputTokens ?: old?.outputTokens,
            usage.cachedTokens ?: old?.cachedTokens, usage.cacheCreationTokens ?: old?.cacheCreationTokens)
        if (merged == old && effectiveWindow == prior?.second) return
        val values = ContentValues().apply {
            put("conversation_id", conversation); put("request_owner", owner); put("request_id", request)
            put("input_tokens", merged.inputTokens); put("output_tokens", merged.outputTokens)
            put("cached_tokens", merged.cachedTokens); put("cache_creation_tokens", merged.cacheCreationTokens)
            put("window_tokens", effectiveWindow)
        }
        check(db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L)
        revision.incrementAndGet()
    }
    fun record(db: SQLiteDatabase, checkpoint: RunCheckpoint, mainWindow: Long? = null) {
        checkpoint.usage.forEach { row -> record(db, checkpoint.message.conversationId.value,
            row.request.owner, row.request.requestId, row.snapshot,
            if (row.source == app.daiyu.android.domain.runtime.UsageRequestSource.MAIN) mainWindow else null) }
    }
    fun read(db: SQLiteDatabase, conversation: String): ConversationTokenUsageUi? {
        var input: Long? = 0; var output: Long? = 0; var cached: Long? = 0; var creation: Long? = 0; var count = 0L
        fun add(left: Long?, right: Long): Long? = left?.takeIf { it <= Long.MAX_VALUE - right }?.plus(right)
        db.rawQuery("SELECT input_tokens,output_tokens,cached_tokens,cache_creation_tokens,window_tokens FROM $TABLE WHERE conversation_id=?",
            arrayOf(conversation)).use { c -> while (c.moveToNext()) {
            val row = readUsage(c, 0)
            val window = if (c.isNull(4)) null else c.getLong(4)
            val acceptedInput = row.inputTokens?.takeIf { it > 0 &&
                (window == null || window <= 0 || it.toDouble() <= window.toDouble() * 1.3) }
            input = add(input, acceptedInput ?: 0); output = add(output, row.outputTokens ?: 0)
            cached = add(cached, if (acceptedInput == null) 0 else row.cachedTokens ?: 0)
            creation = add(creation, if (acceptedInput == null) 0 else row.cacheCreationTokens ?: 0); count++
        } }
        return if (count == 0L) null else ConversationTokenUsageUi(input, output, cached, creation, count)
    }
    private fun readUsage(c: Cursor, offset: Int): Fields {
        fun token(i: Int): Long? = if (c.isNull(i)) null else {
            require(c.getType(i) == Cursor.FIELD_TYPE_INTEGER); c.getLong(i).also { require(it >= 0) }
        }
        return Fields(token(offset), token(offset + 1), token(offset + 2), token(offset + 3))
    }

    /** One migration, streamed by row. No model/window attribution is invented for old billing. */
    fun backfill(db: SQLiteDatabase, conversations: Set<String>? = null) {
        check(db.inTransaction())
        fun numeric(sql: String) = db.rawQuery(sql, null).use { c -> while (c.moveToNext()) {
            if ((0..2).any { c.getType(it) != Cursor.FIELD_TYPE_STRING || c.getString(it).isBlank() || c.getString(it).length > 200 }) continue
            if (conversations != null && c.getString(0) !in conversations) continue
            val value = try { readUsage(c, 3).let {
                ModelEvent.Usage(it.inputTokens, it.outputTokens, it.cachedTokens, it.cacheCreationTokens)
            } } catch (_: IllegalArgumentException) { continue }
            record(db, c.getString(0), c.getString(1), c.getString(2), value)
        } }
        db.rawQuery("SELECT r.* FROM recorded_usage_requests r JOIN conversations c ON c.id=r.conversation_id " +
            "WHERE r.has_usage=1 AND (r.kind='PREPARATION' OR EXISTS(SELECT 1 FROM messages m " +
            "WHERE m.id=r.message_id AND m.conversation_id=r.conversation_id AND m.run_id=r.run_id))", null).use { rows ->
            while (rows.moveToNext()) {
                val observation = try { RecordedUsageHistory.observation(rows) } catch (_: IllegalArgumentException) { continue }
                val row = observation.usage
                if (conversations == null || row.conversationId in conversations)
                    record(db, row.conversationId, row.request.owner, row.request.requestId, row.snapshot)
            }
        }
        numeric("SELECT p.conversation_id,p.request_owner,p.request_id,p.input_tokens,p.output_tokens,p.cached_tokens,p.cache_creation_tokens " +
            "FROM runtime_preparations p JOIN conversations c ON c.id=p.conversation_id WHERE p.has_usage=1")
        numeric("SELECT p.conversation_id,q.request_owner,q.request_id,q.input_tokens,q.output_tokens,q.cached_tokens,q.cache_creation_tokens " +
            "FROM runtime_preparation_chunks q JOIN runtime_preparations p ON p.message_id=q.message_id " +
            "JOIN conversations c ON c.id=p.conversation_id WHERE q.has_usage=1 AND p.state!='HANDED'")
        numeric("SELECT u.branch_id,u.request_owner,u.request_id,u.input_tokens,u.output_tokens,u.cached_tokens,u.cache_creation_tokens " +
            "FROM context_usage u JOIN conversations c ON c.id=u.branch_id WHERE u.has_usage=1 AND u.usage_source IN ('AUXILIARY_VISION','TITLE','MANUAL_SUMMARY')")
        numeric("SELECT t.conversation_id,u.request_owner,u.request_id,u.input_tokens,u.output_tokens,u.cached_tokens,u.cache_creation_tokens " +
            "FROM child_request_usage u JOIN child_tasks t ON t.task_id=u.task_id JOIN messages m ON m.id=t.parent_message_id " +
            "AND m.conversation_id=t.conversation_id AND m.run_id=t.parent_run_id WHERE u.has_usage=1")
        db.rawQuery("SELECT r.message_id,r.conversation_id,r.run_id FROM runtime_checkpoints r JOIN messages m " +
            "ON m.id=r.message_id AND m.conversation_id=r.conversation_id AND m.run_id=r.run_id", null).use { rows ->
            while (rows.moveToNext()) {
                if (conversations != null && rows.getString(1) !in conversations) continue
                val checkpoint = try {
                    (SQLiteRuntimeStore.readCheckpointSnapshot(db, rows.getString(0)) ?: continue).also {
                        require(it.message.id.value == rows.getString(0) && it.message.conversationId.value == rows.getString(1) &&
                            it.message.runId.value == rows.getString(2))
                    }
                } catch (_: IllegalArgumentException) { continue }
                catch (_: IllegalStateException) { continue }
                catch (_: org.json.JSONException) { continue }
                catch (_: java.nio.charset.CharacterCodingException) { continue }
                record(db, checkpoint)
            }
        }
    }
}
