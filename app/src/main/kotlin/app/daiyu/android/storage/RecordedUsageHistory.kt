package app.daiyu.android.storage

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import app.daiyu.android.domain.ModelEvent
import app.daiyu.android.domain.runtime.*
import app.daiyu.android.domain.usage.AttributedRequestUsage
import app.daiyu.android.domain.usage.UsageOrigin

/** Inert observations only. No runtime state, lease, continuation, revision or recovery entry point. */
internal object RecordedUsageHistory {
    const val PARENTS = "recorded_usage_checkpoints"
    const val REQUESTS = "recorded_usage_requests"
    val parentColumns = listOf("message_id", "conversation_id", "run_id")
    val requestColumns = listOf("kind", "message_id", "conversation_id", "run_id", "parent_message_id",
        "request_owner", "request_id", "attempt_id", "operation", "usage_source", "has_usage",
        "input_tokens", "output_tokens", "cached_tokens", "cache_creation_tokens")

    fun install(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS $PARENTS(message_id TEXT PRIMARY KEY REFERENCES messages(id) ON DELETE CASCADE," +
            "conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,run_id TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS $REQUESTS(kind TEXT NOT NULL CHECK(kind IN ('CHECKPOINT','PREPARATION'))," +
            "message_id TEXT NOT NULL,conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE," +
            "run_id TEXT NOT NULL,parent_message_id TEXT REFERENCES messages(id) ON DELETE CASCADE," +
            "request_owner TEXT NOT NULL,request_id TEXT NOT NULL,attempt_id TEXT NOT NULL," +
            "operation TEXT NOT NULL CHECK(operation IN ('NEXT','RESUME')),usage_source TEXT NOT NULL CHECK(usage_source IN ('MAIN','SUMMARY'))," +
            "has_usage INTEGER NOT NULL CHECK(has_usage IN (0,1)),input_tokens INTEGER,output_tokens INTEGER,cached_tokens INTEGER,cache_creation_tokens INTEGER," +
            "PRIMARY KEY(request_owner,request_id))")
        db.execSQL("CREATE INDEX IF NOT EXISTS recorded_usage_requests_parent ON $REQUESTS(message_id,kind)")
        db.execSQL("CREATE INDEX IF NOT EXISTS recorded_usage_requests_conversation ON $REQUESTS(conversation_id)")
    }

    data class Observation(val hasUsage: Boolean, val usage: AttributedRequestUsage)
    /** Caller supplies a bounded cursor, in the report's snapshot transaction. */
    fun observation(c: Cursor): Observation {
        fun text(key: String): String {
            val i = c.getColumnIndexOrThrow(key)
            require(c.getType(i) == Cursor.FIELD_TYPE_STRING)
            return c.getString(i).also { require(it.isNotBlank() && it.length <= 200) }
        }
        fun integer(key: String): Long {
            val i = c.getColumnIndexOrThrow(key); require(c.getType(i) == Cursor.FIELD_TYPE_INTEGER); return c.getLong(i)
        }
        fun token(key: String): Long? = if (c.isNull(c.getColumnIndexOrThrow(key))) null else integer(key).also { require(it >= 0) }
        val kind = text("kind"); require(kind in setOf("CHECKPOINT", "PREPARATION"))
        val source = UsageRequestSource.valueOf(text("usage_source"))
        require(source == UsageRequestSource.MAIN || source == UsageRequestSource.SUMMARY)
        require(kind != "PREPARATION" || source == UsageRequestSource.SUMMARY)
        val presence = integer("has_usage"); require(presence in 0L..1L)
        val snapshot = ModelEvent.Usage(token("input_tokens"), token("output_tokens"), token("cached_tokens"), token("cache_creation_tokens"))
        require(presence == 1L || snapshot == ModelEvent.Usage(null, null, null))
        val request = RequestCheckpoint(text("request_id"), text("attempt_id"), text("request_owner"), RequestOperation.valueOf(text("operation")))
        require(request.operation != RequestOperation.OPEN)
        val conversation = text("conversation_id"); val run = text("run_id"); val message = text("message_id")
        val parentIndex = c.getColumnIndexOrThrow("parent_message_id")
        if (kind == "CHECKPOINT") require(!c.isNull(parentIndex))
        if (!c.isNull(parentIndex)) require(text("parent_message_id") == message)
        return Observation(presence == 1L, AttributedRequestUsage(conversation, run, message, run, message,
            UsageOrigin.Main, request, snapshot, source))
    }
}
