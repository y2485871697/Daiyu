package app.daiyu.android.model.oauth

import app.daiyu.android.model.GptServiceTier
import app.daiyu.android.model.GptSpeedMode
import org.json.JSONArray
import org.json.JSONObject

internal object CodexRequestPolicy {
    fun apply(request: JSONObject, speed: GptSpeedMode?) {
        request.put("store", false)
        if (!request.has("instructions")) request.put("instructions", "")
        require(request.opt("instructions") is String) { "Codex instructions 必须为字符串" }
        request.put("include", JSONArray().put("reasoning.encrypted_content"))
        request.remove("max_output_tokens")
        val reasoning = request.optJSONObject("reasoning") ?: JSONObject().also { request.put("reasoning", it) }
        // Codex has no no-thinking wire mode. Keep that backend constraint explicit in the UI.
        when (reasoning.optString("effort")) {
            "", "none" -> reasoning.put("effort", "medium")
            "minimal" -> reasoning.put("effort", "low")
            "max" -> reasoning.put("effort", "high")
        }
        if (!reasoning.has("summary")) reasoning.put("summary", "auto")
        GptServiceTier.apply(request, speed, codex = true)
        require(!request.optString("service_tier").equals("ultrafast", ignoreCase = true)) { "Codex 不支持 ULTRA_FAST" }
    }
}
