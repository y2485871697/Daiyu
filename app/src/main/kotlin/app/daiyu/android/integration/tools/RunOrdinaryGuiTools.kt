package app.daiyu.android.integration.tools

import android.os.Looper
import android.util.Base64
import app.daiyu.android.RunScreenLocation
import app.daiyu.android.domain.runtime.RunExecutionOwner
import app.daiyu.android.domain.tools.*
import app.daiyu.android.gui.*
import app.daiyu.android.tasksurface.*
import org.json.JSONObject
import java.util.concurrent.CompletableFuture

internal class RunOrdinaryGuiTools(owner: RunExecutionOwner, authorization: TaskAuthorization,
    private val location: RunScreenLocation) : RunOwnedToolScope(owner, authorization) {
    private var current: GuiRunSession? = null
    private var target: String? = null
    init { retain(location) }
    private val targetField = ArgumentField("target_package", ArgumentType.Text(256, minimumLength = 1), required = false)
    // Definitions stay identical to the finite GUI leaf, plus an explicit target routing parameter.
    override val tools = listOf(false, true).map { action -> object : RegisteredTool {
        override val definition = (if (action) GuiScreenToolFactory.actionDefinition() else GuiScreenToolFactory.readDefinition(allowImplicitObservation = true))
            .let { original -> ToolDefinition(original.name, original.version, original.description,
                original.capability, original.effect, ToolSchema(original.schema.fields + targetField)) }
        override fun prepare(arguments: ToolArguments): PreparedTool {
            val requestedTarget = if (arguments.contains("target_package")) arguments.text("target_package") else null
            require(requestedTarget == null || GuiUserActionRequest.validTargetPackage(requestedTarget)) { "invalid_target_package" }
            if (!action) {
                val screenshot = arguments.text("operation") == "screenshot"
                require(screenshot || !arguments.contains("observation_id")) { "observation_id_not_allowed_for_observe" }
                val observationId = if (arguments.contains("observation_id")) arguments.text("observation_id") else null
                return read { context -> withSession(requestedTarget, context) { session ->
                    if (screenshot) encode(session.dynamicScreenshot(observationId, context.cancelled)) { image ->
                        JSONObject().put("observation_id", image.observationId).put("width", image.width).put("height", image.height)
                            .put("mime_type", "image/png").put("data_base64", Base64.encodeToString(image.png, Base64.NO_WRAP)).toString()
                    } else encode(session.dynamicObserve(context.cancelled), GuiScreenToolFactory::observationJson)
                } }
            }
            val command = GuiAction(arguments.text("observation_id"), GuiOperation.valueOf(arguments.text("operation").uppercase()),
                if (arguments.contains("node_id")) arguments.integer("node_id").toInt() else null,
                if (arguments.contains("text")) arguments.text("text") else null)
            val effect = command.effect(GuiOwner(authorization.sessionId, owner.runId.value))
            return this@RunOrdinaryGuiTools.write(effect) { context -> withSession(requestedTarget, context) { session ->
                encode(session.dynamicAction(command, context.authorization, context.cancelled)) { status ->
                    JSONObject().put("observation_id", command.observationId).put("status", status).put("verified_ui_result", false).toString()
                }
            } }
        }
    } }
    private fun withSession(requested: String?, context: ToolExecutionContext,
        execute: (GuiRunSession) -> ExecutionReceipt): ExecutionReceipt {
        val route = location.route(requested, backgroundSupported = false) { !live() || context.cancelled() }
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
                if (session != null && requested == target) future.complete(GuiResult.Value(session))
                else {
                    current = null; target = null
                    session?.close()
                    if (session != null && !session.releaseConfirmed) future.complete(GuiResult.Rejected("FOREGROUND_DRAIN_PENDING_NOT_EXECUTED"))
                    else {
                        val opened = GuiRuntime.openForUser(GuiOwner(authorization.sessionId, owner.runId.value), requested,
                            route.selfTarget, owner)
                        if (opened is GuiResult.Value) {
                            val held = opened.value
                            retain(held) { done -> observeGuiDrain(held, done) }
                            current = held; target = requested
                            held.bindDynamic(owner, authorization)
                            if (abandoned.get() || !live() || context.cancelled()) {
                                held.close(); current = null; target = null
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
