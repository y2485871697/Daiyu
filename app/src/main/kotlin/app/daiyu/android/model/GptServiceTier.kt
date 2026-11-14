package app.daiyu.android.model

import org.json.JSONObject

/** OpenAI GPT 请求的服务档位；null 表示保留请求原有的 service_tier，包括未设置。 */
enum class GptSpeedMode {
    NORMAL,
    FAST,
    ULTRA_FAST;

    internal val serviceTier: String
        get() = when (this) {
            NORMAL -> "default"
            FAST -> "fast"
            ULTRA_FAST -> "ultrafast"
        }
}

/** 按合并后 JSON 中的最终模型应用 GPT 服务档位，避免给非 GPT 模型添加此字段。 */
internal object GptServiceTier {
    /**
     * Add or replace `service_tier` only for an explicit mode and a GPT model.
     * Invalid/missing JSON model values are ignored, as are non-GPT model names.
     */
    fun apply(request: JSONObject, mode: GptSpeedMode?, codex: Boolean = false) {
        if (mode == null) return
        val actualModel = request.opt("model") as? String ?: return
        if (!isGptSpeedModel(actualModel)) return
        if (codex && mode == GptSpeedMode.NORMAL) {
            request.remove("service_tier")
        } else {
            request.put("service_tier", if (codex && mode == GptSpeedMode.FAST) "priority" else mode.serviceTier)
        }
    }

    /**
     * Match the last model path segment after trimming the full identifier.
     * Namespaces may be arbitrary or nested, but similar names such as
     * `foo-gpt-5` must not match merely because they contain "gpt".
     */
    internal fun isGptSpeedModel(model: String): Boolean =
        model.trim().substringAfterLast('/').startsWith("gpt-", ignoreCase = true)
}
