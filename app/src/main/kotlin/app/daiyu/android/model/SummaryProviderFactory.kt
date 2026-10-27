package app.daiyu.android.model

import app.daiyu.android.domain.ModelPort
import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Restricted, independent request policy for a summary, not the main chat factory.
 * No provider-wide settings are mutated. Unknown custom keys fail closed: in particular model,
 * output/reasoning budgets, instructions, tools and provider-specific tool aliases cannot slip
 * through the general-purpose custom-body merge. */
internal object SummaryProviderFactory {
    /** Pure validation shared by profile save/load/freeze and transport creation. No model or I/O. */
    fun validate(snapshot: ProviderSnapshot) {
        validatedCustomBody(snapshot)
    }

    private fun validatedCustomBody(snapshot: ProviderSnapshot): JSONObject {
        snapshot.asModelSettings().validate()
        require(snapshot.protocol in setOf(ProviderProtocol.RESPONSES, ProviderProtocol.MESSAGES, ProviderProtocol.CHAT_COMPLETIONS)) {
            "摘要模型尚不支持 ${snapshot.protocol.wireName}；仅支持 Responses/Messages/Chat Completions"
        }
        val options = snapshot.requestOptions
        options.validate(snapshot.protocol)
        val body = if (options.customBody.isEmpty()) JSONObject()
            else MessagesRequest.jsonObject(options.customBody)
        require(body.keys().asSequence().all { it in setOf("temperature", "top_p", "service_tier") }) {
            "摘要自定义请求体不能覆盖模型、预算、指令或工具；未知字段不受支持"
        }
        for (field in listOf("temperature", "top_p")) if (body.has(field)) {
            val value = (body.opt(field) as? Number)?.toDouble()
            val ceiling = if (field == "temperature" && snapshot.protocol == ProviderProtocol.RESPONSES) 2.0 else 1.0
            require(value != null && value.isFinite() && value >= 0.0 && value <= ceiling) {
                "摘要采样选项必须为有效数值"
            }
        }
        return body
    }

    fun reasoningLevels(snapshot: ProviderSnapshot, maxOutputTokens: Int): List<SummaryReasoningLevel> {
        val bodies = mutableSetOf<String>()
        return SummaryReasoningLevel.entries.filter { level ->
            val body = validatedCustomBody(snapshot)
            if (snapshot.oauth == null) body.put(if (snapshot.protocol == ProviderProtocol.RESPONSES)
                "max_output_tokens" else "max_tokens", maxOutputTokens)
            try {
                val provider = ProviderSnapshot.capture(snapshot.asModelSettings().copy(
                    requestOptions = snapshot.requestOptions.copy(customBody = body.toString())))
                val frozen = ReasoningRequestPolicy.freeze(provider,
                    app.daiyu.android.delegation.ReasoningEffort.valueOf(level.name), summary = true)
                bodies.add(frozen.requestOptions.customBody)
            } catch (invalid: IllegalArgumentException) {
                if (level == SummaryReasoningLevel.OFF) throw invalid
                false
            }
        }
    }

    fun create(snapshot: ProviderSnapshot, maxOutputTokens: Int, instructions: String,
        oauthHeaders: (app.daiyu.android.model.oauth.CodexAuth) -> Map<String, String> = app.daiyu.android.model.oauth.CodexOAuthRuntime::headers,
        reasoning: SummaryReasoningLevel = SummaryReasoningLevel.OFF,
        connect: (URL) -> HttpsURLConnection,
    ): ModelPort {
        require(maxOutputTokens > 0 && instructions.isNotBlank())
        val body = validatedCustomBody(snapshot)
        val options = snapshot.requestOptions
        // Codex rejects the wire cap; its summary adapter enforces local output and deadline limits.
        if (snapshot.oauth == null) body.put(if (snapshot.protocol == ProviderProtocol.RESPONSES) "max_output_tokens" else "max_tokens",
            maxOutputTokens)
        val base = snapshot.asModelSettings().copy(batteryEnabled = false, deviceStatusEnabled = false,
            requestOptions = options.copy(customBody = body.toString(), openAiWebSearchEnabled = false))
        val settings = ReasoningRequestPolicy.freeze(ProviderSnapshot.capture(base),
            app.daiyu.android.delegation.ReasoningEffort.valueOf(reasoning.name), summary = true).asModelSettings()
        settings.validate()
        return when (snapshot.protocol) {
            ProviderProtocol.RESPONSES -> ResponsesModel(settings, connect, null, instructions, oauthHeaders = oauthHeaders, summaryRequest = true)
            ProviderProtocol.MESSAGES -> MessagesModel(settings, maxOutputTokens, connect, null, instructions, summaryRequest = true)
            ProviderProtocol.CHAT_COMPLETIONS -> NativeCompletionModel(settings, connect, instructions, null, summaryRequest = true)
            ProviderProtocol.GEMINI -> throw IllegalArgumentException("摘要模型尚不支持 ${snapshot.protocol.wireName}")
            ProviderProtocol.UNKNOWN -> throw UnsupportedProviderException(snapshot.protocol.wireName)
        }
    }
}
