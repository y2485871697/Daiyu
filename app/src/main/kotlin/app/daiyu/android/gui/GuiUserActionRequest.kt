package app.daiyu.android.gui

import org.json.JSONObject
import java.util.Locale

/** Exact trusted user-entered operation/full input/optional package only. Actual nodes,
 * windows and observations come exclusively from the system-bound tree, never from JSON,
 * model output or conversation text. A missing package never implies a self target. */
internal data class GuiUserActionRequest(
    val operation: GuiOperation,
    val text: String?,
    val targetPackage: String? = null,
) {
    companion object {
        internal fun validTargetPackage(value: String): Boolean =
            value.length in 1..256 && value.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))

        fun parse(raw: String): GuiUserActionRequest {
            require(raw.length <= 32_768) { "gui_request_exceeds_bound" }
            val json = JSONObject(raw)
            require(json.keys().asSequence().toSet().all { it in setOf("operation", "text", "target_package") })
            require(json.get("operation") is String)
            val operation = GuiOperation.valueOf(json.getString("operation").uppercase(Locale.ROOT))
            val input = if (operation == GuiOperation.INPUT) {
                require(json.has("text") && json.get("text") is String)
                json.getString("text").also { require(it.length <= 4096) }
            } else { require(!json.has("text")); null }
            val target = if (json.has("target_package")) {
                require(json.get("target_package") is String)
                json.getString("target_package").also { require(validTargetPackage(it)) }
            } else null
            return GuiUserActionRequest(operation, input, target)
        }
    }
}
