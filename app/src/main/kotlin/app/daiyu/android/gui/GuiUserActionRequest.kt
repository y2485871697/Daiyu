package app.daiyu.android.gui

import org.json.JSONObject
import java.util.Locale

/** Exact user-entered operation/full input only. Actual node/window/observation come exclusively
 * from the system-bound external-tree overlay, never from user JSON or a model suggestion. */
internal data class GuiUserActionRequest(val operation: GuiOperation, val text: String?) {
    companion object {
        fun parse(raw: String): GuiUserActionRequest {
            require(raw.length <= 32_768) { "gui_request_exceeds_bound" }
            val json = JSONObject(raw)
            require(json.keys().asSequence().toSet().all { it in setOf("operation", "text") })
            require(json.get("operation") is String)
            val operation = GuiOperation.valueOf(json.getString("operation").uppercase(Locale.ROOT))
            val input = if (operation == GuiOperation.INPUT) {
                require(json.has("text") && json.get("text") is String)
                json.getString("text").also { require(it.length <= 4096) }
            } else { require(!json.has("text")); null }
            return GuiUserActionRequest(operation, input)
        }
    }
}
