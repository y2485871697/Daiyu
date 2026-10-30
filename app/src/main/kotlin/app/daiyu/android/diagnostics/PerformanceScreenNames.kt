package app.daiyu.android.diagnostics

/** Closed product copy, not an arbitrary UI title. */
internal object PerformanceScreenNames {
    private val names = setOf(
        "APP 信息与更新",
        "Agent任务偏好",
        "Linux 工具环境",
        "上下文压缩",
        "保留会话网关",
        "声音复刻账户",
        "备份与导入",
        "外观与主题",
        "平台只读工具与私密出站",
        "技能",
        "无障碍与屏幕授权",
        "旧版兼容资料库",
        "权限检查",
        "标题模型",
        "模型提供商",
        "浏览器",
        "独立生成模型设置",
        "离线语音",
        "终端",
        "触感反馈",
        "认证方式",
        "设置",
        "运行模式",
        "错误重连",
        "鲸鱼女仆",
    )
    fun label(title: String): String = title.takeIf { it in names } ?: "detail"
}
