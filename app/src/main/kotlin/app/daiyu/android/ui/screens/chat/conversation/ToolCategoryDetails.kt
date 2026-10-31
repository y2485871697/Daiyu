package app.daiyu.android.ui.screens.chat.conversation

import org.json.JSONObject

/** Only allowlisted counts, booleans and closed enums cross this display boundary. */
data class ToolFieldUi(val label: String, val value: String)
data class ToolItemUi(val title: String, val fields: List<ToolFieldUi>)
data class ToolCategoryDetailsUi(
    val heading: String,
    val fields: List<ToolFieldUi> = emptyList(),
    val items: List<ToolItemUi> = emptyList(),
    val notice: String,
    val rich: ToolRichDetailsUi = ToolRichDetailsUi(),
)

internal object ToolCategoryDetails {
    private val states = mapOf("queued" to "排队中", "pending" to "待派发", "running" to "执行中",
        "starting" to "启动中", "completed" to "已完成", "succeeded" to "成功", "failed" to "失败",
        "not_executed" to "未执行", "unknown" to "结果未确认", "paused" to "已暂停", "pausing" to "暂停中",
        "stopping" to "停止中", "stopped" to "已停止", "exited" to "已退出", "closed" to "已关闭",
        "unknown_after_process_restart" to "重启后状态未确认")
    private val labels = mapOf("count" to "数量", "total" to "总数", "bytes" to "字节数", "size" to "大小",
        "offset" to "偏移", "next_offset" to "下次偏移", "total_bytes" to "总字节数", "max_bytes" to "字节上限",
        "max_chars" to "字符上限", "limit" to "数量上限", "width" to "宽度", "height" to "高度",
        "duration_ms" to "时长毫秒", "exit_code" to "退出码", "wait_status" to "原始等待状态",
        "tab_id" to "标签", "frameCount" to "帧数", "hour" to "小时", "minute" to "分钟",
        "seconds" to "计时秒数", "index" to "音量档位", "duration_seconds" to "计时秒数", "percent" to "百分比", "max_age_hours" to "回溯小时",
        "level" to "电量百分比", "capacity" to "容量", "used" to "已使用", "available" to "可用",
        "request_count" to "请求数", "input_tokens" to "已报告输入", "output_tokens" to "已报告输出",
        "cached_tokens" to "已报告缓存", "expires_after_ms" to "有效毫秒", "revision" to "修订")
    private val flags = mapOf("accepted" to "已接纳", "completed" to "任务已完成", "current_configuration_available" to "当前委派配置可用", "settled" to "子任务已结束", "new_request" to "新请求",
        "pty" to "伪终端", "persistent_shell" to "持久会话", "opened" to "会话已打开", "has_more" to "还有输出",
        "log_truncated" to "日志已截断", "output_complete" to "输出已读完", "log_available" to "日志可读取",
        "leader_exited" to "主进程已退出", "scope_drain_confirmed" to "进程组排空已确认",
        "verified_ui_result" to "界面结果已核实", "truncated" to "内容已截断", "enabled" to "已启用",
        "ownerTransportReady" to "宿主通道就绪", "displayInitialized" to "显示已初始化", "hasFrame" to "已有画面",
        "sourceEmpty" to "画面源为空")

