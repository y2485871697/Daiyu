package app.daiyu.android.ui.screens.settings

import app.daiyu.android.ui.navigation.PageBackHandler as BackHandler
import androidx.compose.foundation.background
import app.daiyu.android.ui.haptics.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import app.daiyu.android.ui.haptics.hapticClick
import androidx.compose.ui.unit.dp
import app.daiyu.android.runningmode.*
import app.daiyu.android.terminal.*
import app.daiyu.android.terminal.install.LinuxInstallState
import app.daiyu.android.ui.components.OptionSelectionSheet
import app.daiyu.android.ui.components.OptionSelectionItem
import app.daiyu.android.ui.components.DaiyuConfirmDialog
import app.daiyu.android.ui.icons.DaiyuIcon
import app.daiyu.android.ui.icons.DaiyuIcons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.daiyu.android.ui.haptics.miuix.Button
import top.yukonga.miuix.kmp.basic.Text
import app.daiyu.android.ui.haptics.miuix.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Parent retains the page owner. Browsing and selecting never start a terminal or install. */
@Composable
internal fun LinuxSettingsScreen(controller: LinuxSettingsController, page: LinuxSettingsPageOwner,
    onBack: () -> Unit, modifier: Modifier = Modifier, host: TerminalEnvironmentHost? = null,
    runningModeStore: RunningModeStore = AndroidRunningModeStore.get(LocalContext.current),
    filesOnly: Boolean = false, onOpenTerminal: (() -> Unit)? = null,
    suppliedChoicePreferences: LinuxInstallChoicePreferences? = null) = LinuxSettingsTheme {
    val runningMode by runningModeStore.mode.collectAsState()
    val rootMode = runningMode == RunningMode.ROOT
    val state by page.state.collectAsState()
    val terminal = host?.state?.collectAsState()?.value
    val proot = controller.installation?.state?.collectAsState()?.value
    val chroot = controller.chrootInstallation?.state?.collectAsState()?.value
    val selected = terminal?.linux?.choices?.find { it.reference == terminal.linux.selected }
    val context = LocalContext.current
    val choicePreferences = suppliedChoicePreferences ?: remember(context) {
        LinuxInstallChoicePreferences(context.applicationContext.getSharedPreferences("linux-settings-v1", android.content.Context.MODE_PRIVATE))
    }
    var installChoice by remember(page, choicePreferences) { mutableStateOf(choicePreferences.read()) }
    val distributionDraft = installChoice?.distribution
    val modeDraft = installChoice?.executionMode
    val pendingMode = when {
        proot?.let { it.prepared != null && !it.registered || it.unknown || it.recoveryBlocked || it.cleanupPending } == true -> LinuxExecutionMode.PROOT
        chroot?.let { it.prepared != null && !it.registered || it.unknown || it.recoveryBlocked || it.cleanupPending } == true -> LinuxExecutionMode.CHROOT
        else -> null
    }
    val pendingInstall = if (pendingMode == LinuxExecutionMode.CHROOT) chroot else proot.takeIf { pendingMode != null }
    val distribution = if (filesOnly) selected?.label ?: terminal?.linux?.distribution ?: "alpine"
        else pendingInstall?.prepared?.artifact?.distribution ?: distributionDraft ?: terminal?.linux?.distribution ?: "alpine"
    val mode = if (!rootMode) LinuxExecutionMode.PROOT
        else if (filesOnly) selected?.executionMode ?: LinuxExecutionMode.PROOT
        else pendingMode ?: modeDraft ?: selected?.executionMode ?: LinuxExecutionMode.PROOT
    val installation = if (mode == LinuxExecutionMode.CHROOT) chroot else proot
    val otherInstallation = if (mode == LinuxExecutionMode.CHROOT) proot else chroot
    val scope = rememberCoroutineScope()
    var selecting by remember(page) { mutableStateOf(false) }
    var message by remember(page) { mutableStateOf<String?>(null) }
    var tab by remember(page, filesOnly) { mutableStateOf(if (filesOnly) LinuxSettingsTab.FILES else LinuxSettingsTab.BASIC) }
    var tabScope by remember(page) { mutableStateOf(Any()) }
    val renderedTabScope = tabScope
    fun basicCurrent() = !filesOnly && tab == LinuxSettingsTab.BASIC && tabScope === renderedTabScope && !page.state.value.revoked
    val choiceScope = remember(page, runningMode) { Any() }
    val currentChoiceScope = rememberUpdatedState(choiceScope)
    fun choiceCurrent() = basicCurrent() && currentChoiceScope.value === choiceScope
    var confirmInstall by remember(page) { mutableStateOf(false) }
    var confirmUse by remember(page) { mutableStateOf(false) }
    var confirmPending by remember(page) { mutableStateOf(false) }
    DisposableEffect(page) { onDispose { page.close() } }
    LaunchedEffect(host) { host?.refreshLinux() }
    LaunchedEffect(proot?.registered, chroot?.registered) {
        if (proot?.registered == true || chroot?.registered == true) { page.refresh(); host?.refreshLinux() }
    }
    val leave: () -> Unit = { if (page.revoke()) onBack() }
    BackHandler { leave() }
    val pageEnabled = !state.busy && !state.revoked && !selecting
    val enabled = pageEnabled && state.pendingApproval == null && state.pendingRemoval == null
    val choiceEnabled = enabled && terminal?.canOpen != false && !controller.installationBlocksManual
    val matching = state.entries.filter { it.distribution.equals(distribution, ignoreCase = true) && it.executionMode == mode }
    val installed = state.listConfirmed && matching.isNotEmpty()
    val installationMatches = installation?.prepared?.let {
        it.artifact.distribution.equals(distribution, ignoreCase = true) && it.installation.executionMode == mode
    } == true
    val pendingRequest = installation?.let { it.busy || it.unknown || it.recoveryBlocked || it.cleanupPending || it.registrationPending || (it.prepared != null && !it.registered) } == true
    val displayedInstallation = if (installation?.registered == true && !installationMatches)
        installation.copy(prepared = null, receipt = null, registration = null, registered = false, notice = null)
    else installation

    fun choose(nextDistribution: String, nextMode: LinuxExecutionMode) {
        if (!choiceCurrent() || !choiceEnabled || nextMode == LinuxExecutionMode.CHROOT && runningModeStore.mode.value != RunningMode.ROOT) return
        confirmInstall = false
        confirmUse = false
        val nextChoice = LinuxInstallChoice(nextDistribution, nextMode)
        if (!choicePreferences.save(nextChoice)) { message = "选项未保存，请重试。"; return }
        installChoice = nextChoice
        message = null
        val choice = terminal?.linux?.choices?.firstOrNull {
            it.label.equals(nextDistribution, ignoreCase = true) && it.executionMode == nextMode
        }
        if (host != null && choice != null && choice.reference != terminal.linux.selected) {
            selecting = true
            scope.launch {
                try {
                    val result = withContext(Dispatchers.IO) { host.selectLinux(choice.reference).get() }
                    if (!result.ok) message = "选项已保存，但当前使用的环境未切换，请稍后重试。"
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { message = "选项已保存，但当前使用的环境未切换，请稍后重试。" }
                finally { selecting = false }
            }
        }
    }

    val rootfs = linuxBrowsableRootfs(state, distribution, mode, terminal?.linux?.selected)
    Column(modifier.fillMaxSize().background(settingsPageBackground())
        .navigationBarsPadding().testTag("linux-settings-view")) {
        Box(Modifier.weight(1f)) {
            if (tab == LinuxSettingsTab.FILES) {
                WorkspaceSettingsScreen(onBack = leave, pageTitle = if (filesOnly) "工作区" else "Linux 工具环境", integratedLinux = true,
                    rootfs = rootfs, onOpenTerminal = onOpenTerminal)
            } else SettingsScaffoldPage("Linux 工具环境", leave, compactTitle = true, actions = {
                if (onOpenTerminal != null) {
                    val openTerminal: () -> Unit = { if (basicCurrent()) onOpenTerminal() }
                    val semanticOpenTerminal = hapticClick(true, openTerminal)
                    app.daiyu.android.ui.haptics.miuix.IconButton(onClick = openTerminal,
                        modifier = Modifier.size(48.dp).testTag("linux-open-terminal").semantics {
                            onClick { semanticOpenTerminal(); true }
                        }) { DaiyuIcon(DaiyuIcons.Terminal, "终端") }
                }
            }) {
                item("linux-status") {
                    SettingsCard(Modifier.padding(top = 12.dp)) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(distributionName(distribution), style = MiuixTheme.textStyles.title3)
                            Text(modeName(mode), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            Text(when {
                                selecting || state.busy -> "正在读取环境…"
                                pendingRequest && installation?.unknown == true -> "安装结果未确认，请先检查"
                                pendingRequest && installation?.recoveryBlocked == true -> "上次安装未恢复，请先检查"
                                pendingRequest && installation?.cleanupPending == true -> "安装尚未完成"
                                pendingRequest && installation?.registrationPending == true -> "环境启用尚未确认"
                                installation?.busy == true -> "正在安装环境…"
                                !state.listConfirmed -> "环境状态暂时无法读取"
                                installed && terminal?.linux?.state == "FILES_READY" && selected?.executionMode == mode && terminal.linux.distribution.equals(distribution, true) -> "环境文件已就绪"
                                installed && terminal?.linux?.selected in matching.map { LinuxGenerationRef(it.environmentId, it.generation) } && terminal?.linux?.state == "BROKEN" -> "环境暂时不可用，请重新检查"
                                installed && terminal?.linux?.state == "RUNNER_UNKNOWN" -> "环境运行状态待确认"
                                installed && matching.any { controller.availableThisProcess(LinuxGenerationRef(it.environmentId, it.generation)) } -> "环境已安装"
                                installed -> "环境已安装，重启应用后可用"
                                installationMatches && installation?.registered == true -> "环境已安装，重启应用后可用"
                                else -> "尚未安装此环境"
                            }, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            if (!installed || pendingRequest) {
                                if (pendingRequest && installation?.prepared != null && !installationMatches) {
                                    TextButton("继续上次安装", enabled = pageEnabled, onClick = {
                                        if (basicCurrent()) {
                                            val nextChoice = LinuxInstallChoice(installation.prepared.artifact.distribution,
                                                installation.prepared.installation.executionMode)
                                            if (choicePreferences.save(nextChoice)) {
                                                installChoice = nextChoice
                                                confirmInstall = false; confirmUse = false
                                            } else message = "选项未保存，请重试。"
                                        }
                                    })
                                } else LinuxInstallAction(displayedInstallation, page, mode, distribution, enabled,
                                    otherBlocked = otherInstallation?.let { it.busy || it.unknown || it.recoveryBlocked || it.cleanupPending || it.registrationPending || (it.prepared != null && !it.registered) } == true,
                                    ownerCurrent = ::basicCurrent,
                                    onInstall = { if (basicCurrent()) confirmInstall = true },
                                    onEnable = { if (basicCurrent()) confirmUse = true })
                            }
                        }
                    }
                    message?.let { SettingsNote(it, error = true) }
                    if (state.error) SettingsNote("环境设置未完成，请重试。", error = true)
                    state.pendingRemoval?.let { pending ->
                        SettingsCard { SettingsRow("重试移除环境授权", showChevron = true, onClick = if (pageEnabled) ({ if (basicCurrent()) page.remove(pending); Unit }) else null) }
                    }
                    state.pendingApproval?.takeIf { it.installation.executionMode != LinuxExecutionMode.CHROOT || rootMode }?.let {
                        SettingsCard { SettingsRow("完成环境设置", showChevron = true, onClick = if (pageEnabled) ({ if (basicCurrent()) confirmPending = true }) else null) }
                    }
                }
                item("linux-configuration") {
                    SettingsSectionTitle("环境配置")
                    SettingsCard {
                        LinuxChoiceRow("Linux 发行版", distributionName(distribution), "linux-distribution-choice", choiceEnabled,
                            choices = listOf("Alpine", "Debian"),
                            selectedIndex = if (distribution.equals("debian", true)) 1 else 0,
                            popupScope = choiceScope, ownerCurrent = ::choiceCurrent,
                            onSelect = { choose(if (it == 0) "alpine" else "debian", mode) })
                        LinuxChoiceRow("运行方式", modeName(mode), "linux-execution-choice", choiceEnabled,
                            choices = listOf("普通模式 · PRoot") +
                                if (rootMode) listOf("Root 模式 · chroot") else emptyList(),
                            selectedIndex = if (mode == LinuxExecutionMode.CHROOT) 1 else 0,
                            popupScope = choiceScope, ownerCurrent = ::choiceCurrent,
                            onSelect = { choose(distribution, if (it == 0) LinuxExecutionMode.PROOT else LinuxExecutionMode.CHROOT) })
                    }
                }
                item("linux-tools-heading") { SettingsSectionTitle("可选工具") }
                listOf("Python / uv 环境", "Node.js 环境", "SSH", "APK 分析").forEachIndexed { index, title ->
                    item("linux-tool-$index") {
                        SettingsCard(Modifier.padding(bottom = 12.dp)) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(title, style = MiuixTheme.textStyles.body1)
                                Text("工具状态未检测", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    Button(onClick = {}, enabled = false, modifier = Modifier.testTag("linux-tool-install:$index")) { Text("安装") }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (!filesOnly) LinuxSettingsTabs(tab) {
            if (tabScope === renderedTabScope && tab != it) {
                tabScope = Any(); confirmInstall = false; confirmUse = false; confirmPending = false; tab = it
            }
        }
    }
    val prepared = installation?.prepared
    val installationIdle = installation != null && !installation.busy && !installation.unknown && !installation.recoveryBlocked && !installation.cleanupPending && installation.unavailableReason == null
    if (confirmInstall && prepared != null && installationMatches && installationIdle && installation.receipt == null) DaiyuConfirmDialog(
        title = "安装 ${distributionName(prepared.artifact.distribution)}", message =
            "将下载并安装 Linux 环境${if (mode == LinuxExecutionMode.CHROOT) "，期间需要 Root 授权" else ""}。不会更改已有环境。",
        confirmText = "下载并安装", destructive = false,
        onConfirm = {
            if (basicCurrent()) {
                confirmInstall = false
                if (prepared.installation.executionMode != LinuxExecutionMode.CHROOT || runningModeStore.mode.value == RunningMode.ROOT) page.install(prepared)
            }
        },
        onDismiss = { if (basicCurrent()) confirmInstall = false })
    val registration = installation?.registration
    if (confirmUse && registration != null && installationMatches && registration.installation.distribution.equals(distribution, true) && registration.installation.executionMode == mode && installationIdle && !installation.registered && installation.receipt?.directorySyncConfirmed == true) DaiyuConfirmDialog(
        title = "启用环境", message = "允许应用使用此 Linux 环境？完成后重启应用生效。", confirmText = "允许使用", destructive = false,
        onConfirm = { if (basicCurrent()) { confirmUse = false; page.approveInstalled(registration) } },
        onDismiss = { if (basicCurrent()) confirmUse = false })
    state.pendingApproval?.let { pending ->
        if (confirmPending && pageEnabled && (pending.installation.executionMode != LinuxExecutionMode.CHROOT || rootMode)) DaiyuConfirmDialog("完成环境设置",
            "允许应用使用 ${distributionName(pending.installation.distribution)} 环境？" +
                if (pending.installation.sharedMounts.isEmpty()) "" else "\n将访问以下共享目录：\n" + pending.installation.sharedMounts.keys.joinToString("\n"),
            confirmText = "允许使用", destructive = false,
            onConfirm = { if (basicCurrent()) { confirmPending = false; page.approve(pending) } },
            onDismiss = { if (basicCurrent()) confirmPending = false })
    }
}

private fun distributionName(value: String) = when (value.lowercase()) { "alpine" -> "Alpine"; "debian" -> "Debian"; else -> value }
private fun modeName(mode: LinuxExecutionMode) = if (mode == LinuxExecutionMode.CHROOT) "Root 模式 · chroot" else "普通模式 · PRoot"

@Composable
private fun LinuxChoiceRow(title: String, value: String, tag: String, enabled: Boolean,
    choices: List<String>, selectedIndex: Int, popupScope: Any, ownerCurrent: () -> Boolean, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val currentChoices = rememberUpdatedState(choices)
    val colors = MiuixTheme.colorScheme
    Box(Modifier.fillMaxWidth()) {
        Row(Modifier.testTag(tag).fillMaxWidth().heightIn(min = 72.dp)
            .clickable(enabled = enabled, interactionSource = remember { MutableInteractionSource() }, indication = null) { if (ownerCurrent()) expanded = true }
            .padding(horizontal = 16.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, modifier = Modifier.weight(1f), style = MiuixTheme.textStyles.body1)
            Text(value, style = MiuixTheme.textStyles.body2, color = colors.onSurfaceVariantSummary)
            Column(Modifier.padding(start = 8.dp)) {
                DaiyuIcon(DaiyuIcons.ChevronDown, null, Modifier.size(13.dp).rotate(180f), tint = colors.onSurfaceVariantSummary)
                DaiyuIcon(DaiyuIcons.ChevronDown, null, Modifier.size(13.dp), tint = colors.onSurfaceVariantSummary)
            }
        }
        key(popupScope) {
            if (expanded && enabled) OptionSelectionSheet(title,
                onDismiss = { if (ownerCurrent()) expanded = false }) {
                currentChoices.value.forEachIndexed { index, choice ->
                    val selectChoice: () -> Unit = {
                        if (enabled && expanded && ownerCurrent()) { expanded = false; onSelect(index) }
                    }
                    val semanticSelectChoice = hapticClick(enabled, selectChoice)
                    OptionSelectionItem(choice, selected = index == selectedIndex, enabled = enabled,
                        modifier = Modifier.testTag("linux-choice:$tag:$index").semantics { onClick { semanticSelectChoice(); true } },
                        onClick = selectChoice)
                }
            }
        }
    }
}

@Composable
private fun LinuxInstallAction(state: LinuxInstallState?, page: LinuxSettingsPageOwner, mode: LinuxExecutionMode,
    distribution: String, enabled: Boolean, otherBlocked: Boolean, ownerCurrent: () -> Boolean,
    onInstall: () -> Unit, onEnable: () -> Unit) {
    val prepared = state?.prepared
    val ready = enabled && state != null && !state.busy && state.unavailableReason == null
    when {
        state == null || state.unavailableReason != null -> Text("当前设备暂不可用", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        state.busy -> TextButton("取消安装", onClick = { if (ownerCurrent()) page.cancelInstall() }, modifier = Modifier.testTag("linux-install-cancel"))
        state.unknown || state.recoveryBlocked -> TextButton("恢复安装", enabled = ready, onClick = { if (ownerCurrent()) page.recoverInstall(mode) })
        state.cleanupPending && prepared != null -> TextButton("重试完成安装", enabled = ready, onClick = { if (ownerCurrent()) page.retryInstallCleanup(prepared) })
        state.registered -> Unit
        state.registration != null && state.receipt?.directorySyncConfirmed == true -> TextButton("启用环境", enabled = ready, onClick = onEnable)
        state.receipt?.directorySyncConfirmed == true -> TextButton("继续设置", enabled = ready, onClick = { if (ownerCurrent()) page.prepareInstalledRegistration(mode) })
        state.receipt != null && prepared != null -> TextButton("完成安装", enabled = ready, onClick = { if (ownerCurrent()) page.confirmPublishedDurability(prepared) })
        prepared != null -> Row {
            TextButton("继续安装", enabled = ready, onClick = onInstall)
            TextButton("取消安装", enabled = ready, onClick = { if (ownerCurrent()) page.discardInstall(mode) })
        }
        else -> Button(enabled = ready && !otherBlocked,
            colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.buttonColorsPrimary(),
            modifier = Modifier.testTag("linux-install-environment"), onClick = {
            if (ownerCurrent() && page.prepareInstall(distribution, "primary", mode)) onInstall()
        }) { Text("安装环境", color = if (ready && !otherBlocked) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.disabledOnPrimaryButton) }
    }
    if (state?.notice?.startsWith("操作未确认成功：") == true && !state.busy)
        Text("操作未完成，请重试。", color = MiuixTheme.colorScheme.error)
}
