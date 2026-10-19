package app.daiyu.android.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.Text as MaterialText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.daiyu.android.terminal.LinuxExecutionMode
import app.daiyu.android.terminal.LinuxGenerationRef
import app.daiyu.android.terminal.LinuxInstallation
import app.daiyu.android.terminal.LinuxSettingsController
import app.daiyu.android.terminal.LinuxSettingsDraft
import app.daiyu.android.terminal.LinuxSettingsMount
import app.daiyu.android.terminal.LinuxSettingsPageOwner
import app.daiyu.android.ui.components.MiuixScaffoldPage

/**
 * Parent allocates exactly one controller.enterPage() handle per route visit, outside composition.
 * Invalidate it at navigation commit, even if an outgoing transition keeps this page composed.
 * Installation and registration have separate explicit confirmations. No selection or terminal launch.
 */
@Composable
internal fun LinuxSettingsScreen(
    controller: LinuxSettingsController,
    page: LinuxSettingsPageOwner,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by page.state.collectAsState()
    val installState = controller.installation?.state?.collectAsState()?.value
    val chrootInstallState = controller.chrootInstallation?.state?.collectAsState()?.value
    var draft by remember(page) { mutableStateOf(LinuxSettingsDraft()) }
    var manualExpanded by remember(page) { mutableStateOf(false) }
    var installMode by remember(page) {
        mutableStateOf(if (controller.installation == null) LinuxExecutionMode.CHROOT else LinuxExecutionMode.PROOT)
    }
    var removing by remember(page) { mutableStateOf<LinuxInstallation?>(null) }
    var removalError by remember(page) { mutableStateOf<String?>(null) }
    val remove: (LinuxGenerationRef) -> Boolean = { reference ->
        page.remove(reference).also { accepted ->
            removalError = if (accepted) null else "撤销请求未受理；尚未确认阻止新启动。请等待当前操作完成或重新进入页面后重试。"
        }
    }
    DisposableEffect(page) { onDispose { page.close() } }
    val leave: () -> Unit = { if (page.revoke()) onBack() }
    BackHandler(onBack = leave)
    val enabled = !state.busy && !state.revoked
    val hasPending = state.pendingApproval != null || state.pendingRemoval != null
    val editEnabled = enabled && !hasPending && !controller.installationBlocksManual
    val edit: (LinuxSettingsDraft) -> Unit = { next ->
        if (page.discardPreview()) draft = next
    }

    MiuixScaffoldPage(title = "Linux 工具环境", onBack = leave, modifier = modifier) {
        item(key = "linux_status") {
            SettingsSectionTitle("环境状态")
            SettingsCard {
                SettingsNote(when {
                    state.busy -> "正在读取或验证环境…"
                    !state.listConfirmed -> "环境列表尚未确认，请重新读取。"
                    state.entries.isEmpty() -> "尚无已批准的环境。可先预览安装，或登记已有本地文件。"
                    else -> "已批准 ${state.entries.size} 个配置；本进程可新启动 ${state.entries.count {
                        controller.availableThisProcess(LinuxGenerationRef(it.environmentId, it.generation))
                    }} 个。实际启动仍需文件与会话权限检查。"
                })
                LinuxAction(enabled = enabled, modifier = Modifier.testTag("linux-refresh"), onClick = { page.refresh() }) {
                    Text("刷新环境状态")
                }
            }
            state.notice?.let { notice ->
                if (state.error) {
                    Column(Modifier.heightIn(max = 144.dp).verticalScroll(rememberScrollState())) {
                        SettingsNote(notice, error = true)
                    }
                } else {
                    LinuxDiagnostics(identity = notice, title = "最近操作反馈") { LinuxDetailText(notice) }
                }
            }
            removalError?.let { SettingsNote(it, error = true) }
        }
        if (installState != null || chrootInstallState != null) {
            item(key = "linux_install_mode") {
                SettingsSectionTitle("安装环境")
                SettingsCard {
                    Row(Modifier.fillMaxWidth()) {
                        if (installState != null) LinuxAction(modifier = Modifier.weight(1f), onClick = { installMode = LinuxExecutionMode.PROOT }) {
                            Text("PRoot${if (installMode == LinuxExecutionMode.PROOT) " · 已选" else ""}${installAttentionLabel(installState)}")
                        }
                        if (chrootInstallState != null) LinuxAction(modifier = Modifier.weight(1f), onClick = { installMode = LinuxExecutionMode.CHROOT }) {
                            Text("chroot${if (installMode == LinuxExecutionMode.CHROOT) " · 已选" else "（需 Root）"}${installAttentionLabel(chrootInstallState)}")
                        }
                    }
                    val shownMode = if (installState == null) LinuxExecutionMode.CHROOT else installMode
                    val installation = if (shownMode == LinuxExecutionMode.PROOT) installState else chrootInstallState
                    installation?.let {
                        LinuxInstallPanel(it, page, enabled && !hasPending && state.preview == null, shownMode,
                            otherBlocked = if (shownMode == LinuxExecutionMode.PROOT)
                                controller.chrootInstallation?.blocksManual == true else controller.installation?.blocksManual == true)
                    }
                }
            }
        }
        item(key = "linux_form") {
            SettingsSectionTitle("已有本地文件")
            SettingsCard {
                LinuxAction(modifier = Modifier.testTag("linux-manual-toggle").semantics {
                    stateDescription = if (manualExpanded) "已展开" else "已折叠"
                }, onClick = { manualExpanded = !manualExpanded }) {
                    Text(if (manualExpanded) "收起手工登记" else "手工登记环境（高级）")
                }
                if (manualExpanded) {
                SettingsNote("仅登记已有文件，不下载。批准表示接受完整路径、期望摘要和共享映射，不是来源认证；期望摘要请从独立可信渠道取得。新增批准在下次应用进程启动生效。")
                Column(Modifier.padding(horizontal = 16.dp)) {
                    LinuxAction(enabled = editEnabled, modifier = Modifier.testTag("linux-mode"), onClick = {
                        edit(draft.copy(executionMode = if (draft.executionMode == LinuxExecutionMode.PROOT)
                            LinuxExecutionMode.CHROOT else LinuxExecutionMode.PROOT, proot = "", prootSha256 = "",
                            loader = "", loaderSha256 = "", guestWorkingDirectory = "/", sharedMounts = emptyList()))
                    }) { Text("执行方式：${draft.executionMode.wire}（点击切换）") }
                    if (draft.executionMode == LinuxExecutionMode.CHROOT) {
                        Text("chroot 使用 Android Root，仅接受应用私有安装目录；不开放宿主 root 命令或共享挂载。chroot 不是安全沙箱，只运行可信 rootfs。")
                    }
                    LinuxSettingsField("配置标签（字母、数字、下划线、短横线）", draft.distribution, "linux-distribution", editEnabled) {
                        edit(draft.copy(distribution = it))
                    }
                    LinuxSettingsField("环境 ID", draft.environmentId, "linux-environment", editEnabled) { edit(draft.copy(environmentId = it)) }
                    LinuxSettingsField("rootfs 完整宿主路径", draft.rootfs, "linux-rootfs", editEnabled) { edit(draft.copy(rootfs = it)) }
                    if (draft.executionMode == LinuxExecutionMode.PROOT) {
                    LinuxSettingsField("proot 完整宿主路径", draft.proot, "linux-proot", editEnabled) { edit(draft.copy(proot = it)) }
                    LinuxSettingsField("proot 期望 SHA-256（64 位小写）", draft.prootSha256, "linux-proot-digest", editEnabled) {
                        edit(draft.copy(prootSha256 = it))
                    }
                    LinuxSettingsField("loader 完整宿主路径", draft.loader, "linux-loader", editEnabled) { edit(draft.copy(loader = it)) }
                    LinuxSettingsField("loader 期望 SHA-256（64 位小写）", draft.loaderSha256, "linux-loader-digest", editEnabled) {
                        edit(draft.copy(loaderSha256 = it))
                    }
                    }
                    LinuxSettingsField("guest cwd（绝对路径）", draft.guestWorkingDirectory, "linux-cwd", editEnabled) {
                        edit(draft.copy(guestWorkingDirectory = it))
                    }
                    draft.sharedMounts.forEachIndexed { index, mount ->
                        LinuxSettingsField("共享 ${index + 1}：宿主完整路径", mount.host, "linux-mount-host-$index", editEnabled) { value ->
                            edit(draft.copy(sharedMounts = draft.sharedMounts.mapIndexed { i, old -> if (i == index) old.copy(host = value) else old }))
                        }
                        LinuxSettingsField("共享 ${index + 1}：guest 绝对路径", mount.guest, "linux-mount-guest-$index", editEnabled) { value ->
                            edit(draft.copy(sharedMounts = draft.sharedMounts.mapIndexed { i, old -> if (i == index) old.copy(guest = value) else old }))
                        }
                        LinuxAction(enabled = editEnabled, onClick = { edit(draft.copy(sharedMounts = draft.sharedMounts.filterIndexed { i, _ -> i != index })) }) {
                            Text("移除此共享映射")
                        }
                    }
                    LinuxAction(enabled = editEnabled && draft.executionMode == LinuxExecutionMode.PROOT && draft.sharedMounts.size < 32, modifier = Modifier.testTag("linux-add-mount"),
                        onClick = { edit(draft.copy(sharedMounts = draft.sharedMounts + LinuxSettingsMount())) }) { Text("显式添加共享映射") }
                    LinuxAction(enabled = editEnabled, modifier = Modifier.testTag("linux-preview"), onClick = { page.preview(draft) }) {
                        Text("冻结完整配置并预览（尚不批准）")
                    }
                }
                }
            }
        }
        state.preview?.let { approval ->
            item(key = "linux_frozen_preview") {
                SettingsSectionTitle("待批准的冻结配置")
                SettingsNote(if (state.pendingApproval != null)
                    "上次批准尚未获持久化确认。这里重新展示原对象的全部内容；请再次明确确认同一代次。确认前不能编辑或新建代次，刷新和离页不代表保存成功。"
                    else "以下才是实际提交内容。编辑任何字段将废弃此预览；批准不选择或启动环境。rootfs 没有全树摘要，以下仅为两个 helper 的期望摘要。")
                LinuxSettingsConfiguration(approval.installation)
                LinuxAction(enabled = enabled && state.pendingRemoval == null, modifier = Modifier.testTag("linux-approve"), onClick = { page.approve(approval) }) {
                    Text("我明确批准以上全部配置并验证保存")
                }
            }
        }
        state.pendingRemoval?.let { reference ->
            item(key = "linux_pending_removal") {
                SettingsSectionTitle("待确认持久化的撤销")
                SettingsNote("撤销尚未确认保存；即使列表中已无此项，本进程仍拒绝新启动。请重试原配置。", error = true)
                LinuxDiagnostics(identity = reference) {
                    LinuxDetailText("环境 ID：${reference.environmentId}\n代次：${reference.generation}")
                }
                LinuxAction(enabled = enabled, modifier = Modifier.testTag("linux-retry-revoke"),
                    onClick = { remove(reference) }) { Text("重试确认同一代次的撤销") }
            }
        }
        item(key = "linux_catalog_header") {
            SettingsSectionTitle("已批准的环境")
            SettingsNote(if (state.listConfirmed) "新增批准在下次应用启动生效；批准不代表来源认证。"
                else "列表未确认，不能据此判断为空或允许启动。")
        }
        state.entries.forEach { entry ->
            item(key = "linux-entry:${entry.environmentId}:${entry.generation}") {
                LinuxSettingsConfiguration(entry)
                val reference = LinuxGenerationRef(entry.environmentId, entry.generation)
                SettingsNote(if (controller.availableThisProcess(reference)) "本进程可新启动；实际运行仍需会话与文件租约检查。"
                    else "本进程不可新启动：需重启应用，或已在本进程撤销。")
                LinuxAction(enabled = enabled && !hasPending, modifier = Modifier.testTag("linux-revoke:${entry.environmentId}:${entry.generation}"),
                    onClick = { removing = entry }) { Text("撤销此代次") }
            }
        }
    }

    removing?.let { entry ->
        AlertDialog(onDismissRequest = { removing = null },
            containerColor = MiuixTheme.colorScheme.surface,
            title = { Text("撤销本地配置？") }, text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text("会立即阻止新启动，并保存撤销记录。不会杀死现有进程、释放 UNKNOWN 租约、删除文件或切换其他发行版。")
                LinuxSettingsConfiguration(entry)
                removalError?.let { Text(it, color = MiuixTheme.colorScheme.error) }
            }
        }, confirmButton = {
            LinuxAction(enabled = enabled && !hasPending, fullWidth = false, onClick = {
                if (remove(LinuxGenerationRef(entry.environmentId, entry.generation))) removing = null
            }) { Text("确认撤销此代次") }
        }, dismissButton = { LinuxAction(fullWidth = false, onClick = { removing = null }) { Text("取消") } })
    }
}

