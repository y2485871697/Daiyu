package app.daiyu.android.integration.tools

import android.content.Context
import app.daiyu.android.domain.BatteryTool
import app.daiyu.android.domain.ReadOnlyTool
import app.daiyu.android.domain.runtime.RunExecutionOwner
import app.daiyu.android.domain.tools.ExecutionReceipt
import app.daiyu.android.domain.tools.SessionCapabilities
import app.daiyu.android.domain.tools.TaskAuthorization
import app.daiyu.android.domain.tools.ToolExecutionContext
import app.daiyu.android.platform.AndroidReadOnlyTools
import org.json.JSONObject

/** Run ownership and close confirmation for the current synchronous device reads. */
internal class RunDeviceResources(private val backend: BatteryTool) : AutoCloseable {
    constructor(context: Context) : this(AndroidReadOnlyTools(
        context.applicationContext ?: context, ReadOnlyTool.entries.toSet()))

    private val gate = Any()
    private var owner: RunExecutionOwner? = null
    private var authorization: TaskAuthorization? = null
    private var capabilities: SessionCapabilities? = null
    private var closed = false
    private var inFlight = 0
    private var acknowledgement: (() -> Unit)? = null

    internal fun bind(owner: RunExecutionOwner, authorization: TaskAuthorization,
        capabilities: SessionCapabilities) = synchronized(gate) {
        check(!closed) { "run_closed" }
        require(owner.runId.value == authorization.taskId &&
            authorization.sessionId == capabilities.sessionId) { "tool_run_owner_mismatch" }
        if (this.owner != null) {
            check(this.owner === owner && this.authorization === authorization &&
                this.capabilities === capabilities) { "device_resources_already_bound" }
        } else {
            this.owner = owner; this.authorization = authorization; this.capabilities = capabilities
        }
    }

    internal fun execute(context: ToolExecutionContext, tool: ReadOnlyTool): ExecutionReceipt {
        val capability = when (tool) {
            ReadOnlyTool.BATTERY -> AndroidToolRegistry.BATTERY_CAPABILITY
            ReadOnlyTool.DEVICE_STATUS -> AndroidToolRegistry.DEVICE_STATUS_CAPABILITY
        }
        synchronized(gate) {
            if (closed) return ExecutionReceipt.NotExecuted("run_closed")
            val grants = capabilities ?: return ExecutionReceipt.NotExecuted("device_run_not_bound")
            if (context.owner !== owner) return ExecutionReceipt.NotExecuted("tool_run_owner_mismatch")
            if (context.authorization !== authorization) return ExecutionReceipt.NotExecuted("tool_run_authorization_mismatch")
            if (context.invocation.name != tool.toolName) return ExecutionReceipt.NotExecuted("tool_invocation_mismatch")
            if (capability !in grants.enabled) return ExecutionReceipt.NotExecuted("capability_disabled")
            inFlight++
        }
        try {
            if (context.cancelled()) return ExecutionReceipt.NotExecuted("cancelled_before_execution")
            val payload = backend.execute(context.invocation)
            val result = JSONObject(payload)
            return if (!result.has("ok") || result.get("ok") !is Boolean)
                ExecutionReceipt.Unknown("invalid_backend_receipt")
            else ExecutionReceipt.Confirmed(payload, isError = !result.getBoolean("ok"))
        } finally {
            val notify = synchronized(gate) {
                check(inFlight > 0); inFlight--
                if (closed && inFlight == 0) acknowledgement.also { acknowledgement = null } else null
            }
            notify?.invoke()
        }
    }

    /** No Android I/O, waits or callbacks under the admission lock. */
    override fun close() { synchronized(gate) { closed = true } }

    internal fun closeWhenIdle(onClosed: () -> Unit) {
        val notify = synchronized(gate) {
            closed = true
            if (inFlight == 0) onClosed else {
                check(acknowledgement == null) { "device_close_already_observed" }
                acknowledgement = onClosed; null
            }
        }
        notify?.invoke()
    }
}
