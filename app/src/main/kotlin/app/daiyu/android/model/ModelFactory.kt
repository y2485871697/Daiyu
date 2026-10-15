package app.daiyu.android.model

import app.daiyu.android.domain.ModelPort
import app.daiyu.android.storage.ModelSettings

object ModelFactory {
    fun snapshot(settings: ModelSettings): ProviderSnapshot = ProviderSnapshot.capture(settings)

    fun create(settings: ModelSettings): ModelPort = create(snapshot(settings))

    fun create(snapshot: ProviderSnapshot): ModelPort {
        return when (snapshot.protocol) {
            ProviderProtocol.RESPONSES -> {
                val settings = snapshot.asModelSettings()
                ResponsesModel(settings)
            }
            ProviderProtocol.UNKNOWN -> throw UnsupportedProviderException(snapshot.protocol.wireName)
        }
    }

    fun create(settings: ModelSettings, protocol: ProviderProtocol): ModelPort =
        create(ProviderSnapshot.capture(settings, protocol))

    fun create(settings: ModelSettings, protocol: String): ModelPort {
        val selected = when (protocol.trim().lowercase()) {
            ProviderProtocol.RESPONSES.wireName -> ProviderProtocol.RESPONSES
            else -> throw UnsupportedProviderException(protocol)
        }
        return create(settings, selected)
    }
}
