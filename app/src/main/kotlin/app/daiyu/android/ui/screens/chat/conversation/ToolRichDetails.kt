package app.daiyu.android.ui.screens.chat.conversation

import java.net.URI
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** Display documents are already scrubbed; they are never part of default summary/copy/logging. */
data class ToolDisclosureUi(val title: String, val text: String, val code: Boolean = false) {
    override fun toString(): String = "ToolDisclosureUi(title=$title, content=<hidden>)"
}
data class ToolRichEntryUi(val title: String, val fields: List<ToolFieldUi>, val documents: List<ToolDisclosureUi> = emptyList(), val group: String = "") {
    override fun toString(): String = "ToolRichEntryUi(fields=${fields.size}, documents=${documents.size})"
}
data class ToolRichDetailsUi(
    val fields: List<ToolFieldUi> = emptyList(),
    val entries: List<ToolRichEntryUi> = emptyList(),
    val documents: List<ToolDisclosureUi> = emptyList(),
    val source: String = "本次调用记录 · 非实时监控",
    val operation: String = "调用详情",
    val technical: List<ToolDisclosureUi> = emptyList(),
    val prose: ToolDisclosureUi? = null,
    val bodyRestricted: Boolean = false,
)

/** A bounded, read-only projection. No URI resolving, asset loading or execution lives here. */
internal object ToolRichDetails {
    private const val MAX_INPUT = 131_072
    private const val MAX_TEXT = 16_384
    private val secretKey = Regex("(?i)(password|passwd|pwd|secret|token|authorization|cookie|credential|api.?key|private.?key|access.?key|verification.?code|auth|sensitive|data_base64|验证码)")
    private val privateBlock = Regex("-----BEGIN [^-]*PRIVATE KEY-----[\\s\\S]*?(?:-----END [^-]*PRIVATE KEY-----|$)")
    private val bearer = Regex("(?i)\\b(?:bearer|basic)\\s+[A-Za-z0-9+/_=.-]+")
    private val assignment = Regex("(?i)([\\\"']?(?:password|passwd|pwd|secret|token|api[-_]?key|access[-_]?token|authorization|cookie|set-cookie)[\\\"']?\\s*[:=]\\s*)(?:\\\"[^\\\"]*\\\"|'[^']*'|[^\\s,;}]+)")
    private val flags = Regex("(?i)(--(?:password|passwd|token|api[-_]?key|secret)(?:=|\\s+))[^\\s]+")
    private val url = Regex("https?://[^\\s<>\\\"']+")
    private val knownToken = Regex("\\b(?:sk-[A-Za-z0-9_-]{8,}|gh[pousr]_[A-Za-z0-9_]{12,}|eyJ[A-Za-z0-9_-]{12,}\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+)\\b")

    fun scrub(value: String, hidden: Set<String> = emptySet(), limit: Int = MAX_TEXT): String {
        var text = value.take(MAX_INPUT).replace(privateBlock, "[私钥已隐藏]")
        hidden.filter { it.isNotEmpty() }.sortedByDescending { it.length }.forEach { text = text.replace(it, "[已隐藏]") }
        text = text.replace(bearer, "[认证值已隐藏]").replace(knownToken, "[令牌已隐藏]")
            .replace(assignment) { it.groupValues[1] + "[已隐藏]" }.replace(flags) { it.groupValues[1] + "[已隐藏]" }
            .replace(url) { match -> safeUrl(match.value) }
            .filter { it == '\n' || it == '\t' || !it.isISOControl() }
        return if (text.length <= limit) text else text.take(limit) + "\n…内容已截断"
    }

