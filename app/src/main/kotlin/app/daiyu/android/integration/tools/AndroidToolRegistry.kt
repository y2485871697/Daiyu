package app.daiyu.android.integration.tools

import android.content.Context
import app.daiyu.android.domain.ReadOnlyTool
import app.daiyu.android.domain.ToolCallId
import app.daiyu.android.domain.ToolInvocation
import app.daiyu.android.domain.tools.*
import app.daiyu.android.platform.AndroidReadOnlyTools
import org.json.JSONObject

/** Central registration only, not replacements for existing domain backends or ModelPort. */
object AndroidToolRegistry {
    const val ASK_USER_CAPABILITY = AskUserPlanTool.CAPABILITY
    const val BATTERY_CAPABILITY = "device.battery.read"
    const val DEVICE_STATUS_CAPABILITY = "device.status.read"

    fun capabilitiesFor(allowed: Set<ReadOnlyTool>): Set<String> = allowed.map { capability(it) }.toSet()

    fun create(context: Context, additionalTools: Iterable<RegisteredTool> = emptyList(),
        enableUserBarrier: Boolean = false): ToolRegistry {
        val registry = ToolRegistry()
        registerReadOnlyTools(registry, context)
        if (enableUserBarrier) registry.register(AskUserPlanTool)
        additionalTools.forEach { registry.register(it) }
        return registry
    }

    fun registerReadOnlyTools(registry: ToolRegistry, context: Context) {
        val backend = AndroidReadOnlyTools(context.applicationContext ?: context, ReadOnlyTool.entries.toSet())
        for (tool in ReadOnlyTool.entries) {
            registry.register(object : RegisteredTool {
                override val definition = ToolDefinition(tool.toolName, 1, tool.description, capability(tool),
                    ToolEffect.READ_ONLY, ToolSchema())
                override fun prepare(arguments: ToolArguments) = PreparedTool {
                    // Existing real backend enforces empty arguments, reads live Android services, and returns JSON.
                    // Its call ID is only transport metadata; central ledger uses the caller's real ID.
                    val payload = backend.execute(ToolInvocation(ToolCallId("registry.read-only"), tool.toolName, arguments.canonicalJson()))
                    val result = JSONObject(payload)
                    if (!result.has("ok") || result.get("ok") !is Boolean) ExecutionReceipt.Unknown("invalid_backend_receipt")
                    else ExecutionReceipt.Confirmed(payload, isError = !result.getBoolean("ok"))
                }
            })
        }
    }
    private fun capability(tool: ReadOnlyTool): String = when (tool) {
        ReadOnlyTool.BATTERY -> BATTERY_CAPABILITY
        ReadOnlyTool.DEVICE_STATUS -> DEVICE_STATUS_CAPABILITY
    }
}
