package app.daiyu.android.ui

internal enum class FeatureControlKind(val label: String) {
    FIELD("字段"), CHOICE("选项"), TOGGLE("开启 / 关闭"), INFO("内容"), ACTION("操作")
}
internal data class FeatureControl(val id: String, val title: String, val kind: FeatureControlKind,
    val route: FeatureRoute? = null)
internal data class FeatureSection(val id: String, val title: String, val controls: List<FeatureControl>)

/** UI schema only: no fabricated values, preference writes or business command callbacks. */
internal object FeatureDetails {
    val pages: Map<String, List<FeatureSection>> = mapOf(
        "P01" to listOf(
            FeatureSection("content", "设置与内容", listOf(
                FeatureControl("identity", "助手名称与身份", FeatureControlKind.FIELD),
                FeatureControl("persona", "人格与提示词", FeatureControlKind.FIELD),
                FeatureControl("memory", "助手记忆", FeatureControlKind.FIELD),
                FeatureControl("skills", "助手技能", FeatureControlKind.CHOICE),
                FeatureControl("avatar", "助手头像", FeatureControlKind.CHOICE),
            )),
            FeatureSection("actions", "操作", listOf(
                FeatureControl("create", "新建助手", FeatureControlKind.ACTION),
                FeatureControl("edit", "编辑助手", FeatureControlKind.ACTION),
            )),
        ),
        "P07" to listOf(
            FeatureSection("content", "设置与内容", listOf(
                FeatureControl("assistants", "助手栏", FeatureControlKind.CHOICE),
                FeatureControl("search", "会话标题与预览搜索", FeatureControlKind.FIELD),
                FeatureControl("folders", "文件夹", FeatureControlKind.CHOICE),
                FeatureControl("groups", "会话分组", FeatureControlKind.INFO),
                FeatureControl("markers", "运行与完成标记", FeatureControlKind.INFO),
            )),
            FeatureSection("actions", "操作", listOf(
                FeatureControl("menu", "会话长按菜单", FeatureControlKind.ACTION),
                FeatureControl("open", "打开侧边栏", FeatureControlKind.ACTION, FeatureRoute.SIDEBAR),
            )),
        ),
        "P22" to listOf(
            FeatureSection("content", "设置与内容", listOf(
                FeatureControl("list", "聊天列表", FeatureControlKind.INFO),
                FeatureControl("search", "聊天记录搜索", FeatureControlKind.FIELD),
            )),
            FeatureSection("actions", "操作", listOf(
                FeatureControl("pinned", "置顶与取消置顶", FeatureControlKind.ACTION),
                FeatureControl("delete", "删除聊天", FeatureControlKind.ACTION),
                FeatureControl("delete-all", "删除全部聊天", FeatureControlKind.ACTION),
                FeatureControl("open", "打开管理聊天", FeatureControlKind.ACTION, FeatureRoute.MANAGE_CHATS),
            )),
        ),
        "P27" to listOf(
            FeatureSection("content", "设置与内容", listOf(
                FeatureControl("scope", "当前会话与全部记录", FeatureControlKind.CHOICE),
                FeatureControl("query", "搜索关键词", FeatureControlKind.FIELD),
                FeatureControl("results", "正文与工具记录命中", FeatureControlKind.INFO),
            )),
            FeatureSection("actions", "操作", listOf(
                FeatureControl("location", "命中消息定位", FeatureControlKind.ACTION),
            )),
        ),
        "P28" to listOf(
            FeatureSection("content", "设置与内容", listOf(
                FeatureControl("query", "会话标题与预览关键词", FeatureControlKind.FIELD),
                FeatureControl("folder", "文件夹范围", FeatureControlKind.CHOICE),
            )),
            FeatureSection("actions", "操作", listOf(
                FeatureControl("clear", "清空搜索", FeatureControlKind.ACTION),
                FeatureControl("open", "打开会话搜索", FeatureControlKind.ACTION, FeatureRoute.CONVERSATION_SEARCH),
            )),
        ),
        "P28.folders" to listOf(
            FeatureSection("content", "设置与内容", listOf(
                FeatureControl("folders", "文件夹列表", FeatureControlKind.INFO),
                FeatureControl("uncategorized", "未分类会话", FeatureControlKind.INFO),
                FeatureControl("filter", "文件夹内会话", FeatureControlKind.INFO),
            )),
            FeatureSection("actions", "操作", listOf(
                FeatureControl("move", "移动会话到文件夹", FeatureControlKind.ACTION),
                FeatureControl("open", "打开文件夹浏览", FeatureControlKind.ACTION, FeatureRoute.FOLDERS),
            )),
        ),
        "R03" to listOf(
            FeatureSection("content", "设置与内容", listOf(
                FeatureControl("protocol", "模型协议", FeatureControlKind.CHOICE),
                FeatureControl("endpoint", "服务地址", FeatureControlKind.FIELD),
                FeatureControl("model", "模型名称", FeatureControlKind.FIELD),
                FeatureControl("key", "API 密钥", FeatureControlKind.FIELD),
                FeatureControl("reasoning", "思考强度", FeatureControlKind.CHOICE),
                FeatureControl("speed", "服务档位", FeatureControlKind.CHOICE),
            )),
            FeatureSection("actions", "操作", listOf(
                FeatureControl("open", "打开模型设置", FeatureControlKind.ACTION, FeatureRoute.MODEL_SETTINGS),
            )),
        ),
        "P02" to listOf(
            FeatureSection("content", "设置与内容", listOf(
                FeatureControl("speed", "速度档位", FeatureControlKind.CHOICE),
                FeatureControl("reasoning", "思考强度", FeatureControlKind.CHOICE),
                FeatureControl("gateway", "网关服务档位", FeatureControlKind.INFO),
            )),
            FeatureSection("actions", "操作", listOf(
                FeatureControl("open", "打开模型设置", FeatureControlKind.ACTION, FeatureRoute.MODEL_SETTINGS),
            )),
        ),
        "P03" to listOf(
            FeatureSection("content", "设置与内容", listOf(
                FeatureControl("session", "会话 ID", FeatureControlKind.FIELD),
                FeatureControl("thread", "线程 ID", FeatureControlKind.FIELD),
                FeatureControl("gateway", "网关规则", FeatureControlKind.FIELD),
                FeatureControl("retention", "保留策略", FeatureControlKind.CHOICE),
                FeatureControl("key-source", "密钥来源", FeatureControlKind.CHOICE),
            )),
        ),
        "P04" to listOf(
            FeatureSection("content", "设置与内容", listOf(
                FeatureControl("body", "自定义请求体 JSON", FeatureControlKind.FIELD),
                FeatureControl("merge", "对象递归合并", FeatureControlKind.INFO),
                FeatureControl("arrays", "数组替换", FeatureControlKind.INFO),
                FeatureControl("types", "字符串与数字类型", FeatureControlKind.INFO),
            )),
            FeatureSection("actions", "操作", listOf(
                FeatureControl("open", "打开模型设置", FeatureControlKind.ACTION, FeatureRoute.MODEL_SETTINGS),
            )),
        ),
        "P05" to listOf(
            FeatureSection("content", "设置与内容", listOf(
                FeatureControl("provider", "供应商", FeatureControlKind.CHOICE),
                FeatureControl("query", "余额查询配置", FeatureControlKind.FIELD),
                FeatureControl("balance", "余额金额", FeatureControlKind.INFO),
            )),
            FeatureSection("actions", "操作", listOf(
                FeatureControl("refresh", "查询余额", FeatureControlKind.ACTION),
            )),
        ),
        "P06" to listOf(
            FeatureSection("content", "设置与内容", listOf(
                FeatureControl("query", "模型搜索", FeatureControlKind.FIELD),
                FeatureControl("select", "模型勾选", FeatureControlKind.CHOICE),
                FeatureControl("count", "已选模型数量", FeatureControlKind.INFO),
            )),
            FeatureSection("actions", "操作", listOf(
                FeatureControl("all", "全选与反选", FeatureControlKind.ACTION),
                FeatureControl("confirm", "添加所选模型", FeatureControlKind.ACTION),
                FeatureControl("open", "打开模型设置", FeatureControlKind.ACTION, FeatureRoute.MODEL_SETTINGS),
            )),
        ),
        "P08" to listOf(
            FeatureSection("content", "设置与内容", listOf(
                FeatureControl("model", "标题生成模型", FeatureControlKind.CHOICE),
                FeatureControl("rename", "手动标题", FeatureControlKind.FIELD),
                FeatureControl("late", "迟到结果与手动改名保护", FeatureControlKind.INFO),
            )),
            FeatureSection("actions", "操作", listOf(
                FeatureControl("open", "打开独立标题模型设置", FeatureControlKind.ACTION, FeatureRoute.TITLE_MODEL_SETTINGS),
            )),
        ),
        "P25" to listOf(
            FeatureSection("content", "设置与内容", listOf(
                FeatureControl("model", "辅助视觉模型", FeatureControlKind.CHOICE),
                FeatureControl("evidence", "观察图片证据", FeatureControlKind.INFO),
                FeatureControl("retry", "失败重连策略", FeatureControlKind.CHOICE),
            )),
            FeatureSection("actions", "操作", listOf(
                FeatureControl("cancel", "取消视觉请求", FeatureControlKind.ACTION),
            )),
        ),
        "P26" to listOf(
            FeatureSection("content", "设置与内容", listOf(
                FeatureControl("duration", "重连等待时长", FeatureControlKind.CHOICE),
                FeatureControl("pause", "暂停时冻结等待", FeatureControlKind.INFO),
                FeatureControl("segment", "新回复片段", FeatureControlKind.INFO),
            )),
            FeatureSection("actions", "操作", listOf(
                FeatureControl("stop", "停止重连", FeatureControlKind.ACTION),
            )),
        ),
        "P11" to listOf(
            FeatureSection("content", "设置与内容", listOf(
                FeatureControl("text", "消息文字", FeatureControlKind.FIELD),
                FeatureControl("images", "图片附件", FeatureControlKind.INFO),
                FeatureControl("files", "文件附件", FeatureControlKind.INFO),
                FeatureControl("references", "会话引用", FeatureControlKind.CHOICE),
            )),
            FeatureSection("actions", "操作", listOf(
                FeatureControl("send", "发送消息", FeatureControlKind.ACTION),
                FeatureControl("open", "前往聊天输入", FeatureControlKind.ACTION, FeatureRoute.CHAT),
            )),
        ),
        "P12" to listOf(
            FeatureSection("content", "设置与内容", listOf(
                FeatureControl("state", "当前回复状态", FeatureControlKind.INFO),
            )),
            FeatureSection("actions", "操作", listOf(
                FeatureControl("pause", "暂停当前回复", FeatureControlKind.ACTION),
                FeatureControl("resume", "继续当前回复", FeatureControlKind.ACTION),
                FeatureControl("open", "前往聊天控制", FeatureControlKind.ACTION, FeatureRoute.CHAT),
            )),
        ),
        "P13" to listOf(
            FeatureSection("content", "设置与内容", listOf(
                FeatureControl("owner", "当前会话与运行", FeatureControlKind.INFO),
                FeatureControl("children", "子任务保留", FeatureControlKind.INFO),
            )),
            FeatureSection("actions", "操作", listOf(
                FeatureControl("stop", "长按停止主回复", FeatureControlKind.ACTION),
                FeatureControl("open", "前往聊天控制", FeatureControlKind.ACTION, FeatureRoute.CHAT),
            )),
        ),
        "P14" to listOf(
            FeatureSection("content", "设置与内容", listOf(
                FeatureControl("text", "追加文字与追问", FeatureControlKind.FIELD),
                FeatureControl("attachments", "图片与媒体引用", FeatureControlKind.INFO),
                FeatureControl("receipt", "追加接受回执", FeatureControlKind.INFO),
            )),
            FeatureSection("actions", "操作", listOf(
                FeatureControl("supplement", "追加到当前运行", FeatureControlKind.ACTION),
            )),
        ),
        "P17" to listOf(
            FeatureSection("content", "设置与内容", listOf(
                FeatureControl("attachments", "当前会话图片附件", FeatureControlKind.INFO),
            )),
            FeatureSection("actions", "操作", listOf(
                FeatureControl("select", "选择图片", FeatureControlKind.ACTION),
                FeatureControl("preview", "原图预览", FeatureControlKind.ACTION),
                FeatureControl("remove", "移除附件", FeatureControlKind.ACTION),
            )),
        ),
        "P18" to listOf(
            FeatureSection("content", "设置与内容", listOf(
                FeatureControl("source", "原始视频文件", FeatureControlKind.INFO),
                FeatureControl("metadata", "类型、时长与封面", FeatureControlKind.INFO),
                FeatureControl("size", "导入大小限制", FeatureControlKind.INFO),
            )),
            FeatureSection("actions", "操作", listOf(
                FeatureControl("select", "选择视频", FeatureControlKind.ACTION),
                FeatureControl("remove", "移除视频", FeatureControlKind.ACTION),
            )),
        ),
        "P19" to listOf(
            FeatureSection("content", "设置与内容", listOf(
                FeatureControl("prompt", "图像描述", FeatureControlKind.FIELD),
                FeatureControl("reference", "参考图与遮罩", FeatureControlKind.CHOICE),
                FeatureControl("parameters", "图像生成参数", FeatureControlKind.FIELD),
                FeatureControl("results", "生成图片与部分结果", FeatureControlKind.INFO),
            )),
            FeatureSection("actions", "操作", listOf(
                FeatureControl("generate", "生成图片", FeatureControlKind.ACTION),
                FeatureControl("cancel", "取消生成", FeatureControlKind.ACTION),
            )),
        ),
        "P20" to listOf(
            FeatureSection("content", "设置与内容", listOf(
                FeatureControl("prompt", "视频描述", FeatureControlKind.FIELD),
                FeatureControl("parameters", "视频生成参数", FeatureControlKind.FIELD),
                FeatureControl("task", "生成任务与轮询进度", FeatureControlKind.INFO),
                FeatureControl("results", "下载与视频结果", FeatureControlKind.INFO),
            )),
            FeatureSection("actions", "操作", listOf(
                FeatureControl("generate", "生成视频", FeatureControlKind.ACTION),
                FeatureControl("cancel", "取消生成", FeatureControlKind.ACTION),
            )),
        ),
        "P09" to listOf(
            FeatureSection("open", "设置入口", listOf(
                FeatureControl("open", "打开压缩设置", FeatureControlKind.ACTION, FeatureRoute.COMPRESSION_SETTINGS),
            )),
            FeatureSection("content", "设置与内容", listOf(
                FeatureControl("mode", "手动与自动压缩", FeatureControlKind.CHOICE),
                FeatureControl("model", "压缩模型与端点", FeatureControlKind.FIELD),
                FeatureControl("tail", "保留消息尾部", FeatureControlKind.INFO),
                FeatureControl("archive", "压缩归档", FeatureControlKind.INFO),
            )),
            FeatureSection("actions", "操作", listOf(
                FeatureControl("compress", "压缩当前会话", FeatureControlKind.ACTION),
            )),
        ),
        "P10" to listOf(
            FeatureSection("content", "设置与内容", listOf(
                FeatureControl("window", "云端上下文窗口占用", FeatureControlKind.INFO),
                FeatureControl("budget", "静默发送预算", FeatureControlKind.INFO),
                FeatureControl("receipts", "请求回执与未知状态", FeatureControlKind.INFO),
            )),
        ),
        "P21" to listOf(
            FeatureSection("content", "设置与内容", listOf(
                FeatureControl("range", "统计时间范围", FeatureControlKind.CHOICE),
                FeatureControl("overview", "统计概览与会话热力图", FeatureControlKind.INFO),
                FeatureControl("models", "供应商与模型统计", FeatureControlKind.INFO),
                FeatureControl("input", "输入 Token", FeatureControlKind.INFO),
                FeatureControl("output", "输出 Token", FeatureControlKind.INFO),
                FeatureControl("cache", "缓存命中与写入", FeatureControlKind.INFO),
            )),
            FeatureSection("actions", "操作", listOf(
                FeatureControl("open", "打开用量统计", FeatureControlKind.ACTION, FeatureRoute.USAGE_STATS),
            )),
        ),
        "P39" to listOf(
            FeatureSection("content", "设置与内容", listOf(
                FeatureControl("repeat", "重复输出保护", FeatureControlKind.INFO),
                FeatureControl("attempt", "当前尝试归属", FeatureControlKind.INFO),
                FeatureControl("history", "历史正文保全", FeatureControlKind.INFO),
            )),
        ),
        "P42" to listOf(
            FeatureSection("content", "设置与内容", listOf(
                FeatureControl("archive", "当前压缩归档", FeatureControlKind.INFO),
                FeatureControl("state", "会话压缩状态", FeatureControlKind.INFO),
                FeatureControl("source", "原始消息与归档定位", FeatureControlKind.INFO),
            )),
        ),
        "P43" to listOf(
            FeatureSection("content", "设置与内容", listOf(
                FeatureControl("request", "请求标识与尝试", FeatureControlKind.INFO),
                FeatureControl("run", "运行状态", FeatureControlKind.INFO),
                FeatureControl("checkpoint", "暂停、挂起与恢复记录", FeatureControlKind.INFO),
                FeatureControl("terminal", "终态与未知结果", FeatureControlKind.INFO),
            )),
        ),
        "P15" to listOf(
            FeatureSection("content", "设置与内容", listOf(
                FeatureControl("tasks", "子任务列表", FeatureControlKind.INFO),
                FeatureControl("group", "所属任务组与父会话", FeatureControlKind.INFO),
                FeatureControl("progress", "最近有效进展", FeatureControlKind.INFO),
                FeatureControl("delivery", "任务结果与产物记录", FeatureControlKind.INFO),
            )),
            FeatureSection("actions", "操作", listOf(
                FeatureControl("pause", "暂停子任务", FeatureControlKind.ACTION),
                FeatureControl("resume", "继续子任务", FeatureControlKind.ACTION),
                FeatureControl("stop", "停止子任务", FeatureControlKind.ACTION),
            )),
        ),
        "P16" to listOf(
            FeatureSection("content", "设置与内容", listOf(
                FeatureControl("profile", "子代理 Profile", FeatureControlKind.CHOICE),
                FeatureControl("preset", "配置预设", FeatureControlKind.CHOICE),
                FeatureControl("model", "模型与候选模型池", FeatureControlKind.CHOICE),
                FeatureControl("parallel", "并行任务上限", FeatureControlKind.FIELD),
                FeatureControl("snapshot", "启动时配置与授权快照", FeatureControlKind.INFO),
            )),
        ),
        "P23" to listOf(
            FeatureSection("content", "设置与内容", listOf(
                FeatureControl("enabled", "交互模式", FeatureControlKind.TOGGLE),
                FeatureControl("question", "问题标题与内容", FeatureControlKind.INFO),
                FeatureControl("options", "互斥答案与推荐说明", FeatureControlKind.CHOICE),
                FeatureControl("other", "其他答案", FeatureControlKind.FIELD),
                FeatureControl("delegate", "由你决定", FeatureControlKind.CHOICE),
                FeatureControl("note", "补充说明", FeatureControlKind.FIELD),
            )),
            FeatureSection("actions", "操作", listOf(
                FeatureControl("submit", "提交答案", FeatureControlKind.ACTION),
                FeatureControl("open", "回到聊天问答", FeatureControlKind.ACTION, FeatureRoute.CHAT),
            )),
        ),
        "P32" to listOf(
            FeatureSection("content", "设置与内容", listOf(
                FeatureControl("enabled", "语音模式", FeatureControlKind.TOGGLE),
                FeatureControl("mode", "级联对话与实时双工", FeatureControlKind.CHOICE),
                FeatureControl("configuration", "语音对话配置", FeatureControlKind.FIELD),
                FeatureControl("transcript", "对话文字", FeatureControlKind.INFO),
            )),
            FeatureSection("actions", "操作", listOf(
                FeatureControl("start", "开始语音对话", FeatureControlKind.ACTION),
                FeatureControl("stop", "结束语音对话", FeatureControlKind.ACTION),
            )),
        ),
        "P32.dictation" to listOf(
            FeatureSection("content", "设置与内容", listOf(
                FeatureControl("configuration", "听写识别配置", FeatureControlKind.FIELD),
                FeatureControl("text", "识别文字", FeatureControlKind.INFO),
            )),
            FeatureSection("actions", "操作", listOf(
                FeatureControl("start", "开始听写", FeatureControlKind.ACTION),
                FeatureControl("finish", "结束录音", FeatureControlKind.ACTION),
                FeatureControl("confirm", "确认听写文字", FeatureControlKind.ACTION),
                FeatureControl("cancel", "取消本次听写", FeatureControlKind.ACTION),
            )),
        ),
        "P38" to listOf(
            FeatureSection("content", "设置与内容", listOf(
                FeatureControl("configuration", "TTS 配置", FeatureControlKind.FIELD),
                FeatureControl("voice", "朗读音色", FeatureControlKind.CHOICE),
                FeatureControl("text", "试听文本", FeatureControlKind.FIELD),
            )),
            FeatureSection("actions", "操作", listOf(
                FeatureControl("preview", "试听所选音色", FeatureControlKind.ACTION),
                FeatureControl("read", "朗读选定内容", FeatureControlKind.ACTION),
                FeatureControl("stop", "停止播放", FeatureControlKind.ACTION),
            )),
        ),
        "P38.files" to listOf(
            FeatureSection("content", "设置与内容", listOf(
                FeatureControl("uri", "文件引用与类型", FeatureControlKind.INFO),
                FeatureControl("grant", "读取授权", FeatureControlKind.INFO),
                FeatureControl("format", "完整备份与纯文本兼容备份", FeatureControlKind.CHOICE),
            )),
            FeatureSection("actions", "操作", listOf(
                FeatureControl("confirm", "确认导入", FeatureControlKind.ACTION),
                FeatureControl("open", "打开备份与导入", FeatureControlKind.ACTION, FeatureRoute.BACKUP),
            )),
        ),
        "P33" to listOf(
            FeatureSection("content", "设置与内容", listOf(
                FeatureControl("enabled", "技能总开关", FeatureControlKind.TOGGLE),
                FeatureControl("skill", "单项技能启停", FeatureControlKind.TOGGLE),
                FeatureControl("metadata", "名称、说明与技能 ID", FeatureControlKind.INFO),
                FeatureControl("version", "来源、提交与版本", FeatureControlKind.INFO),
                FeatureControl("compatibility", "静态兼容检测", FeatureControlKind.INFO),
            )),
            FeatureSection("actions", "操作", listOf(
                FeatureControl("open", "打开技能管理", FeatureControlKind.ACTION, FeatureRoute.SKILLS),
            )),
        ),
        "P34" to listOf(
            FeatureSection("content", "设置与内容", listOf(
                FeatureControl("main", "主代理浏览器池", FeatureControlKind.INFO),
                FeatureControl("child", "子代理浏览器池", FeatureControlKind.INFO),
                FeatureControl("tabs", "标签与会话隔离", FeatureControlKind.INFO),
                FeatureControl("takeover", "可见接管", FeatureControlKind.INFO),
                FeatureControl("downloads", "下载", FeatureControlKind.INFO),
            )),
            FeatureSection("actions", "操作", listOf(
                FeatureControl("open", "打开浏览器设置", FeatureControlKind.ACTION, FeatureRoute.BROWSER_SETTINGS),
            )),
        ),
        "R06" to listOf(
            FeatureSection("content", "设置与内容", listOf(
                FeatureControl("system", "系统状态", FeatureControlKind.INFO),
                FeatureControl("notifications", "通知", FeatureControlKind.INFO),
                FeatureControl("files", "数据库与文件", FeatureControlKind.INFO),
                FeatureControl("personal", "媒体、日历与联系人", FeatureControlKind.INFO),
                FeatureControl("root", "Root 设备适配", FeatureControlKind.INFO),
                FeatureControl("authorization", "用户授权边界", FeatureControlKind.INFO),
            )),
        ),
        "R07" to listOf(
            FeatureSection("content", "设置与内容", listOf(
                FeatureControl("accessibility", "无障碍观察", FeatureControlKind.INFO),
                FeatureControl("snapshot", "UI 树快照", FeatureControlKind.INFO),
                FeatureControl("actions", "GUI 动作", FeatureControlKind.INFO),
                FeatureControl("protection", "用户显式请求系统重绑（不保证保活）", FeatureControlKind.INFO),
                FeatureControl("lock", "屏幕锁与前后台权限", FeatureControlKind.INFO),
            )),
            FeatureSection("actions", "操作", listOf(
                FeatureControl("open", "打开无障碍与 GUI 设置", FeatureControlKind.ACTION, FeatureRoute.GUI_SETTINGS),
            )),
        ),
        "R08" to listOf(
            FeatureSection("content", "设置与内容", listOf(
                FeatureControl("display", "虚拟屏", FeatureControlKind.INFO),
                FeatureControl("owner", "所有者与代次", FeatureControlKind.INFO),
                FeatureControl("authorization", "授权快照", FeatureControlKind.INFO),
                FeatureControl("isolation", "主子代理隔离", FeatureControlKind.INFO),
                FeatureControl("cleanup", "生命周期收尾", FeatureControlKind.INFO),
            )),
        ),
        "R09" to listOf(
            FeatureSection("actions", "操作", listOf(
                FeatureControl("open", "打开终端", FeatureControlKind.ACTION, route = FeatureRoute.TERMINAL),
            )),
            FeatureSection("content", "设置与内容", listOf(
                FeatureControl("session", "终端会话与 PTY", FeatureControlKind.INFO),
                FeatureControl("async", "异步读写与退出确认", FeatureControlKind.INFO),
                FeatureControl("mapping", "路径映射与共享目录", FeatureControlKind.INFO),
                FeatureControl("identity", "Android 身份", FeatureControlKind.INFO),
                FeatureControl("installation", "Linux 可信安装配置（未提供安装器）", FeatureControlKind.INFO),
                FeatureControl("cancel", "取消", FeatureControlKind.INFO),
            )),
        ),
        "R12" to listOf(
            FeatureSection("content", "设置与内容", listOf(
                FeatureControl("complete", "完整中立备份", FeatureControlKind.CHOICE),
                FeatureControl("text", "纯文本兼容备份", FeatureControlKind.CHOICE),
            )),
            FeatureSection("actions", "操作", listOf(
                FeatureControl("export", "导出备份", FeatureControlKind.ACTION),
                FeatureControl("import", "导入备份", FeatureControlKind.ACTION),
                FeatureControl("restore", "人工恢复模型配置", FeatureControlKind.ACTION),
                FeatureControl("open", "打开备份与导入", FeatureControlKind.ACTION, FeatureRoute.BACKUP),
            )),
        ),
        "P24" to listOf(
            FeatureSection("content", "设置与内容", listOf(
                FeatureControl("enabled", "鲸鱼女仆开关", FeatureControlKind.TOGGLE),
                FeatureControl("display", "角色显示", FeatureControlKind.INFO),
                FeatureControl("satiety", "饱食度", FeatureControlKind.INFO),
                FeatureControl("actions", "动作与完工反应", FeatureControlKind.INFO),
                FeatureControl("memory", "角色记忆", FeatureControlKind.INFO),
            )),
            FeatureSection("actions", "操作", listOf(
                FeatureControl("feed", "喂食", FeatureControlKind.ACTION),
            )),
        ),
        "P29" to listOf(
            FeatureSection("content", "设置与内容", listOf(
                FeatureControl("cat", "作者猫图标", FeatureControlKind.INFO),
                FeatureControl("symbols", "第三方 UI 符号", FeatureControlKind.INFO),
            )),
        ),
        "P30" to listOf(
            FeatureSection("content", "设置与内容", listOf(
                FeatureControl("background", "图标底色", FeatureControlKind.FIELD),
                FeatureControl("cat", "猫色", FeatureControlKind.FIELD),
                FeatureControl("brightness", "明暗配置", FeatureControlKind.CHOICE),
                FeatureControl("launcher", "桌面应用图标", FeatureControlKind.INFO),
                FeatureControl("notification", "通知图标", FeatureControlKind.INFO),
            )),
        ),
        "P31" to listOf(
            FeatureSection("content", "设置与内容", listOf(
                FeatureControl("enabled", "震动总开关", FeatureControlKind.TOGGLE),
                FeatureControl("generation", "生成震动", FeatureControlKind.TOGGLE),
                FeatureControl("background", "后台震动", FeatureControlKind.TOGGLE),
                FeatureControl("reasoning", "后台推理震动", FeatureControlKind.TOGGLE),
                FeatureControl("strength", "震动强度", FeatureControlKind.FIELD),
            )),
            FeatureSection("actions", "操作", listOf(
                FeatureControl("preview", "强度试听", FeatureControlKind.ACTION),
            )),
        ),
        "P35" to listOf(
            FeatureSection("content", "设置与内容", listOf(
                FeatureControl("timestamp", "消息时间戳", FeatureControlKind.INFO),
                FeatureControl("presentation", "时间戳外观与行为", FeatureControlKind.INFO),
            )),
        ),
        "P36" to listOf(
            FeatureSection("content", "设置与内容", listOf(
                FeatureControl("indicator", "圆形加载指示器", FeatureControlKind.INFO),
                FeatureControl("presentation", "加载外观与行为", FeatureControlKind.INFO),
            )),
        ),
        "P37" to listOf(
            FeatureSection("content", "设置与内容", listOf(
                FeatureControl("toolbar", "消息操作栏", FeatureControlKind.INFO),
            )),
            FeatureSection("actions", "操作", listOf(
                FeatureControl("copy", "复制消息", FeatureControlKind.ACTION),
            )),
        ),
        "P40" to listOf(
            FeatureSection("content", "设置与内容", listOf(
                FeatureControl("foreground", "前台窗口刷新率", FeatureControlKind.INFO),
                FeatureControl("supported", "最高受支持刷新率", FeatureControlKind.INFO),
                FeatureControl("background", "后台恢复窗口原刷新率", FeatureControlKind.INFO),
            )),
        ),
        "P41" to listOf(
            FeatureSection("content", "设置与内容", listOf(
                FeatureControl("touch", "触摸暂停跟随", FeatureControlKind.INFO),
                FeatureControl("bottom", "回到底部恢复跟随", FeatureControlKind.INFO),
                FeatureControl("send", "发送消息定位", FeatureControlKind.INFO),
                FeatureControl("search", "搜索命中定位", FeatureControlKind.INFO),
                FeatureControl("history", "加载更早消息保持阅读位置", FeatureControlKind.INFO),
            )),
            FeatureSection("actions", "操作", listOf(
                FeatureControl("open", "定位已加载的用户消息", FeatureControlKind.ACTION, FeatureRoute.USER_MESSAGE),
            )),
        ),
    )
    fun sections(entryId: String): List<FeatureSection> = pages.getValue(entryId)
}
