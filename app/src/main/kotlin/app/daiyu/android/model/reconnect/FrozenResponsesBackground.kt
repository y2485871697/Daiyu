package app.daiyu.android.model.reconnect

import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.model.ProviderSnapshot
import app.daiyu.android.storage.ModelSettings
import java.net.URI

/** Explicit informed opt-in bound to an immutable provider snapshot. Not a protocol heuristic.
 * Only the official exact Responses endpoint has the documented retrieve-stream contract.
 * Custom gateways, query parameters, redirects and body options cannot manufacture this token. */
class FrozenResponsesBackground private constructor(private val frozen: ModelSettings) {
    internal fun requireMatches(settings: ModelSettings) {
        require(settings == frozen) { "Background capability does not match frozen provider" }
    }
    companion object {
        fun freeze(settings: ReconnectSettings, provider: ProviderSnapshot): FrozenResponsesBackground? {
            if (!settings.enabled || !settings.responsesBackgroundEnabled || provider.protocol != ProviderProtocol.RESPONSES) return null
            val model = provider.asModelSettings()
            val uri = try { URI(model.requestEndpoint()) } catch (_: Exception) { return null }
            if (uri.scheme != "https" || uri.host != "api.openai.com" || uri.port != -1 ||
                uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
                uri.rawPath != "/v1/responses") return null
            return FrozenResponsesBackground(model)
        }
    }
}