private fun installAttentionLabel(state: app.daiyu.android.terminal.install.LinuxInstallState): String = when {
    state.busy -> " · 处理中"
    state.unknown || state.recoveryBlocked || state.cleanupPending || state.registrationPending -> " · 待处理"
    state.prepared != null && !state.registered -> " · 有请求"
    else -> ""
}

@Composable
private fun LinuxInstallPanel(
    state: app.daiyu.android.terminal.install.LinuxInstallState,
    page: LinuxSettingsPageOwner,
    enabled: Boolean,
    mode: LinuxExecutionMode = LinuxExecutionMode.PROOT,
    otherBlocked: Boolean = false,
) {
    var distribution by remember(page, mode) { mutableStateOf("alpine") }
    var mirror by remember(page, mode) { mutableStateOf("primary") }
    SettingsNote(if (mode == LinuxExecutionMode.CHROOT)
        "需要 Android Root 和可用 cgroup2。仅用应用私有目录；不是安全沙箱，只运行可信 rootfs。"
        else "PRoot 仅适用于 Android 14+（API 34+）的 arm64 / x86_64 实际 64 位进程，不代表已验证兼容。")
    val needsAttention = state.unknown || state.registrationPending || state.recoveryBlocked || state.cleanupPending
    SettingsNote(when {
        state.busy -> "正在处理安装请求…"
        state.unavailableReason != null -> "当前设备无法安装此执行方式。"
        state.unknown -> "上次运行状态未知，已阻止新的安装。"
        state.recoveryBlocked -> "原安装请求恢复受阻，请先核验。"
        state.cleanupPending -> "安装事务仍待清理。"
        state.registrationPending -> "注册保存尚未确认，请重试原配置。"
        state.registered -> "此安装已注册；新增批准在下次应用启动生效。"
        state.receipt?.directorySyncConfirmed == false -> "安装已发布，持久化尚未确认。"
        state.receipt?.directorySyncConfirmed == true -> "安装已发布并确认持久化，注册需单独批准。"
        state.prepared != null -> "已保留安装条目；下载与探测需要明确确认。"
        else -> "选择发行版和镜像后可预览，不会立即下载。"
    }, error = needsAttention || state.unavailableReason != null)
    if (needsAttention || state.unavailableReason != null) {
        Column(Modifier.heightIn(max = 144.dp).verticalScroll(rememberScrollState())) {
            state.notice?.let { SettingsNote(it, error = needsAttention) }
            state.unavailableReason?.let { SettingsNote("不可用原因：$it", error = true) }
        }
    } else {
        state.notice?.let {
            Text(it, maxLines = 2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 4.dp))
        }
    }
    val idle = enabled && !state.busy && !state.unknown && !state.recoveryBlocked && !state.cleanupPending && state.unavailableReason == null
    if (state.recoveryBlocked || state.unknown) {
        SettingsNote("原请求恢复未通过验证，禁止生成新 UUID 绕过。不会自动下载、重放探针、清理 UNKNOWN 锁或更换原 helper。", error = true)
        LinuxAction(enabled = enabled && !state.busy && state.unavailableReason == null,
            onClick = { page.recoverInstall(mode) }) { Text("仅重新读取并验证原安装请求（无授权）") }
    }
    if (otherBlocked) SettingsNote("另一种执行方式有未完成安装请求；请先处理原请求。已有请求仍可恢复、放弃或完成注册。")
    if (!state.recoveryBlocked && (state.prepared == null || state.registered)) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth()) {
                listOf("alpine", "debian").forEach { choice ->
                    LinuxAction(enabled = idle && !otherBlocked, modifier = Modifier.weight(1f), onClick = {
                        if (distribution != choice) { distribution = choice; mirror = "primary" }
                    }) { Text("${if (distribution == choice) "✓ " else ""}$choice") }
                }
            }
            Row(Modifier.fillMaxWidth()) {
                listOf("primary", if (distribution == "alpine") "aliyun" else "gh-proxy").forEach { choice ->
                    LinuxAction(enabled = idle && !otherBlocked, modifier = Modifier.weight(1f),
                        onClick = { mirror = choice }) {
                        Text("${if (mirror == choice) "✓ " else ""}${when (choice) {
                            "primary" -> "默认源"
                            "aliyun" -> "阿里云镜像"
                            else -> "GitHub 代理"
                        }}")
                    }
                }
            }
            LinuxAction(enabled = idle && !otherBlocked,
                modifier = Modifier.testTag("linux-install-${mode.wire}-prepare-$distribution-$mirror"),
                onClick = { page.prepareInstall(distribution, mirror, mode) }) { Text("预览安装（不下载）") }
        }
    }
    state.prepared?.let { prepared ->
        if (state.cleanupPending) {
            LinuxAction(enabled = enabled && !state.busy && !state.unknown && state.unavailableReason == null,
                modifier = Modifier.testTag("linux-install-${mode.wire}-cleanup"),
                onClick = { page.retryInstallCleanup(prepared) }) { Text("显式重试原事务清理（不下载、不执行探针）") }
        }
        SettingsNote("${prepared.artifact.version} · ${prepared.mirror.id}")
        LinuxSettingsConfiguration(prepared.installation, extraDetails =
            "条目：${prepared.artifact.id}\n版本：${prepared.artifact.version}\nABI：${prepared.artifact.abi}\n归档：${prepared.artifact.archive}\n大小：${prepared.artifact.compressedBytes} bytes\n归档 SHA-256：${prepared.artifact.sha256}\n镜像：${prepared.mirror.id}\nURL：${prepared.mirror.uri}")
        if (state.receipt == null && !state.registered) {
            SettingsNote(if (mode == LinuxExecutionMode.CHROOT)
                "确认后会申请 Root 并下载、安装、运行隔离探测；不会自动注册或切换环境。"
                else "确认后会下载、安装并运行隔离探测；不会自动注册或切换环境。")
            LinuxAction(enabled = idle, modifier = Modifier.testTag(if (mode == LinuxExecutionMode.PROOT) "linux-install-confirm" else "linux-install-chroot-confirm"),
                onClick = { page.install(prepared) }) { Text("确认以上条目：下载、安装并探测（不批准注册）") }
            LinuxAction(enabled = idle, onClick = { page.discardInstall(mode) }) { Text("放弃此下载预览") }
        }
        if (state.receipt?.directorySyncConfirmed == false) {
            LinuxAction(enabled = idle, modifier = Modifier.testTag(if (mode == LinuxExecutionMode.PROOT) "linux-install-durability" else "linux-install-chroot-durability"),
                onClick = { page.confirmPublishedDurability(prepared) }) { Text("显式重试：确认同一发布的持久化") }
        }
    }
    if (state.busy) {
        LinuxAction(enabled = true, modifier = Modifier.testTag(if (mode == LinuxExecutionMode.PROOT) "linux-install-cancel" else "linux-install-chroot-cancel"),
            onClick = { page.cancelInstall() }) { Text("取消请求（阻止排队注册；已开始写入仍等待回执）") }
    }
    if (state.receipt?.directorySyncConfirmed == true && state.registration == null && !state.registered) {
        LinuxAction(enabled = idle, modifier = Modifier.testTag("linux-install-${mode.wire}-registration-preview"),
            onClick = { page.prepareInstalledRegistration(mode) }) {
            Text("重试准备注册冻结预览（不批准）")
        }
    }
    state.registration?.takeIf { !state.registered && state.receipt?.directorySyncConfirmed == true }?.let { approval ->
        SettingsSectionTitle("独立的完整注册批准")
        SettingsNote("下载安装授权不包含注册授权。以下为固定的注册对象，可能使用独立的目录代次；重试时绝不重新生成。批准只供下次进程启动，不热换当前终端。")
        LinuxSettingsConfiguration(approval.installation)
        LinuxAction(enabled = idle, modifier = Modifier.testTag(if (mode == LinuxExecutionMode.PROOT) "linux-install-register" else "linux-install-chroot-register"),
            onClick = { page.approveInstalled(approval) }) {
            Text(if (state.registrationPending) "重试批准原冻结配置并确认持久化" else "另行明确批准以上完整配置并注册")
        }
    }
    LinuxDiagnostics(identity = mode, title = "安装说明与诊断") {
        LinuxDetailText("仅使用固定下载条目，不接受任意 URL，不跟随 HTTP 重定向，不自动换源。共享目录不隐式添加。")
        LinuxDetailText(if (mode == LinuxExecutionMode.CHROOT)
            "chroot 仅安装到应用私有目录，不使用 PRoot helper、不挂载共享目录。确认安装会启动真实 Root 的隔离 PTY 探针，仅在整棵子进程树清退后发布。"
            else "PRoot helper 随 APK 提供，不在运行时下载。API 26–33 不可用。下载后的 rootfs 会执行隔离 PTY 的 id -u 探测，平台条件不等于已验证运行支持。")
        state.notice?.let { LinuxDetailText("最近反馈：$it") }
        state.unavailableReason?.let { LinuxDetailText("不可用原因：$it") }
    }
}

