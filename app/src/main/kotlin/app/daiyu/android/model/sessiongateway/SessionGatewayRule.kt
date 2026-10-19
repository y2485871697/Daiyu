package app.daiyu.android.model.sessiongateway

import app.daiyu.android.model.MessagesRequest
import org.json.JSONObject

/** Five frozen editor fields. retention/keySource are NOT inferred TTL or wire dispatch policy. */
data class SessionGatewayRule(
    val modelPattern: String = DEFAULT_MODEL,
    val pathPattern: String = DEFAULT_PATH,
    val retention: String = DEFAULT_RETENTION,
    val keySource: String = DEFAULT_SOURCE,
    val keyField: String = DEFAULT_FIELD,
) {
    fun matches(model: String, url: String): Boolean =
        modelPattern.lineSequence().filter { it.isNotBlank() }.any { pattern ->
            runCatching { Regex(pattern).matches(model) }.getOrDefault(false)
        } && pathPattern.lineSequence().filter { it.isNotBlank() }.any { pattern ->
            runCatching { Regex(pattern).containsMatchIn(url) }.getOrDefault(false)
        }

    /** A literal top-level JSON key, never a JSON path. Invalid syntax retains the frozen fallback. */
    fun safeKeyField(): String {
        val field = keyField.takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,80}")) } ?: DEFAULT_FIELD
        require(field !in RUNTIME_FIELDS) { "会话网关键名不能覆盖运行时字段" }
        return field
    }

    fun validate() {
        require(listOf(modelPattern, pathPattern, retention, keySource, keyField).all { it.length <= MAX_FIELD_LENGTH }) {
            "会话网关规则字段过长"
        }
        safeKeyField()
    }

    fun unsupportedSemantics(): String? = when {
        retention != DEFAULT_RETENTION -> "retention 的非默认值语义未核实；已保留供编辑，不能用于请求"
        keySource != DEFAULT_SOURCE -> "keySource 的非默认值语义未核实；已保留供编辑，不能用于请求"
        else -> null
    }

    internal fun requireExecutable() {
        validate()
        require(unsupportedSemantics() == null) { unsupportedSemantics().orEmpty() }
    }

    internal fun toJson(): JSONObject = JSONObject().put("modelPattern", modelPattern).put("pathPattern", pathPattern)
        .put("retention", retention).put("keySource", keySource).put("keyField", keyField)

    companion object {
        const val DEFAULT_MODEL = "^gpt-.*$"
        const val DEFAULT_PATH = "/v1/responses"
        const val DEFAULT_RETENTION = "继承全局默认"
        const val DEFAULT_SOURCE = "gjson"
        const val DEFAULT_FIELD = "prompt_cache_key"
        private const val MAX_FIELD_LENGTH = 4096
        private val RUNTIME_FIELDS = setOf(
            "model", "input", "messages", "tools", "stream", "store", "instructions", "system", "max_tokens",
            "max_output_tokens", "include", "previous_response_id", "conversation", "background", "tool_choice",
            "parallel_tool_calls", "metadata", "service_tier", "reasoning", "temperature", "top_p", "stop_sequences",
            "contents", "systemInstruction", "generationConfig", "toolConfig", "stream_options", "n"
        )

        fun encode(rule: SessionGatewayRule): String = rule.toJson().toString()

        /** Frozen standalone codec: blank/malformed input falls back; unknown fields are ignored. */
        fun decode(raw: String): SessionGatewayRule = if (raw.isBlank()) SessionGatewayRule() else
            runCatching { fromJson(MessagesRequest.jsonObject(raw, 2)) }.getOrDefault(SessionGatewayRule())

        internal fun fromJson(value: JSONObject): SessionGatewayRule {
            fun field(name: String, default: String): String = if (value.has(name))
                value.get(name) as? String ?: error("Invalid session gateway field") else default
            return SessionGatewayRule(field("modelPattern", DEFAULT_MODEL), field("pathPattern", DEFAULT_PATH),
                field("retention", DEFAULT_RETENTION), field("keySource", DEFAULT_SOURCE), field("keyField", DEFAULT_FIELD))
                .also { require(listOf(it.modelPattern, it.pathPattern, it.retention, it.keySource, it.keyField).all { s -> s.length <= MAX_FIELD_LENGTH }) }
        }
    }
}
