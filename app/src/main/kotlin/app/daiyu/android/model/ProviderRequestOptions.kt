package app.daiyu.android.model

import org.json.JSONArray
import org.json.JSONObject

/** Immutable user request policy, captured with the provider for a run. Never a credential source. */
data class ProviderRequestOptions(
    val customBody: String = "",
    val speedMode: GptSpeedMode? = null,
    val codex: Boolean = false
) {
    override fun toString(): String =
        "ProviderRequestOptions(customBody=<redacted>, speedMode=$speedMode, codex=$codex)"

    fun validate(protocol: ProviderProtocol) {
        require(customBody.length <= MAX_BODY_BYTES && customBody.toByteArray(Charsets.UTF_8).size <= MAX_BODY_BYTES) {
            "自定义请求体过大"
        }
        if (customBody.isNotEmpty()) {
            val body = parseBody()
            require(body.keys().asSequence().none { it in OWNED_FIELDS }) { "自定义请求体不能覆盖会话协议字段" }
        }
        require(protocol == ProviderProtocol.RESPONSES || (speedMode == null && !codex)) {
            "GPT 档位仅用于 Responses"
        }
        require(!codex || speedMode != GptSpeedMode.ULTRA_FAST) { "Codex 不支持 ULTRA_FAST" }
    }

    internal fun apply(base: JSONObject, protocol: ProviderProtocol): JSONObject {
        if (customBody.isEmpty() && speedMode == null && !codex) return base
        validate(protocol)
        val merged = if (customBody.isEmpty()) base else RequestBodyMerge.merge(base, parseBody())
        if (protocol == ProviderProtocol.RESPONSES) GptServiceTier.apply(merged, speedMode, codex)
        val model = merged.opt("model") as? String
        require(model != null && model.isNotBlank() && model.length <= 200) { "请求模型无效" }
        require(merged.opt("stream") == true) { "请求必须使用流式响应" }
        when (protocol) {
            ProviderProtocol.RESPONSES -> require(merged.opt("input") is JSONArray || merged.opt("input") is String) { "Responses 输入无效" }
            ProviderProtocol.MESSAGES -> {
                require(merged.opt("messages") is JSONArray) { "Messages 输入无效" }
                val tokens = merged.opt("max_tokens")
                require(tokens is Int && tokens > 0) { "Messages 输出上限无效" }
            }
            ProviderProtocol.UNKNOWN -> throw UnsupportedProviderException(protocol.wireName)
        }
        MessagesRequest.validateObject(merged, MessagesRequest.MAX_JSON_CONTAINER_DEPTH)
        return merged
    }

    internal fun encode(): String = JSONObject().put("custom_body", customBody)
        .put("speed_mode", speedMode?.name ?: JSONObject.NULL).put("codex", codex).toString()

    private fun parseBody(): JSONObject = try {
        MessagesRequest.jsonObject(customBody, MAX_BODY_DEPTH)
    } catch (_: Exception) {
        throw IllegalArgumentException("自定义请求体必须为严格 JSON 对象")
    }

    companion object {
        const val MAX_BODY_BYTES = 64 * 1024
        private const val MAX_BODY_DEPTH = 64
        // History, advertised tools, and continuation state must match the local runner.
        private val OWNED_FIELDS = setOf("input", "messages", "tools", "stream", "store", "include",
            "previous_response_id", "conversation", "background", "tool_choice", "parallel_tool_calls")

        internal fun decode(raw: String?): ProviderRequestOptions {
            if (raw == null) return ProviderRequestOptions()
            return try {
                require(raw.length <= MAX_BODY_BYTES * 6 + 256)
                val value = MessagesRequest.jsonObject(raw, 2)
                require(value.keys().asSequence().toSet() == setOf("custom_body", "speed_mode", "codex"))
                val body = value.get("custom_body") as? String ?: error("body")
                val codex = value.get("codex") as? Boolean ?: error("codex")
                val speed = value.get("speed_mode")
                val mode = if (speed == JSONObject.NULL) null else GptSpeedMode.valueOf(speed as? String ?: error("speed"))
                ProviderRequestOptions(body, mode, codex)
            } catch (_: Exception) {
                throw IllegalStateException("模型请求配置无效；请重新保存")
            }
        }
    }
}
