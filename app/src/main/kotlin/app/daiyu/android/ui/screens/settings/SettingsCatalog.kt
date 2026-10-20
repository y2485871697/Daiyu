package app.daiyu.android.ui.screens.settings

import androidx.compose.ui.graphics.vector.ImageVector
import app.daiyu.android.ui.FeatureRoute
import app.daiyu.android.ui.icons.DaiyuIcons

internal val RetainedSettingsEntryAction =
    androidx.compose.ui.semantics.SemanticsPropertyKey<(SettingsEntry) -> Unit>("RetainedSettingsEntryAction")

internal enum class SettingControl { PAGE, TOGGLE, CHOICE, ACTION }
/** Pages hosted by the retained settings home, not an Activity-level FeatureRoute. */
internal enum class SettingsLocalPage { TASK_SURFACE, RUNNING_MODE, ENHANCEMENTS, SHIZUKU_ACCESS, OVERLAY, WORKSPACE }
internal data class SettingsEntry(
    val id: String, val title: String, val icon: ImageVector,
    val control: SettingControl = SettingControl.PAGE, val route: FeatureRoute? = null,
    val localPage: SettingsLocalPage? = null,
    val summary: String? = null,
)
internal data class SettingsGroup(val id: String, val title: String, val summary: String, val entries: List<SettingsEntry>)

