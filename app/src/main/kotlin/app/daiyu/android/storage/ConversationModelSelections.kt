package app.daiyu.android.storage

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import app.daiyu.android.domain.ConversationId

/** Stable local identities only; credentials and editable catalog revisions stay in their stores. */
internal data class ConversationModelBinding(val providerSlotId: String, val localModelIdentity: String)

internal object ConversationModelSelections {
    fun install(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS conversation_model_selections(owner_id TEXT PRIMARY KEY,provider_slot TEXT,local_model_id TEXT,CHECK((provider_slot IS NULL)=(local_model_id IS NULL)))")
    }
    private fun owner(id: ConversationId?) = id?.value ?: ""
    fun read(db: SQLiteDatabase, id: ConversationId?): ConversationModelBinding? = db.rawQuery(
        "SELECT provider_slot,local_model_id FROM conversation_model_selections WHERE owner_id=?", arrayOf(owner(id))
    ).use { c -> if (!c.moveToFirst() || c.isNull(0)) null else ConversationModelBinding(c.getString(0), c.getString(1)) }

    fun write(db: SQLiteDatabase, id: ConversationId?, binding: ConversationModelBinding?) {
        val values = ContentValues().apply {
            put("owner_id", owner(id)); put("provider_slot", binding?.providerSlotId); put("local_model_id", binding?.localModelIdentity)
        }
        check(db.insertWithOnConflict("conversation_model_selections", null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L)
        check(db.rawQuery("SELECT provider_slot,local_model_id FROM conversation_model_selections WHERE owner_id=?", arrayOf(owner(id))).use { c ->
            c.moveToFirst() && if (binding == null) c.isNull(0) && c.isNull(1)
                else c.getString(0) == binding.providerSlotId && c.getString(1) == binding.localModelIdentity
        }) { "会话模型保存回执不匹配" }
    }
    /** Freeze all legacy defaults once, before any conversation can change its own model. */
    fun initialize(db: SQLiteDatabase, fallback: ConversationModelBinding?) {
        val owners = db.rawQuery("SELECT id FROM conversations UNION SELECT ''", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        for (id in owners) {
            val exists = db.rawQuery("SELECT 1 FROM conversation_model_selections WHERE owner_id=?", arrayOf(id)).use { it.moveToFirst() }
            if (!exists) write(db, id.takeIf(String::isNotEmpty)?.let(::ConversationId), fallback)
        }
    }
    fun inherit(db: SQLiteDatabase, source: ConversationId?, target: ConversationId?) = write(db, target, read(db, source))
    fun delete(db: SQLiteDatabase, id: ConversationId) {
        db.delete("conversation_model_selections", "owner_id=?", arrayOf(id.value))
    }
}
