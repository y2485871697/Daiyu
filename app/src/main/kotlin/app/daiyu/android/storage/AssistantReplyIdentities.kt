package app.daiyu.android.storage

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import app.daiyu.android.assistants.AssistantReplyIdentity
import app.daiyu.android.domain.Message

/** Side metadata leaves protocol history and runtime checkpoint equality untouched. */
internal object AssistantReplyIdentities {
    fun install(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS assistant_reply_identities(
            message_id TEXT PRIMARY KEY NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
            conversation_id TEXT NOT NULL, run_id TEXT NOT NULL,
            assistant_id TEXT NOT NULL, name TEXT NOT NULL, avatar_png TEXT)""")
    }

    fun insert(db: SQLiteDatabase, message: Message, identity: AssistantReplyIdentity) {
        require(message.role == Message.ASSISTANT_ROLE)
        check(db.insertOrThrow("assistant_reply_identities", null, ContentValues().apply {
            put("message_id", message.id.value); put("conversation_id", message.conversationId.value)
            put("run_id", message.runId.value); put("assistant_id", identity.assistantId)
            put("name", identity.name); put("avatar_png", identity.avatarPng)
        }) != -1L)
        check(read(db, message) == identity) { "回复助手身份未持久化" }
    }

    fun read(db: SQLiteDatabase, message: Message): AssistantReplyIdentity? = db.rawQuery(
        "SELECT assistant_id,name,avatar_png FROM assistant_reply_identities WHERE message_id=? AND conversation_id=? AND run_id=?",
        arrayOf(message.id.value, message.conversationId.value, message.runId.value),
    ).use { cursor ->
        if (!cursor.moveToFirst()) null else AssistantReplyIdentity(cursor.getString(0), cursor.getString(1),
            if (cursor.isNull(2)) null else cursor.getString(2))
    }
    fun encode(db: SQLiteDatabase, message: Message): org.json.JSONObject? = read(db, message)?.let {
        org.json.JSONObject().put("assistant_id", it.assistantId).put("name", it.name)
            .put("avatar_png", it.avatarPng ?: org.json.JSONObject.NULL)
    }

    fun decode(value: org.json.JSONObject): AssistantReplyIdentity {
        require(value.keys().asSequence().toSet() == setOf("assistant_id", "name", "avatar_png"))
        val id = value.get("assistant_id").also { require(it is String) } as String
        val name = value.get("name").also { require(it is String) } as String
        require(id.isNotBlank() && id.length <= 200 && name.isNotBlank() && name.length <= 200)
        val avatar = if (value.isNull("avatar_png")) null else value.get("avatar_png").also { require(it is String) } as String
        if (avatar != null) {
            require(avatar.length <= 2 * 1024 * 1024)
            val bytes = android.util.Base64.decode(avatar, android.util.Base64.NO_WRAP)
            require(bytes.size >= 8 && bytes.take(8) == listOf(137, 80, 78, 71, 13, 10, 26, 10).map { it.toByte() })
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            require(bounds.outWidth in 1..512 && bounds.outHeight in 1..512)
        }
        return AssistantReplyIdentity(id, name, avatar)
    }

}
