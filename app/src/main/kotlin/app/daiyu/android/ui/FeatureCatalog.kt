package app.daiyu.android.ui

internal enum class FeatureRoute {
    CONVERSATIONS, MODEL_SETTINGS, SKILLS, BACKUP, CONVERSATION_SEARCH, FOLDERS, CHAT, USER_MESSAGE
}

internal data class FeatureEntry(val id: String, val title: String, val route: FeatureRoute? = null)
internal data class FeatureGroup(val id: String, val title: String, val entries: List<FeatureEntry>)

/** Stable UI slots, not a capability registry or a business-completion ledger. */
internal object FeatureCatalog {
    val groups: List<FeatureGroup> = listOf(
        FeatureGroup("conversation", "会话与助手", listOf(
            FeatureEntry("P01", "助手管理"),
            FeatureEntry("P07", "侧边栏"),
            FeatureEntry("P22", "会话管理", FeatureRoute.CONVERSATIONS),
            FeatureEntry("P27", "聊天记录搜索"),
            FeatureEntry("P28", "搜索会话", FeatureRoute.CONVERSATION_SEARCH),
            FeatureEntry("P28.folders", "按文件夹浏览", FeatureRoute.FOLDERS)
        )),
        FeatureGroup("model", "模型与供应商", listOf(
            FeatureEntry("R03", "模型设置", FeatureRoute.MODEL_SETTINGS),
            FeatureEntry("P02", "GPT fast", FeatureRoute.MODEL_SETTINGS),
            FeatureEntry("P03", "会话 ID 与网关"),
            FeatureEntry("P04", "自定义请求体", FeatureRoute.MODEL_SETTINGS),
            FeatureEntry("P05", "供应商余额"),
            FeatureEntry("P06", "远端模型目录", FeatureRoute.MODEL_SETTINGS),
            FeatureEntry("P08", "标题模型"),
            FeatureEntry("P25", "辅助视觉模型"),
            FeatureEntry("P26", "错误重连")
        )),
        FeatureGroup("input", "输入与生成", listOf(
            FeatureEntry("P11", "发送消息", FeatureRoute.CHAT),
            FeatureEntry("P12", "暂停与继续", FeatureRoute.CHAT),
            FeatureEntry("P13", "停止回复", FeatureRoute.CHAT),
            FeatureEntry("P14", "追加与追问"),
            FeatureEntry("P17", "发送图片"),
            FeatureEntry("P18", "发送视频"),
            FeatureEntry("P19", "会话生图"),
            FeatureEntry("P20", "会话生视频")
        )),
        FeatureGroup("context", "上下文与统计", listOf(
            FeatureEntry("P09", "上下文压缩"),
            FeatureEntry("P10", "上下文统计"),
            FeatureEntry("P21", "用量统计"),
            FeatureEntry("P39", "重复输出保护"),
            FeatureEntry("P42", "会话压缩状态"),
            FeatureEntry("P43", "请求与运行状态")
        )),
        FeatureGroup("collaboration", "协作与交互", listOf(
            FeatureEntry("P15", "子代理运行"),
            FeatureEntry("P16", "子代理设置与状态"),
            FeatureEntry("P23", "交互模式与问答", FeatureRoute.CHAT)
        )),
        FeatureGroup("voice", "语音与文件打开", listOf(
            FeatureEntry("P32", "语音模式"),
            FeatureEntry("P32.dictation", "语音听写"),
            FeatureEntry("P38", "TTS 播放与试听"),
            FeatureEntry("P38.files", "文件打开方式")
        )),
        FeatureGroup("workspace", "工具与数据", listOf(
            FeatureEntry("P33", "技能管理", FeatureRoute.SKILLS),
            FeatureEntry("P34", "浏览器"),
            FeatureEntry("R06", "设备与 Root"),
            FeatureEntry("R07", "无障碍与屏幕操作"),
            FeatureEntry("R08", "虚拟屏"),
            FeatureEntry("R09", "终端与 Linux 环境"),
            FeatureEntry("R12", "备份与导入", FeatureRoute.BACKUP)
        )),
        FeatureGroup("appearance", "外观与阅读", listOf(
            FeatureEntry("P24", "鲸鱼女仆"),
            FeatureEntry("P29", "应用图标"),
            FeatureEntry("P30", "图标外观设置"),
            FeatureEntry("P31", "震动设置"),
            FeatureEntry("P35", "消息时间戳"),
            FeatureEntry("P36", "圆形加载指示器"),
            FeatureEntry("P37", "消息操作栏"),
            FeatureEntry("P40", "刷新率策略"),
            FeatureEntry("P41", "跟随与消息定位", FeatureRoute.USER_MESSAGE)
        ))
    )
}
