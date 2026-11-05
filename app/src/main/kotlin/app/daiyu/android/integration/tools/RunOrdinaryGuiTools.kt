package app.daiyu.android.integration.tools

import android.os.Looper
import app.daiyu.android.RunScreenLocation
import app.daiyu.android.domain.runtime.RunExecutionOwner
import app.daiyu.android.domain.tools.*
import app.daiyu.android.gui.*
import app.daiyu.android.tasksurface.*
import org.json.JSONObject
import java.util.concurrent.CompletableFuture

internal class RunOrdinaryGuiTools(owner: RunExecutionOwner, authorization: TaskAuthorization,
    private val location: RunScreenLocation) : RunOwnedToolScope(owner, authorization) {
    @Volatile private var current: GuiRunSession? = null
    private var observation: GuiObservation? = null
    private var target: String? = null
    private var capture: Pair<String, String>? = null
    init { retain(location) }
    private val targetField = ArgumentField("target_package", ArgumentType.Text(256, minimumLength = 1), required = false)
    // Definitions stay identical to the finite GUI leaf, plus an explicit target routing parameter.
    override val tools: List<RegisteredTool> = listOf(false, true).map { action -> object : RegisteredTool {
        override val definition = (if (action) GuiScreenToolFactory.actionDefinition(allowVisualCapture = true) else GuiScreenToolFactory.readDefinition(allowImplicitObservation = true))
            .let { original -> ToolDefinition(original.name, original.version, original.description,
                original.capability, original.effect, ToolSchema(original.schema.fields + targetField)) }
        override fun prepare(arguments: ToolArguments): PreparedTool {
            val requestedTarget = if (arguments.contains("target_package")) arguments.text("target_package") else null
            require(requestedTarget == null || GuiUserActionRequest.validTargetPackage(requestedTarget)) { "invalid_target_package" }
            if (!action) {
                val screenshot = arguments.text("operation") == "screenshot"
                require((screenshot || !arguments.contains("observation_id")) && (!screenshot || !arguments.contains("max_nodes"))) { "observation_id_not_allowed_for_observe" }
                val observationId = if (arguments.contains("observation_id")) arguments.text("observation_id") else null
                return read { context -> withSession(if (screenshot && observationId == null) requestedTarget ?: target else requestedTarget, context, observationId) { session ->
                    if (observationId == null) { observation = null; capture = null }
                    if (screenshot) encode(session.dynamicScreenshot(observationId, context.cancelled)) { image ->
                        capture = image.visualPackage?.let { image.observationId to it }
                        GuiScreenToolFactory.screenshotJson(image)
                    }
                    else encode(session.dynamicScreen(context.cancelled, GuiScreenToolFactory.maxNodes(arguments))) { result ->
                        when (result) {
                            is GuiScreenRead.Nodes -> {
                                observation = result.observation
                                GuiScreenToolFactory.observationJson(result.observation)
                            }
                            is GuiScreenRead.Visual -> {
                                capture = result.image.visualPackage?.let { result.image.observationId to it }
                                GuiScreenToolFactory.screenshotJson(result.image, result.nodeFailureCode)
                            }
                        }
                    }
                } }
            }
            val command = GuiScreenToolFactory.parseAction(arguments)
            val effect = command.effect(GuiOwner(authorization.sessionId, owner.runId.value))
            return this@RunOrdinaryGuiTools.write(effect) { context -> withSession(requestedTarget, context, command.observationId, command.visual) { session ->
                encode(session.dynamicAction(command, context.authorization, context.cancelled)) { status ->
                    JSONObject().put(if (command.visual) "capture_id" else "observation_id", command.observationId).put("status", status).put("verified_ui_result", false).toString()
                }
            } }
        }
    } } + object : RegisteredTool {
        override val definition = ToolDefinition("launch_app", 1,
            "打开一个精确包名的已安装应用。不确定包名时先用search_apps搜索，不猜包名。" +
                "从助手页面操作其它应用时直接调用本工具，不需要先观察助手或返回桌面。" +
                "遵守本次前后台位置、原owner和屏幕占用；不使用shell、不更改权限。" +
                "启动受理不等于目标页面已显示；需要操作页面时重新screen observe。UNKNOWN不得重发。",
            GuiScreenToolFactory.CAPABILITY, ToolEffect.WRITE, ToolSchema(listOf(
                ArgumentField("package_name", ArgumentType.Text(256, minimumLength = 1)))))
        override fun prepare(arguments: ToolArguments): PreparedTool {
            val request = GuiAppLaunch(arguments.text("package_name"))
            return write(request.effect(GuiOwner(authorization.sessionId, owner.runId.value)), repeatConfirmed = true) { context ->
                withSession(request.packageName, context) { session ->
                    observation = null; capture = null
                    encode(session.dynamicLaunch(request, context.authorization, context.cancelled)) { status ->
                        JSONObject().put("package_name", request.packageName).put("status", status)
                            .put("launch_request_accepted", true).put("target_window_state", "NOT_VERIFIED")
                            .put("verification_required", true).put("verified_ui_result", false)
                            .put("next_action", "仅确认启动请求已提交，不代表应用界面或目标页面已显示。需要节点操作时必须取得成功的screen观察；节点树不可读时screen observe会自动尝试一次受保护视觉读取；返回screen_read_mode=visual_fallback时按图像和capture_id继续，仅在visual_actions_available=true时执行其支持的视觉坐标动作，不要重试节点观察或编造节点ID。截图或窗口见证仍失败时停止屏幕操作，不要据此要求点亮、解锁或重授普通应用权限。")
                            .toString()
                    }
                }
            }
        }
    }
    private fun withSession(requested: String?, context: ToolExecutionContext, observationId: String? = null, visual: Boolean = false,
        execute: (GuiRunSession) -> ExecutionReceipt): ExecutionReceipt {
        denial(context, original = false)?.let { return it }
        current?.replacementRejection(owner, authorization)?.let { return ExecutionReceipt.NotExecuted(it.code) }
        val selectedTarget = if (observationId != null) {
            val observed = observation
            val visualTarget = capture?.takeIf { visual && it.first == observationId }?.second
            if (visual) {
                if (visualTarget == null) return ExecutionReceipt.NotExecuted("CAPTURE_ID_MISMATCH_NOT_EXECUTED")
                if (requested != null && requested != visualTarget) return ExecutionReceipt.NotExecuted("OBSERVATION_TARGET_MISMATCH_NOT_EXECUTED")
            } else if (observed == null || observed.observationId != observationId)
                return ExecutionReceipt.NotExecuted("OBSERVATION_ID_MISMATCH")
            if (!visual && requested != null && requested != observed!!.packageName)
                return ExecutionReceipt.NotExecuted("OBSERVATION_TARGET_MISMATCH_NOT_EXECUTED")
            target
        } else requested
        if (observationId != null) current?.admission(owner, authorization)?.let { return ExecutionReceipt.NotExecuted(it.code) }
        val route = location.route(selectedTarget, backgroundSupported = false) { !live() || context.cancelled() }
        if (route !is TaskSurfaceDecision.Ready) return ExecutionReceipt.NotExecuted(
            (route as? TaskSurfaceDecision.NotExecuted)?.code ?: "TASK_SURFACE_NOT_READY")
        if (route.surface != TaskSurface.FOREGROUND) return ExecutionReceipt.NotExecuted("TASK_SURFACE_BACKGROUND_UNSUPPORTED")
        val future = CompletableFuture<GuiResult<GuiRunSession>>()
        val abandoned = java.util.concurrent.atomic.AtomicBoolean(false)
        beginCreation()
        if (!GuiRuntime.main.post {
            try {
            if (abandoned.get() || !live() || context.cancelled()) future.complete(GuiResult.Rejected("CANCELLED_NOT_EXECUTED"))
            else {
                val session = current
                val admission = session?.admission(owner, authorization)
                val replacementDenied = session?.replacementRejection(owner, authorization)
                if (replacementDenied != null) future.complete(replacementDenied)
                else if (admission != null && observationId != null) future.complete(admission)
                else if (session != null && admission == null && selectedTarget == target) future.complete(GuiResult.Value(session))
                else {
                    current = null; observation = null; capture = null
                    session?.close()
                    if (session != null && !session.releaseConfirmed) future.complete(GuiResult.Rejected("FOREGROUND_DRAIN_PENDING_NOT_EXECUTED"))
                    else {
                        val opened = GuiRuntime.openForUser(GuiOwner(authorization.sessionId, owner.runId.value), selectedTarget,
                            route.selfTarget, owner)
                        if (opened is GuiResult.Value) {
                            val held = opened.value
                            retain(held) { done -> observeGuiDrain(held, done) }
                            current = held; target = selectedTarget
                            held.bindDynamic(owner, authorization)
                            if (abandoned.get() || !live() || context.cancelled()) {
                                held.close(); current = null
                            }
                        }
                        future.complete(opened)
                    }
                }
            }
            } catch (_: Exception) { future.complete(GuiResult.Unknown("GUI_OPERATION_UNCONFIRMED")) }
            finally { endCreation() }
        }) { endCreation(); return ExecutionReceipt.NotExecuted("GUI_HOST_UNAVAILABLE") }
        val opened = try { future.get(5, java.util.concurrent.TimeUnit.SECONDS) }
        catch (failure: Exception) {
            abandoned.set(true)
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            return ExecutionReceipt.NotExecuted("GUI_SESSION_PREPARATION_UNAVAILABLE_NOT_EXECUTED")
        }
        return when (opened) {
            is GuiResult.Rejected -> ExecutionReceipt.NotExecuted(opened.code)
            is GuiResult.Unknown -> ExecutionReceipt.Unknown(opened.code)
            is GuiResult.Value -> if (!live() || context.cancelled()) ExecutionReceipt.NotExecuted("CANCELLED_NOT_EXECUTED")
                else opened.value.admission(owner, authorization)?.let { ExecutionReceipt.NotExecuted(it.code) } ?: execute(opened.value)
        }
    }
    override fun markUnknown() { super.markUnknown(); current?.onCentralUnknown() }
    private fun <T> encode(result: GuiResult<T>, body: (T) -> String): ExecutionReceipt = when (result) {
        is GuiResult.Rejected -> ExecutionReceipt.NotExecuted(result.code)
        is GuiResult.Unknown -> ExecutionReceipt.Unknown(result.code)
        is GuiResult.Value -> ExecutionReceipt.Confirmed(body(result.value))
    }
}
