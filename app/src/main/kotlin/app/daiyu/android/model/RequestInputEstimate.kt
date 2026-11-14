package app.daiyu.android.model

import app.daiyu.android.context.TokenTailCompression
import app.daiyu.android.model.images.FrozenMessageImagePlan
import org.json.JSONArray
import org.json.JSONObject

/** 估算最终请求体中提示内容的 token 数，排除 JSON 包装、设置及不透明数据。 */
internal object RequestInputEstimate {
    internal fun payload(value: JSONObject): String? = when (value.optString("type")) {
        "input_image" -> value.opt("image_url") as? String
        "image_url" -> value.optJSONObject("image_url")?.opt("url") as? String
        "image" -> value.optJSONObject("source")?.takeIf { it.optString("type") == "base64" }?.opt("data") as? String
        else -> null
    }

    fun from(request: JSONObject, images: FrozenMessageImagePlan?,
        screenImages: Map<String, Pair<Int, Int>> = emptyMap(),
        protocol: ProviderProtocol = when {
            request.has("input") || request.has("instructions") -> ProviderProtocol.RESPONSES
            request.has("contents") -> ProviderProtocol.GEMINI
            request.has("system") -> ProviderProtocol.MESSAGES
            else -> ProviderProtocol.CHAT_COMPLETIONS
        }, videoBytes: Map<String, Long> = emptyMap()): Long {
        val sizes = images?.estimateImageSizes().orEmpty() + screenImages
        var tokens = 0L
        fun add(value: Any?) {
            if (value != null && value !== JSONObject.NULL) tokens += TokenTailCompression.textTokens(value.toString())
        }
        fun each(array: JSONArray?, consume: (JSONObject) -> Unit) {
            if (array != null) for (i in 0 until array.length()) array.optJSONObject(i)?.let(consume)
        }
        fun content(value: Any?) {
            when (value) {
                is String -> add(value)
                is JSONArray -> for (i in 0 until value.length()) content(value.opt(i))
                is JSONObject -> when (value.optString("type")) {
                    "text", "input_text", "output_text", "summary_text", "reasoning_text" -> add(value.opt("text"))
                    "refusal" -> add(value.opt("refusal"))
                    "thinking" -> add(value.opt("thinking"))
                    "tool_use", "server_tool_use" -> {
                        add(value.opt("name")); add(value.opt("input")); add(value.opt("id"))
                    }
                    "tool_result" -> { add(value.opt("tool_use_id")); content(value.opt("content")) }
                    "video", "video_url", "input_video" -> {
                        val url = value.optJSONObject("video_url")?.optString("url") ?: value.optString("video_url")
                        val source = value.optJSONObject("source")
                        val marker = url.indexOf("base64,", ignoreCase = true)
                        val encoded = source?.takeIf { it.optString("type") == "base64" }?.optString("data")
                        val token = if (encoded?.startsWith("daiyu-media-") == true) encoded
                            else if (marker >= 0 && url.startsWith("daiyu-media-", marker + 7)) url.substring(marker + 7) else null
                        val bytes = token?.let { requireNotNull(videoBytes[it]) { "Unrecognized frozen video estimate" } }
                        val chars = if (bytes != null) ((bytes + 2) / 3) * 4
                            else encoded?.length?.toLong() ?: if (marker >= 0) (url.length - marker - 7).toLong() else 0L
                        tokens += maxOf(1_200L, chars * 3 / 4 / 1024)
                    }
                    "image", "image_url", "input_image" -> {
                        val size = requireNotNull(payload(value)?.let(sizes::get)) { "Unrecognized frozen image estimate" }
                        tokens += TokenTailCompression.imageTokens(size.first, size.second)
                    }
                }
            }
        }
        fun message(value: JSONObject) {
            tokens += 3
            content(value.opt("content"))
            if (protocol == ProviderProtocol.CHAT_COMPLETIONS) add(value.opt("reasoning_content"))
            add(value.opt("tool_call_id")); add(value.opt("name"))
            each(value.optJSONArray("tool_calls")) { add(it) }
            add(value.opt("function_call"))
        }
        request.optJSONArray("tools")?.takeIf { it.length() > 0 }?.let(::add)
        add(if (protocol == ProviderProtocol.RESPONSES) request.optJSONObject("text")?.opt("format")
            else request.opt("response_format"))
        when (protocol) {
            ProviderProtocol.RESPONSES -> {
                content(request.opt("instructions"))
                val input = request.opt("input")
                if (input is String) { tokens += 3; add(input) }
                else each(input as? JSONArray) { item -> when (item.optString("type")) {
                    "", "message" -> message(item)
                    "function_call" -> {
                        tokens += 3; add(item.opt("name")); add(item.opt("arguments")); add(item.opt("call_id"))
                    }
                    "function_call_output" -> { tokens += 3; add(item.opt("call_id")); content(item.opt("output")) }
                    "reasoning" -> {
                        content(item.opt("summary")); content(item.opt("content")); add(item.opt("reasoning_text"))
                    }
                } }
            }
            ProviderProtocol.MESSAGES -> {
                content(request.opt("system")); each(request.optJSONArray("messages"), ::message)
            }
            ProviderProtocol.CHAT_COMPLETIONS -> each(request.optJSONArray("messages"), ::message)
            ProviderProtocol.GEMINI -> {
                fun parts(value: JSONObject) = each(value.optJSONArray("parts")) { part ->
                    add(part.opt("text"))
                    part.optJSONObject("functionCall")?.let { call ->
                        add(call.opt("name")); add(call.opt("args")); add(call.opt("id"))
                    }
                    part.optJSONObject("functionResponse")?.let { result ->
                        add(result.opt("name")); add(result.opt("response")); add(result.opt("id"))
                    }
                }
                request.optJSONObject("systemInstruction")?.let(::parts)
                each(request.optJSONArray("contents")) { tokens += 3; parts(it) }
                add(request.optJSONObject("generationConfig")?.opt("responseSchema"))
            }
            ProviderProtocol.UNKNOWN -> throw UnsupportedProviderException(protocol.wireName)
        }
        return tokens
    }
}
