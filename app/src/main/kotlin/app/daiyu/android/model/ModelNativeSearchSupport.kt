package app.daiyu.android.model

import app.daiyu.android.domain.ToolDefinition
import java.util.Locale

/** The picker and request transports share the same model/protocol boundary. */
internal object ModelNativeSearchSupport {
    fun supports(protocol: ProviderProtocol, model: String, endpoint: String?): Boolean {
        val id = model.trim().lowercase(Locale.ROOT)
        if (id.isEmpty() || listOf("embedding", "whisper", "tts", "transcribe", "realtime", "audio", "image", "video", "music").any(id::contains)) return false
        val family = runCatching { NativeSearchPolicy.family(endpoint) }.getOrNull() ?: return false
        return when (protocol) {
            ProviderProtocol.RESPONSES -> when (family) {
                NativeSearchPolicy.Family.OPENAI -> openAi(id)
                NativeSearchPolicy.Family.XAI -> id.startsWith("grok-")
                NativeSearchPolicy.Family.ARK -> arkResponses(id)
                NativeSearchPolicy.Family.QWEN -> id in qwenResponsesModels
                NativeSearchPolicy.Family.DEEPSEEK -> deepSeek(protocol, id)
                NativeSearchPolicy.Family.MINIMAX -> id.startsWith("minimax-")
                NativeSearchPolicy.Family.COMPATIBLE -> openAi(id) || id.startsWith("grok-") || id.startsWith("deepseek-")
                else -> false
            }
            ProviderProtocol.CHAT_COMPLETIONS -> when (family) {
                NativeSearchPolicy.Family.OPENAI -> id in setOf("gpt-4o-search-preview", "gpt-4o-mini-search-preview", "gpt-5-search-api") ||
                    id.startsWith("gpt-4o-search-preview-") || id.startsWith("gpt-4o-mini-search-preview-")
                NativeSearchPolicy.Family.QWEN -> id.startsWith("qwen") || id in qwenDeepSeekModels
                NativeSearchPolicy.Family.GLM -> id.startsWith("glm-")
                NativeSearchPolicy.Family.ARK -> ark(id)
                NativeSearchPolicy.Family.DEEPSEEK -> deepSeek(protocol, id)
                NativeSearchPolicy.Family.PERPLEXITY -> id == "sonar" || id.startsWith("sonar-")
                NativeSearchPolicy.Family.HUNYUAN -> id.startsWith("hunyuan-") && id != "hunyuan-lite"
                NativeSearchPolicy.Family.BAIDU -> id.startsWith("ernie-") && id !in setOf("ernie-5.5-flash-preview", "ernie-light-dt-1.0")
                NativeSearchPolicy.Family.STEPFUN -> id.startsWith("step-") && !id.startsWith("step-5-preview")
                else -> false
            }
            ProviderProtocol.GEMINI -> family in setOf(NativeSearchPolicy.Family.GOOGLE, NativeSearchPolicy.Family.COMPATIBLE) && id.removePrefix("models/").let { it.startsWith("gemini-") && "live" !in it.split('-') }
            ProviderProtocol.MESSAGES -> when (family) {
                NativeSearchPolicy.Family.ANTHROPIC, NativeSearchPolicy.Family.COMPATIBLE -> id.startsWith("claude-")
                NativeSearchPolicy.Family.MINIMAX -> id.startsWith("minimax-")
                NativeSearchPolicy.Family.ARK -> id == "doubao-seed-2-1-pro-260915"
                NativeSearchPolicy.Family.DEEPSEEK -> deepSeek(protocol, id)
                else -> false
            }
            else -> false
        }
    }

    fun canMixTools(protocol: ProviderProtocol, model: String, endpoint: String?, tools: List<ToolDefinition>): Boolean {
        if (tools.isEmpty()) return true
        val family = NativeSearchPolicy.family(endpoint)
        return when (protocol) {
            ProviderProtocol.GEMINI -> false
            ProviderProtocol.CHAT_COMPLETIONS -> family !in setOf(NativeSearchPolicy.Family.OPENAI, NativeSearchPolicy.Family.ARK)
            ProviderProtocol.MESSAGES -> tools.none { it.name == NativeSearchPolicy.messagesSearchName(model, endpoint) }
            else -> true
        }
    }

    private fun modelFamily(id: String, family: String) = id == family || id.startsWith("$family-") || id.startsWith("$family.")

    private fun openAi(id: String) = id in openAiResponsesModels || modelFamily(id, "gpt-4o") ||
        modelFamily(id, "gpt-4.1") || modelFamily(id, "gpt-5") || modelFamily(id, "o3") || modelFamily(id, "o4-mini")

    private fun deepSeek(protocol: ProviderProtocol, id: String) =
        protocol == ProviderProtocol.MESSAGES && id.startsWith("claude") || NativeSearchPolicy.isDeepSeekFlash(id) ||
        id == "deepseek-v4-pro" && protocol != ProviderProtocol.CHAT_COMPLETIONS

    private fun arkResponses(id: String) = id != "doubao-seed-character-251128" &&
        (ark(id) || id in setOf("glm-5-3-flash-260828", "glm-5-2-260617", "kimi-k3-1-pro-261024"))

    private val openAiResponsesModels = setOf("gpt-6-astra", "gpt-6-sol", "gpt-6.1-sol", "gpt-6.1-terra", "gpt-6-luna", "gpt-6-cyber")

    private val qwenDeepSeekModels = setOf("deepseek-v4-pro", "deepseek-v4-pro-0813",
        "deepseek-v4-flash-0731", "deepseek-v4.1-flash-1017")

    private val qwenResponsesModels = qwenDeepSeekModels + setOf(
        "qwen4-max", "qwen3.8-max", "qwen3.8-max-0902", "qwen3.8-flash", "qwen3.8-2.4t-a95b", "qwen3.8-27b", "qwen3.8-omni-flash",
        "qwen3.7-max", "qwen3.7-max-preview", "qwen3.7-max-2026-05-17", "qwen3.7-max-2026-05-20", "qwen3.7-max-2026-06-08",
        "qwen3.7-plus", "qwen3.7-plus-2026-05-26", "qwen3.7-flash", "qwen3.7-flash-2026-07-15",
        "qwen3.6-plus", "qwen3.6-plus-2026-04-02", "qwen3.6-flash", "qwen3.6-flash-2026-04-16", "qwen3.6-35b-a3b",
        "qwen3.5-plus", "qwen3.5-plus-2026-02-15", "qwen3.5-plus-2026-04-20", "qwen3.5-flash", "qwen3.5-flash-2026-02-23",
        "qwen3.5-27b", "qwen3.5-35b-a3b", "qwen3.5-122b-a10b", "qwen3.5-397b-a17b", "glm-5.2", "kimi-k3", "kimi-k3.1-pro")
    private fun ark(id: String) = id.startsWith("doubao-") || id.startsWith("deepseek-") || id.startsWith("ep-")
}
