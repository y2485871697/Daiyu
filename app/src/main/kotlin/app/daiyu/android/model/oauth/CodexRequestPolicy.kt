package app.daiyu.android.model.oauth

import app.daiyu.android.model.GptServiceTier
import app.daiyu.android.model.GptSpeedMode
import org.json.JSONArray
import org.json.JSONObject

internal object CodexRequestPolicy {
    fun apply(request: JSONObject, speed: GptSpeedMode?, preserveReasoning: Boolean = false) {
        request.put("store", false)
        if (!request.has("instructions")) request.put("instructions", "")
        require(request.opt("instructions") is String) { "Codex instructions 必须为字符串" }
        request.put("include", JSONArray().put("reasoning.encrypted_content"))
        request.remove("max_output_tokens")
        val reasoning = request.optJSONObject("reasoning") ?: JSONObject().also { request.put("reasoning", it) }
        if (!preserveReasoning && !reasoning.has("effort")) reasoning.put("effort", "medium")
        if (!preserveReasoning && reasoning.optString("effort") != "none" && !reasoning.has("summary")) reasoning.put("summary", "auto")
        GptServiceTier.apply(request, speed, codex = true)
    }

    fun routingHint(request: JSONObject): String? {
        val model = request.opt("model") as? String ?: return null
        val tier = request.opt("service_tier") as? String
        val hint = "model=$model" + if (tier == null) "" else ";tier=$tier"
        return hint.takeIf { value -> value.all { it.code in 32..126 } }
    }
}
