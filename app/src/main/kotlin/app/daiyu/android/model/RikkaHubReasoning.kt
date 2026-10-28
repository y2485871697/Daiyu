/* Adapted from RikkaHub ai provider request builders, AGPL-3.0.
 * Upstream ad2c4430d50ba52392b2941367f98ef59614cb73; see THIRD_PARTY/rikkahub/NOTICE.md.
 */
package app.daiyu.android.model

import app.daiyu.android.delegation.ReasoningEffort
import org.json.JSONObject
import java.net.URI

/** Provider wire rules, not a capability inference for arbitrary model names. */
internal object RikkaHubReasoning {
    fun chat(body: JSONObject, endpoint: String, model: String, effort: ReasoningEffort) {
        val enabled = effort != ReasoningEffort.OFF
        val wire = if (enabled) effort.wireValue else "none"
        fun thinking() { body.put("thinking", JSONObject().put("type", if (enabled) "enabled" else "disabled")) }
        when (URI(endpoint).host.lowercase()) {
            "openrouter.ai" -> body.put("reasoning", JSONObject().put("effort", wire))
            "ark.cn-beijing.volces.com", "open.bigmodel.cn", "api.xiaomimimo.com", "token-plan-cn.xiaomimimo.com", "api.moonshot.cn" -> {
                thinking()
                if (enabled && URI(endpoint).host.equals("api.moonshot.cn", true) && model.contains("kimi-k2.6", true))
                    body.getJSONObject("thinking").put("keep", "all")
            }
            "chat.intern-ai.org.cn" -> body.put("thinking_mode", enabled)
            "api.siliconflow.cn", "aiping.cn" -> body.put("enable_thinking", enabled)
            "api.deepseek.com" -> {
                thinking()
                body.put("reasoning_effort", wire)
            }
            else -> body.put("reasoning_effort", wire)
        }
    }

    fun gemini(body: JSONObject, model: String, effort: ReasoningEffort) {
        val generation = if (body.has("generationConfig")) body.opt("generationConfig") as? JSONObject
            ?: throw IllegalArgumentException("Gemini generationConfig 必须为 JSON 对象") else JSONObject()
        val enabled = effort != ReasoningEffort.OFF
        val thinking = JSONObject().put("includeThoughts", enabled)
        val levelModel = usesGeminiLevel(model)
        if (levelModel) thinking.put("thinkingLevel", if (enabled) effort.wireValue else "none") else if (enabled) thinking.put("thinkingBudget", when (effort) {
            ReasoningEffort.MINIMAL -> 512
            ReasoningEffort.LOW -> 1000
            ReasoningEffort.MEDIUM -> 2000
            ReasoningEffort.HIGH -> 8000
            ReasoningEffort.XHIGH -> 16000
            ReasoningEffort.MAX -> 32000
            else -> error("Unresolved thinking preference")
        }) else thinking.put("thinkingBudget", 0)
        body.put("generationConfig", generation.put("thinkingConfig", thinking))
    }

    private fun usesGeminiLevel(model: String): Boolean {
        val tokens = tokenize(model)
        fun matches(sequence: List<String>): Boolean {
            var at = 0
            tokens.forEachIndexed { index, token ->
                if (token.first().isDigit() && tokens.getOrNull(index + 1) in setOf("b", "k")) return@forEachIndexed
                if (token == sequence[at] && ++at == sequence.size) return true
            }
            return false
        }
        return listOf(listOf("gemini", "3", "pro"), listOf("gemini", "3", "flash"), listOf("gemini", "3", "5"), listOf("gemini", "4")).any(::matches)
    }

    private fun tokenize(modelId: String): List<String> {
        val tokens = mutableListOf<String>()
        val input = modelId.lowercase()
        var index = 0
        while (index < input.length) {
            val ch = input[index]
            when {
                ch.isLetter() -> {
                    val start = index
                    index += 1
                    while (index < input.length && input[index].isLetter()) {
                        index += 1
                    }
                    tokens.add(input.substring(start, index))
                }

                ch.isDigit() -> {
                    val start = index
                    index += 1
                    while (index < input.length && input[index].isDigit()) {
                        index += 1
                    }
                    tokens.add(input.substring(start, index))
                }

                else -> {
                    tokens.add(ch.toString())
                    index += 1
                }
            }
        }
        return tokens
    }
}
