package app.daiyu.android.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

    MiuixScaffoldPage(title = "Linux 本地配置", onBack = leave, modifier = modifier) {
        installState?.let { installation ->
            item(key = "linux_install") {
                LinuxInstallPanel(installation, page, enabled && !hasPending && state.preview == null,
                    otherBlocked = controller.chrootInstallation?.blocksManual == true)
            }
        }
        chrootInstallState?.let { installation ->
            item(key = "linux_chroot_install") {
                LinuxInstallPanel(installation, page, enabled && !hasPending && state.preview == null,
                    LinuxExecutionMode.CHROOT, otherBlocked = controller.installation?.blocksManual == true)
            }
        }
        item(key = "linux_scope") {
            SettingsNote("下方手工配置仅登记已有本地文件。上方安装只使用固定目录条目及随 APK 安装的 helper，不接受任意 URL。下载安装并不等于批准注册。手工批准表示接受路径、期望摘要和共享映射，不表示来源认证；请从独立可信渠道取得期望摘要。")
            SettingsNote("新增批准仅在下次应用进程启动生效。撤销立即阻止该配置的新启动，但不终止已有会话、不释放其资源租约、不清理 UNKNOWN、不删除安装文件。")
            if (state.busy) SettingsNote("正在工作线程读取、冻结或验证配置…")
            state.notice?.let { SettingsNote(it, error = state.error) }
            removalError?.let { SettingsNote(it, error = true) }
        }
        item(key = "linux_form") {
            SettingsSectionTitle("完整本地配置（共享目录默认空）")
            Column(Modifier.padding(horizontal = 16.dp)) {
                TextButton(enabled = editEnabled, modifier = Modifier.testTag("linux-mode"), onClick = {
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
                    TextButton(enabled = editEnabled, onClick = { edit(draft.copy(sharedMounts = draft.sharedMounts.filterIndexed { i, _ -> i != index })) }) {
                        Text("移除此共享映射")
                    }
                }
                TextButton(enabled = editEnabled && draft.executionMode == LinuxExecutionMode.PROOT && draft.sharedMounts.size < 32, modifier = Modifier.testTag("linux-add-mount"),
                    onClick = { edit(draft.copy(sharedMounts = draft.sharedMounts + LinuxSettingsMount())) }) { Text("显式添加共享映射") }
                TextButton(enabled = editEnabled, modifier = Modifier.testTag("linux-preview"), onClick = { page.preview(draft) }) {
                    Text("冻结完整配置并预览（尚不批准）")
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
                TextButton(enabled = enabled && state.pendingRemoval == null, modifier = Modifier.testTag("linux-approve"), onClick = { page.approve(approval) }) {
                    Text("我明确批准以上全部配置并验证保存")
                }
            }
        }
        state.pendingRemoval?.let { reference ->
            item(key = "linux_pending_removal") {
                SettingsSectionTitle("待确认持久化的撤销")
                SettingsNote("环境 ID：${reference.environmentId}；代次：${reference.generation}。即使活动列表中已无此项，也未确认撤销已持久化；本进程拒绝新启动不会回退。", error = true)
                TextButton(enabled = enabled, modifier = Modifier.testTag("linux-retry-revoke"),
                    onClick = { remove(reference) }) { Text("重试确认同一代次的撤销") }
            }
        }
        item(key = "linux_catalog_header") {
            SettingsSectionTitle("用户批准的本地配置（非来源认证）")
            SettingsNote(if (state.listConfirmed) "已读取并验证当前活动记录。目录条目本身不是可变的直接运行权限；文件仍须在启动时再次验证。"
                else "列表尚未确认或上次读取失败；不能把这当作空目录或启动授权。")
            TextButton(enabled = enabled, modifier = Modifier.testTag("linux-refresh"), onClick = { page.refresh() }) { Text("重新读取 / 验证目录") }
            if (state.entries.isEmpty() && state.listConfirmed) SettingsNote("没有已批准的活动配置。")
        }
        state.entries.forEach { entry ->
            item(key = "linux-entry:${entry.environmentId}:${entry.generation}") {
                LinuxSettingsConfiguration(entry)
                val reference = LinuxGenerationRef(entry.environmentId, entry.generation)
                SettingsNote(if (controller.availableThisProcess(reference)) "当前进程启动快照包含此代次；实际运行仍由终端 owner 和文件租约检查决定。"
                    else "当前进程不可新启动此代次：新增批准需下次启动，或本进程已撤销。")
                TextButton(enabled = enabled && !hasPending, modifier = Modifier.testTag("linux-revoke:${entry.environmentId}:${entry.generation}"),
                    onClick = { removing = entry }) { Text("撤销此代次") }
            }
        }
    }

    removing?.let { entry ->
        AlertDialog(onDismissRequest = { removing = null }, title = { Text("撤销本地配置？") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("会立即阻止新启动，并保存撤销记录。不会杀死现有进程、释放 UNKNOWN 租约、删除文件或切换其他发行版。")
                LinuxSettingsConfiguration(entry)
                removalError?.let { Text(it) }
            }
        }, confirmButton = {
            TextButton(enabled = enabled && !hasPending, onClick = {
                if (remove(LinuxGenerationRef(entry.environmentId, entry.generation))) removing = null
            }) { Text("确认撤销此代次") }
        }, dismissButton = { TextButton(onClick = { removing = null }) { Text("取消") } })
    }
}

