package app.daiyu.android.storage

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject

/** Kept outside temporary conversations; binding and memory commit in the same transaction. */
internal object VoiceCallModelMemory {
    private const val KEY = "voice_call_model_binding_v1"
    fun readOrInitialize(db: SQLiteDatabase, initial: () -> ConversationModelBinding?): ConversationModelBinding? {
        val raw = db.rawQuery("SELECT value FROM neutral_config WHERE name=?", arrayOf(KEY)).use {
            if (it.moveToFirst()) it.getString(0) else null
        }
        if (raw == null) return initial().also { write(db, it) }
        val value = JSONObject(raw)
        check(value.getInt("version") == 1) { "语音模型记忆无效，请重新选择模型" }
        if (value.isNull("binding")) { check(value.has("binding")); return null }
        val binding = value.getJSONObject("binding")
        val provider = binding.get("providerSlotId") as? String
        val model = binding.get("localModelIdentity") as? String
        check(!provider.isNullOrBlank() && !model.isNullOrBlank()) { "语音模型记忆无效，请重新选择模型" }
        return ConversationModelBinding(provider, model)
    }
    fun write(db: SQLiteDatabase, binding: ConversationModelBinding?) {
        val encoded = JSONObject().put("version", 1).put("binding", binding?.let {
            JSONObject().put("providerSlotId", it.providerSlotId).put("localModelIdentity", it.localModelIdentity)
        } ?: JSONObject.NULL).toString()
        check(db.insertWithOnConflict("neutral_config", null, ContentValues().apply {
            put("name", KEY); put("value", encoded)
        }, SQLiteDatabase.CONFLICT_REPLACE) != -1L)
        check(db.rawQuery("SELECT value FROM neutral_config WHERE name=?", arrayOf(KEY)).use {
            it.moveToFirst() && it.getString(0) == encoded
        }) { "语音模型记忆保存未确认" }
    }
}
