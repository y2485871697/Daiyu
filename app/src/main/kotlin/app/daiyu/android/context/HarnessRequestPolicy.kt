package app.daiyu.android.context

import app.daiyu.android.model.*
import org.json.JSONObject

internal object HarnessRequestPolicy {
    fun outputReservation(snapshot: ProviderSnapshot): Long {
        if (snapshot.oauth != null) return 0
        val body = snapshot.requestOptions.customBody.takeIf { it.isNotBlank() }?.let(::JSONObject) ?: JSONObject()
        val value = when (snapshot.protocol) {
            ProviderProtocol.RESPONSES -> body.opt("max_output_tokens")
            ProviderProtocol.MESSAGES -> body.opt("max_tokens") ?: MessagesRequest.DEFAULT_MAX_TOKENS
            ProviderProtocol.CHAT_COMPLETIONS -> body.opt("max_completion_tokens") ?: body.opt("max_tokens")
            ProviderProtocol.GEMINI -> body.optJSONObject("generationConfig")?.opt("maxOutputTokens")
            ProviderProtocol.UNKNOWN -> null
        } ?: return 0
        require(value is Number && value.toDouble().isFinite() && value.toDouble() == value.toLong().toDouble() && value.toLong() >= 0) {
            "模型输出上限必须为非负整数"
        }
        return value.toLong()
    }

    fun header(snapshot: ProviderSnapshot, system: String): String =
        app.daiyu.android.ui.model.contextReceiptIdentity(snapshot.protocol.wireName, snapshot.model,
            snapshot.protocol.wireName, snapshot.endpoint, snapshot.requestOptions.customBody + "\n" + system)
}
