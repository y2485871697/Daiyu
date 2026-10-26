package app.daiyu.android.model

import org.json.JSONObject

/**
 * The service tier selected for an OpenAI GPT request.
 *
 * A null mode is deliberate: it means that this feature is not selected and the
 * request must retain the caller's existing service_tier policy (including no
 * service_tier at all).
 */
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

/**
 * Applies the opt-in GPT service tier to a fully merged request body.
 *
 * This helper intentionally receives the final JSON model rather than a model
 * setting. Consequently custom-body/model merging is complete before the
 * decision is made, and a non-GPT provider cannot acquire an OpenAI-only field.
 * It does not alter model/reasoning fields and never performs fallback or
 * retry behaviour on a service error.
 */
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