@Composable
private fun LinuxSettingsField(label: String, value: String, tag: String, enabled: Boolean, onChange: (String) -> Unit) {
    val colors = MiuixTheme.colorScheme
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label, color = colors.onSurfaceVariantSummary) }, singleLine = true,
        textStyle = MiuixTheme.textStyles.body2,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = colors.onSurface, unfocusedTextColor = colors.onSurface,
            disabledTextColor = colors.onSurfaceVariantSummary,
            cursorColor = colors.primary, focusedBorderColor = colors.primary,
            unfocusedBorderColor = colors.onSurfaceVariantSummary,
        ),
        enabled = enabled, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag(tag))
}

/** Summary and expandable full details both use the frozen/record object, never the draft. */
@Composable
private fun LinuxSettingsConfiguration(value: LinuxInstallation, extraDetails: String? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("${value.distribution} · ${value.executionMode.wire}",
            style = MiuixTheme.textStyles.body1, color = MiuixTheme.colorScheme.onSurface)
        LinuxDetailText(if (value.sharedMounts.isEmpty()) "未共享宿主目录" else "共享目录：${value.sharedMounts.size} 项")
        LinuxDiagnostics(identity = value) {
            extraDetails?.let { LinuxDetailText(it) }
            LinuxDetailText("标签：${value.distribution}\n环境 ID：${value.environmentId}\n代次：${value.generation}\n执行方式：${value.executionMode.wire}")
            LinuxDetailText("rootfs：${value.rootfs.path}")
            if (value.executionMode == LinuxExecutionMode.PROOT) {
                LinuxDetailText("proot：${value.proot?.path}\nproot 期望 SHA-256：${value.prootSha256}")
                LinuxDetailText("loader：${value.loader?.path}\nloader 期望 SHA-256：${value.loaderSha256}")
            }
            LinuxDetailText("guest cwd：${value.guestWorkingDirectory}")
            LinuxDetailText("共享映射（host → guest）：")
            if (value.sharedMounts.isEmpty()) LinuxDetailText("无（不隐式添加共享目录）")
            value.sharedMounts.forEach { (host, guest) -> LinuxDetailText("$host → $guest") }
        }
    }
}

