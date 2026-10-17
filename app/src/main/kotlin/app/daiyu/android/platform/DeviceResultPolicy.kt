package app.daiyu.android.platform

import org.json.JSONObject

/** Preserved candidate's host-owned, destination-specific consent policy; not Android permissions.
 * Catalog currently has one shared persisted payload, so it requires BOTH destination grants.
 */
class DeviceResultPolicy(
    modelPrivateTools: Set<String> = emptySet(),
    historyPrivateTools: Set<String> = emptySet()
) {
    private val modelAllowed = modelPrivateTools.toSet()
    private val historyAllowed = historyPrivateTools.toSet()
    fun forModel(toolName: String, result: JSONObject): JSONObject = project(toolName, result, modelAllowed)
    fun forHistory(toolName: String, result: JSONObject): JSONObject = project(toolName, result, historyAllowed)

    /** No raw local-only results can enter the dispatcher ledger, model or MessageBlock.Tool. */
    internal fun allowsShared(toolName: String): Boolean = toolName !in PRIVATE_TOOLS ||
        (toolName in modelAllowed && toolName in historyAllowed)

    internal fun forSharedDelivery(toolName: String, result: JSONObject): JSONObject = when {
        toolName !in modelAllowed -> forModel(toolName, result)
        else -> forHistory(toolName, result)
    }

    private fun project(name: String, result: JSONObject, allowed: Set<String>): JSONObject {
        if (name !in PRIVATE_TOOLS || name in allowed) return JSONObject(result.toString())
        // Preserve status/permission diagnostics, never arbitrary exceptions, URI or content.
        val safe = JSONObject().put("tool", name).put("ok", false).put("status", "private_result_withheld")
            .put("source_status", result.optString("status", "unknown")).put("privacy_redacted", true)
        if (result.has("observed_at")) safe.put("observed_at", result.getLong("observed_at"))
        if (!result.optBoolean("ok")) {
            if (result.has("error")) safe.put("error", result.getString("error"))
            if (result.has("required_permissions")) safe.put("required_permissions", result.getJSONArray("required_permissions"))
            if (result.has("required_permission")) safe.put("required_permission", result.getString("required_permission"))
        }
        return safe
    }

    companion object {
        /** Historical names retained so a recovered policy cannot accidentally treat them as public. */
        val PRIVATE_TOOLS = setOf("device_location", "query_notifications", "query_calendar", "query_contacts", "query_media", "query_files",
            "search_apps", "search_media", "file_metadata", "read_text_file", "list_files", "calendar_events", "search_contacts", "app_notifications")
    }
}
