package app.daiyu.android.ui.screens.settings

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import app.daiyu.android.platform.DeviceResultPolicy
import app.daiyu.android.platform.PlatformReadTool
import app.daiyu.android.platform.PlatformSettingsAccess
import app.daiyu.android.storage.PlatformToolSettings

/** No prompt, permission grant, data query or outbound request occurs merely by opening this page. */
@Composable
internal fun PlatformToolSettingsScreen(context: Context, settings: PlatformToolSettings, revision: Int,
    grants: () -> List<Uri>, ownerCurrent: () -> Boolean, onBack: () -> Unit,
    onPermission: (PlatformReadTool, String) -> Unit, onPick: (Boolean) -> Unit,
    onCopy: (Uri) -> Unit, onRevoke: (Uri) -> Unit) {
    var snapshot by remember(settings, revision) { mutableStateOf(settings.freeze()) }
    var error by remember { mutableStateOf<String?>(null) }
    val current by rememberUpdatedState(ownerCurrent)
    val alive = remember { booleanArrayOf(true) }
    DisposableEffect(Unit) { alive[0] = true; onDispose { alive[0] = false } }
    fun act(block: () -> Unit) {
        if (!alive[0] || !current()) return
        try { block(); snapshot = settings.freeze(); error = null }
        catch (_: Exception) { error = "操作未确认成功；请重新检查设置或系统授权，未读取数据" }
    }
    val explicitGrants = remember(revision) { grants() }
    SettingsScaffoldPage(title = "平台只读工具与私密出站", onBack = onBack,
        modifier = Modifier.testTag("platform-tool-settings")) {
        item(key = "policy") {
            SettingsNote("${PlatformReadTool.entries.size}项独立选择，默认全部关闭。系统授权、工具选择、发给模型、写入历史是四个不同决定，互不代开。私密工具必须同时同意模型与历史，才会读取并进入同一工具账本/历史/模型通道；不同意则安全拒绝。设置修改仅影响下次run，已运行任务使用冻结值；立即停止请使用聊天的停止按钮。")
            error?.let { SettingsNote(it) }
            SettingsRow("打开本应用系统权限设置", onClick = { act { onPermission(PlatformReadTool.PERMISSIONS, "") } },
                summary = "可撤回授权；不会自动开启任何工具或私密出站")
        }
        PlatformReadTool.entries.forEach { tool ->
            item(key = tool.toolName) {
                SettingsSectionTitle(tool.toolName)
                SettingsCard {
                    SettingsSwitchRow("选择 ${tool.toolName}", tool in snapshot.platformAllowed,
                        onCheckedChange = { act { settings.select(tool, it) } })
                    SettingsRow("系统授权与范围", summary = PlatformSettingsAccess.status(context, tool))
                    if (tool == PlatformReadTool.CROSS_APP_NOTIFICATIONS || tool == PlatformReadTool.NOTIFICATION_HISTORY) {
                        NotificationSettingsRows(context, tool, revision, { block -> act(block) }, onPermission)
                    }
                    if (tool.toolName in DeviceResultPolicy.PRIVATE_TOOLS) {
                        SettingsSwitchRow("${tool.toolName}：同意发送私密结果给模型", snapshot.modelConsent(tool),
                            onCheckedChange = { act { settings.consentModel(tool, it) } })
                        SettingsSwitchRow("${tool.toolName}：同意私密结果写入历史/账本", snapshot.historyConsent(tool),
                            onCheckedChange = { act { settings.consentHistory(tool, it) } })
                        if (!snapshot.modelConsent(tool) || !snapshot.historyConsent(tool))
                            SettingsRow("出站同意不完整；调用拒绝，不读取私密内容")
                    }
                    PlatformSettingsAccess.runtimePermissions(tool).filterNot {
                        it == android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
                    }.forEach { permission ->
                        SettingsRow("请求 $permission", onClick = { act { onPermission(tool, permission) } },
                            summary = if (tool == PlatformReadTool.LAST_KNOWN_LOCATION)
                                "仅申请前台位置：精确请求同时附带大致位置，可在系统中仅同意大致；不申请后台位置，不开启工具或读取缓存"
                            else "仅此明确系统授权；拒绝后不会代开或降级绕过。Android 14+图片/视频可仅选中")
                    }
                }
            }
        }
        item(key = "saf") {
            SettingsSectionTitle("精确只读SAF授权（不自动发送URI）")
            SettingsCard {
                SettingsRow("选择目录树（list_files）", onClick = { act { onPick(true) } },
                    summary = "只读、精确持久化目录树；只列直接子项，无递归/全盘权限")
                SettingsRow("选择单个文档（metadata/text）", onClick = { act { onPick(false) } },
                    summary = "只读精确content URI；读取文本仍要求text/*、UTF-8、最多8192字节及双出站同意")
                explicitGrants.forEach { uri ->
                    SettingsRow("已授权：$uri", summary = "仅在本页显示。复制后由你决定是否将精确URI提供给模型",
                        onClick = { act { onCopy(uri) } })
                    SettingsRow("撤回：$uri", onClick = { act { onRevoke(uri) } })
                }
                if (explicitGrants.isEmpty()) SettingsRow("本页尚无有效精确持久只读授权；文件调用将拒绝")
            }
        }
    }
}
