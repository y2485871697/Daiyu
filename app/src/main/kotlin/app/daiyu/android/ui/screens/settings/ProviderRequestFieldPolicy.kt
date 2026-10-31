package app.daiyu.android.ui.screens.settings

import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.model.sessiongateway.SessionGatewayRule
import app.daiyu.android.voice.providers.DoubaoSpeech

internal fun newProviderSessionGateway(protocol: ProviderProtocol, speechOnly: Boolean): SessionGatewayRule =
    SessionGatewayRule(enabled = !speechOnly && protocol in setOf(ProviderProtocol.CHAT_COMPLETIONS, ProviderProtocol.RESPONSES))

/** Hidden fields are excluded from validation, connection tests and explicit saves, not rewritten on load. */
internal fun ModelSettingsDraftUi.providerRequestDraft(
    speechOnly: Boolean = DoubaoSpeech.isOpenspeech(endpoint),
): ModelSettingsDraftUi {
    val bodyAllowed = !speechOnly && protocol == ProviderProtocol.MESSAGES
    if (!speechOnly && bodyAllowed) return this
    return copy(
        customBody = if (bodyAllowed) customBody else "",
        bodyFieldsValid = if (bodyAllowed) bodyFieldsValid else true,
        bodyFieldRows = if (bodyAllowed) bodyFieldRows else null,
        bodyFieldsError = if (bodyAllowed) bodyFieldsError else null,
        customHeaders = if (speechOnly) "" else customHeaders,
        headerFieldsValid = if (speechOnly) true else headerFieldsValid,
        headerFieldRows = if (speechOnly) null else headerFieldRows,
        headerFieldsError = if (speechOnly) null else headerFieldsError,
    )
}
