package app.daiyu.android.integration.tools

import android.content.Context
import app.daiyu.android.domain.ReadOnlyTool
import app.daiyu.android.domain.tools.*

/** Central registration only, not replacements for existing domain backends or ModelPort. */
object AndroidToolRegistry {
    const val ASK_USER_CAPABILITY = AskUserPlanTool.CAPABILITY
    const val BATTERY_CAPABILITY = "device.battery.read"
    const val DEVICE_STATUS_CAPABILITY = "device.status.read"

    fun capabilitiesFor(allowed: Set<ReadOnlyTool>): Set<String> = allowed.map { capability(it) }.toSet()

    fun create(context: Context, additionalTools: Iterable<RegisteredTool> = emptyList(),
        enableUserBarrier: Boolean = false): ToolRegistry =
        create(RunDeviceResources(context), additionalTools, enableUserBarrier)

    internal fun create(resources: RunDeviceResources,
        additionalTools: Iterable<RegisteredTool> = emptyList(),
        enableUserBarrier: Boolean = false): ToolRegistry {
        val registry = ToolRegistry()
        registerReadOnlyTools(registry, resources)
        if (enableUserBarrier) registry.register(AskUserPlanTool)
        additionalTools.forEach { registry.register(it) }
        return registry
    }

    fun registerReadOnlyTools(registry: ToolRegistry, context: Context) {
        registerReadOnlyTools(registry, RunDeviceResources(context))
    }

    internal fun registerReadOnlyTools(registry: ToolRegistry, resources: RunDeviceResources) {
        for (tool in ReadOnlyTool.entries) {
            registry.register(object : RegisteredTool {
                override val definition = ToolDefinition(tool.toolName, 1, tool.description, capability(tool),
                    ToolEffect.READ_ONLY, ToolSchema())
                override fun prepare(arguments: ToolArguments) = PreparedTool.contextual { context ->
                    resources.execute(context, tool)
                }
            })
        }
    }
    private fun capability(tool: ReadOnlyTool): String = when (tool) {
        ReadOnlyTool.BATTERY -> BATTERY_CAPABILITY
        ReadOnlyTool.DEVICE_STATUS -> DEVICE_STATUS_CAPABILITY
    }
}
