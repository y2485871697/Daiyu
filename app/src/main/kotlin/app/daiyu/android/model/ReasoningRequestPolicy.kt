package app.daiyu.android.model

import app.daiyu.android.delegation.ReasoningEffort
import org.json.JSONObject

/** Protocol parameters only, not a claim that every remote model supports reasoning.
 * No model-name inference, fallback, retry, or extraction of private internal reasoning.
 * Responses display only reported summary_text; Messages display only native thinking text.
 */
internal object ReasoningRequestPolicy {
    private val keys = setOf("reasoning", "reasoning_effort", "thinking", "output_config")

    fun freeze(provider: ProviderSnapshot, effort: ReasoningEffort): ProviderSnapshot {
        val options = provider.requestOptions
        val body = if (options.customBody.isEmpty()) JSONObject() else MessagesRequest.jsonObject(options.customBody)
        if (effort != ReasoningEffort.DEFAULT) {
            require(keys.none(body::has)) { "思考偏好与自定义推理参数冲突；请选择默认或移除自定义参数" }
            when (provider.protocol) {
                ProviderProtocol.RESPONSES -> {
                    require(effort != ReasoningEffort.MAX) { "Responses 不支持 max 推理强度" }
                    body.put("reasoning", JSONObject().put("effort", if (effort == ReasoningEffort.OFF) "none" else effort.wireValue)
                        .also { if (effort != ReasoningEffort.OFF) it.put("summary", "auto") })
                }
                ProviderProtocol.MESSAGES -> {
                    val budget = when (effort) {
                        ReasoningEffort.OFF -> null
                        ReasoningEffort.MINIMAL -> 1024
                        ReasoningEffort.LOW -> 2048
                        ReasoningEffort.MEDIUM -> 4096
                        ReasoningEffort.HIGH -> 8192
                        else -> throw IllegalArgumentException("Messages 手动 thinking 不支持 ${effort.wireValue}；请选择最小、低、中、高或关闭")
                    }
                    body.put("thinking", JSONObject().put("type", if (budget == null) "disabled" else "enabled")
                        .also { if (budget != null) it.put("budget_tokens", budget) })
                    if (budget != null && !body.has("max_tokens")) body.put("max_tokens", budget + 4096)
                }
                else -> throw IllegalArgumentException("当前协议尚未接通思考偏好；请选择默认")
            }
        }
        validate(body, provider.protocol)
        if (body.toString() == "{}" && options.customBody.isEmpty()) return provider
        val settings = provider.asModelSettings().copy(requestOptions = options.copy(customBody = body.toString()))
        return ProviderSnapshot.capture(settings, provider.protocol, provider.imageInputSupported)
    }

    /** Called on the final merged request, before any connection, on every tool round. */
    fun validate(body: JSONObject, protocol: ProviderProtocol) {
        if (keys.none(body::has)) return
        fun strict(value: JSONObject, key: String): String = value.opt(key) as? String
            ?: throw IllegalArgumentException("推理参数 $key 必须为字符串")
        when (protocol) {
            ProviderProtocol.RESPONSES -> {
                require(listOf("thinking", "reasoning_effort", "output_config").none(body::has)) { "Responses 仅支持 reasoning 对象" }
                val reasoning = body.opt("reasoning") as? JSONObject ?: throw IllegalArgumentException("Responses reasoning 必须为对象")
                require(reasoning.keys().asSequence().all { it in setOf("effort", "summary") }) { "Responses reasoning 包含未支持参数" }
                if (reasoning.has("effort")) require(strict(reasoning, "effort") in setOf("none", "minimal", "low", "medium", "high", "xhigh")) { "Responses reasoning.effort 不支持此值" }
                if (reasoning.has("summary")) require(strict(reasoning, "summary") in setOf("auto", "concise", "detailed")) { "Responses reasoning.summary 不支持此值" }
            }
            ProviderProtocol.MESSAGES -> {
                require(listOf("reasoning", "reasoning_effort", "output_config").none(body::has)) { "Messages 仅接通手动 thinking；adaptive/output_config 尚未支持" }
                val thinking = body.opt("thinking") as? JSONObject ?: throw IllegalArgumentException("Messages thinking 必须为对象")
                when (strict(thinking, "type")) {
                    "disabled" -> require(thinking.keys().asSequence().toSet() == setOf("type")) { "禁用 thinking 不能附带预算或未知参数" }
                    "enabled" -> {
                        require(thinking.keys().asSequence().toSet() == setOf("type", "budget_tokens")) { "Messages thinking 仅支持 type/budget_tokens" }
                        val budget = thinking.opt("budget_tokens")
                        require(budget is Int && budget >= 1024) { "Messages thinking.budget_tokens 必须为至少 1024 的整数" }
                        val maximum = body.opt("max_tokens") ?: 4096
                        require(maximum is Int && maximum > budget) { "Messages max_tokens 必须大于 thinking.budget_tokens" }
                        require(!body.has("temperature") || body.opt("temperature") is Number && (body.opt("temperature") as Number).toDouble() == 1.0) { "手动 thinking 仅支持 temperature=1" }
                        require(listOf("top_p", "top_k").none(body::has)) { "手动 thinking 尚未接通 top_p/top_k" }
                    }
                    else -> throw IllegalArgumentException("Messages 仅支持 enabled/disabled thinking；adaptive 尚未接通")
                }
            }
            else -> throw IllegalArgumentException("当前协议尚未接通推理参数")
        }
    }
}
