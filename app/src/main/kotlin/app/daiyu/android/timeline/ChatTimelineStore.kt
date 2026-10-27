package app.daiyu.android.timeline

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import app.daiyu.android.domain.*
import org.json.JSONObject

data class SummaryModelIdentity(val providerId: String, val model: String, val protocol: String,
    val providerName: String? = null) {
    val displayLabel: String get() = "${providerName?.takeIf { it.isNotBlank() } ?: providerId.takeUnless { it.contains(":v1:") || it == "frozen-chat-summary" } ?: "供应商未记录"} · $model"
}
data class CompressionActivity(val id: String, val conversationId: String,
    val messages: Int, val model: SummaryModelIdentity?, val running: Boolean = true,
    val agentLabel: String = "主代理", val runId: String? = null, val taskId: String? = null,
    val waiting: Boolean = false, val failure: String? = null, val anchor: TimelineAnchor? = null)

data class TimelineAnchor(val conversationId: String, val runId: String,
    val messageId: String, val segment: Int) {
    val listKey: String get() = listOf(conversationId, runId, messageId, segment.toString())
        .joinToString("", prefix = "turn:") { "${it.length}:$it" }
    companion object {
        fun keys(history: List<Message>): Set<String> {
            val counts = hashMapOf<Triple<ConversationId, RunId, MessageId>, Int>()
            return MessageHistory.expand(history).mapTo(hashSetOf()) { message ->
                val owner = Triple(message.conversationId, message.runId, message.id)
                val segment = counts[owner] ?: 0
                counts[owner] = segment + 1
                TimelineAnchor(message.conversationId.value, message.runId.value, message.id.value, segment).listKey
            }
        }
        fun at(history: List<Message>, index: Int): TimelineAnchor {
            val message = history[index]
            val segment = history.take(index).count { it.id == message.id && it.runId == message.runId &&
                it.conversationId == message.conversationId }
            return TimelineAnchor(message.conversationId.value, message.runId.value, message.id.value, segment)
        }
    }
}

sealed interface ChatTimelineEntry {
    val id: String
    val anchor: TimelineAnchor
    val created: Long
    data class Compression(override val id: String, override val anchor: TimelineAnchor,
        override val created: Long, val summary: String, val model: SummaryModelIdentity?,
        val messages: Int, val beforeTokens: Long?, val afterTokens: Long?,
        val agentLabel: String = "主代理", val failure: String? = null) : ChatTimelineEntry
    data class Reconnect(override val id: String, override val anchor: TimelineAnchor,
        override val created: Long, val status: String, val elapsedMs: Long, val reason: String,
        val round: Int, val model: String) : ChatTimelineEntry
}