    private fun safeUrl(value: String): String = runCatching {
        val uri = URI(value)
        if (uri.host == null) "[链接已隐藏]" else URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null).toASCIIString()
    }.getOrDefault("[链接已隐藏]")

    fun project(category: String, toolName: String, arguments: String, result: String?): ToolRichDetailsUi {
        val args = json(arguments)
        val encodedScreenshot = toolName == "screen" && args?.optString("operation") == "screenshot"
        val envelope = if (encodedScreenshot) null else json(result)
        val output = if (category in setOf("apps", "files", "personal_search", "device_read", "system_action"))
            envelope?.optJSONObject("data") ?: envelope else envelope
        val cookies = category == "browser" && (args?.optString("action") in setOf("get_cookies", "set_cookies"))
        val hidden = linkedSetOf<String>()
        fun secrets(value: Any?, depth: Int = 0, sensitive: Boolean = false) {
            if (depth > 8) return
            when (value) {
                is JSONObject -> value.keys().asSequence().take(128).forEach { key ->
                    val item = value.opt(key)
                    val cookieMetadata = key == "cookies" && item is JSONArray
                    secrets(item, depth + 1, sensitive || (!cookieMetadata && secretKey.containsMatchIn(key)))
                }
                is JSONArray -> (0 until minOf(value.length(), 48)).forEach { secrets(value.opt(it), depth + 1, sensitive) }
                is String -> if (sensitive && value.isNotBlank()) hidden += value
                is Number -> if (sensitive) hidden += value.toString()
            }
        }
        secrets(args); secrets(envelope)
        if (category == "gui") (args?.opt("text") as? String)?.takeIf { it.isNotBlank() }?.let { hidden += it }
        fun clean(value: Any?, max: Int = 512): String? = when (value) {
            null, JSONObject.NULL -> null
            is String -> value.takeIf { it.isNotBlank() }?.let { scrub(it, hidden, max) }
            is Number -> scrub(value.toString(), hidden, max)
            is Boolean -> value.toString()
            else -> null
        }
        var outputDocument = false
        val fields = mutableListOf<ToolFieldUi>()
        val returnedFields = mutableListOf<ToolFieldUi>()
        val docs = mutableListOf<ToolDisclosureUi>()
        val entries = mutableListOf<ToolRichEntryUi>()
        fun field(source: JSONObject?, key: String, label: String) {
            clean(source?.opt(key))?.let {
                val value = ToolFieldUi(label, it)
                fields += value
                if (source !== args) returnedFields += value
            }
        }
        fun document(source: JSONObject?, key: String, title: String, code: Boolean = false) {
            val value = source?.opt(key) as? String ?: return
            if (value.isNotBlank()) {
                if (source !== args) outputDocument = true
                val text = scrub(value, hidden)
                val aliases = when (title) {
                    "技能说明" -> setOf("技能说明")
                    "文件内容", "读取内容" -> setOf("文件内容", "读取内容")
                    "网页正文", "页面文本", "页面内容" -> setOf("网页正文", "页面文本", "页面内容")
                    "标准输出", "已收到输出" -> setOf("标准输出", "已收到输出")
                    "任务日志", "守护日志" -> setOf("任务日志", "守护日志")
                    "已保留片段" -> setOf("已保留片段")
                    else -> setOf(title)
                }
                if (docs.none { it.title in aliases && it.text == text }) docs += ToolDisclosureUi(title, text, code)
            }
        }
        fun list(source: JSONObject?, key: String, title: String, columns: List<Pair<String, String>>, private: Boolean = false) {
            val rows = source?.optJSONArray(key) ?: return
            repeat(minOf(rows.length(), 32)) { index ->
                val row = rows.opt(index)
                if (row is JSONObject) {
                    val cells = columns.mapNotNull { (name, label) -> clean(row.opt(name))?.let { ToolFieldUi(label, it) } }
                    val documents = if (title == "Cookie属性") emptyList() else listOf("text" to "已记录正文", "content" to "内容", "description" to "说明", "result" to "已读取结果", "partial_result" to "已保留片段", "partial" to "已保留片段", "snippet" to "命中片段", "output" to "已读取输出")
                        .mapNotNull { (name, label) -> (row.opt(name) as? String)?.takeIf { it.isNotBlank() }
                            ?.let { ToolDisclosureUi(label, scrub(it, hidden)) } }
                    val heading = if (private) null else clean(if (title == "Cookie属性") row.opt("name") else row.opt("name") ?: row.opt("title"), 100)
                    entries += ToolRichEntryUi(heading ?: "$title ${index + 1}",
                        cells.filterNot { it.label in setOf("名称", "标题") && it.value == heading },
                        documents.distinctBy { it.text }.filterNot { doc -> cells.any { it.value == doc.text } }, title)
                } else if (title != "Cookie属性") clean(row)?.let { entries += ToolRichEntryUi("$title ${index + 1}", emptyList(), listOf(ToolDisclosureUi("条目", it)), title) }
            }
            if (rows.length() > 32) fields += ToolFieldUi("列表范围", "显示前32项 / 共${rows.length()}项")
        }
        fun fields(source: JSONObject?, vararg columns: Pair<String, String>) = columns.forEach { (key, label) -> field(source, key, label) }
        fun documents(source: JSONObject?, vararg columns: Pair<String, String>) = columns.forEach { (key, label) -> document(source, key, label) }
        val operation = operationLabel(category, toolName, args?.optString("action"))
        val identityColumns = listOf("id" to "标识", "name" to "名称", "title" to "标题")
        when (category) {
            "memory" -> {
                fields(args, "id" to "条目", "revision" to "修订", "expectedRevision" to "预期修订")
                field(output, "revision", "修订")
                fields(output, "assistant_id" to "助手", "offset" to "读取偏移", "next_offset" to "下次偏移", "truncated" to "已截断")
                list(output, "hits", "记忆命中", listOf("offset" to "偏移"), private = true)
                document(args, "query", "检索条件"); document(output, "text", "记忆正文"); document(output, "content", "记忆内容")
                list(output, "matches", "记忆命中", identityColumns + listOf("revision" to "修订"), private = true)
                list(output, "results", "记忆命中", identityColumns, private = true)
            }
            "skills" -> {
                document(args, "query", "技能查询")
                field(args, "skill_id", "技能"); field(args, "skillId", "技能"); field(args, "relative_path", "资源"); field(args, "relativePath", "资源")
                fields(args, "source" to "来源", "repository" to "仓库", "ref" to "固定引用", "commit" to "固定提交")
                documents(args, "path" to "技能路径", "resource" to "技能资源")
                fields(output, "source" to "来源", "repository" to "仓库", "commitSha" to "固定提交")
                list(output, "compatibility", "兼容检查", listOf("name" to "条件", "status" to "状态", "required" to "必需"))
                list(output, "capabilities", "技能能力", listOf("name" to "能力", "supported" to "支持"))
                field(output, "id", "技能"); field(output, "name", "名称"); field(output, "revision", "固定版本")
                list(output, "skills", "技能", identityColumns + listOf("source" to "来源", "enabled" to "已启用"))
                document(output, "bodyMarkdown", "技能说明"); document(output, "body", "技能说明"); document(output, "content", "资源内容", true)
            }
            "terminal", "background_job" -> {
                field(args, "environment", "环境"); field(args, "identity", "身份")
                listOf("session_id" to "会话", "job_id" to "任务", "task_id" to "守护任务", "mode" to "模式").forEach { (key, label) -> field(output, key, label); field(args, key, label) }
                fields(args, "offset" to "读取偏移", "offset_chars" to "字符偏移", "max_chars" to "字符上限", "max_lines" to "行数上限", "tail" to "日志尾行数", "restart_policy" to "重启策略")
                document(output, "log", "任务日志", true); document(output, "logs", "守护日志", true)
                field(output, "duration_ms", "运行时长毫秒"); field(output, "last_event", "最近事件")
                document(args, "cwd", "工作目录", true); document(args, "command", "命令", true); document(args, "text", "会话输入", true)
                document(output, "stdout", "标准输出", true); document(output, "stderr", "标准错误", true); document(output, "output", "已收到输出", true)
                field(output, "command_completion", "命令完成确认"); field(output, "exit_code", "退出码")
            }
            "files", "workspace" -> {
                listOf("uri" to "授权文件URI", "tree_uri" to "授权目录URI", "path" to "路径", "root" to "范围", "source" to "来源", "destination" to "目标", "pattern" to "匹配").forEach { (key, label) -> document(args, key, label, true) }
                fields(args, "offset_bytes" to "字节偏移", "max_bytes" to "字节上限", "offset" to "读取偏移", "max_chars" to "字符上限", "expected_matches" to "预期匹配数", "recursive" to "递归", "overwrite" to "覆盖")
                documents(args, "old_text" to "替换前", "new_text" to "替换后", "new_name" to "新名称")
                fields(output, "task_id" to "关联任务", "changed_files" to "变更文件数", "conflicts" to "冲突数")
                list(output, "checks", "工作区检查", listOf("name" to "检查", "status" to "状态"))
                list(output, "changes", "逐文件差异", listOf("path" to "文件", "added" to "新增行", "removed" to "删除行"))
                field(args, "workspace_id", "工作区"); field(output, "workspace_id", "工作区")
                fields(output, "name" to "文件名", "mime_type" to "格式", "size_bytes" to "字节", "scope" to "读取范围")
                list(output, "items", "文件条目", identityColumns + listOf("mime_type" to "格式", "size_bytes" to "字节", "modified_ms" to "修改时间"))
                list(output, "entries", "目录条目", identityColumns + listOf("type" to "类型", "bytes" to "字节", "size" to "大小"))
                list(output, "files", "文件", identityColumns + listOf("type" to "类型", "size" to "大小"))
                document(output, "text", "文件内容", true); document(output, "content", "读取内容", true); document(output, "diff", "差异", true)
            }
            "browser" -> {
                field(args, "action", "动作"); field(args, "tab_id", "标签")
                document(args, "url", "页面 · 已去除认证和查询参数"); document(args, "selector", "目标元素", true)
                if (!cookies) field(output, "title", "页面标题"); document(output, "url", "返回页面 · 已去除认证和查询参数")
                documents(args, "query" to "搜索条件", "destination" to "下载目标")
                list(output, "results", "网页命中", listOf("title" to "标题", "url" to "页面", "source" to "来源"))
                list(output, "downloads", "下载回执", listOf("name" to "文件", "bytes" to "字节", "status" to "状态"))
                // Values and bodies remain suppressed for Cookie operations.
                if (args?.optString("action") in setOf("get_cookies", "set_cookies")) {
                    list(output, "cookies", "Cookie属性", listOf("name" to "名称", "domain" to "域", "path" to "范围", "secure" to "仅安全连接", "httpOnly" to "仅HTTP", "sameSite" to "同站策略"))
                }
                list(output, "tabs", "标签", identityColumns + listOf("tab_id" to "标签"))
                list(output, "elements", "元素", identityColumns + listOf("tag" to "标签类型", "selector" to "选择器", "role" to "角色"))
                document(output, "markdown", "网页正文"); document(output, "text", "页面文本"); document(output, "content", "页面内容")
            }
            "gui" -> {
                if (encodedScreenshot) {
                    outputDocument = true
                }
                field(args, "operation", "动作"); field(args, "observation_id", "观察"); field(args, "index", "目标节点"); field(args, "node_id", "目标节点")
                fields(args, "button" to "按键", "direction" to "浏览方向", "coordinate_space" to "坐标系", "x" to "X", "y" to "Y", "duration_ms" to "持续毫秒", "timeout_ms" to "等待上限毫秒")
                if (args?.has("text") == true) fields += ToolFieldUi("输入文本", "已提供 · 不在默认详情回显")
                field(output, "observation_id", "观察快照"); field(output, "package_name", "应用")
                list(output, "ui_nodes", "观察节点", listOf("index" to "序号", "class" to "类型", "clickable" to "可点击", "enabled" to "可用"))
                list(output, "nodes", "节点", listOf("id" to "序号", "parent" to "父节点", "class_name" to "类型", "text" to "文字", "description" to "描述", "view_id" to "控件", "clickable" to "可点击", "enabled" to "可用", "bounds" to "范围"))
            }
            "apps" -> {
                document(args, "uri", "外部链接 · 不自动打开"); field(args, "include_system", "包含系统应用")
                document(args, "query", "应用查询"); field(args, "package_name", "包名")
                list(output, "items", "匹配应用", listOf("label" to "应用", "package" to "包名", "enabled" to "可用"))
                list(output, "apps", "应用", listOf("label" to "应用", "name" to "名称", "package" to "包名", "package_name" to "包名", "enabled" to "可用"))
            }
            "personal_search" -> {
                fields(args, "max_age_hours" to "回溯小时", "max_age_minutes" to "回溯分钟", "days" to "天数", "limit" to "数量上限", "package_name" to "来源应用")
                fields(args, "start_ms" to "起始时间", "end_ms" to "截止时间", "kind" to "媒体类型", "package" to "来源应用")
                document(args, "query", "检索条件")
                fields(output, "scope" to "读取范围", "limit" to "返回上限", "truncated" to "已截断", "history_available" to "历史可用")
                listOf("events", "contacts", "calls", "messages", "notifications", "orders", "media", "files", "recordings", "results", "items").forEach { key ->
                    list(output, key, "个人资料命中", listOf("type" to "类型", "timestamp" to "时间", "date" to "日期", "duration_seconds" to "时长秒", "status" to "状态", "start_ms" to "开始时间", "end_ms" to "结束时间", "posted_at" to "发布时间", "display_name" to "显示名称", "mime_type" to "格式", "size_bytes" to "字节"), private = true)
                }
            }
            "device_read" -> {
                fields(args, "namespace" to "命名空间", "key" to "设置键", "limit" to "读取上限")
                field(envelope, "observed_at", "快照时间")
                fields(output, "local_iso" to "本地时间", "epoch_ms" to "时间戳", "api_level" to "API", "android_release" to "Android版本", "manufacturer" to "厂商", "model" to "设备型号", "locale" to "区域", "power_save" to "省电模式", "interactive" to "屏幕交互", "active_network" to "存在网络", "metered" to "计费网络", "music_active" to "正在播放", "volume_fixed" to "固定音量")
                list(output, "transports", "网络类型", emptyList())
                val streams = output?.optJSONObject("streams")
                streams?.keys()?.asSequence()?.take(8)?.forEach { name ->
                    val stream = streams.optJSONObject(name) ?: return@forEach
                    entries += ToolRichEntryUi(clean(name, 80) ?: "音频通道", listOfNotNull(
                        clean(stream.opt("level"))?.let { ToolFieldUi("当前音量", it) },
                        clean(stream.opt("minimum"))?.let { ToolFieldUi("最小音量", it) },
                        clean(stream.opt("maximum"))?.let { ToolFieldUi("最大音量", it) }), group = "音频通道")
                }
                fields(output, "temperature" to "温度", "accuracy" to "位置精度米", "age_ms" to "位置年龄毫秒", "total_bytes" to "总字节", "available_bytes" to "可用字节", "used_bytes" to "已用字节", "memory_total" to "总内存", "memory_available" to "可用内存", "volume" to "音量", "do_not_disturb" to "勿扰", "ringer_mode" to "铃声模式")
                list(output, "processes", "进程快照", listOf("name" to "进程", "rss_bytes" to "RSS字节", "package_name" to "应用"))
                list(output, "permissions", "权限状态", listOf("name" to "权限", "permission" to "权限", "granted" to "已授予"))
                fields(output, "percent" to "电量百分比", "internet_capability" to "可访问互联网", "density_dpi" to "屏幕密度", "orientation" to "屏幕方向", "uptime_ms" to "开机时长毫秒")
                val battery = output?.optJSONObject("battery")?.takeIf { it.opt("ok") == true }
                fields(battery, "percent" to "电量百分比", "charging" to "充电")
                val android = output?.optJSONObject("android")
                fields(android, "api_level" to "API", "release" to "Android版本")
                for ((key, prefix) in listOf("memory" to "内存", "storage" to "存储")) {
                    val sample = output?.optJSONObject(key) ?: continue
                    if (sample.opt("ok") == false) fields += ToolFieldUi(prefix, "本次采样不可用")
                    else fields(sample, "total_bytes" to "${prefix}总字节", "available_bytes" to "${prefix}可用字节", "low_memory" to "低内存")
                }
                for ((key, label) in listOf("wifi" to "Wi-Fi", "bluetooth" to "蓝牙")) {
                    val sample = output?.optJSONObject(key) ?: continue
                    val enabled = sample.optJSONObject("data")?.opt("enabled") as? Boolean
                    fields += ToolFieldUi(label, if (sample.opt("ok") == true && enabled != null) { if (enabled) "已启用" else "已关闭" } else "本次采样不可用")
                }
                listOf("charging" to "充电", "battery_percent" to "电量百分比", "network_type" to "网络", "validated" to "网络已验证", "locked" to "已锁屏", "timezone" to "时区", "datetime" to "快照时间", "android_version" to "Android版本", "sdk" to "API").forEach { (key, label) -> field(output, key, label) }
            }
            "system_action" -> {
                listOf("stream" to "通道", "target" to "目标", "action" to "动作", "enabled" to "请求启用", "percent" to "请求百分比", "hour" to "请求小时", "minute" to "请求分钟").forEach { (key, label) -> field(args, key, label) }
                fields(args, "duration_seconds" to "计时秒数", "panel" to "系统面板", "namespace" to "命名空间", "key" to "设置键", "session" to "媒体会话")
                document(args, "label", "标签"); document(args, "repeat_days", "重复计划")
                if (args?.has("text") == true) fields += ToolFieldUi("文本长度", args.optString("text").length.toString())
                field(output, "enabled", "返回启用值"); field(output, "percent", "返回百分比")
                fields(output, "stream" to "返回通道", "index" to "返回音量", "read_back" to "回读确认")
            }
            "generation", "image_read", "speech" -> {
                if (category == "image_read") {
                    documents(args, "path" to "读取来源", "question" to "视觉问题"); field(args, "frame", "请求画面")
                    fields(output, "frame" to "封面帧", "duration_ms" to "视频时长毫秒")
                    documents(output, "summary" to "视觉摘要", "text" to "已记录视觉返回")
                }
                if (category == "speech") {
                    fields(args, "rate" to "语速", "timestamps" to "时间戳", "speaker_labels" to "说话人标签")
                    documents(output, "transcript" to "识别文本", "partial" to "增量听写", "final" to "听写终稿")
                    list(output, "segments", "转写段落", listOf("start" to "开始秒", "end" to "结束秒", "speaker" to "说话人"))
                }
                val job = output?.optJSONObject("job")
                fields(job, "id" to "生成任务", "state" to "生成阶段", "progress" to "生成进度")
                list(job, "artifacts", "已登记产物", listOf("id" to "产物", "mime" to "格式", "width" to "宽度", "height" to "高度"))
                fields(args, "duration_seconds" to "请求时长秒", "job_id" to "生成任务", "artifact_id" to "目标产物")
                listOf("model" to "模型", "size" to "尺寸", "language" to "语言", "voice" to "声音", "format" to "格式").forEach { (key, label) -> field(args, key, label) }
                document(args, "prompt", "提示摘要"); document(args, "text", "文本摘要")
                list(output, "artifacts", "产物记录", listOf("id" to "标识", "mime" to "格式", "width" to "宽度", "height" to "高度"))
            }
            "delegation", "child_control" -> {
                listOf("task_id" to "目标任务", "group_id" to "组", "kind" to "控制动作").forEach { (key, label) -> field(args, key, label) }
                documents(args, "guidance" to "指导内容", "instruction" to "指导内容", "task" to "任务说明")
                list(output, "results", "已读取子任务结果", listOf("child_run_id" to "子任务", "profile_id" to "代理", "status" to "返回状态"))
                list(output, "tasks", "工具返回任务记录", listOf("id" to "标识", "child_run_id" to "子任务", "task_id" to "任务", "profile_id" to "代理", "model" to "模型", "role" to "职责", "state" to "返回状态", "status" to "返回状态", "result_id" to "结果标识"))
                documents(output, "partial_result" to "已保留片段", "partial" to "已保留片段", "result" to "已读取结果", "checkpoint" to "检查点摘要")
                // Live children are rendered by the host, never promoted from provider JSON.
            }
            "mcp" -> {
                fields(args, "connection_id" to "连接", "transport" to "传输类型", "previous_revision" to "先前目录修订")
                fields(output, "revision" to "目录修订", "connection_id" to "连接", "transport" to "传输类型")
                field(args, "tool", "原工具"); field(args, "name", "原工具"); field(args, "server_id", "服务")
                list(output, "tools", "动态工具", identityColumns)
            }
            "context" -> {
                documents(args, "query" to "历史查询", "range" to "压缩范围", "preserve" to "保留内容")
                fields(args, "session_id" to "会话", "run_id" to "运行", "after_event_id" to "事件起点")
                documents(output, "summary" to "公开压缩摘要", "text" to "公开历史正文")
                list(output, "matches", "公开历史命中", listOf("id" to "记录", "source" to "来源", "timestamp" to "时间"))
                field(args, "scope", "范围"); field(output, "source", "来源"); field(output, "window_tokens", "窗口占用")
                list(output, "requests", "已记录请求", listOf("source" to "来源", "input_tokens" to "已报告输入", "output_tokens" to "已报告输出"))
            }
        }
        // Raw payloads are optional, explicit disclosure only, and always independently scrubbed.
        // get_cookies/set_cookies deliberately have no body disclosure, even on explicit expansion.
        if (!cookies && category != "user_question") {
            documents(output, "message" to "操作回执", "error" to "错误回执")
            if (envelope !== output) documents(envelope, "message" to "操作回执", "error" to "错误回执")
            if (category !in setOf("delegation", "child_control")) document(output, "result", "操作回执")
        }
        if (!cookies && category != "user_question") {
            if (!outputDocument && entries.isEmpty() && result != null && result.isNotBlank() &&
                (output == null || category !in ToolCardPresentations.categories || category == "generic")) {
                val cleaned = if (output == null) scrub(result, hidden) else scrub(sanitizedJson(envelope ?: output, 0).toString(2), hidden)
                docs += ToolDisclosureUi(if (category in setOf("terminal", "background_job")) "操作回执" else "已记录返回", cleaned, category in setOf("files", "workspace", "mcp", "generic"))
            }
        }
        val receiptStates = listOfNotNull(envelope, envelope?.optJSONObject("data")).distinct()
        val privacyRestricted = receiptStates.any {
            it.opt("privacy_redacted") == true || it.optString("status") in setOf("private_result_withheld", "private_result_consent_required")
        }
        val failureStatus = receiptStates.map { it.optString("status") }.firstOrNull {
            it in setOf("permission_denied", "unsupported", "unavailable", "no_cached_location")
        }
        val failed = privacyRestricted || failureStatus != null || receiptStates.any { it.opt("ok") == false }
        val failure = if (privacyRestricted) "结果未获展示授权" else when (failureStatus) {
            "permission_denied" -> "缺少本次操作所需权限"
            "no_cached_location" -> "没有可用的最近位置缓存"
            "unsupported" -> "设备不支持本次操作"
            "unavailable" -> "本次读取不可用"
            else -> "本次调用未完成，详情见技术详情"
        }
        val technical = if (cookies || encodedScreenshot || category == "user_question") emptyList() else buildList {
            if (arguments.isNotBlank()) add(ToolDisclosureUi("调用参数", if (args == null) scrub(arguments, hidden) else scrub(sanitizedJson(args, 0).toString(2), hidden), true))
            if (!result.isNullOrBlank()) add(ToolDisclosureUi("调用回执", if (privacyRestricted) failure else if (output == null) scrub(result, hidden) else scrub(sanitizedJson(envelope ?: output, 0).toString(2), hidden), true))
        }
        val compact = category in setOf("device_read", "system_action", "terminal", "background_job")
        val receipt = docs.firstOrNull { it.title in setOf("操作回执", "错误回执") }?.text
        val prose = if (compact || failed || (!outputDocument && entries.isEmpty() && docs.none { it.title == "已记录返回" })) {
            val time = clean(output?.opt("local_iso"))
            val latitude = (output?.opt("latitude") as? Number)?.takeIf { it.toDouble().isFinite() && it.toDouble() in -90.0..90.0 }?.let { clean(it) }
            val longitude = (output?.opt("longitude") as? Number)?.takeIf { it.toDouble().isFinite() && it.toDouble() in -180.0..180.0 }?.let { clean(it) }
            fun readable(field: ToolFieldUi): String = "${field.label}：" + when (field.value) { "true" -> "是"; "false" -> "否"; else -> field.value }
            val body = when {
                result.isNullOrBlank() -> "等待执行回执"
                failed -> failure
                receipt != null -> receipt
                category == "device_read" && time != null -> formatDeviceTime(time, clean(output?.opt("timezone")))
                category == "device_read" && latitude != null && longitude != null -> {
                    val metadata = listOfNotNull(
                        (output?.opt("accuracy_m") as? Number)?.takeIf { it.toDouble().isFinite() && it.toDouble() >= 0 }?.let { "精度 ${clean(it)}m" },
                        (output?.opt("age_ms") as? Number)?.takeIf { it.toDouble().isFinite() && it.toDouble() >= 0 }?.let {
                            val seconds = it.toLong() / 1000
                            if (seconds >= 60) "${seconds / 60} 分钟前" else "$seconds 秒前"
                        }).joinToString(" · ")
                    listOf("$latitude, $longitude", metadata).filter { it.isNotBlank() }.joinToString("\n")
                }
                output == null -> scrub(result, hidden)
                category == "system_action" -> returnedFields.distinct().joinToString("\n", transform = ::readable)
                    .ifBlank { if (output.opt("accepted") == true) "请求已受理，结果未确认" else "已收到系统回执，详情见技术详情" }
                category in setOf("terminal", "background_job") -> "已收到运行回执，未返回输出"
                category == "device_read" -> fields.filterNot { it.label in setOf("命名空间", "设置键", "读取上限", "快照时间", "时间戳", "本地时间", "位置年龄毫秒") }
                    .distinct().joinToString("\n", transform = ::readable).ifBlank {
                        if (entries.isNotEmpty() || outputDocument) "" else "已收到设备快照，详情见技术详情"
                    }
                else -> "已收到回执，详情见技术详情"
            }
            body.takeIf { it.isNotBlank() }?.let { ToolDisclosureUi("", it) }
        } else null
        val source = when (category) {
            "context" -> "上下文与用量记录 · 未报告字段不按0处理"
            "delegation", "child_control" -> "工具回执与子任务生命周期分别记录"
            "personal_search" -> "个人资料按需展开 · 不进入安全摘要"
            "generation" -> "生成受理与产物登记分别记录 · 不自动重试"
            else -> "本次调用记录 · 非实时监控"
        }
        val personal = category == "personal_search"
        fun privateText(text: String): String = if (personal) maskPersonal(text) else text
        if (privacyRestricted) return ToolRichDetailsUi(source = source, operation = operation,
            technical = technical.map { it.copy(text = privateText(it.text)) },
            prose = ToolDisclosureUi("", failure), bodyRestricted = true)
        return ToolRichDetailsUi((if (cookies) fields.filter { it.label in setOf("动作", "标签") } else fields).distinct().map { it.copy(value = privateText(it.value)) },
            (if (cookies) entries.filter { it.group == "Cookie属性" } else entries).map { entry -> entry.copy(fields = entry.fields.map { it.copy(value = privateText(it.value)) },
                documents = entry.documents.map { it.copy(text = privateText(it.text)) }) },
            (if (cookies) emptyList() else docs).distinctBy { it.title to it.text }.map { it.copy(text = privateText(it.text)) }, source, operation,
            technical.map { it.copy(text = privateText(it.text)) }, prose?.let { it.copy(text = privateText(it.text)) }, failed)
    }

    private fun formatDeviceTime(value: String, timezone: String?): String {
        val parsed = runCatching { OffsetDateTime.parse(value) }.getOrNull()
        val date = parsed?.format(DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss", Locale.ROOT)) ?: value
        val offset = parsed?.offset?.id?.let { if (it == "Z") "UTC" else "UTC$it" }
        return listOf(date, listOfNotNull(timezone, offset).joinToString(" · ")).filter { it.isNotBlank() }.joinToString("\n")
    }

    private fun maskPersonal(value: String): String = value
        .replace(Regex("""(?<![0-9])\+?[0-9](?:[ ()-]*[0-9]){9,14}(?![0-9])""")) { "[号码已隐藏]" }
        .replace(Regex("""(?i)(["']?(?:验证码|verification[ _-]?code|otp|code)["']?\s*[:=：]\s*["']?)[A-Za-z0-9]{4,8}""")) { it.groupValues[1] + "[已隐藏]" }
        .replace(Regex("""(?:/data/(?:data|user)/|/storage/)[^\s"']*(?:cache|Cache)[^\s"']*"""), "[私有缓存路径已隐藏]")

    private fun operationLabel(category: String, toolName: String, action: String?): String {
        val name = when (category) {
            "memory" -> "记忆检索与修订"; "skills" -> "技能来源与资源"; "delegation" -> "任务派发与结果"; "child_control" -> "任务控制回执"
            "workspace" -> "工作区检查与差异"; "terminal" -> "命令与会话输出"; "background_job" -> "后台任务与日志"
            "files" -> "文件范围与内容"; "image_read" -> "视觉读取范围"; "browser" -> "页面、元素与下载"; "gui" -> "观察与操作回执"
            "apps" -> "应用匹配与操作"; "device_read" -> "设备读取快照"; "personal_search" -> "个人资料检索"
            "system_action" -> "系统动作配置"; "generation" -> "生成任务与产物登记"; "speech" -> "语音文本与转写"
            "mcp" -> "外部服务与动态工具"; "context" -> "公开上下文与用量"; "user_question" -> "提问记录"; else -> "未分类工具"
        }
        val identity = listOfNotNull(toolName.takeIf { it.matches(Regex("[A-Za-z][A-Za-z0-9_.-]{0,79}")) },
            action?.takeIf { it.matches(Regex("[a-z_]{1,40}")) }).joinToString(" · ")
        return if (identity.isBlank()) name else "$name · $identity"
    }

    private fun sanitizedJson(source: JSONObject, depth: Int): JSONObject {
        if (depth > 6) return JSONObject().put("内容", "层级已截断")
        val result = JSONObject()
        source.keys().asSequence().take(96).forEach { key ->
            val value = source.opt(key)
            result.put(key, when {
                secretKey.containsMatchIn(key) -> "[已隐藏]"
                value is JSONObject -> sanitizedJson(value, depth + 1)
                value is JSONArray -> JSONArray().apply { repeat(minOf(value.length(), 32)) { index ->
                    val row = value.opt(index)
                    put(if (row is JSONObject) sanitizedJson(row, depth + 1) else if (row is String) scrub(row) else if (row is Number || row is Boolean) row else "[结构已省略]")
                } }
                value is String -> scrub(value)
                else -> value
            })
        }
        return result
    }
    private fun json(value: String?): JSONObject? = value?.takeIf { it.length <= MAX_INPUT && it.trimStart().startsWith('{') }
        ?.let { runCatching { JSONObject(it) }.getOrNull() }
}
