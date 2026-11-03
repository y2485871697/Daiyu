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
                "If truncated, request a fresh observe with max_nodes up to 512; never act on an incomplete tree. " +
                (if (allowImplicitObservation) "screenshot accepts a current observation_id for a tree-bound image; omit it for a current visual-only image. Visual-only results have capture_id, not observation_id; run observe before node actions. "
                 else "screenshot requires observation_id from a successful observe in this session. ") +
                "IDs expire after ${GUI_OBSERVATION_TTL_MILLIS / 1000} seconds, a target window change, or an action. Never retry a stale ID automatically. " +
                (if (allowImplicitObservation) "For a fresh observe of the current screen omit target_package. Screenshots without a target retain this session target; use a fresh observe to change it. Self-app observation requires target_package=app.daiyu.android. To open another app, use launch_app first; target_package only selects a window, it does not launch an app. " else "") +
                "Window failures are not Android Root permission errors. Never use shell, input, screencap or uiautomator to bypass screen admission, target or location restrictions.",
            CAPABILITY, ToolEffect.READ_ONLY, ToolSchema(listOf(
                ArgumentField("operation", ArgumentType.Text(16, setOf("observe", "screenshot"))),
                ArgumentField("observation_id", ArgumentType.Text(64, minimumLength = 1), required = false),
                ArgumentField("max_nodes", ArgumentType.Integer(128, 512), required = false),
            )))

    private class ReadTool(private val session: GuiRunSession) : RegisteredTool {
        override val definition = readDefinition()
        override fun prepare(arguments: ToolArguments): PreparedTool {
            val screenshot = arguments.text("operation") == "screenshot"
            require(screenshot == arguments.contains("observation_id") && (!screenshot || !arguments.contains("max_nodes"))) { "invalid_arguments" }
            val id = if (screenshot) arguments.text("observation_id") else null
            return PreparedTool.contextual(admit = { admission(session, it) }) { context ->
                admission(session, context)?.let { return@contextual it }
                if (screenshot) result(session.screenshot(id!!, context.cancelled), ::screenshotJson) else result(session.observe(context.cancelled, maxNodes(arguments)), ::observationJson)
            }
        }
    }
    internal fun screenshotJson(image: GuiScreenshot): String = JSONObject()
        .put(if (image.observationBound) "observation_id" else "capture_id", image.observationId)
        .put("width", image.width).put("height", image.height).put("mime_type", "image/png")
        .put("data_base64", Base64.encodeToString(image.png, Base64.NO_WRAP)).toString()

    internal fun actionDefinition(): ToolDefinition = ToolDefinition("screen_action", 1,
            "One exact authorized accessibility node, coordinate gesture or global action, bound to the same fresh observation_id. " +
                "coordinate_tap uses x/y; swipe uses x/y/end_x/end_y and optional duration_ms (50-2000, default 500). Coordinates are screen pixels in the observed application window, never screenshot-scaled. " +
                "ime_submit requires an editable focused node advertising IME_ENTER on API 30+. Explicit gestures are not a fallback for denied or unknown actions. " +
                "Self-app observations do not authorize HOME/BACK/RECENTS/NOTIFICATIONS; when provided, use launch_app to open another app. No automatic coordinate fallback/retry or shell/input/uiautomator bypass of screen admission, target or location restrictions. Android acceptance is not verified business success.",
            CAPABILITY, ToolEffect.WRITE, ToolSchema(listOf(
                ArgumentField("operation", ArgumentType.Text(32, GuiOperation.entries.map { it.name.lowercase() }.toSet())),
                ArgumentField("observation_id", ArgumentType.Text(64, minimumLength = 1)),
                ArgumentField("node_id", ArgumentType.Integer(0, 511), required = false),
                ArgumentField("x", ArgumentType.Integer(0, 100_000), required = false),
                ArgumentField("y", ArgumentType.Integer(0, 100_000), required = false),
                ArgumentField("end_x", ArgumentType.Integer(0, 100_000), required = false),
                ArgumentField("end_y", ArgumentType.Integer(0, 100_000), required = false),
                ArgumentField("duration_ms", ArgumentType.Integer(50, 2000), required = false),
                ArgumentField("text", ArgumentType.Text(4096), required = false),
            )))

    internal fun maxNodes(arguments: ToolArguments): Int =
        if (arguments.contains("max_nodes")) arguments.integer("max_nodes").toInt() else 128
    internal fun parseAction(arguments: ToolArguments): GuiAction {
        val operation = GuiOperation.valueOf(arguments.text("operation").uppercase())
        val coordinate = operation in setOf(GuiOperation.COORDINATE_TAP, GuiOperation.SWIPE)
        val swipe = operation == GuiOperation.SWIPE
        require(arguments.contains("x") == coordinate && arguments.contains("y") == coordinate) { "invalid_gesture_parameters" }
        require(arguments.contains("end_x") == swipe && arguments.contains("end_y") == swipe) { "invalid_gesture_parameters" }
        require(swipe || !arguments.contains("duration_ms")) { "invalid_gesture_parameters" }
        val gesture = if (coordinate) {
            val x = arguments.integer("x").toInt(); val y = arguments.integer("y").toInt()
            GuiGesture(x, y, if (swipe) arguments.integer("end_x").toInt() else x,
                if (swipe) arguments.integer("end_y").toInt() else y,
                if (!swipe) 50 else if (arguments.contains("duration_ms")) arguments.integer("duration_ms") else 500)
        } else null
        return GuiAction(arguments.text("observation_id"), operation,
            if (arguments.contains("node_id")) arguments.integer("node_id").toInt() else null,
            if (arguments.contains("text")) arguments.text("text") else null, gesture)
    }

    private class ActionTool(private val session: GuiRunSession) : RegisteredTool {
        override val definition = actionDefinition()
        override fun prepare(arguments: ToolArguments): PreparedTool {
            val action = parseAction(arguments)
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
        .put("window_id", value.windowId).put("truncated", value.truncated).put("max_nodes", value.maxNodes)
        .put("recovery_hint", if (value.truncated && value.maxNodes < 512) "observe_again_with_max_nodes_512" else JSONObject.NULL)
        .put("captured_elapsed_realtime_ms", value.capturedAt).put("expires_after_ms", GUI_OBSERVATION_TTL_MILLIS)
        .put("nodes", JSONArray().apply {
            value.nodes.forEach { node -> put(JSONObject().put("id", node.id).put("parent", node.parent ?: JSONObject.NULL)
                .put("bounds", JSONArray(listOf(node.bounds.left, node.bounds.top, node.bounds.right, node.bounds.bottom)))
                .put("text", node.text).put("description", node.description).put("class_name", node.className)
                .put("view_id", node.viewId).put("clickable", node.clickable).put("long_clickable", node.longClickable)
                .put("scrollable", node.scrollable).put("editable", node.editable).put("enabled", node.enabled)
                .put("password", node.password)) }
        }).toString()
}
