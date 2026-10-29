package app.daiyu.android.gui

import android.util.Base64
import app.daiyu.android.domain.tools.*
import org.json.JSONArray
import org.json.JSONObject

/** Catalog leaf only. Register in the EXISTING registry/dispatcher/ledger; no alternate executor.
 * screen reads are bounded by explicit UI approval; screen_action has an exact WRITE effect.
 * The fixed-effect catalog cannot safely combine reads and writes under one polymorphic definition.
 */
internal object GuiScreenToolFactory {
    const val CAPABILITY = "gui.accessibility"
    fun create(session: GuiRunSession): List<RegisteredTool> = listOf(ReadTool(session), ActionTool(session))

    private fun admission(session: GuiRunSession, context: ToolExecutionContext): ExecutionReceipt.NotExecuted? =
        session.admission(context.owner, context.authorization)?.let { ExecutionReceipt.NotExecuted(it.code) }

    internal fun readDefinition(allowImplicitObservation: Boolean = false): ToolDefinition = ToolDefinition("screen", 1,
            "Bounded accessibility observation or an explicitly requested screenshot. observe forbids observation_id. " +
                (if (allowImplicitObservation) "screenshot accepts a current observation_id; if omitted, creates exactly one bounded observation in the same target session before capture. "
                 else "screenshot requires observation_id from a successful observe in this session. ") +
                "IDs expire after 10 seconds or a UI change/action. Never retry a stale ID automatically. " +
                (if (allowImplicitObservation) "For the current screen omit any previous target_package. Self-app observation requires target_package=app.daiyu.android. " else "") +
                "Window failures are not Android Root permission errors. Never use shell, input, screencap or uiautomator to bypass screen admission, target or location restrictions.",
            CAPABILITY, ToolEffect.READ_ONLY, ToolSchema(listOf(
                ArgumentField("operation", ArgumentType.Text(16, setOf("observe", "screenshot"))),
                ArgumentField("observation_id", ArgumentType.Text(64, minimumLength = 1), required = false),
            )))

    private class ReadTool(private val session: GuiRunSession) : RegisteredTool {
        override val definition = readDefinition()
        override fun prepare(arguments: ToolArguments): PreparedTool {
            val screenshot = arguments.text("operation") == "screenshot"
            require(screenshot == arguments.contains("observation_id")) { "invalid_arguments" }
            val id = if (screenshot) arguments.text("observation_id") else null
            return PreparedTool.contextual(admit = { admission(session, it) }) { context ->
                admission(session, context)?.let { return@contextual it }
                if (screenshot) result(session.screenshot(id!!, context.cancelled)) { image ->
                    JSONObject().put("observation_id", image.observationId).put("width", image.width)
                        .put("height", image.height).put("mime_type", "image/png")
                        .put("data_base64", Base64.encodeToString(image.png, Base64.NO_WRAP)).toString()
                } else result(session.observe(context.cancelled), ::observationJson)
            }
        }
    }
    internal fun actionDefinition(): ToolDefinition = ToolDefinition("screen_action", 1,
            "One exact user-confirmed accessibility node or global action, bound to the same observation_id. " +
                "No coordinate fallback/retry or shell/input/uiautomator bypass of screen admission, target or location restrictions. Android acceptance is not verified business success.",
            CAPABILITY, ToolEffect.WRITE, ToolSchema(listOf(
                ArgumentField("operation", ArgumentType.Text(32, GuiOperation.entries.map { it.name.lowercase() }.toSet())),
                ArgumentField("observation_id", ArgumentType.Text(64, minimumLength = 1)),
                ArgumentField("node_id", ArgumentType.Integer(0, 127), required = false),
                ArgumentField("text", ArgumentType.Text(4096), required = false),
            )))

    private class ActionTool(private val session: GuiRunSession) : RegisteredTool {
        override val definition = actionDefinition()
        override fun prepare(arguments: ToolArguments): PreparedTool {
            val action = GuiAction(arguments.text("observation_id"),
                GuiOperation.valueOf(arguments.text("operation").uppercase()),
                if (arguments.contains("node_id")) arguments.integer("node_id").toInt() else null,
                if (arguments.contains("text")) arguments.text("text") else null)
            return PreparedTool.contextual(action.effect(session.identity), admit = { admission(session, it) }) { context ->
                admission(session, context)?.let { return@contextual it }
                result(session.action(action, context.cancelled)) { status ->
                    JSONObject().put("observation_id", action.observationId).put("status", status)
                        .put("verified_ui_result", false).toString()
                }
            }
        }
    }
    private fun <T> result(value: GuiResult<T>, encode: (T) -> String): ExecutionReceipt = when (value) {
        is GuiResult.Rejected -> ExecutionReceipt.NotExecuted(value.code)
        is GuiResult.Unknown -> ExecutionReceipt.Unknown(value.code)
        is GuiResult.Value -> ExecutionReceipt.Confirmed(encode(value.value))
    }
    internal fun observationJson(value: GuiObservation): String = JSONObject()
        .put("observation_id", value.observationId).put("package_name", value.packageName)
        .put("window_id", value.windowId).put("truncated", value.truncated)
        .put("captured_elapsed_realtime_ms", value.capturedAt).put("expires_after_ms", 10_000)
        .put("nodes", JSONArray().apply {
            value.nodes.forEach { node -> put(JSONObject().put("id", node.id).put("parent", node.parent ?: JSONObject.NULL)
                .put("bounds", JSONArray(listOf(node.bounds.left, node.bounds.top, node.bounds.right, node.bounds.bottom)))
                .put("text", node.text).put("description", node.description).put("class_name", node.className)
                .put("view_id", node.viewId).put("clickable", node.clickable).put("long_clickable", node.longClickable)
                .put("scrollable", node.scrollable).put("editable", node.editable).put("enabled", node.enabled)
                .put("password", node.password)) }
        }).toString()
}
