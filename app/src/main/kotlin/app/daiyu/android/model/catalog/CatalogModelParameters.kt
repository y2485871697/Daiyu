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
) {
    companion object {
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
        .put("request_options", record.requestOptions.encode()).toString()

    override fun decode(encoded: String): CatalogModelParameters {
        val value = JSONObject(encoded)
        require(value.keys().asSequence().toSet() == setOf("local_identity", "battery", "device_status", "request_options"))
        val identity = value.get("local_identity") as? String ?: error("Invalid local identity")
        require(UUID.fromString(identity).toString() == identity)
        return CatalogModelParameters(identity,
            value.get("battery") as? Boolean ?: error("Invalid battery flag"),
            value.get("device_status") as? Boolean ?: error("Invalid device flag"),
            ProviderRequestOptions.decode(value.get("request_options") as? String ?: error("Invalid model options")))
    }
}
