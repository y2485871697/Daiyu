package app.daiyu.android.model

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.Locale

/** Select a wire adapter, not a promise about a model's remote entitlement. Opt-in stays per model. */
internal object NativeSearchPolicy {
    enum class Family { OPENAI, XAI, GOOGLE, ANTHROPIC, QWEN, GLM, ARK, DEEPSEEK, PERPLEXITY, MINIMAX, HUNYUAN, TOKENHUB, BAIDU, MISTRAL, MOONSHOT, STEPFUN, COMPATIBLE }

    fun family(endpoint: String?): Family {
        val host = endpoint?.let { URI(it).host }?.lowercase(Locale.ROOT) ?: return Family.COMPATIBLE
        return when {
            host == "api.openai.com" -> Family.OPENAI
            host == "api.x.ai" -> Family.XAI
            host == "generativelanguage.googleapis.com" -> Family.GOOGLE
            host == "api.anthropic.com" -> Family.ANTHROPIC
            host in setOf("dashscope.aliyuncs.com", "dashscope-intl.aliyuncs.com", "dashscope-us.aliyuncs.com", "cn-hongkong.dashscope.aliyuncs.com") ||
                host.matches(Regex("[a-z0-9-]+\\.(cn-beijing|ap-southeast-1|us-east-1|cn-hongkong|eu-central-1|ap-northeast-1)\\.maas\\.aliyuncs\\.com")) -> Family.QWEN
            host in setOf("open.bigmodel.cn", "api.z.ai") -> Family.GLM
            host == "ark.cn-beijing.volces.com" -> Family.ARK
            host == "api.deepseek.com" -> Family.DEEPSEEK
            host == "api.perplexity.ai" -> Family.PERPLEXITY
            host in setOf("api.minimax.io", "api.minimax.cn", "api.minimaxi.com") -> Family.MINIMAX
            host == "api.hunyuan.cloud.tencent.com" -> Family.HUNYUAN
            host in setOf("tokenhub.tencentmaas.com", "tokenhub-intl.tencentmaas.com") -> Family.TOKENHUB
            host == "qianfan.baidubce.com" -> Family.BAIDU
            host == "api.mistral.ai" -> Family.MISTRAL
            host in setOf("api.moonshot.cn", "api.moonshot.ai") -> Family.MOONSHOT
            host == "api.stepfun.com" -> Family.STEPFUN
            else -> Family.COMPATIBLE
        }
    }

    fun apply(body: JSONObject, protocol: ProviderProtocol, model: String, endpoint: String?,
              enabled: Boolean, localTools: List<app.daiyu.android.domain.ToolDefinition>): JSONObject {
        if (!enabled) return body
        val family = family(endpoint)
        require(family !in setOf(Family.MISTRAL, Family.MOONSHOT, Family.TOKENHUB)) {
            "当前接口的原生搜索协议尚未接入，请使用本轮提供的浏览器工具"
        }
        require(family !in setOf(Family.STEPFUN, Family.HUNYUAN, Family.BAIDU) || protocol == ProviderProtocol.CHAT_COMPLETIONS) {
            "该提供商的原生搜索请使用 Chat Completions 格式"
        }
        fun append(tool: JSONObject) {
            val tools = body.optJSONArray("tools") ?: JSONArray().also { body.put("tools", it) }
            tools.put(tool)
        }
        val flash = isDeepSeekFlash(model)
        when (protocol) {
            ProviderProtocol.RESPONSES -> {
                val kind = if ((family == Family.DEEPSEEK || family == Family.COMPATIBLE) && flash)
                    "deepseek_web_search" else "web_search"
                append(JSONObject().put("type", kind))
                if (family == Family.XAI) append(JSONObject().put("type", "x_search"))
            }
            ProviderProtocol.CHAT_COMPLETIONS -> when (family) {
                Family.MINIMAX -> throw IllegalArgumentException("MiniMax 原生搜索请使用 Messages 或 Responses 格式")
                Family.XAI -> throw IllegalArgumentException("Grok 原生搜索请使用 Responses 格式")
                Family.QWEN -> {
                    body.put("enable_search", true)
                    if (model.trim().lowercase(Locale.ROOT).let { it == "qwen3.8-omni-flash" || it.startsWith("qwen3.5-omni-") })
                        body.put("search_options", JSONObject().put("search_strategy", "agent"))
                }
                Family.HUNYUAN -> body.put("enable_enhancement", true).put("search_info", true).put("citation", true)
                Family.BAIDU -> body.put("web_search", JSONObject().put("enable", true).put("enable_trace", true)
                    .put("enable_status", true).put("enable_citation", true))
                Family.STEPFUN -> {
                    require(!model.lowercase(Locale.ROOT).startsWith("step-5-preview")) { "此型号不支持内置原生搜索" }
                    append(JSONObject().put("type", "web_search").put("function", JSONObject().put("description", "搜索互联网信息")))
                }
                Family.GLM -> append(JSONObject().put("type", "web_search")
                    .put("web_search", JSONObject().put("enable", true).put("search_result", true)))
                Family.DEEPSEEK -> append(JSONObject().put("type", "deepseek_web_search"))
                Family.PERPLEXITY -> Unit // Sonar's search is server-side and intrinsic to its model.
                else -> {
                    require(family != Family.ARK || localTools.isEmpty()) {
                        "火山 Chat 的原生搜索与自定义工具互斥；请切换 Responses 格式"
                    }
                    require(family != Family.OPENAI || localTools.isEmpty()) {
                        "OpenAI Chat 搜索专用模型不支持自定义工具；请使用 Responses 格式"
                    }
                    body.put("web_search_options", JSONObject())
                }
            }
            ProviderProtocol.GEMINI -> {
                require(localTools.isEmpty()) {
                    "Gemini generateContent 的原生搜索与自定义工具不能混用；请关闭该助手的自定义工具"
                }
                append(JSONObject().put("googleSearch", JSONObject()))
            }
            ProviderProtocol.MESSAGES -> {
                val name = messagesSearchName(model, endpoint)
                require(localTools.none { it.name == name }) { "自定义工具名称与原生搜索冲突：$name" }
                append(JSONObject().put("type", if (name == "deepseek_web_search") name else "web_search_20250305")
                    .put("name", name))
            }
            else -> throw IllegalArgumentException("当前协议未接入原生搜索")
        }
        return body
    }
    internal fun isDeepSeekFlash(model: String) = model.trim().lowercase(Locale.ROOT) in setOf(
        "deepseek-flash", "deepseek-v4-flash", "deepseek-v4-flash-vision-exp", "deepseek-v4.1-flash")

    internal fun messagesSearchName(model: String, endpoint: String?): String {
        val id = model.trim().lowercase(Locale.ROOT)
        val flash = isDeepSeekFlash(id) || id.startsWith("claude") && !id.startsWith("claude-opus")
        return if (family(endpoint) == Family.DEEPSEEK && flash) "deepseek_web_search" else "web_search"
    }

}
