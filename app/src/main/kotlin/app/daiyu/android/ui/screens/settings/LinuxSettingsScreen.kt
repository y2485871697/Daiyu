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
 * This page only edits the local approval catalog: no installation, selection or terminal launch.
 */
@Composable
internal fun LinuxSettingsScreen(
    controller: LinuxSettingsController,
    page: LinuxSettingsPageOwner,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by page.state.collectAsState()
    var draft by remember(page) { mutableStateOf(LinuxSettingsDraft()) }
    var removing by remember(page) { mutableStateOf<LinuxInstallation?>(null) }
    DisposableEffect(page) { onDispose { page.close() } }
    val leave: () -> Unit = { if (page.revoke()) onBack() }
    BackHandler(onBack = leave)
    val enabled = !state.busy && !state.revoked
    val hasPending = state.pendingApproval != null || state.pendingRemoval != null
    val editEnabled = enabled && !hasPending
    val edit: (LinuxSettingsDraft) -> Unit = { next ->
        if (page.discardPreview()) draft = next
    }

    MiuixScaffoldPage(title = "Linux 本地配置", onBack = leave, modifier = modifier) {
        item(key = "linux_scope") {
            SettingsNote("仅登记已经安装的本地文件，不下载、不安装、不执行。批准表示你接受这些路径、期望摘要和共享映射，不表示来自可信发行方。请从独立可信渠道取得期望摘要；本页不会扫描文件生成摘要或证明 rootfs 来源。")
            SettingsNote("新增批准仅在下次应用进程启动生效。撤销立即阻止该配置的新启动，但不终止已有会话、不释放其资源租约、不清理 UNKNOWN、不删除安装文件。")
            if (state.busy) SettingsNote("正在工作线程读取、冻结或验证配置…")
            state.notice?.let { SettingsNote(it, error = state.error) }
        }
        item(key = "linux_form") {
            SettingsSectionTitle("完整本地配置（共享目录默认空）")
            Column(Modifier.padding(horizontal = 16.dp)) {
                LinuxSettingsField("配置标签（字母、数字、下划线、短横线）", draft.distribution, "linux-distribution", editEnabled) {
                    edit(draft.copy(distribution = it))
                }
                LinuxSettingsField("环境 ID", draft.environmentId, "linux-environment", editEnabled) { edit(draft.copy(environmentId = it)) }
                LinuxSettingsField("rootfs 完整宿主路径", draft.rootfs, "linux-rootfs", editEnabled) { edit(draft.copy(rootfs = it)) }
                LinuxSettingsField("proot 完整宿主路径", draft.proot, "linux-proot", editEnabled) { edit(draft.copy(proot = it)) }
                LinuxSettingsField("proot 期望 SHA-256（64 位小写）", draft.prootSha256, "linux-proot-digest", editEnabled) {
                    edit(draft.copy(prootSha256 = it))
                }
                LinuxSettingsField("loader 完整宿主路径", draft.loader, "linux-loader", editEnabled) { edit(draft.copy(loader = it)) }
                LinuxSettingsField("loader 期望 SHA-256（64 位小写）", draft.loaderSha256, "linux-loader-digest", editEnabled) {
                    edit(draft.copy(loaderSha256 = it))
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
                TextButton(enabled = editEnabled && draft.sharedMounts.size < 32, modifier = Modifier.testTag("linux-add-mount"),
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
                    onClick = { page.remove(reference) }) { Text("重试确认同一代次的撤销") }
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
            }
        }, confirmButton = {
            TextButton(enabled = enabled && !hasPending, onClick = {
                page.remove(LinuxGenerationRef(entry.environmentId, entry.generation))
                removing = null
            }) { Text("确认撤销此代次") }
        }, dismissButton = { TextButton(onClick = { removing = null }) { Text("取消") } })
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
        Text("proot：${value.proot.path}")
        Text("proot 期望 SHA-256：${value.prootSha256}")
        Text("loader：${value.loader.path}")
        Text("loader 期望 SHA-256：${value.loaderSha256}")
        Text("guest cwd：${value.guestWorkingDirectory}")
        Text("共享映射（host → guest）：")
        if (value.sharedMounts.isEmpty()) Text("无（不隐式添加共享目录）")
        value.sharedMounts.forEach { (host, guest) -> Text("$host → $guest") }
    }
}
