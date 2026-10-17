package app.daiyu.android.platform

import android.content.Context
import android.content.pm.PackageManager
import org.json.JSONArray
import org.json.JSONObject

/** Read-only helpers extracted from the preserved new-project DeviceToolRegistry candidate. */
internal fun success(data: JSONObject): JSONObject = JSONObject().put("ok", true).put("status", "ok").put("data", data)
internal fun failure(status: String, reason: String = status, permissions: List<String> = emptyList()): JSONObject =
    JSONObject().put("ok", false).put("status", status).put("error", reason).apply {
        if (permissions.isNotEmpty()) {
            put("required_permissions", JSONArray(permissions))
            put("required_permission", permissions.first())
        }
    }
internal fun hasPermission(context: Context, permission: String): Boolean =
    context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
internal fun requirePermission(context: Context, permission: String): JSONObject? =
    if (hasPermission(context, permission)) null else failure("permission_denied", permissions = listOf(permission))
internal fun JSONObject.queryText(): String = optString("query", "")
internal fun JSONObject.queryLimit(): Int = optInt("limit", 20)
internal fun boundedText(value: CharSequence?): String? = value?.toString()?.let { AndroidPlatformReads.bounded(it) }
internal fun clippedText(value: CharSequence?): Boolean = value != null && boundedText(value) != value.toString()
internal fun checkReadCancellation(cancelled: () -> Boolean) {
    if (cancelled()) throw android.os.OperationCanceledException()
}
