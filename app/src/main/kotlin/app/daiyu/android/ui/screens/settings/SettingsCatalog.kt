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
            SettingsEntry("balance", "供应商余额", DaiyuIcons.Balance, SettingControl.PAGE, FeatureRoute.BALANCE_SETTINGS),
            SettingsEntry("default_reasoning", "默认启用推理", DaiyuIcons.Reasoning, SettingControl.TOGGLE),
        )),
        SettingsGroup("models", "模型功能", "压缩、视觉、子代理与标题模型", listOf(
            SettingsEntry("compression", "上下文压缩", DaiyuIcons.Compress, SettingControl.PAGE, FeatureRoute.COMPRESSION_SETTINGS),
            SettingsEntry("generation", "独立生成模型", DaiyuIcons.Vision, SettingControl.PAGE, FeatureRoute.GENERATION_SETTINGS),
            SettingsEntry("vision", "辅助视觉模型", DaiyuIcons.Vision, SettingControl.PAGE, FeatureRoute.AUXILIARY_VISION_SETTINGS),
            SettingsEntry("subagents", "子代理", DaiyuIcons.SubAgent, SettingControl.PAGE),
            SettingsEntry("title_model", "标题模型", DaiyuIcons.Title, SettingControl.PAGE, FeatureRoute.TITLE_MODEL_SETTINGS),
            SettingsEntry("reconnect", "错误重连", DaiyuIcons.Reconnect, SettingControl.PAGE, FeatureRoute.RECONNECT_SETTINGS),
        )),
        SettingsGroup("extensions", "扩展", "助手、Skills、MCP 与鲸鱼女仆", listOf(
            SettingsEntry("pet", "鲸鱼女仆", DaiyuIcons.Pet, SettingControl.PAGE, FeatureRoute.PET_SETTINGS),
            SettingsEntry("assistants", "助手", DaiyuIcons.Assistant, SettingControl.PAGE),
            SettingsEntry("skills", "Skills", DaiyuIcons.Skills, SettingControl.PAGE, FeatureRoute.SKILLS),
            SettingsEntry("mcp", "MCP 服务器", DaiyuIcons.Gateway, SettingControl.PAGE, FeatureRoute.MCP_SETTINGS),
        )),
        SettingsGroup("general", "通用", "语音、外观、用量与数据备份", listOf(
            SettingsEntry("voice", "语音", DaiyuIcons.Voice, SettingControl.PAGE),
            SettingsEntry("appearance", "外观与主题", DaiyuIcons.Palette, SettingControl.PAGE),
            SettingsEntry("usage", "用量统计", DaiyuIcons.Chart, SettingControl.PAGE, FeatureRoute.USAGE_STATS),
            SettingsEntry("backup", "数据备份", DaiyuIcons.Backup, SettingControl.PAGE, FeatureRoute.BACKUP),
        )),
        SettingsGroup("tools", "工具", "工作区、Linux 与任务执行偏好", listOf(
            SettingsEntry("platform_tools", "平台只读工具与私密出站", DaiyuIcons.Shield, SettingControl.PAGE),
            SettingsEntry("workspace", "工作区与文件", DaiyuIcons.Folder, SettingControl.PAGE),
            SettingsEntry("terminal", "终端", DaiyuIcons.Terminal, SettingControl.PAGE, FeatureRoute.TERMINAL),
            SettingsEntry("browser", "浏览器", DaiyuIcons.Browser, SettingControl.PAGE, FeatureRoute.BROWSER_SETTINGS),
            SettingsEntry("linux", "Linux 工具环境", DaiyuIcons.Terminal, SettingControl.PAGE, FeatureRoute.LINUX_SETTINGS),
            SettingsEntry("task_surface", "Agent任务偏好", DaiyuIcons.VirtualScreen, SettingControl.PAGE),
        )),
        SettingsGroup("haptics", "震动", "震动反馈与试听", listOf(
            SettingsEntry("haptics", "震动列表", DaiyuIcons.Vibration, SettingControl.PAGE),
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
            SettingsEntry("accessibility", "无障碍与 GUI 设置", DaiyuIcons.Accessibility, SettingControl.PAGE, FeatureRoute.GUI_SETTINGS),
            SettingsEntry("accessibility_keep", "无障碍服务状态与重新绑定", DaiyuIcons.Shield, SettingControl.PAGE, FeatureRoute.GUI_SETTINGS),
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
