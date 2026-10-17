package app.daiyu.android.model

import app.daiyu.android.domain.ReadOnlyTool
import app.daiyu.android.storage.ModelSettings
import java.util.Collections

enum class ProviderProtocol(val wireName: String) {
    RESPONSES("responses"),
    MESSAGES("messages"),
    CHAT_COMPLETIONS("chat_completions"),
    GEMINI("gemini"),
    UNKNOWN("unknown")
}

class UnsupportedProviderException(protocol: String) :
    IllegalArgumentException("不支持的模型协议: $protocol")

class MissingProviderCredentialException :
    IllegalArgumentException("模型供应商缺少凭据；请重新输入密钥")

/** Validated, immutable configuration retained for one run; diagnostics redact the key. */
class ProviderSnapshot private constructor(
    val protocol: ProviderProtocol,
    val endpoint: String,
    val model: String,
    val apiKey: String,
    val responsesPath: String,
    val batteryEnabled: Boolean,
    val deviceStatusEnabled: Boolean,
    val requestOptions: ProviderRequestOptions,
    /** Host-verified capability for this exact endpoint/protocol/model; null means unknown. */
    val imageInputSupported: Boolean?,
    tools: Set<ReadOnlyTool>
) {
    val tools: Set<ReadOnlyTool> =
        Collections.unmodifiableSet(tools.toSet())

    fun asModelSettings(): ModelSettings = ModelSettings(
        endpoint = endpoint,
        model = model,
        apiKey = apiKey,
        batteryEnabled = batteryEnabled,
        responsesPath = responsesPath,
        deviceStatusEnabled = deviceStatusEnabled,
        protocol = protocol,
        requestOptions = requestOptions
    )

    override fun toString(): String =
        "ProviderSnapshot(protocol=${protocol.wireName}, endpoint=$endpoint, model=$model, " +
            "responsesPath=$responsesPath, batteryEnabled=$batteryEnabled, " +
            "deviceStatusEnabled=$deviceStatusEnabled, imageInputSupported=$imageInputSupported, tools=$tools, apiKey=<redacted>)"

    override fun equals(other: Any?): Boolean = other is ProviderSnapshot &&
        protocol == other.protocol && endpoint == other.endpoint && model == other.model &&
        apiKey == other.apiKey && responsesPath == other.responsesPath &&
        batteryEnabled == other.batteryEnabled && deviceStatusEnabled == other.deviceStatusEnabled &&
        tools == other.tools && requestOptions == other.requestOptions && imageInputSupported == other.imageInputSupported

    override fun hashCode(): Int {
        var result = protocol.hashCode()
        result = 31 * result + endpoint.hashCode()
        result = 31 * result + model.hashCode()
        result = 31 * result + apiKey.hashCode()
        result = 31 * result + responsesPath.hashCode()
        result = 31 * result + batteryEnabled.hashCode()
        result = 31 * result + deviceStatusEnabled.hashCode()
        result = 31 * result + tools.hashCode()
        result = 31 * result + requestOptions.hashCode()
        result = 31 * result + (imageInputSupported?.hashCode() ?: 0)
        return result
    }

    companion object {
        fun capture(settings: ModelSettings): ProviderSnapshot = capture(settings, settings.protocol)

        fun capture(settings: ModelSettings, protocol: ProviderProtocol, imageInputSupported: Boolean? = null): ProviderSnapshot {
            if (settings.apiKey.isBlank()) throw MissingProviderCredentialException()
            if (protocol == ProviderProtocol.UNKNOWN) settings.validate()
            else settings.copy(protocol = protocol).validate()
            return ProviderSnapshot(
                protocol = protocol,
                endpoint = settings.endpoint,
                model = settings.model,
                apiKey = settings.apiKey,
                responsesPath = settings.responsesPath,
                batteryEnabled = settings.batteryEnabled,
                deviceStatusEnabled = settings.deviceStatusEnabled,
                requestOptions = settings.requestOptions,
                imageInputSupported = imageInputSupported,
                tools = settings.enabledTools()
            )
        }

        fun from(settings: ModelSettings): ProviderSnapshot = capture(settings)

        fun from(settings: ModelSettings, protocol: ProviderProtocol): ProviderSnapshot =
            capture(settings, protocol)
    }
}
