package app.daiyu.android.model

import app.daiyu.android.domain.ToolDefinition
import java.util.Locale

/** The picker and request transports share the same model/protocol boundary. */
internal object ModelNativeSearchSupport {
    fun supports(protocol: ProviderProtocol, model: String, endpoint: String?): Boolean {
        val id = model.trim().lowercase(Locale.ROOT)
        if (id.isEmpty() || listOf("embedding", "whisper", "tts", "transcribe", "realtime", "audio", "image", "music").any(id::contains)) return false
        val family = runCatching { NativeSearchPolicy.family(endpoint) }.getOrNull() ?: return false
        return when (protocol) {
            ProviderProtocol.RESPONSES -> when (family) {
                NativeSearchPolicy.Family.OPENAI -> openAi(id)
                NativeSearchPolicy.Family.XAI -> id.startsWith("grok-")
                NativeSearchPolicy.Family.ARK -> ark(id)
                NativeSearchPolicy.Family.DEEPSEEK -> id.startsWith("deepseek-")
                NativeSearchPolicy.Family.MINIMAX -> id.startsWith("minimax-")
                NativeSearchPolicy.Family.COMPATIBLE -> openAi(id) || id.startsWith("grok-") || id.startsWith("deepseek-")
                else -> false
            }
            ProviderProtocol.CHAT_COMPLETIONS -> when (family) {
                NativeSearchPolicy.Family.OPENAI -> id in setOf("gpt-4o-search-preview", "gpt-4o-mini-search-preview", "gpt-5-search-api") ||
                    id.startsWith("gpt-4o-search-preview-") || id.startsWith("gpt-4o-mini-search-preview-")
                NativeSearchPolicy.Family.QWEN -> id.startsWith("qwen")
                NativeSearchPolicy.Family.GLM -> id.startsWith("glm-")
                NativeSearchPolicy.Family.ARK -> ark(id)
                NativeSearchPolicy.Family.DEEPSEEK -> id.startsWith("deepseek-")
                NativeSearchPolicy.Family.PERPLEXITY -> id == "sonar" || id.startsWith("sonar-")
                NativeSearchPolicy.Family.HUNYUAN -> id.startsWith("hunyuan-")
                NativeSearchPolicy.Family.BAIDU -> id.startsWith("ernie-")
                NativeSearchPolicy.Family.STEPFUN -> id.startsWith("step-") && !id.startsWith("step-5-preview")
                else -> false
            }
            ProviderProtocol.GEMINI -> family in setOf(NativeSearchPolicy.Family.GOOGLE, NativeSearchPolicy.Family.COMPATIBLE) && id.startsWith("gemini-")
            ProviderProtocol.MESSAGES -> when (family) {
                NativeSearchPolicy.Family.ANTHROPIC, NativeSearchPolicy.Family.COMPATIBLE -> id.startsWith("claude-")
                NativeSearchPolicy.Family.MINIMAX -> id.startsWith("minimax-")
                NativeSearchPolicy.Family.DEEPSEEK -> id.startsWith("deepseek-")
                else -> false
            }
            else -> false
        }
    }

    fun canMixTools(protocol: ProviderProtocol, endpoint: String?, tools: List<ToolDefinition>): Boolean {
        if (tools.isEmpty()) return true
        val family = NativeSearchPolicy.family(endpoint)
        return when (protocol) {
            ProviderProtocol.GEMINI -> false
            ProviderProtocol.CHAT_COMPLETIONS -> family !in setOf(NativeSearchPolicy.Family.OPENAI, NativeSearchPolicy.Family.ARK)
            ProviderProtocol.MESSAGES -> tools.none { it.name == if (family == NativeSearchPolicy.Family.DEEPSEEK) "deepseek_web_search" else "web_search" }
            else -> true
        }
    }

    private fun openAi(id: String) = id.startsWith("gpt-4o") || id.startsWith("gpt-4.1") || id.startsWith("gpt-5") || id == "o3" || id.startsWith("o3-") || id == "o4-mini" || id.startsWith("o4-mini-")
    private fun ark(id: String) = id.startsWith("doubao-") || id.startsWith("deepseek-") || id.startsWith("ep-")
}