    fun project(category: String, arguments: String, result: String?, toolName: String = "", terminalTarget: TerminalToolTarget? = null): ToolCategoryDetailsUi {
        val args = parse(arguments)
        val launchingDelegation = category == "delegation" && toolName in setOf("run_child_models", "start_child_tasks")
        val envelope = parse(result)
        val output = if (category in setOf("apps", "files", "personal_search", "device_read", "system_action"))
            envelope?.optJSONObject("data") ?: envelope else envelope
        val fields = mutableListOf<ToolFieldUi>()
        fun numeric(source: JSONObject?, vararg keys: String) { keys.forEach { key ->
            val number = source?.opt(key) as? Number ?: return@forEach
            val value = number.toLong()
            if (number.toString() == value.toString() && value in -1_000_000_000L..1_000_000_000L)
                fields += ToolFieldUi(labels.getValue(key), value.toString())
        } }
        fun boolean(source: JSONObject?, vararg keys: String) { keys.forEach { key ->
            (source?.opt(key) as? Boolean)?.let { fields += ToolFieldUi(flags.getValue(key), if (it) "是" else "否") }
        } }
        fun count(source: JSONObject?, key: String, label: String) {
            source?.optJSONArray(key)?.let { fields += ToolFieldUi(label, it.length().toString()) }
        }
        fun state(source: JSONObject?) { states[(source?.opt("state") as? String)?.lowercase()
            ?: (source?.opt("status") as? String)?.lowercase()]?.let { fields += ToolFieldUi("返回状态", it) } }
        val items = mutableListOf<ToolItemUi>()
        val (heading, notice) = when (category) {
            "memory" -> {
                numeric(args, "limit", "offset"); numeric(output, "count", "total", "revision"); count(output, "matches", "命中数")
                "记忆条目" to "命中、修订与脱敏正文分别展示；查看不修改记忆。"
            }
            "skills" -> {
                numeric(args, "max_bytes", "offset", "limit"); numeric(output, "count", "bytes"); count(output, "skills", "技能数")
                "技能与资源" to "技能说明与兼容性以实际返回为准；阅读卡片不会安装或运行技能。"
            }
            "delegation", "child_control" -> {
                count(args, "tasks", "请求任务数")
                if (!launchingDelegation) {
                    count(output, "tasks", "返回任务数"); count(output, "results", "返回结果数"); count(output, "available_profiles", "可用配置数")
                    boolean(output, "accepted", "completed", "new_request", "current_configuration_available", "settled"); state(output)
                }
                val tasks = if (launchingDelegation) null else (output?.optJSONArray("tasks") ?: output?.optJSONArray("results"))
                if (tasks != null) for (index in 0 until minOf(tasks.length(), 16)) {
                    val task = tasks.optJSONObject(index) ?: continue
                    val taskState = states[(task.opt("status") as? String)?.lowercase() ?: (task.opt("state") as? String)?.lowercase()]
                    items += ToolItemUi("子任务 ${index + 1}", listOf(ToolFieldUi("记录状态", taskState ?: "未提供")))
                }
                "子任务记录" to "委派回执不是生命周期终态；实时控制使用本会话子任务记录页。"
            }
            "terminal", "background_job" -> {
                (terminalTarget ?: TerminalToolTarget.fromResult(toolName, result, output))?.let { target ->
                    fields += ToolFieldUi("执行环境", target.label)
                    target.hostIdentity?.let { fields += ToolFieldUi("Android 宿主权限", it) }
                }
                numeric(output, "exit_code", "wait_status", "offset", "next_offset", "total_bytes")
                boolean(output, "pty", "persistent_shell", "opened", "has_more", "log_truncated", "output_complete", "log_available", "leader_exited", "scope_drain_confirmed")
                state(output)
                "运行与输出" to "命令、环境变量与原始日志可能含凭据；仅按需展示脱敏命令与输出，不把退出或读取成功推断成业务完成。"
            }
            "files", "workspace" -> {
                numeric(args, "max_bytes", "offset", "limit"); numeric(output, "bytes", "size", "count", "total", "offset")
                count(output, "entries", "目录条目数"); count(output, "files", "文件数")
                "文件与范围" to "读取范围、条目与脱敏内容分别展示；展开、复制不会写入文件或合并工作区。"
            }
            "browser" -> {
                numeric(args, "tab_id", "max_chars", "offset"); count(output, "tabs", "标签页数"); count(output, "elements", "元素数")
                numeric(output, "width", "height", "bytes"); boolean(output, "truncated", "has_more")
                "网页观察" to "页面正文按需展开，认证与 URL 查询参数已脱敏，Cookie 值不展示；历史页面信息不授予操作权。"
            }
            "gui" -> {
                count(output, "nodes", "节点数"); numeric(output, "width", "height", "expires_after_ms", "frameCount", "bytes")
                boolean(output, "verified_ui_result", "truncated", "ownerTransportReady", "displayInitialized", "hasFrame", "sourceEmpty")
                "屏幕与动作回执" to "派发动作不等于业务目标完成；过期观察与其他 owner 的记录不能重新执行。"
            }
            "apps" -> { count(output, "apps", "应用数"); numeric(output, "count", "total"); "应用结果" to "应用匹配来自本次快照，不自动打开应用或链接。" }
            "device_read" -> {
                numeric(output, "level", "percent", "capacity", "used", "available", "count"); boolean(output, "enabled")
                "设备快照" to "这是本次调用返回的快照，不是持续监控或实时设备状态。"
            }
            "personal_search" -> {
                numeric(args, "limit", "max_age_hours"); numeric(output, "count", "total"); count(output, "items", "条目数"); count(output, "results", "命中数")
                "检索范围与命中" to "个人条目按需展开，号码与验证码保持隐藏；没有结果不等于没有个人活动。"
            }
            "system_action" -> {
                numeric(args, "hour", "minute", "seconds", "index", "duration_seconds", "percent"); boolean(args, "enabled"); boolean(output, "accepted", "enabled")
                "请求与系统回执" to "请求值不代表系统确认值；结果未确认时不显示重试或重复执行入口。"
            }
            "generation", "image_read" -> {
                numeric(output, "count", "width", "height", "duration_ms", "bytes"); count(output, "artifacts", "产物数"); state(output)
                "媒体与产物" to "仅使用宿主校验过的媒体附件；不会从返回 URL 自动加载或重新发起付费生成。"
            }
            "speech" -> { numeric(output, "duration_ms", "bytes"); state(output); "语音记录" to "播放、识别与合成状态以宿主回执为准；本卡不重新合成或上传音频。" }
            "mcp" -> { numeric(output, "count", "total"); state(output); "外部工具返回" to "保留原始工具标识；外部返回、参数与连接凭据不直接进入默认展示。" }
            "context" -> {
                numeric(output, "request_count", "input_tokens", "output_tokens", "cached_tokens"); state(output)
                "上下文事件" to "用量仅统计已报告字段；累计用量、窗口占用与压缩范围分别记录。"
            }
            "user_question" -> "交互问答" to "专用问题投影不可用；保留只读调用记录，不从历史恢复回答权限。"
            else -> "工具记录" to "未识别的工具使用只读安全回退；不根据返回文本猜测执行成功。"
        }
        if (result == null) fields += ToolFieldUi("返回内容", "尚未收到")
        return ToolCategoryDetailsUi(heading, fields.distinct(), items, notice, ToolRichDetails.project(category, toolName, arguments, result))
    }
    private fun parse(value: String?): JSONObject? = if (value == null || value.length > 65_536 || !value.trimStart().startsWith('{')) null
        else runCatching { JSONObject(value) }.getOrNull()
}
