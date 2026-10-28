package app.daiyu.android.model

import app.daiyu.android.delegation.ReasoningEffort
import org.json.JSONObject
import app.daiyu.android.domain.RequestReasoningEffort

/** Freeze the chosen wire policy once; validate the final merged body on every tool round. */
internal object ReasoningRequestPolicy {
    private val keys = setOf("reasoning", "reasoning_effort", "thinking", "output_config", "enable_thinking", "thinking_budget", "thinking_mode")
    private val efforts = setOf("none", "minimal", "low", "medium", "high", "xhigh", "max")
    fun hasReasoning(body: JSONObject): Boolean = keys.any(body::has) ||
        body.optJSONObject("generationConfig")?.has("thinkingConfig") == true

    fun requestModel(model: String, options: ProviderRequestOptions): String =
        if (options.customBody.isEmpty()) model else requestModel(model, MessagesRequest.jsonObject(options.customBody))

    private fun requestModel(model: String, body: JSONObject): String = if (!body.has("model")) model else
        (body.opt("model") as? String)?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("model 必须为非空字符串")

    fun freeze(provider: ProviderSnapshot, requested: ReasoningEffort, summary: Boolean = false): ProviderSnapshot {
        val options = provider.requestOptions
        val body = if (options.customBody.isEmpty()) JSONObject() else MessagesRequest.jsonObject(options.customBody)
        val model = requestModel(provider.model, body)
        val effort = requested
        if (effort != ReasoningEffort.DEFAULT) {
            require(!hasReasoning(body)) { "思考偏好与自定义推理参数冲突；请移除自定义推理参数后重试" }
            when (provider.protocol) {
                ProviderProtocol.RESPONSES -> body.put("reasoning", JSONObject()
                    .put("effort", if (effort == ReasoningEffort.OFF) "none" else effort.wireValue)
                    .also { if (!summary && effort != ReasoningEffort.OFF && java.net.URI(provider.asModelSettings().endpoint).host != "ark.cn-beijing.volces.com") it.put("summary", "auto") })
                ProviderProtocol.MESSAGES -> {
                    when (effort) {
                        ReasoningEffort.OFF -> body.put("thinking", JSONObject().put("type", "disabled"))
                        // MINIMAL is a Daiyu legacy choice absent from upstream adaptive effort.
                        ReasoningEffort.MINIMAL -> {
                            body.put("thinking", JSONObject().put("type", "enabled").put("budget_tokens", 1024))
                            if (!body.has("max_tokens")) body.put("max_tokens", MessagesRequest.DEFAULT_MAX_TOKENS)
                        }
                        else -> {
                            body.put("thinking", JSONObject().put("type", "adaptive").put("display", "summarized"))
                            body.put("output_config", JSONObject().put("effort", effort.wireValue))
                        }
                    }
                }
                ProviderProtocol.CHAT_COMPLETIONS -> {
                    RikkaHubReasoning.chat(body, provider.asModelSettings().endpoint, model, effort)
                }
                ProviderProtocol.GEMINI -> RikkaHubReasoning.gemini(body, model, effort)
                else -> throw IllegalArgumentException("当前协议尚未接通思考偏好")
            }
        }
        validate(body, provider.protocol, if (summary) null else provider.reasoningOverride, if (summary) null else model)
        if (body.toString() == "{}" && options.customBody.isEmpty()) return provider
        return ProviderSnapshot.capture(provider.asModelSettings().copy(requestOptions = options.copy(customBody = body.toString())), provider.protocol, provider.imageInputSupported)
    }

    fun wireEffort(body: JSONObject, param: String): RequestReasoningEffort? {
        val value = when (param) {
            "reasoning.effort" -> body.optJSONObject("reasoning")?.opt("effort")
            "reasoning_effort" -> body.opt("reasoning_effort")
            "output_config.effort" -> body.optJSONObject("output_config")?.opt("effort")
            "generationConfig.thinkingConfig.thinkingLevel" -> body.optJSONObject("generationConfig")?.optJSONObject("thinkingConfig")?.opt("thinkingLevel")
            else -> null
        } as? String ?: return null
        if (value !in efforts) return null
        val effort = ReasoningEffort.fromWireValue(value) ?: return null
        return RequestReasoningEffort.valueOf(effort.name)
    }