/** Display evidence only. Never read by request admission, replay, or transport recovery. */
internal object ChatTimelineStore {
    val revision = java.util.concurrent.atomic.AtomicLong()
    val columns = listOf("id", "conversation_id", "message_id", "kind", "payload", "created")
    fun install(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS chat_timeline_events(id TEXT PRIMARY KEY,conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,message_id TEXT NOT NULL,kind TEXT NOT NULL,payload TEXT NOT NULL,created INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS chat_timeline_owner ON chat_timeline_events(conversation_id,created,id)")
    }
    fun write(db: SQLiteDatabase, entry: ChatTimelineEntry) {
        val kind = if (entry is ChatTimelineEntry.Compression) "compression" else "reconnect"
        if (entry is ChatTimelineEntry.Compression && db.rawQuery("SELECT 1 FROM chat_timeline_events WHERE id=?", arrayOf(entry.id)).use { it.moveToFirst() }) return
        val values = ContentValues().apply {
            put("id", entry.id); put("conversation_id", entry.anchor.conversationId)
            put("message_id", entry.anchor.messageId); put("kind", kind)
            put("payload", encode(entry).toString()); put("created", entry.created)
        }
        if (entry is ChatTimelineEntry.Reconnect) {
            // A stopped segment is final. Resume receives a new reconnectId from the budget.
            val prior = db.rawQuery("SELECT payload FROM chat_timeline_events WHERE id=?", arrayOf(entry.id)).use {
                if (it.moveToFirst()) decode(entry.id, entry.created, JSONObject(it.getString(0))) as ChatTimelineEntry.Reconnect else null
            }
            if (prior != null && prior.status != "running") return
        }
        db.insertWithOnConflict("chat_timeline_events", null, values, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
        revision.incrementAndGet()
    }
    fun read(db: SQLiteDatabase, conversationId: String): List<ChatTimelineEntry> =
        db.rawQuery("SELECT id,created,payload FROM chat_timeline_events WHERE conversation_id=? ORDER BY created,id", arrayOf(conversationId)).use { c ->
            buildList { while (c.moveToNext()) add(decode(c.getString(0), c.getLong(1), JSONObject(c.getString(2)))) }
        }
    fun encode(entry: ChatTimelineEntry): JSONObject = JSONObject().put("conversation", entry.anchor.conversationId)
        .put("run", entry.anchor.runId).put("message", entry.anchor.messageId).put("segment", entry.anchor.segment)
        .apply { when (entry) {
            is ChatTimelineEntry.Compression -> {
                put("kind", "compression"); put("summary", entry.summary); put("messages", entry.messages)
                put("agentLabel", entry.agentLabel); put("failure", entry.failure ?: JSONObject.NULL)
                put("before", entry.beforeTokens ?: JSONObject.NULL); put("after", entry.afterTokens ?: JSONObject.NULL)
                put("model", entry.model?.let { JSONObject().put("provider", it.providerId).put("model", it.model).put("protocol", it.protocol).put("providerName", it.providerName ?: JSONObject.NULL) } ?: JSONObject.NULL)
            }
            is ChatTimelineEntry.Reconnect -> {
                put("kind", "reconnect"); put("status", entry.status); put("elapsed", entry.elapsedMs)
                put("reason", entry.reason); put("round", entry.round); put("model", entry.model)
            }
        } }
    fun decode(id: String, created: Long, row: JSONObject): ChatTimelineEntry {
        require(id.isNotBlank() && id.length <= 500 && created >= 0)
        fun text(key: String) = (row.get(key) as? String ?: error("timeline_text_required")).also { require(it.isNotBlank()) }
        fun number(key: String): Long { val value = row.get(key); require(value is Int || value is Long); return (value as Number).toLong().also { require(it >= 0) } }
        val kind = text("kind")
        val common = setOf("conversation", "run", "message", "segment", "kind")
        val detail = when (kind) {
            "compression" -> setOf("summary", "messages", "before", "after", "model")
            "reconnect" -> setOf("status", "elapsed", "reason", "round", "model")
            else -> error("unknown_timeline_kind")
        }
        val keys = row.keys().asSequence().toSet()
        require(keys == common + detail || (kind == "compression" && keys == common + detail + setOf("agentLabel", "failure")))
        val anchor = TimelineAnchor(text("conversation"), text("run"), text("message"), number("segment").also { require(it <= Int.MAX_VALUE) }.toInt())
        return when (kind) {
            "compression" -> {
                val model = if (row.isNull("model")) null else row.getJSONObject("model").let {
                    require(it.keys().asSequence().toSet() in setOf(setOf("provider", "model", "protocol"),
                        setOf("provider", "model", "protocol", "providerName")))
                    fun field(key: String) = (it.get(key) as? String ?: error("timeline_model_text_required")).also { require(it.isNotBlank()) }
                    SummaryModelIdentity(field("provider"), field("model"), field("protocol"),
                        if (it.has("providerName") && !it.isNull("providerName")) field("providerName") else null)
                }
                ChatTimelineEntry.Compression(id, anchor, created, text("summary"), model,
                    number("messages").also { require(it in 0..50000) }.toInt(),
                    if (row.isNull("before")) null else number("before"), if (row.isNull("after")) null else number("after"),
                    if (row.has("agentLabel")) text("agentLabel") else "主代理",
                    if (row.has("failure") && !row.isNull("failure")) text("failure") else null)
            }
            "reconnect" -> ChatTimelineEntry.Reconnect(id, anchor, created,
                text("status").also { require(it in setOf("running", "succeeded", "failed", "stopped")) },
                number("elapsed"), text("reason"), number("round").also { require(it <= Int.MAX_VALUE) }.toInt(), text("model"))
            else -> error("unknown_timeline_kind")
        }
    }
}
