package app.daiyu.android.model

import org.json.JSONArray
import org.json.JSONObject

/** Chat wraps citations differently; validation and clickable rendering share the Responses policy. */
internal object ChatSearchProtocol {
    fun textPart(row: JSONObject): JSONObject {
        val annotations = row.getJSONArray("annotations")
        require(annotations.length() <= 128) { "网页引用过多" }
        val flattened = JSONArray()
        repeat(annotations.length()) { index ->
            val annotation = annotations.getJSONObject(index)
            require(annotation.getString("type") == "url_citation") { "不支持此引用类型" }
            val citation = annotation.getJSONObject("url_citation")
            flattened.put(JSONObject().put("type", "url_citation")
                .put("start_index", citation.get("start_index")).put("end_index", citation.get("end_index"))
                .put("url", citation.get("url")).put("title", citation.get("title")))
        }
        return JSONObject().put("type", "output_text").put("text", row.getString("content"))
            .put("annotations", flattened).also(ResponsesSearchProtocol::validateTextPart)
    }

    fun validate(row: JSONObject) {
        if (row.has("annotations")) {
            require(row.optString("role") == "assistant") { "网页引用必须属于模型回复" }
            textPart(row)
        }
    }

    fun displayText(row: JSONObject): String {
        val plain = row.getString("content")
        if (!row.has("annotations")) return plain
        return ResponsesSearchProtocol.displayText(JSONObject().put("type", "message")
            .put("content", JSONArray().put(textPart(row))), plain)
    }
}