@Composable
private fun LinuxInstallPanel(
    state: app.daiyu.android.terminal.install.LinuxInstallState,
    page: LinuxSettingsPageOwner,
    enabled: Boolean,
    mode: LinuxExecutionMode = LinuxExecutionMode.PROOT,
    otherBlocked: Boolean = false,
) {
    SettingsSectionTitle("固定发行版 ${mode.wire} 安装（不自动注册）")
    if (mode == LinuxExecutionMode.CHROOT) SettingsNote("chroot 需要 Android Root 授权及可用 cgroup2 清退能力。仅安装到应用私有目录，不使用 PRoot helper、不挂载共享目录，不自动切换既有环境。确认安装会启动一次真实 Root 的隔离 PTY 探针；仅在整棵子进程树已清退后发布。chroot 不是安全沙箱，rootfs 及其内容必须可信。")
    else
        SettingsNote("本批预编译 PRoot 仅支持 Android API 34+ 的 arm64 / x86_64 实际 64 位进程。API 26–33 明确不可用；不是已验证的运行支持。无运行时 helper 下载、不跟随 HTTP 重定向、不隐式挂载共享目录。下载后的 rootfs 会执行一次隔离 PTY 的 id -u 探测。")
    state.notice?.let { SettingsNote(it, error = state.unknown || state.registrationPending || state.recoveryBlocked || state.cleanupPending) }
    state.unavailableReason?.let { SettingsNote("安装不可用 (Unavailable)：$it", error = true) }
    val idle = enabled && !state.busy && !state.unknown && !state.recoveryBlocked && !state.cleanupPending && state.unavailableReason == null
    if (state.recoveryBlocked || state.unknown) {
        SettingsNote("原请求恢复未通过验证，禁止生成新 UUID 绕过。不会自动下载、重放探针、清理 UNKNOWN 锁或更换原 helper。", error = true)
        TextButton(enabled = enabled && !state.busy && state.unavailableReason == null,
            onClick = { page.recoverInstall(mode) }) { Text("仅重新读取并验证原安装请求（无授权）") }
    }
    if (otherBlocked) SettingsNote("另一种执行方式有未完成安装请求；请先处理原请求。已有请求仍可恢复、放弃或完成注册。")
    if (!state.recoveryBlocked && (state.prepared == null || state.registered)) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            listOf("alpine" to "primary", "alpine" to "aliyun", "debian" to "primary", "debian" to "gh-proxy").forEach { (distribution, mirror) ->
                TextButton(enabled = idle && !otherBlocked,
                    modifier = Modifier.testTag("linux-install-${mode.wire}-prepare-$distribution-$mirror"), onClick = { page.prepareInstall(distribution, mirror, mode) }) {
                    Text("预览 $distribution / $mirror（不下载）")
                }
            }
            Text("GitHub 等来源若返回重定向将失败关闭；可显式选择其他固定镜像，不自动切换。")
        }
    }
    state.prepared?.let { prepared ->
        if (state.cleanupPending) {
            TextButton(enabled = enabled && !state.busy && !state.unknown && state.unavailableReason == null,
                modifier = Modifier.testTag("linux-install-${mode.wire}-cleanup"),
                onClick = { page.retryInstallCleanup(prepared) }) { Text("显式重试原事务清理（不下载、不执行探针）") }
        }
        SettingsNote("条目：${prepared.artifact.id}\n版本：${prepared.artifact.version}\nABI：${prepared.artifact.abi}\n归档：${prepared.artifact.archive}\n大小：${prepared.artifact.compressedBytes} bytes\n归档 SHA-256：${prepared.artifact.sha256}\n镜像：${prepared.mirror.id}\nURL：${prepared.mirror.uri}")
        LinuxSettingsConfiguration(prepared.installation)
        if (state.receipt == null && !state.registered) {
            TextButton(enabled = idle, modifier = Modifier.testTag(if (mode == LinuxExecutionMode.PROOT) "linux-install-confirm" else "linux-install-chroot-confirm"),
                onClick = { page.install(prepared) }) { Text("确认以上条目：下载、安装并探测（不批准注册）") }
            TextButton(enabled = idle, onClick = { page.discardInstall(mode) }) { Text("放弃此下载预览") }
        }
        if (state.receipt?.directorySyncConfirmed == false) {
            TextButton(enabled = idle, modifier = Modifier.testTag(if (mode == LinuxExecutionMode.PROOT) "linux-install-durability" else "linux-install-chroot-durability"),
                onClick = { page.confirmPublishedDurability(prepared) }) { Text("显式重试：确认同一发布的持久化") }
        }
    }
    if (state.busy) {
        TextButton(enabled = true, modifier = Modifier.testTag(if (mode == LinuxExecutionMode.PROOT) "linux-install-cancel" else "linux-install-chroot-cancel"),
            onClick = { page.cancelInstall() }) { Text("取消请求（阻止排队注册；已开始写入仍等待回执）") }
    }
    if (state.receipt?.directorySyncConfirmed == true && state.registration == null && !state.registered) {
        TextButton(enabled = idle, modifier = Modifier.testTag("linux-install-${mode.wire}-registration-preview"),
            onClick = { page.prepareInstalledRegistration(mode) }) {
            Text("重试准备注册冻结预览（不批准）")
        }
    }
    state.registration?.takeIf { !state.registered && state.receipt?.directorySyncConfirmed == true }?.let { approval ->
        SettingsSectionTitle("独立的完整注册批准")
        SettingsNote("下载安装授权不包含注册授权。以下为固定的注册对象，可能使用独立的目录代次；重试时绝不重新生成。批准只供下次进程启动，不热换当前终端。")
        LinuxSettingsConfiguration(approval.installation)
        TextButton(enabled = idle, modifier = Modifier.testTag(if (mode == LinuxExecutionMode.PROOT) "linux-install-register" else "linux-install-chroot-register"),
            onClick = { page.approveInstalled(approval) }) {
            Text(if (state.registrationPending) "重试批准原冻结配置并确认持久化" else "另行明确批准以上完整配置并注册")
        }
    }
}

@Composable
private fun LinuxSettingsField(label: String, value: String, tag: String, enabled: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        enabled = enabled, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag(tag))
}

/** Uses only the frozen/record object, never the editable draft or a shortened path. */
@Composable
private fun LinuxSettingsConfiguration(value: LinuxInstallation) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text("标签：${value.distribution}")
        Text("环境 ID：${value.environmentId}")
        Text("代次：${value.generation}")
        Text("rootfs：${value.rootfs.path}")
        Text("执行方式：${value.executionMode.wire}")
        if (value.executionMode == LinuxExecutionMode.PROOT) {
        Text("proot：${value.proot?.path}")
        Text("proot 期望 SHA-256：${value.prootSha256}")
        Text("loader：${value.loader?.path}")
        Text("loader 期望 SHA-256：${value.loaderSha256}")
        }
        Text("guest cwd：${value.guestWorkingDirectory}")
        Text("共享映射（host → guest）：")
        if (value.sharedMounts.isEmpty()) Text("无（不隐式添加共享目录）")
        value.sharedMounts.forEach { (host, guest) -> Text("$host → $guest") }
    }
}
