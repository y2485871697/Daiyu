package app.daiyu.android.device

import org.json.JSONObject

/** Empty data is a successful read; denied and unsupported are not empty successes. */
data class DeviceAdapterResult(val status: String, val data: JSONObject = JSONObject(), val truncated: Boolean = false) {
    fun toJson(): JSONObject = JSONObject().put("status", status).put("data", data).put("truncated", truncated)
    companion object {
        fun ok(data: JSONObject, truncated: Boolean = false) = DeviceAdapterResult("ok", data, truncated)
        fun denied() = DeviceAdapterResult("permission_denied")
        fun unsupported() = DeviceAdapterResult("unsupported")
        fun unavailable() = DeviceAdapterResult("unavailable")
        fun invalid() = DeviceAdapterResult("invalid_arguments")
    }
}
