package app.daiyu.android.ui.screens.settings

import androidx.compose.ui.graphics.vector.ImageVector
import app.daiyu.android.ui.FeatureRoute
import app.daiyu.android.ui.icons.DaiyuIcons

internal enum class SettingControl { PAGE, TOGGLE, CHOICE, ACTION }
internal data class SettingsEntry(
    val id: String, val title: String, val icon: ImageVector,
    val control: SettingControl = SettingControl.PAGE, val route: FeatureRoute? = null,
)
internal data class SettingsGroup(val id: String, val title: String, val summary: String, val entries: List<SettingsEntry>)

/** Original settings inventory, independently presented; not the feature-migration ledger. */
internal object SettingsCatalog {
    val groups = listOf(
        SettingsGroup("llm", "LLM 提供商", "服务商连接与默认推理", listOf(
            SettingsEntry("providers", "模型提供商", DaiyuIcons.Provider, SettingControl.PAGE, FeatureRoute.MODEL_SETTINGS),
            SettingsEntry("default_reasoning", "默认启用推理", DaiyuIcons.Reasoning, SettingControl.TOGGLE),
        )),
        SettingsGroup("models", "模型功能", "压缩、视觉、子代理与标题模型", listOf(
            SettingsEntry("compression", "上下文压缩", DaiyuIcons.Compress, SettingControl.PAGE),
            SettingsEntry("vision", "辅助视觉模型", DaiyuIcons.Vision, SettingControl.PAGE),
            SettingsEntry("subagents", "子代理", DaiyuIcons.SubAgent, SettingControl.PAGE),
            SettingsEntry("title_model", "标题模型", DaiyuIcons.Title, SettingControl.PAGE),
            SettingsEntry("reconnect", "错误重连", DaiyuIcons.Reconnect, SettingControl.PAGE),
        )),
        SettingsGroup("extensions", "扩展", "助手、Skills、MCP 与鲸鱼女仆", listOf(
            SettingsEntry("pet", "鲸鱼女仆", DaiyuIcons.Pet, SettingControl.PAGE),
            SettingsEntry("assistants", "助手", DaiyuIcons.Assistant, SettingControl.PAGE),
            SettingsEntry("skills", "Skills", DaiyuIcons.Skills, SettingControl.PAGE, FeatureRoute.SKILLS),
            SettingsEntry("mcp", "MCP 服务器", DaiyuIcons.Gateway, SettingControl.PAGE),
        )),
        SettingsGroup("general", "通用", "语音、外观、用量与数据备份", listOf(
            SettingsEntry("voice", "语音", DaiyuIcons.Voice, SettingControl.PAGE),
            SettingsEntry("appearance", "外观与主题", DaiyuIcons.Palette, SettingControl.PAGE),
            SettingsEntry("usage", "用量统计", DaiyuIcons.Chart, SettingControl.PAGE, FeatureRoute.USAGE_STATS),
            SettingsEntry("backup", "数据备份", DaiyuIcons.Backup, SettingControl.PAGE, FeatureRoute.BACKUP),
        )),
        SettingsGroup("tools", "工具", "工具开关、任务偏好与运行环境", listOf(
            SettingsEntry("tool_list", "工具列表", DaiyuIcons.Tool, SettingControl.PAGE),
            SettingsEntry("task_surface", "Agent任务偏好", DaiyuIcons.VirtualScreen, SettingControl.PAGE),
            SettingsEntry("browser_tools", "启用网页浏览工具", DaiyuIcons.Browser, SettingControl.TOGGLE),
            SettingsEntry("device_tools", "启用设备直达工具", DaiyuIcons.Device, SettingControl.TOGGLE),
            SettingsEntry("sensitive_read", "允许读取敏感设备信息", DaiyuIcons.Vision, SettingControl.TOGGLE),
            SettingsEntry("sensitive_action", "允许敏感设备操作", DaiyuIcons.Shield, SettingControl.TOGGLE),
            SettingsEntry("terminal_tools", "启用终端/文件工具", DaiyuIcons.Terminal, SettingControl.TOGGLE),
            SettingsEntry("linux", "Linux 工具环境", DaiyuIcons.Terminal, SettingControl.PAGE),
            SettingsEntry("workspace", "工作区与文件", DaiyuIcons.Folder, SettingControl.PAGE),
        )),
        SettingsGroup("haptics", "震动", "震动反馈与试听", listOf(
            SettingsEntry("haptics", "震动列表", DaiyuIcons.Vibration, SettingControl.PAGE),
        )),
        SettingsGroup("system", "系统助手接管", "默认助理与电源键设置", listOf(
            SettingsEntry("system_assistant", "代鱼 系统助手", DaiyuIcons.Assistant, SettingControl.PAGE),
            SettingsEntry("power_key", "电源键长按", DaiyuIcons.Device, SettingControl.CHOICE),
            SettingsEntry("auto_assistant", "自动设置默认助理", DaiyuIcons.Settings, SettingControl.TOGGLE),
        )),
        SettingsGroup("vendor", "小布/小爱兼容入口", "厂商助手模型与接管方式", listOf(
            SettingsEntry("vendor_model", "启用厂商助手自定义模型", DaiyuIcons.Model, SettingControl.TOGGLE),
            SettingsEntry("agent_prefix", "仅以 /agent 前缀接管", DaiyuIcons.Context, SettingControl.TOGGLE),
        )),
        SettingsGroup("gemini", "Gemini", "语音唤醒与系统增强", listOf(
            SettingsEntry("hotword", "息屏后维持 Hey Google 检测", DaiyuIcons.Voice, SettingControl.TOGGLE),
            SettingsEntry("lock_voice", "锁屏唤起自动语音输入", DaiyuIcons.Voice, SettingControl.TOGGLE),
            SettingsEntry("screen_voice", "亮屏唤起自动语音输入", DaiyuIcons.Voice, SettingControl.TOGGLE),
            SettingsEntry("systemizer", "将 Google App 转为系统应用", DaiyuIcons.Device, SettingControl.ACTION),
        )),
        SettingsGroup("circle", "一圈即搜", "手势触发方式", listOf(
            SettingsEntry("gesture_search", "手势条长按触发一圈即搜", DaiyuIcons.Search, SettingControl.TOGGLE),
            SettingsEntry("finger_search", "双指长按触发一圈即搜", DaiyuIcons.Search, SettingControl.TOGGLE),
        )),
        SettingsGroup("diagnostics", "诊断", "日志记录、导出与清理", listOf(
            SettingsEntry("logging", "记录诊断日志", DaiyuIcons.File, SettingControl.TOGGLE),
            SettingsEntry("export_logs", "导出日志", DaiyuIcons.Export, SettingControl.ACTION),
            SettingsEntry("clear_logs", "清除日志", DaiyuIcons.Delete, SettingControl.ACTION),
        )),
        SettingsGroup("permissions", "权限", "授权检查、悬浮窗与无障碍", listOf(
            SettingsEntry("permission_check", "权限检查", DaiyuIcons.Shield, SettingControl.PAGE),
            SettingsEntry("enhancements", "Root 与系统增强", DaiyuIcons.Device, SettingControl.PAGE),
            SettingsEntry("overlay", "悬浮窗权限", DaiyuIcons.VirtualScreen, SettingControl.PAGE),
            SettingsEntry("accessibility", "无障碍增强工具", DaiyuIcons.Accessibility, SettingControl.PAGE),
            SettingsEntry("accessibility_keep", "强制保持无障碍", DaiyuIcons.Shield, SettingControl.TOGGLE),
        )),
        SettingsGroup("about", "关于", "版本、社区与项目入口", listOf(
            SettingsEntry("updates", "检查更新", DaiyuIcons.Refresh, SettingControl.ACTION),
            SettingsEntry("telegram", "Telegram 频道", DaiyuIcons.Browser, SettingControl.ACTION),
            SettingsEntry("qq", "QQ 群", DaiyuIcons.Browser, SettingControl.ACTION),
            SettingsEntry("source", "源代码", DaiyuIcons.Browser, SettingControl.ACTION),
            SettingsEntry("relay", "自用中转站", DaiyuIcons.Gateway, SettingControl.PAGE),
            SettingsEntry("relay_st", "ST API", DaiyuIcons.Gateway, SettingControl.ACTION),
            SettingsEntry("relay_moyu", "摸鱼", DaiyuIcons.Gateway, SettingControl.ACTION),
            SettingsEntry("donate", "给煮波恰饭🍚", DaiyuIcons.Balance, SettingControl.ACTION),
        )),
    )
    val entries: List<SettingsEntry> = groups.flatMap { it.entries }
    fun entry(id: String): SettingsEntry = entries.single { it.id == id }
}

internal fun filterSettingsGroups(query: String, groups: List<SettingsGroup> = SettingsCatalog.groups): List<SettingsGroup> {
    val term = query.trim()
    if (term.isEmpty()) return groups
    return groups.mapNotNull { group ->
        val entries = group.entries.filter { it.title.contains(term, ignoreCase = true) }
        group.copy(entries = entries).takeIf { entries.isNotEmpty() }
    }
}
