package app.daiyu.android.integration.tools

import android.content.Context
import app.daiyu.android.domain.ReadOnlyTool
import app.daiyu.android.domain.tools.*
import app.daiyu.android.platform.AndroidPlatformReads
import app.daiyu.android.platform.PlatformReadTool
import app.daiyu.android.platform.DeviceResultPolicy

/** Central registration only, not replacements for existing domain backends or ModelPort. */
object AndroidToolRegistry {
    const val ASK_USER_CAPABILITY = AskUserPlanTool.CAPABILITY
    const val BATTERY_CAPABILITY = "device.battery.read"
    const val DEVICE_STATUS_CAPABILITY = "device.status.read"

    /** Platform grants are explicit run-start settings, independent of legacy battery/status toggles. */
    fun capabilitiesFor(allowed: Set<ReadOnlyTool>,
        platformAllowed: Set<PlatformReadTool> = emptySet()): Set<String> =
        allowed.map { capability(it) }.toSet() + platformAllowed.map { it.capability }

    fun create(context: Context, additionalTools: Iterable<RegisteredTool> = emptyList(),
        enableUserBarrier: Boolean = false,
        resultPolicy: DeviceResultPolicy = DeviceResultPolicy()): ToolRegistry =
        create(RunDeviceResources(context, resultPolicy), additionalTools, enableUserBarrier)

    internal fun create(resources: RunDeviceResources,
        additionalTools: Iterable<RegisteredTool> = emptyList(),
        enableUserBarrier: Boolean = false): ToolRegistry {
        val registry = ToolRegistry()
        registerReadOnlyTools(registry, resources)
        registerPlatformTools(registry, resources)
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

    /** All Android reads share the same bound owner/task and close/drain fence. */
    internal fun registerPlatformTools(registry: ToolRegistry, resources: RunDeviceResources) {
        if (resources.platformBackend == null) return
        for (tool in PlatformReadTool.entries) {
            registry.register(object : RegisteredTool {
                override val definition = tool.definition()
                override fun prepare(arguments: ToolArguments): PreparedTool {
                    // Pure cross-field validation; admission rejects the WHOLE batch before reservation.
                    val valid = when (tool) {
                        PlatformReadTool.CALENDAR -> {
                            val start = arguments.integer("start_ms"); val end = arguments.integer("end_ms")
                            end > start && end - start <= AndroidPlatformReads.MAX_CALENDAR_WINDOW_MS
                        }
                        PlatformReadTool.FILE_TEXT ->
                            !(arguments.contains("offset_bytes") && arguments.contains("start_line")) &&
                                !(arguments.contains("start_line") && arguments.contains("skip_lines")) &&
                                (!arguments.contains("query") || arguments.text("query").isNotBlank())
                        PlatformReadTool.CONTACTS -> arguments.text("query").isNotBlank()
                        else -> true
                    }
                    return PreparedTool.contextual(
                        admit = { context -> resources.admit(context, tool.toolName, tool.capability)
                            ?: if (!valid) ExecutionReceipt.NotExecuted("invalid_arguments") else null },
                        execute = { context -> resources.execute(context, tool, arguments) })
                }
            })
        }
    }

    private fun capability(tool: ReadOnlyTool): String = when (tool) {
        ReadOnlyTool.BATTERY -> BATTERY_CAPABILITY
        ReadOnlyTool.DEVICE_STATUS -> DEVICE_STATUS_CAPABILITY
    }
}
