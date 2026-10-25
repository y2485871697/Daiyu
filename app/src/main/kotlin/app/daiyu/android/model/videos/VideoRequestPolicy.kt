package app.daiyu.android.model.videos

import app.daiyu.android.model.MessagesRequest
import app.daiyu.android.storage.ModelSettings
import org.json.JSONArray
import org.json.JSONObject

/** Video-only fail-closed policy. Unknown gateway extensions may change the recipient even when
 * `model` is unchanged. Do not turn this into a routing-key denylist or relax the shared options.
 */
internal object VideoRequestPolicy {
    // Only scalar, standard sampling/output-budget controls; no arbitrary objects or extensions.
    private val optionFields = setOf("temperature", "top_p", "max_output_tokens")
    private val protocolFields = setOf("model", "input", "stream", "store", "include", "tools", "instructions", "reasoning")

    fun disableReasoning(request: JSONObject) {
        require(!app.daiyu.android.model.ReasoningRequestPolicy.hasReasoning(request)) { "Video reasoning policy conflicts with request options" }
        request.put("reasoning", JSONObject().put("effort", "none"))
    }

    fun preflight(settings: ModelSettings) {
        val options = settings.requestOptions
        options.validate(settings.protocol)
        // Service tiers/speed presets can influence routing; none are authorized by a video grant.
        require(options.speedMode == null && !options.codex) { "Video requests do not permit service routing presets" }
        if (options.customBody.isEmpty()) return
        val body = MessagesRequest.jsonObject(options.customBody, 64)
        require(body.keys().asSequence().all { it in optionFields }) {
            "Video custom body permits only scalar sampling and output-budget options"
        }
        validateScalars(body)
    }

    /** Recheck the actual payload after every merge, including tool-continuation rounds. */
    fun finalPayload(settings: ModelSettings, request: JSONObject, input: JSONArray) {
        preflight(settings)
        require(request.keys().asSequence().all { it in protocolFields || it in optionFields }) {
            "Video payload contains an unauthorized extension"
        }
        validateScalars(request)
        val reasoning = request.optJSONObject("reasoning")
        require(reasoning != null && reasoning.keys().asSequence().toSet() == setOf("effort") && reasoning.opt("effort") == "none") {
            "Video requests require explicit reasoning.effort=none"
        }
        require(request.opt("model") == settings.model) { "Custom body cannot change the video model" }
        require(request.optJSONArray("input")?.toString() == input.toString()) {
            "Custom body cannot change frozen video history"
        }
        require(request.opt("stream") == true && request.opt("store") == false &&
            request.optJSONArray("include")?.toString() == JSONArray().put("reasoning.encrypted_content").toString()) {
            "Video payload cannot change session protocol controls"
        }
        require(!request.has("instructions") || request.opt("instructions") is String) {
            "Video instructions must be plain text"
        }
    }

    private fun validateScalars(body: JSONObject) {
        for ((key, upper) in listOf("temperature" to 2.0, "top_p" to 1.0)) {
            if (!body.has(key)) continue
            val value = body.opt(key)
            require(value is Number && value.toDouble().isFinite() && value.toDouble() in 0.0..upper) {
                "Video sampling options must be finite numbers in range"
            }
        }
        if (body.has("max_output_tokens")) {
            val value = body.opt("max_output_tokens")
            require((value is Int && value > 0) || (value is Long && value > 0)) {
                "Video output budget must be a positive integer"
            }
        }
    }
}
