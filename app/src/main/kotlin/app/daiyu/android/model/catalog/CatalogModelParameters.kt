package app.daiyu.android.model.catalog

import app.daiyu.android.model.ProviderRequestOptions
import app.daiyu.android.storage.EditingSettings
import app.daiyu.android.storage.ModelSettings
import org.json.JSONObject
import java.util.UUID

/** The local per-model fields supported by the current host, without connection credentials. */
internal data class CatalogModelParameters(
    val localIdentity: String = UUID.randomUUID().toString(),
    val batteryEnabled: Boolean = false,
    val deviceStatusEnabled: Boolean = false,
    val requestOptions: ProviderRequestOptions = ProviderRequestOptions(),
    val contextWindowOverride: Int? = null,
    val reasoningOverride: Boolean? = null,
    val visionOverride: Boolean? = null,
) {
    val reasoningSupported: Boolean get() = reasoningOverride ?: true
    val visionSupported: Boolean get() = visionOverride ?: true

    /** Retain only the CURRENT connection/credential; model-local policy comes from this record. */
    fun applyTo(current: ModelSettings, model: String): ModelSettings = current.copy(
        model = model, batteryEnabled = batteryEnabled, deviceStatusEnabled = deviceStatusEnabled,
        requestOptions = requestOptions,
        contextWindowOverride = contextWindowOverride,
        reasoningOverride = reasoningOverride,
        visionOverride = visionOverride,
        defaultVisionSupported = true,
    )

    companion object {
        fun updated(settings: ModelSettings, previous: SavedCatalogModel<CatalogModelParameters>?): SavedCatalogModel<CatalogModelParameters> {
            val parameters = previous?.settings ?: CatalogModelParameters()
            return SavedCatalogModel(settings.model, previous?.displayName ?: settings.model,
                parameters.copy(batteryEnabled = settings.batteryEnabled,
                    deviceStatusEnabled = settings.deviceStatusEnabled, requestOptions = settings.requestOptions.copy(
                        openAiWebSearchEnabled = previous?.settings?.requestOptions?.openAiWebSearchEnabled
                            ?: settings.requestOptions.openAiWebSearchEnabled)))
        }

        /** Preserve the loadForEditing error flag: its fallback options are NOT saved policy. */
        fun fromEditing(editing: EditingSettings): SavedCatalogModel<CatalogModelParameters> {
            check(!editing.requestOptionsUnavailable) { "原请求策略不可用，请先修复并保存配置" }
            return fromCurrent(editing.settings)
        }

        /** One-time migration of validated current settings, not loadForEditing fallback values. */
        fun fromCurrent(settings: ModelSettings) = SavedCatalogModel(
            settings.model, settings.model,
            CatalogModelParameters(batteryEnabled = settings.batteryEnabled,
                deviceStatusEnabled = settings.deviceStatusEnabled, requestOptions = settings.requestOptions),
        )

        /** Normal detached new-model defaults, NOT a copy of the active model's overrides. */
        fun newModel(remote: RemoteModel) = SavedCatalogModel(remote.id, remote.displayName, CatalogModelParameters())
    }
}

internal object CatalogModelParametersCodec : CatalogRecordCodec<CatalogModelParameters> {
    override fun encode(record: CatalogModelParameters): String = JSONObject()
        .put("local_identity", record.localIdentity)
        .put("battery", record.batteryEnabled)
        .put("device_status", record.deviceStatusEnabled)
        .put("request_options", record.requestOptions.encode())
        .put("context_window_override", record.contextWindowOverride ?: JSONObject.NULL)
        .put("reasoning_override", record.reasoningOverride ?: JSONObject.NULL)
        .put("vision_override", record.visionOverride ?: JSONObject.NULL).toString()

    override fun decode(encoded: String): CatalogModelParameters {
        val value = JSONObject(encoded)
        val required = setOf("local_identity", "battery", "device_status", "request_options")
        val optional = setOf("context_window_override", "reasoning_override", "vision_override")
        val keys = value.keys().asSequence().toSet()
        require(keys.containsAll(required) && keys.all { it in required || it in optional })
        fun optionalBoolean(key: String): Boolean? = if (!value.has(key) || value.isNull(key)) null
            else value.get(key) as? Boolean ?: error("Invalid capability override")
        val context = if (!value.has("context_window_override") || value.isNull("context_window_override")) null
            else (value.get("context_window_override") as? Int)?.also { require(it > 0) }
                ?: error("Invalid context window override")
        val identity = value.get("local_identity") as? String ?: error("Invalid local identity")
        require(UUID.fromString(identity).toString() == identity)
        return CatalogModelParameters(identity,
            value.get("battery") as? Boolean ?: error("Invalid battery flag"),
            value.get("device_status") as? Boolean ?: error("Invalid device flag"),
            ProviderRequestOptions.decode(value.get("request_options") as? String ?: error("Invalid model options")),
            context, optionalBoolean("reasoning_override"), optionalBoolean("vision_override"))
    }
}
