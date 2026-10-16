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
        require(snapshot.protocol in setOf(ProviderProtocol.RESPONSES, ProviderProtocol.MESSAGES)) {
            "摘要模型协议不受支持"
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

    fun create(snapshot: ProviderSnapshot, maxOutputTokens: Int, instructions: String,
        connect: (URL) -> HttpsURLConnection): ModelPort {
        require(maxOutputTokens > 0 && instructions.isNotBlank())
        val body = validatedCustomBody(snapshot)
        val options = snapshot.requestOptions
        body.put(if (snapshot.protocol == ProviderProtocol.RESPONSES) "max_output_tokens" else "max_tokens",
            maxOutputTokens)
        val settings = snapshot.asModelSettings().copy(batteryEnabled = false, deviceStatusEnabled = false,
            requestOptions = options.copy(customBody = body.toString()))
        settings.validate()
        return when (snapshot.protocol) {
            ProviderProtocol.RESPONSES -> ResponsesModel(settings, connect, null, instructions)
            ProviderProtocol.MESSAGES -> MessagesModel(settings, maxOutputTokens, connect, null, instructions)
            ProviderProtocol.UNKNOWN -> throw UnsupportedProviderException(snapshot.protocol.wireName)
        }
    }
}
