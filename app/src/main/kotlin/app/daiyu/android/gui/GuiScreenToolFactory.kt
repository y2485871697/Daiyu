package app.daiyu.android.gui

import android.util.Base64
import app.daiyu.android.media.ScreenToolImageStore
import app.daiyu.android.domain.tools.*
import org.json.JSONArray
import org.json.JSONObject

/** Catalog leaf only. Register in the EXISTING registry/dispatcher/ledger; no alternate executor.
 * screen reads are bounded by explicit UI approval; screen_action has an exact WRITE effect.
 * The fixed-effect catalog cannot safely combine reads and writes under one polymorphic definition.
 */
internal object GuiScreenToolFactory {
    const val CAPABILITY = "gui.accessibility"
    private const val VISUAL_RECOVERY = "CAPTURE_CONTENT_CHANGED_NOT_EXECUTED and CAPTURE_STALE_NOT_EXECUTED mean the requested action was not delivered and the old capture is retired. " +
        "Do not end an otherwise authorized task solely for these recoverable results: first call screen operation=observe without observation_id or capture_id, inspect the fresh nodes or image, and select the target again using its new ID and current coordinates. " +
        "This is a new decision after a read, never an automatic retry of the old ID or coordinates. If a fresh read fails or the target cannot be verified, report that obstacle; do not bypass it. " +
        "UNKNOWN, unconfirmed delivery, cancellation and authorization denial do not authorize retry. "
    fun create(session: GuiRunSession, images: ScreenToolImageStore? = null): List<RegisteredTool> = listOf(ReadTool(session, images), ActionTool(session))

    private fun admission(session: GuiRunSession, context: ToolExecutionContext): ExecutionReceipt.NotExecuted? =
        session.admission(context.owner, context.authorization)?.let { ExecutionReceipt.NotExecuted(it.code) }

    internal fun readDefinition(allowImplicitObservation: Boolean = false): ToolDefinition = ToolDefinition("screen", 1,
            "Bounded accessibility observation or an explicitly requested screenshot. observe forbids observation_id. " +
                "Active system dialogs support observation and exact node actions, not screenshots or coordinate/global actions. The actual package_name and window_type are returned; omit target_package to inspect an intervening system dialog. " +
                "If truncated, request a fresh observe with max_nodes up to 512; never act on an incomplete tree. " +
                (if (allowImplicitObservation) "If the accessibility root is missing, observe automatically attempts one protected visual capture. A screen_read_mode=visual_fallback result is an image with capture_id, node_tree_available=false and node_failure_code, not a node observation; continue with its supported visual actions instead of retrying observe. screenshot accepts a current observation_id for a tree-bound image; omit it for a current visual-only image. Visual-only results have capture_id, not observation_id; run observe before node actions. "
                 else "screenshot requires observation_id from a successful observe in this session. ") +
                "IDs expire after ${GUI_OBSERVATION_TTL_MILLIS / 1000} seconds, a target window change, or an action. Never retry a stale ID automatically. " +
                (if (allowImplicitObservation) "For a fresh observe of the current screen omit target_package. Screenshots without a target retain this session target; use a fresh observe to change it. Self-app observation requires target_package=app.daiyu.android. To open another app, use launch_app first; target_package only selects a window, it does not launch an app. " else "") +
                (if (allowImplicitObservation) VISUAL_RECOVERY else "") +
                "If nodes are unavailable, an explicit screenshot without observation_id may use the already-authorized Root window witness plus platform capture. It returns no synthetic nodes; it never changes Root mode, HMA or accessibility settings. " +
                "APPLICATION_WINDOW_NOT_ACTIVE_NOT_EXECUTED means no active/focused window was verified, not that the phone is locked. Only SCREEN_LOCKED_NOT_EXECUTED reports a locked or noninteractive screen. Do not infer lock state from a window failure. " +
                "Window failures are not Android Root permission errors. Never use shell, input, screencap or uiautomator to bypass screen admission, target or location restrictions.",
            CAPABILITY, ToolEffect.READ_ONLY, ToolSchema(listOf(
                ArgumentField("operation", ArgumentType.Text(16, setOf("observe", "screenshot"))),
                ArgumentField("observation_id", ArgumentType.Text(64, minimumLength = 1), required = false),
                ArgumentField("max_nodes", ArgumentType.Integer(128, 512), required = false),
            )))

