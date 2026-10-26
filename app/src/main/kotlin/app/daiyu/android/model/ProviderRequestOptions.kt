package app.daiyu.android.model

import app.daiyu.android.model.sessiongateway.SessionGatewayRule
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** Immutable user request policy, captured with the provider for a run. Never a credential source. */
data class ProviderRequestOptions(
    val customBody: String = "",
    val speedMode: GptSpeedMode? = null,
    val codex: Boolean = false,
    val sessionGateway: SessionGatewayRule = SessionGatewayRule(),
    val anthropicVersion: String? = null,
    val openAiWebSearchEnabled: Boolean = false,
) {
    override fun toString(): String =
        "ProviderRequestOptions(customBody=<redacted>, speedMode=$speedMode, codex=$codex)"

    fun validate(protocol: ProviderProtocol) {
        sessionGateway.validate()
        // Endpoint-specific preferences remain stored; only their matching transport applies them.
        if (anthropicVersion != null) {
            val date = if (Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(anthropicVersion)) {
                try { LocalDate.parse(anthropicVersion) } catch (_: DateTimeParseException) { null }
            } else null
            require(date != null && date.year > 0) { "Anthropic 版本须为有效日期 YYYY-MM-DD" }
        }
        require(customBody.length <= MAX_BODY_BYTES && customBody.toByteArray(Charsets.UTF_8).size <= MAX_BODY_BYTES) {
            "自定义请求体过大"
        }
        if (customBody.isNotEmpty()) {
            val body = parseBody()
            require(body.keys().asSequence().none { it in OWNED_FIELDS }) { "自定义请求体不能覆盖会话协议字段" }
            if (protocol == ProviderProtocol.GEMINI) require(body.keys().asSequence().all { it in setOf("generationConfig", "safetySettings") }) {
                "Gemini 自定义请求体仅支持 generationConfig 与 safetySettings"
            }
            validateBodyOptions(body, protocol)
        }
    }

    internal fun apply(base: JSONObject, protocol: ProviderProtocol): JSONObject {
        if (customBody.isEmpty() && speedMode == null && !codex && anthropicVersion == null && !openAiWebSearchEnabled) return base
        validate(protocol)
        val merged = if (customBody.isEmpty()) base else RequestBodyMerge.merge(base, parseBody())
        if (protocol == ProviderProtocol.RESPONSES) GptServiceTier.apply(merged, speedMode, codex)
        validateBodyOptions(merged, protocol)
        if (protocol != ProviderProtocol.GEMINI) {
            require(merged.has("model")) { "请求模型无效" }
            require(merged.opt("stream") == true) { "请求必须使用流式响应" }
        }
        when (protocol) {
            ProviderProtocol.RESPONSES -> require(merged.opt("input") is JSONArray || merged.opt("input") is String) { "Responses 输入无效" }
            ProviderProtocol.MESSAGES -> {
                require(merged.opt("messages") is JSONArray) { "Messages 输入无效" }
                val tokens = merged.opt("max_tokens")
                require(tokens is Int && tokens > 0) { "Messages 输出上限无效" }
            }
            ProviderProtocol.CHAT_COMPLETIONS -> require(merged.opt("messages") is JSONArray) { "Chat Completions 输入无效" }
            ProviderProtocol.GEMINI -> require(merged.opt("contents") is JSONArray) { "Gemini 输入无效" }
            ProviderProtocol.UNKNOWN -> throw UnsupportedProviderException(protocol.wireName)
        }
        MessagesRequest.validateObject(merged, MessagesRequest.MAX_JSON_CONTAINER_DEPTH)
        return merged
    }

    /** Validate explicit options before persistence and again after merging. Never coerce JSON types. */
    private fun validateBodyOptions(body: JSONObject, protocol: ProviderProtocol) {
        if (protocol == ProviderProtocol.GEMINI) {
            require(!body.has("model") && !body.has("messages") && !body.has("stream")) { "Gemini 模型与流式选择由请求地址确定" }
            if (body.has("generationConfig")) {
                val config = body.opt("generationConfig")
                require(config is JSONObject) { "Gemini generationConfig 必须为 JSON 对象" }
                if (config.has("thinkingConfig")) ReasoningRequestPolicy.validate(body, protocol)
                require(!config.has("candidateCount") || config.opt("candidateCount") == 1) { "当前仅支持一个 Gemini 候选" }
            }
        }
        if (protocol == ProviderProtocol.CHAT_COMPLETIONS && body.has("n")) require(body.opt("n") == 1) { "当前仅支持一个回复候选" }
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
                ProviderProtocol.CHAT_COMPLETIONS -> require(tier is String && tier.isNotBlank()) { "服务档位无效" }
                ProviderProtocol.GEMINI -> throw IllegalArgumentException("Gemini 不支持 service_tier")
                ProviderProtocol.UNKNOWN -> throw UnsupportedProviderException(protocol.wireName)
            }
        }
    }

    internal fun encode(): String = JSONObject().put("custom_body", customBody)
        .put("speed_mode", speedMode?.name ?: JSONObject.NULL).put("codex", codex)
        .put("session_gateway", sessionGateway.toJson())
        .also {
            if (anthropicVersion != null) it.put("anthropic_version", anthropicVersion)
            if (openAiWebSearchEnabled) it.put("openai_web_search_enabled", true)
        }.toString()

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
            "previous_response_id", "conversation", "background", "tool_choice", "parallel_tool_calls",
            "contents", "systemInstruction", "toolConfig", "stream_options", "candidateCount")

        internal fun decode(raw: String?): ProviderRequestOptions {
            if (raw == null) return ProviderRequestOptions()
            return try {
                require(raw.length <= MAX_BODY_BYTES * 6 + 128 * 1024)
                val value = MessagesRequest.jsonObject(raw, 3)
                val keys = value.keys().asSequence().toSet()
                val legacyKeys = setOf("custom_body", "speed_mode", "codex")
                require(keys.containsAll(legacyKeys) && keys.all { it in legacyKeys + setOf("session_gateway", "anthropic_version", "openai_web_search_enabled") })
                val body = value.get("custom_body") as? String ?: error("body")
                val codex = value.get("codex") as? Boolean ?: error("codex")
                val speed = value.get("speed_mode")
                val mode = if (speed == JSONObject.NULL) null else GptSpeedMode.valueOf(speed as? String ?: error("speed"))
                val rule = if (value.has("session_gateway"))
                    SessionGatewayRule.fromJson(value.getJSONObject("session_gateway")) else SessionGatewayRule()
                val version = if (value.has("anthropic_version"))
                    value.get("anthropic_version") as? String ?: error("version") else null
                val search = if (value.has("openai_web_search_enabled"))
                    value.get("openai_web_search_enabled") as? Boolean ?: error("web search") else false
                ProviderRequestOptions(body, mode, codex, rule, version, search)
            } catch (_: Exception) {
                throw IllegalStateException("模型请求配置无效；请重新保存")
            }
        }
    }
}
