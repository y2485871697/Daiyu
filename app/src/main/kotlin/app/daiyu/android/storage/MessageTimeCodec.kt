package app.daiyu.android.storage

import org.json.JSONObject

/** Shared optional wire field. Omit unknown times to retain legacy bytes and digests. */
internal object MessageTimeCodec {
    const val FIELD = "created_at"

    fun read(value: JSONObject): Long? {
        if (!value.has(FIELD) || value.isNull(FIELD)) return null
        val raw = value.get(FIELD)
        require(raw is Int || raw is Long) { "消息时间须为整数毫秒" }
        return (raw as Number).toLong().also { require(it >= 0) { "消息时间无效" } }
    }

    fun write(value: JSONObject, timestamp: Long?) {
        timestamp?.let {
            require(it >= 0)
            value.put(FIELD, it)
        }
    }
}