    private class ReadTool(private val session: GuiRunSession, private val images: ScreenToolImageStore?) : RegisteredTool {
        override val definition = readDefinition()
        override fun prepare(arguments: ToolArguments): PreparedTool {
            val screenshot = arguments.text("operation") == "screenshot"
            require(screenshot == arguments.contains("observation_id") && (!screenshot || !arguments.contains("max_nodes"))) { "invalid_arguments" }
            val id = if (screenshot) arguments.text("observation_id") else null
            return PreparedTool.contextual(admit = { admission(session, it) }) { context ->
                admission(session, context)?.let { return@contextual it }
                if (screenshot) result(session.screenshot(id!!, context.cancelled)) { image -> screenshotReceipt(image, null, context, images) } else result(session.observe(context.cancelled, maxNodes(arguments)), ::observationJson)
            }
        }
    }
    internal fun screenshotJson(image: GuiScreenshot): String = screenshotJson(image, null)
    internal fun screenshotJson(image: GuiScreenshot, nodeFailureCode: String?): String = screenshotMetadata(image, nodeFailureCode)
        .put("data_base64", Base64.encodeToString(image.png, Base64.NO_WRAP)).toString()

    internal fun screenshotReceipt(image: GuiScreenshot, nodeFailureCode: String?, context: ToolExecutionContext,
        images: ScreenToolImageStore?): String = if (images == null) screenshotJson(image, nodeFailureCode) else
        images.receipt(context.owner.conversationId.value, context.owner.runId.value, context.invocation.id.value,
            image, screenshotMetadata(image, nodeFailureCode).toString())

    private fun screenshotMetadata(image: GuiScreenshot, nodeFailureCode: String?): JSONObject = JSONObject()
        .put(if (image.observationBound) "observation_id" else "capture_id", image.observationId)
        .put("width", image.width).put("height", image.height)
        .put("package_name", image.visualPackage ?: JSONObject.NULL)
        .put("visual_actions_available", image.visualPackage != null).put("mime_type", "image/png")
        .apply {
            if (nodeFailureCode != null) {
                put("screen_read_mode", "visual_fallback")
                put("node_tree_available", false)
                put("node_failure_code", nodeFailureCode)
            }
        }

    internal fun actionDefinition(allowVisualCapture: Boolean = false): ToolDefinition = ToolDefinition("screen_action", 1,
            "One exact authorized accessibility node, coordinate gesture or global action, bound to the same fresh observation_id. " +
                (if (allowVisualCapture) "When screen returns capture_id, including an observe visual_fallback result, it has no nodes. Use capture_id (not observation_id) only for coordinate_tap, swipe or back; the real foreground window, display geometry and target bounds are checked before delivery. For coordinate_tap, pixel changes (including target-tile changes) are diagnostic only and do not block an authorized tap; swipe and back still require a stable frame. Other actions require observe. " else "") +
                (if (allowVisualCapture) VISUAL_RECOVERY else "") +
                "coordinate_tap uses x/y; swipe uses x/y/end_x/end_y and optional duration_ms (50-2000, default 500). Coordinates are screen pixels in the observed application window, never screenshot-scaled. " +
                "ime_submit requires an editable focused node advertising IME_ENTER on API 30+. Explicit gestures are not a fallback for denied or unknown actions. " +
                "Self-app observations do not authorize HOME/BACK/RECENTS/NOTIFICATIONS; when provided, use launch_app to open another app. No automatic coordinate fallback/retry or shell/input/uiautomator bypass of screen admission, target or location restrictions. Android acceptance is not verified business success.",
            CAPABILITY, ToolEffect.WRITE, ToolSchema(listOf(
                ArgumentField("operation", ArgumentType.Text(32, GuiOperation.entries.map { it.name.lowercase() }.toSet())),
                ArgumentField("observation_id", ArgumentType.Text(64, minimumLength = 1), required = !allowVisualCapture),
                *if (allowVisualCapture) arrayOf(ArgumentField("capture_id", ArgumentType.Text(64, minimumLength = 1), required = false)) else emptyArray(),
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
        val visual = arguments.contains("capture_id")
        require(arguments.contains("observation_id") != visual) { "one_screen_identity_required" }
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
        return GuiAction(arguments.text(if (visual) "capture_id" else "observation_id"), operation,
            if (arguments.contains("node_id")) arguments.integer("node_id").toInt() else null,
            if (arguments.contains("text")) arguments.text("text") else null, gesture, visual)
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
        .put("window_id", value.windowId).put("window_type", value.windowType).put("truncated", value.truncated).put("max_nodes", value.maxNodes)
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
