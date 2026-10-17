package app.daiyu.android.ui.screens.settings

import android.content.Context
import androidx.compose.runtime.*
import app.daiyu.android.platform.CrossAppNotifications
import app.daiyu.android.platform.PlatformReadTool

/** Explicit local capture consent is independent of the two outbound/run consents. */
@Composable
internal fun NotificationSettingsRows(context: Context, tool: PlatformReadTool, revision: Int,
    act: (() -> Unit) -> Unit, onPermission: (PlatformReadTool, String) -> Unit) {
    val host = remember(context) { CrossAppNotifications.from(context) }
    var recording by remember(revision) { mutableStateOf(host.recording()) }
    SettingsRow("打开系统通知读取授权", summary = "独立特殊访问；系统授权不选择工具，不同意任何出站，不等于监听已连接。不是发通知权限。",
        onClick = { act { onPermission(tool, "") } })
    if (tool == PlatformReadTool.NOTIFICATION_HISTORY) {
        SettingsSwitchRow("单独启用本地通知历史记录", recording, onCheckedChange = { enabled ->
            act { host.record(enabled); recording = host.recording() }
        })
        SettingsRow("关闭记录并清除通知历史", onClick = { act { host.record(false); recording = false } },
            summary = "立即停采并清除独立通知记录；不回写/删除已发送的聊天消息。")
        SettingsRow("记录范围与预算", summary = "仅启用之后的新posted事件，不补录授权前或当前通知。最多7天、500条、512KiB；单条4096字节。断连/系统过滤/预算淘汰可能缺失；无记录绝不代表最近无通知。后台存储不能直接发送给模型。")
    }
}
