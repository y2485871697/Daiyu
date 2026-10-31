package app.daiyu.android.integration.tools

import android.content.Context
import app.daiyu.android.assistants.AssistantProfile
import app.daiyu.android.browser.*
import app.daiyu.android.browser.session.*
import app.daiyu.android.domain.*
import app.daiyu.android.domain.delegation.*
import app.daiyu.android.domain.runtime.*
import app.daiyu.android.domain.tools.*
import app.daiyu.android.platform.DeviceResultPolicy
import app.daiyu.android.skills.*
import app.daiyu.android.terminal.*
import app.daiyu.android.workspace.ConversationWorkspaceRun
import java.util.concurrent.atomic.AtomicBoolean

/** Child-owned catalog, frozen parent ceiling, and no user-facing authorization flow. */
internal class CatalogExecutionChildFactory(
    private val context: Context,
    private val assistant: AssistantProfile,
    private val ledger: ToolLedger,
    private val workspace: ConversationWorkspaceRun,
    private val browserSettings: BrowserSettings,
    private val androidCommands: TerminalConfiguration,
    private val resultPolicy: DeviceResultPolicy,
    private val shizukuAccess: app.daiyu.android.shizuku.ShizukuAuthorization,
    private val shizukuUid: Int?,
    private val runMode: app.daiyu.android.runningmode.RunningMode,
    private val linux: () -> CommandLinuxBinding,
) : ChildToolAssemblyFactory {
    private val capabilitiesByName = buildMap {
        put(ReadOnlyTool.BATTERY.toolName, AndroidToolRegistry.BATTERY_CAPABILITY)
        put(ReadOnlyTool.DEVICE_STATUS.toolName, AndroidToolRegistry.DEVICE_STATUS_CAPABILITY)
        app.daiyu.android.platform.PlatformReadTool.entries.forEach { put(it.toolName, it.capability) }
        put("skills_list", SkillRunSession.READ); put("skills_read", SkillRunSession.READ)
        put("skills_read_resource", SkillRunSession.RESOURCE)
        put("terminal_exec", RunCommandTools.CAPABILITY); put("terminal_read", RunCommandTools.CAPABILITY)
        put(RunBrowserTools.READ_TOOL_NAME, RunBrowserTools.READ_CAPABILITY)
        put(RunBrowserTools.WRITE_TOOL_NAME, RunBrowserTools.WRITE_CAPABILITY)
    }
    override fun restrict(session: SessionCapabilities, access: ChildToolAccess): ChildToolAccess {
        val browser = when {
            browserSettings.access == BrowserAccess.DISABLED || access.browser == ChildBrowserAccess.DISABLED -> ChildBrowserAccess.DISABLED
            browserSettings.access == BrowserAccess.READ_ONLY || access.browser == ChildBrowserAccess.READ_ONLY -> ChildBrowserAccess.READ_ONLY
            else -> ChildBrowserAccess.FULL
        }
        val names = capabilitiesByName.filter { (name, capability) ->
            access.permits(name) && capability in session.enabled &&
                (browser != ChildBrowserAccess.DISABLED || name !in setOf(RunBrowserTools.READ_TOOL_NAME, RunBrowserTools.WRITE_TOOL_NAME))
        }.keys
        return ChildToolAccess.of(names, browser)
    }
    override fun retain(): AutoCloseable = workspace.retain()
    override fun create(owner: ChildRunOwner, parentSession: SessionCapabilities,
        parentAuthorization: TaskAuthorization, access: ChildToolAccess): ChildToolAssembly {
        val effective = restrict(parentSession, access)
        val names = checkNotNull(effective.toolNames)
        val browserAccess = when (effective.browser) {
            ChildBrowserAccess.DISABLED -> BrowserAccess.DISABLED
            ChildBrowserAccess.READ_ONLY -> BrowserAccess.READ_ONLY
            ChildBrowserAccess.FULL -> BrowserAccess.FULL
        }
        val capabilities = names.map { capabilitiesByName.getValue(it) }.toSet()
        val scope = ChildRunScope.capture(owner, parentSession, parentAuthorization, capabilities)
        val pin = workspace.retain()
        return try {
            val devices = RunDeviceResources(context, resultPolicy = resultPolicy)
            val registry = AndroidToolRegistry.create(devices)
            val parentPermission = BrowserDefaultPermission.main(BrowserGrant(
                BrowserOwner(owner.parentRunId, BrowserOwnerKind.MAIN), browserSettings.access))
            var shizukuScope: RunOrdinaryShizukuTools? = null
            val catalog = RunCatalogTools(registry, scope.session, ledger, ConversationId(owner.conversationId),
                RunId(owner.childRunId), deviceResources = devices, authorization = scope.authorization,
                workspacePin = pin, allowedToolNames = names,
                ordinaryFactory = { realOwner, _, authorization ->
                    buildList {
                        if (shizukuUid != null && RunCommandTools.CAPABILITY in scope.session.enabled) {
                            val commands = RunOrdinaryShizukuTools(realOwner, authorization, scope.session,
                                shizukuAccess, shizukuUid, runMode,
                                isolatedAccess = { app.daiyu.android.shizuku.ShizukuAuthorization(context) })
                            shizukuScope = commands; add(commands)
                        }
                        if (browserAccess != BrowserAccess.DISABLED &&
                            scope.session.enabled.any { it == RunBrowserTools.READ_CAPABILITY || it == RunBrowserTools.WRITE_CAPABILITY })
                            add(RunOrdinaryBrowserTools.child(context, realOwner, authorization, assistant,
                                MessageId(owner.assistantMessageId), browserSettings.copy(access = browserAccess), scope, parentPermission))
                    }
                },
                commandFactory = if (RunCommandTools.CAPABILITY !in scope.session.enabled) null else { realOwner, authorization ->
                    val binding = linux()
                    try {
                        RunCommandTools.android(context, realOwner, authorization, binding.launch,
                            workingDirectory = workspace.resolved.hostDirectory, linuxState = binding.state,
                            androidConfiguration = androidCommands, shizukuUid = shizukuUid,
                            shizukuBackend = { shizukuScope?.backendForCommands() },
                            approve = { invocation, _, _ -> invocation.owner === realOwner &&
                                invocation.authorization === authorization && !invocation.cancelled() && access.permits(invocation.invocation.name) })
                    } catch (failure: Throwable) { binding.launch?.close(); throw failure }
                }) { grants, authorization ->
                SkillToolRegistry(context).captureForRun(grants, authorization, assistant.enabledSkillIds.toSet())
            }
            CatalogExecutionChildAssembly(scope, catalog)
        } catch (failure: Throwable) { pin.close(); throw failure }
    }
}

internal class CatalogExecutionChildAssembly(override val scope: ChildRunScope,
    private val catalog: RunCatalogTools) : ChildToolAssembly {
    private val closed = AtomicBoolean(false)
    override val tools: CatalogTool get() = catalog
    override val receipts: ReceiptBatchToolExecutor get() = catalog
    override fun prepareOnWorker(): Boolean = !closed.get()
    override fun close() { if (closed.compareAndSet(false, true)) catalog.close() }
    override fun observeClose(observer: (ChildToolDrain) -> Unit) = catalog.closeWithResult { result ->
        observer(when (result) {
            RunCatalogCloseResult.Drained -> ChildToolDrain.Drained
            is RunCatalogCloseResult.Unknown -> ChildToolDrain.Unknown(result.reason)
        })
    }
}