/** Settings inventory and home grouping; route identities stay independent of presentation. */
internal object SettingsCatalog {
    val groups = listOf(
        SettingsGroup("general", "通用设置", "外观、助手、语音与触感", listOf(
            SettingsEntry("appearance", "外观与主题", DaiyuIcons.Palette, SettingControl.PAGE, summary = "主题、图标与界面显示"),
            SettingsEntry("assistants", "助手", DaiyuIcons.Assistant, SettingControl.PAGE, summary = "角色、提示词与个性化"),
            SettingsEntry("voice", "语音", DaiyuIcons.Voice, SettingControl.PAGE, summary = "语音输入、朗读与对话"),
            SettingsEntry("haptics", "震动列表", DaiyuIcons.Vibration, SettingControl.PAGE, summary = "触感反馈与试听"),
        )),
        SettingsGroup("models", "模型与服务", "提供商、模型用途与请求设置", listOf(
            SettingsEntry("providers", "模型提供商", DaiyuIcons.Provider, SettingControl.PAGE, FeatureRoute.MODEL_SETTINGS, summary = "服务商连接与模型管理"),
            SettingsEntry("balance", "供应商余额", DaiyuIcons.Balance, SettingControl.PAGE, FeatureRoute.BALANCE_SETTINGS),
            SettingsEntry("compression", "上下文压缩", DaiyuIcons.Compress, SettingControl.PAGE, FeatureRoute.COMPRESSION_SETTINGS, summary = "摘要模型与上下文压缩"),
            SettingsEntry("generation", "独立生成模型", DaiyuIcons.Vision, SettingControl.PAGE, FeatureRoute.GENERATION_SETTINGS),
            SettingsEntry("vision", "辅助视觉模型", DaiyuIcons.Vision, SettingControl.PAGE, FeatureRoute.AUXILIARY_VISION_SETTINGS, summary = "图片理解使用的模型"),
            SettingsEntry("subagents", "子代理", DaiyuIcons.SubAgent, SettingControl.PAGE, summary = "协作模型与任务分工"),
            SettingsEntry("title_model", "标题模型", DaiyuIcons.Title, SettingControl.PAGE, FeatureRoute.TITLE_MODEL_SETTINGS, summary = "会话标题使用的模型"),
            SettingsEntry("reconnect", "错误重连", DaiyuIcons.Reconnect, SettingControl.PAGE, FeatureRoute.RECONNECT_SETTINGS, summary = "请求重试与错误重连"),
        )),
        SettingsGroup("extensions", "扩展能力", "桌面伙伴、Skills 与 MCP", listOf(
            SettingsEntry("pet", "鲸鱼女仆", DaiyuIcons.Pet, SettingControl.PAGE, FeatureRoute.PET_SETTINGS, summary = "桌面伙伴与互动"),
            SettingsEntry("skills", "Skills", DaiyuIcons.Skills, SettingControl.PAGE, FeatureRoute.SKILLS, summary = "技能管理与工具扩展"),
            SettingsEntry("mcp", "MCP 服务器", DaiyuIcons.Gateway, SettingControl.PAGE, FeatureRoute.MCP_SETTINGS, summary = "外部工具与服务连接"),
        )),
        SettingsGroup("tools", "工具与环境", "工作区、Linux 与任务执行偏好", listOf(
            SettingsEntry("platform_tools", "平台只读工具与私密出站", DaiyuIcons.Shield, SettingControl.PAGE),
            SettingsEntry("running_mode", "运行模式", DaiyuIcons.Device, localPage = SettingsLocalPage.RUNNING_MODE),
            SettingsEntry("workspace", "工作区与文件", DaiyuIcons.Folder, SettingControl.PAGE, localPage = SettingsLocalPage.WORKSPACE, summary = "文件、目录与共享访问"),
            SettingsEntry("browser", "浏览器", DaiyuIcons.Browser, SettingControl.PAGE, FeatureRoute.BROWSER_SETTINGS),
            SettingsEntry("linux", "Linux 工具环境", DaiyuIcons.Terminal, SettingControl.PAGE, FeatureRoute.LINUX_SETTINGS, summary = "运行环境与可选工具"),
            SettingsEntry("task_surface", "Agent任务偏好", DaiyuIcons.VirtualScreen, SettingControl.PAGE,
                localPage = SettingsLocalPage.TASK_SURFACE, summary = "前台、后台或每次询问"),
        )),
        SettingsGroup("data", "数据与记录", "用量、备份与诊断日志", listOf(
            SettingsEntry("usage", "用量统计", DaiyuIcons.Chart, SettingControl.PAGE, FeatureRoute.USAGE_STATS, summary = "对话、Token 与模型用量"),
            SettingsEntry("backup", "数据备份", DaiyuIcons.Backup, SettingControl.PAGE, FeatureRoute.BACKUP, summary = "导入与导出应用数据"),
            SettingsEntry("logging", "记录诊断日志", DaiyuIcons.File, SettingControl.TOGGLE),
            SettingsEntry("export_logs", "导出日志", DaiyuIcons.Export, SettingControl.ACTION),
            SettingsEntry("clear_logs", "清除日志", DaiyuIcons.Delete, SettingControl.ACTION),
        )),
        SettingsGroup("permissions", "权限", "授权检查、悬浮窗与无障碍", listOf(
            SettingsEntry("enhancements", "Root 与系统增强", DaiyuIcons.Device, SettingControl.PAGE, localPage = SettingsLocalPage.ENHANCEMENTS),
            SettingsEntry("overlay", "悬浮窗权限", DaiyuIcons.VirtualScreen, SettingControl.PAGE, localPage = SettingsLocalPage.OVERLAY),
            SettingsEntry("accessibility", "无障碍与 GUI 设置", DaiyuIcons.Accessibility, SettingControl.PAGE, FeatureRoute.GUI_SETTINGS),
        )),
        SettingsGroup("about", "关于", "版本、社区与项目入口", listOf(
            SettingsEntry("updates", "检查更新", DaiyuIcons.Refresh, SettingControl.ACTION),
            SettingsEntry("telegram", "Telegram 频道", DaiyuIcons.Browser, SettingControl.ACTION, summary = "公告与项目动态"),
            SettingsEntry("qq", "QQ 群", DaiyuIcons.Browser, SettingControl.ACTION, summary = "交流与使用反馈"),
            SettingsEntry("source", "源代码", DaiyuIcons.Browser, SettingControl.ACTION, summary = "项目代码与开发记录"),
            SettingsEntry("relay", "自用中转站", DaiyuIcons.Gateway, SettingControl.PAGE, summary = "常用服务入口"),
            SettingsEntry("relay_st", "ST API", DaiyuIcons.Gateway, SettingControl.ACTION, summary = "打开 ST API"),
            SettingsEntry("relay_moyu", "摸鱼", DaiyuIcons.Gateway, SettingControl.ACTION, summary = "打开摸鱼"),
            SettingsEntry("donate", "给煮波恰饭🍚", DaiyuIcons.Balance, SettingControl.ACTION),
        )),
    )
    private val hiddenEntryIds = setOf(
        "balance", "generation", "platform_tools", "browser", "overlay", "enhancements", "accessibility",
    )

    // Retain underlying routes; remove only product entry points.
    @Suppress("UNUSED_PARAMETER")
    fun visibleGroups(engineeringUi: Boolean = false): List<SettingsGroup> = groups.mapNotNull { group ->
        val entries = group.entries.filterNot { it.id in hiddenEntryIds }
        group.copy(entries = entries).takeIf { entries.isNotEmpty() }
    }
    val entries: List<SettingsEntry> = groups.flatMap { it.entries }
    fun entry(id: String): SettingsEntry = entries.single { it.id == id }
}

internal fun filterSettingsGroups(query: String, groups: List<SettingsGroup> = SettingsCatalog.visibleGroups()): List<SettingsGroup> {
    val term = query.trim()
    if (term.isEmpty()) return groups
    return groups.mapNotNull { group ->
        val entries = group.entries.filter { it.title.contains(term, ignoreCase = true) }
        group.copy(entries = entries).takeIf { entries.isNotEmpty() }
    }
}
