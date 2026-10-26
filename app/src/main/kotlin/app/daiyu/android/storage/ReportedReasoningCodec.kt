package app.daiyu.android.storage

import app.daiyu.android.domain.ReportedReasoning
import org.json.JSONObject

/** Optional elapsed time keeps existing histories byte-compatible when time is unknown. */
internal object ReportedReasoningCodec {
    fun encode(value: ReportedReasoning): JSONObject = JSONObject()
        .put("item_id", value.itemId).put("text", value.text).also { result ->
            value.elapsedMillis?.let { result.put("elapsed_ms", it) }
            value.blockOffset?.let { result.put("block_offset", it) }
        }

    fun decode(value: JSONObject): ReportedReasoning {
        require(value.keys().asSequence().toSet() - setOf("elapsed_ms", "block_offset") == setOf("item_id", "text"))
        val elapsed = if (!value.has("elapsed_ms")) null else {
            val raw = value.get("elapsed_ms")
            require(raw is Int || raw is Long) { "思考耗时须为整数毫秒" }
            (raw as Number).toLong()
        }
        val offset = if (!value.has("block_offset")) null else {
            val raw = value.get("block_offset")
            require(raw is Int || raw is Long) { "思考位置须为整数" }
            val number = (raw as Number).toLong()
            require(number in 0..Int.MAX_VALUE.toLong()) { "思考位置越界" }
            number.toInt()
        }
        return ReportedReasoning(value.get("item_id") as? String ?: error("思考标识须为文本"),
            value.get("text") as? String ?: error("思考摘要须为文本"), elapsedMillis = elapsed, blockOffset = offset)
    }
}