/** Local presentation only: expanding details never calls a controller or grants approval. */
@Composable
private fun LinuxDiagnostics(identity: Any, title: String = "诊断详情", content: @Composable () -> Unit) {
    var expanded by remember(identity) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        LinuxAction(modifier = Modifier.semantics { stateDescription = if (expanded) "已展开" else "已折叠" },
            onClick = { expanded = !expanded }) { Text("$title · ${if (expanded) "收起" else "展开"}") }
        if (expanded) {
            SelectionContainer {
                Column(Modifier.fillMaxWidth().heightIn(max = 280.dp)
                    .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun LinuxDetailText(text: String) {
    Text(text, style = MiuixTheme.textStyles.body2,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        softWrap = true, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
}

private val LocalLinuxActionColor = staticCompositionLocalOf<Color?> { null }

/** Use the existing native settings palette, including inside Material dialogs and fields. */
@Composable
private fun Text(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MiuixTheme.textStyles.body2,
    color: Color = LocalLinuxActionColor.current ?: MiuixTheme.colorScheme.onSurface,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
) {
    MaterialText(text = text, modifier = modifier, style = style, color = color, softWrap = softWrap,
        maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

/** Explicit no-indication action, including dialogs composed outside the scaffold. */
@Composable
private fun LinuxAction(
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    fullWidth: Boolean = true,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    CompositionLocalProvider(
        LocalLinuxActionColor provides
            if (enabled) colors.primary else colors.onSurfaceVariantSummary,
    ) {
        Box(modifier.then(if (fullWidth) Modifier.fillMaxWidth() else Modifier).heightIn(min = 48.dp).clickable(
            enabled = enabled, role = Role.Button,
            interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick,
        ).padding(horizontal = 16.dp, vertical = 12.dp), contentAlignment = Alignment.CenterStart) {
            content()
        }
    }
}