    fun validate(body: JSONObject, protocol: ProviderProtocol, reasoningOverride: Boolean? = null, model: String? = null) {
        if (!hasReasoning(body)) return
        fun string(value: JSONObject, key: String): String = value.opt(key) as? String ?: throw IllegalArgumentException("推理参数 $key 必须为字符串")
        fun effort(value: JSONObject, key: String): String = string(value, key).also {
            require(it in efforts) { "推理参数 $key 不支持此值" }
        }
        fun boolean(value: JSONObject, key: String) { if (value.has(key)) require(value.opt(key) is Boolean) { "推理参数 $key 必须为布尔值" } }
        fun objectValue(value: JSONObject, key: String): JSONObject = value.opt(key) as? JSONObject ?: throw IllegalArgumentException("推理参数 $key 必须为对象")
        fun noOtherKeys(allowed: Set<String>) { require((keys - allowed).none(body::has) && body.optJSONObject("generationConfig")?.has("thinkingConfig") != true) { "思考参数与当前协议不匹配" } }
        when (protocol) {
            ProviderProtocol.RESPONSES -> {
                noOtherKeys(setOf("reasoning"))
                val value = objectValue(body, "reasoning")
                require(value.keys().asSequence().all { it in setOf("effort", "summary") })
                if (value.has("effort")) effort(value, "effort")
                if (value.has("summary")) require(string(value, "summary") in setOf("auto", "concise", "detailed"))
            }
            ProviderProtocol.MESSAGES -> {
                noOtherKeys(setOf("thinking", "output_config"))
                val thinking = if (body.has("thinking")) objectValue(body, "thinking") else null
                when (thinking?.let { string(it, "type") }) {
                    null -> Unit
                    "disabled" -> require(thinking.keys().asSequence().toSet() == setOf("type"))
                    "adaptive" -> {
                        require(thinking.keys().asSequence().all { it in setOf("type", "display") })
                        if (thinking.has("display")) require(string(thinking, "display") in setOf("summarized", "omitted"))
                    }
                    "enabled" -> {
                        require(thinking.keys().asSequence().toSet() == setOf("type", "budget_tokens"))
                        val budget = thinking.opt("budget_tokens")
                        require(budget is Int && budget >= 1024) { "Messages thinking.budget_tokens 必须为至少 1024 的整数" }
                        val maximum = body.opt("max_tokens") ?: MessagesRequest.DEFAULT_MAX_TOKENS
                        require(maximum is Int && maximum > budget) { "Messages max_tokens 必须大于 thinking.budget_tokens" }
                        require(!body.has("temperature") || body.opt("temperature") is Number && (body.opt("temperature") as Number).toDouble() == 1.0)
                        require(listOf("top_p", "top_k").none(body::has))
                    }
                    else -> throw IllegalArgumentException("Messages thinking.type 无效")
                }
                if (body.has("output_config")) {
                    val config = objectValue(body, "output_config")
                    if (config.has("effort")) {
                        effort(config, "effort")
                    }
                    require(thinking?.opt("type") != "disabled" || !config.has("effort"))
                }
            }
            ProviderProtocol.CHAT_COMPLETIONS -> {
                noOtherKeys(keys - setOf("output_config"))
                if (body.has("reasoning_effort")) effort(body, "reasoning_effort")
                for (key in listOf("enable_thinking", "thinking_mode")) boolean(body, key)
                if (body.has("thinking_budget")) require(body.opt("thinking_budget") is Int && body.getInt("thinking_budget") >= 0)
                if (body.has("thinking")) {
                    val thinking = objectValue(body, "thinking")
                    require(string(thinking, "type") in setOf("enabled", "disabled"))
                    if (thinking.has("keep")) require(string(thinking, "keep") == "all")
                }
                if (body.has("reasoning")) {
                    val value = objectValue(body, "reasoning")
                    if (value.has("effort")) effort(value, "effort")
                    boolean(value, "enabled"); boolean(value, "exclude")
                    if (value.has("max_tokens")) require(value.opt("max_tokens") is Int && value.getInt("max_tokens") >= 0)
                }
            }
            ProviderProtocol.GEMINI -> {
                require(keys.none(body::has)) { "Gemini 仅支持嵌套 thinkingConfig" }
                val thinking = objectValue(objectValue(body, "generationConfig"), "thinkingConfig")
                require(thinking.keys().asSequence().all { it in setOf("thinkingBudget", "thinkingLevel", "includeThoughts") })
                require(!(thinking.has("thinkingBudget") && thinking.has("thinkingLevel"))) { "Gemini 思考预算和档位不能同时设置" }
                boolean(thinking, "includeThoughts")
                if (thinking.has("thinkingBudget")) require(thinking.opt("thinkingBudget") is Int && thinking.getInt("thinkingBudget") >= -1)
                if (thinking.has("thinkingLevel")) {
                    effort(thinking, "thinkingLevel")
                }
            }
            else -> throw IllegalArgumentException("当前协议尚未接通推理参数")
        }
    }
}
