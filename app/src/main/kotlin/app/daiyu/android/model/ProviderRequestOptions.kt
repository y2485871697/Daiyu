package app.daiyu.android.model

import app.daiyu.android.model.sessiongateway.SessionGatewayRule
import org.json.JSONArray
import org.json.JSONObject

/** Immutable user request policy, captured with the provider for a run. Never a credential source. */
data class ProviderRequestOptions(
    val customBody: String = "",
    val speedMode: GptSpeedMode? = null,
    val codex: Boolean = false,
    val sessionGateway: SessionGatewayRule = SessionGatewayRule(),
) {
    override fun toString(): String =
        "ProviderRequestOptions(customBody=<redacted>, speedMode=$speedMode, codex=$codex)"

    fun validate(protocol: ProviderProtocol) {
        sessionGateway.validate()
        require(customBody.length <= MAX_BODY_BYTES && customBody.toByteArray(Charsets.UTF_8).size <= MAX_BODY_BYTES) {
            "自定义请求体过大"
        }
        if (customBody.isNotEmpty()) {
            val body = parseBody()
            require(body.keys().asSequence().none { it in OWNED_FIELDS }) { "自定义请求体不能覆盖会话协议字段" }
            validateBodyOptions(body, protocol)
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
        validateBodyOptions(merged, protocol)
        require(merged.has("model")) { "请求模型无效" }
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

    /** Validate explicit options before persistence and again after merging. Never coerce JSON types. */
    private fun validateBodyOptions(body: JSONObject, protocol: ProviderProtocol) {
        if (body.has("model")) {
            val model = body.opt("model") as? String
            require(model != null && model.isNotBlank() && model.length <= 200) { "请求模型无效" }
        }
        if (protocol == ProviderProtocol.MESSAGES && body.has("max_tokens")) {
            val tokens = body.opt("max_tokens")
            require(tokens is Int && tokens > 0) { "Messages 输出上限无效" }
        }
        if (body.has("service_tier")) {
            val tier = body.opt("service_tier")
            when (protocol) {
                // Gateway tiers (including fast/ultrafast) are deliberately not an OpenAI enum whitelist.
                // An omitted/nullable Responses tier leaves selection to the server.
                ProviderProtocol.RESPONSES -> require(tier == JSONObject.NULL || tier is String && tier.isNotBlank()) {
                    "Responses 服务档位必须为非空字符串或 null"
                }
                // Messages has its own capacity policy; GPT speed modes must not be mapped to it.
                ProviderProtocol.MESSAGES -> require(tier is String && tier in setOf("auto", "standard_only")) {
                    "Messages 服务档位无效"
                }
                ProviderProtocol.UNKNOWN -> throw UnsupportedProviderException(protocol.wireName)
            }
        }
    }

    internal fun encode(): String = JSONObject().put("custom_body", customBody)
        .put("speed_mode", speedMode?.name ?: JSONObject.NULL).put("codex", codex)
        .put("session_gateway", sessionGateway.toJson()).toString()

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
                require(raw.length <= MAX_BODY_BYTES * 6 + 128 * 1024)
                val value = MessagesRequest.jsonObject(raw, 3)
                val keys = value.keys().asSequence().toSet()
                val legacyKeys = setOf("custom_body", "speed_mode", "codex")
                require(keys == legacyKeys || keys == legacyKeys + "session_gateway")
                val body = value.get("custom_body") as? String ?: error("body")
                val codex = value.get("codex") as? Boolean ?: error("codex")
                val speed = value.get("speed_mode")
                val mode = if (speed == JSONObject.NULL) null else GptSpeedMode.valueOf(speed as? String ?: error("speed"))
                val rule = if (value.has("session_gateway"))
                    SessionGatewayRule.fromJson(value.getJSONObject("session_gateway")) else SessionGatewayRule()
                ProviderRequestOptions(body, mode, codex, rule)
            } catch (_: Exception) {
                throw IllegalStateException("模型请求配置无效；请重新保存")
            }
        }
    }
}
