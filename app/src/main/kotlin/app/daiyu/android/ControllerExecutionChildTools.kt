package app.daiyu.android

import android.content.Context
import app.daiyu.android.assistants.AssistantProfile
import app.daiyu.android.browser.BrowserSettings
import app.daiyu.android.domain.tools.ToolLedger
import app.daiyu.android.integration.tools.*
import app.daiyu.android.platform.DeviceResultPolicy
import app.daiyu.android.terminal.*
import app.daiyu.android.workspace.ConversationWorkspaceRun

/** Freeze the parent's tool environment once. Each child obtains its own resource owner. */
internal fun executionChildTools(context: Context, assistant: AssistantProfile, ledger: ToolLedger,
    workspace: ConversationWorkspaceRun, browserSettings: BrowserSettings,
    resultPolicy: DeviceResultPolicy, shizukuAccess: app.daiyu.android.shizuku.ShizukuAuthorization): CatalogExecutionChildFactory {
    val mode = app.daiyu.android.runningmode.AndroidRunningModeStore.get(context).mode.value
    val androidCommands = RunCommandTools.androidConfiguration(context, workspace.resolved.hostDirectory, mode)
    val host = ((context as? ChatApplication)?.terminalProvider?.state?.value as? TerminalApplicationState.Ready)?.application
    val shizukuUid = if (androidCommands.backend == "shizuku") shizukuAccess.refresh().uid else null
    val state = host?.linuxStateOnWorker()
    val reference = workspace.resolved.rootfs?.let { LinuxGenerationRef(it.environmentId, it.generation) } ?: state?.selected
    val linuxRoot = host?.workspaceRootsOnWorker()?.firstOrNull {
        it.environmentId == reference?.environmentId && it.generation == reference?.generation
    }
    return CatalogExecutionChildFactory(context, assistant.copy(enabledSkillIds = assistant.enabledSkillIds.toList()),
        ledger, workspace, browserSettings, androidCommands, resultPolicy, shizukuAccess, shizukuUid, mode) {
        CommandLinuxBinding.freeze(state, reference) {
            if (host == null || reference == null || linuxRoot == null) null else
                host.freezeTerminalLaunchOnWorker(reference, context.cacheDir, workspace.resolved.hostDirectory,
                    workspace.linux(reference))
        }
    }
}
