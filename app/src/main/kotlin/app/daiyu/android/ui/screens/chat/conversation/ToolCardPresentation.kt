package app.daiyu.android.ui.screens.chat.conversation

import org.json.JSONObject

/** Display taxonomy, not a registry or an execution permission. */
internal object ToolCardPresentations {
    val categories = setOf("memory", "skills", "delegation", "child_control", "workspace", "terminal", "background_job", "files", "image_read", "browser", "gui", "apps", "device_read", "personal_search", "system_action", "generation", "speech", "mcp", "context", "user_question", "generic")
    data class Presentation(val category: String, val title: String, val summary: String, val sections: List<ToolDetailSectionUi>, val details: ToolCategoryDetailsUi, val targetLabel: String? = null)
    private val tools = buildMap {
        fun group(category: String, vararg entries: Pair<String, String>) { entries.forEach { (name, title) -> put(name, category to title) } }
        group("memory", "assistant_memory_search" to "检索记忆", "assistant_memory_read" to "读取记忆")
        group("skills", "skills_list" to "查找技能", "skills_read" to "读取技能说明", "skills_read_resource" to "读取技能资源")
        group("delegation", "run_child_models" to "批量委派", "start_child_tasks" to "后台委派")
        group("child_control", "direct_child_task" to "指导子任务", "inspect_child_tasks" to "查看子任务",
            "get_child_task" to "读取子任务结果", "control_child_task" to "控制子任务")
        group("terminal", "terminal_exec" to "执行命令", "terminal_read" to "读取命令输出", "terminal_pty_step" to "执行终端步骤", "terminal_pty_read" to "读取输出", "shizuku_execute" to "执行命令")
        group("background_job", "terminal_job_start" to "启动后台任务", "terminal_job_read" to "读取后台结果")
        group("files", "list_files" to "列目录", "file_metadata" to "读取文件信息", "read_text_file" to "读取文件")
        group("browser", "browser_read" to "读取网页", "browser_write" to "操作网页")
        group("gui", "screen" to "查看屏幕", "screen_action" to "操作屏幕", "r08_virtual_display_step" to "执行虚拟显示步骤", "r08_virtual_display_read" to "读取虚拟显示状态")
        group("apps", "search_apps" to "查找应用")
        group("device_read", "get_battery_status" to "查询电池", "device_status" to "查询设备状态", "network_status" to "查询网络", "device_time" to "查询时间", "device_audio" to "查询音频环境", "device_environment" to "查询设备环境", "platform_permissions" to "查询权限", "last_known_location" to "查询位置")
        group("personal_search", "search_media" to "检索媒体元数据", "calendar_events" to "检索日历", "search_contacts" to "检索联系人", "app_notifications" to "读取本应用通知", "current_notifications" to "检索通知", "notification_history" to "检索通知历史")
        group("system_action", "platform_set_alarm" to "设置闹钟", "platform_set_timer" to "设置计时器", "platform_set_volume" to "调整音量", "platform_media_key" to "控制媒体", "platform_set_wifi" to "切换 Wi-Fi")
        group("generation", "generate_images" to "生成图片", "generate_videos" to "生成视频")
        group("context", "conversation_reference_read" to "读取会话引用", "context_archive_page" to "读取历史档案", "context_usage" to "查询上下文")
        group("user_question", "ask_user" to "向用户提问")
    }
    private val browserActions = mapOf("list_tabs" to "查看标签页", "get_readable" to "读取正文", "get_text" to "读取正文", "find_elements" to "查找元素", "get_page_info" to "读取页面信息", "get_backbone" to "读取页面结构", "screenshot" to "网页截图", "new_tab" to "新建标签页", "close_tab" to "关闭标签页", "navigate" to "打开页面", "click" to "网页点击", "type" to "网页输入", "hover" to "悬停网页元素", "scroll" to "网页滚动", "execute_js" to "执行网页脚本", "fetch" to "获取网页资源", "get_cookies" to "读取 Cookie", "set_cookies" to "设置 Cookie", "scroll_and_collect" to "滚动收集网页内容", "set_user_agent" to "设置浏览器标识", "set_viewport" to "设置网页视口", "wait_for_selector" to "等待网页元素", "wait_for_dom_stable" to "等待页面稳定", "reload" to "刷新网页", "go_back" to "网页后退", "go_forward" to "网页前进")
    private val guiActions = mapOf("observe" to "查看屏幕", "screenshot" to "手机截图", "tap" to "点击", "long_press" to "长按", "scroll_forward" to "滚动", "scroll_backward" to "滚动", "input" to "输入", "back" to "系统按键", "home" to "系统按键", "recents" to "系统按键", "notifications" to "系统按键")

    fun present(toolName: String, arguments: String, result: String?): Presentation {
        val args = parse(arguments)
        var kind = tools[toolName] ?: if (isMcpToolName(toolName)) "mcp" to "调用 MCP 工具" else "generic" to "执行其他工具"
        val branch = when (toolName) {
            "browser_read", "browser_write" -> browserActions[args?.optString("action")]
            "screen", "screen_action" -> guiActions[args?.optString("operation")]
            "search_media" -> if (args?.optString("kind") == "audio") "检索音乐" else "检索相册"
            "control_child_task" -> mapOf("continue" to "继续子任务", "pause" to "暂停子任务", "stop" to "停止子任务")[args?.optString("action")]
            "direct_child_task" -> mapOf("APPEND" to "追加子任务", "REPLACE" to "替换子任务", "REDISPATCH" to "失败后重新委派")[args?.optString("kind")]
            else -> null
        }
        if (toolName == "search_media") kind = kind.first to requireNotNull(branch)
        if (toolName in setOf("browser_read", "browser_write", "screen", "screen_action", "direct_child_task", "control_child_task")) {
            kind = if (branch == null) "generic" to "执行其他工具" else kind.first to branch
        }
        // Free-form strings (commands, URLs, paths, errors, prompts, opaque MCP data) are not echoed.
        val paramKeys = when (kind.first) {
            "browser" -> listOf("tab_id", "max_chars", "offset", "amount")
            "files", "skills", "memory" -> listOf("max_bytes", "offset", "limit")
            "personal_search", "apps" -> listOf("limit", "max_age_hours")
            "system_action" -> listOf("hour", "minute", "seconds", "index", "duration_seconds", "percent")
            else -> emptyList()
        }
        val params = listOf(numbers(args, paramKeys), when (toolName) {
            "run_child_models", "start_child_tasks" -> arrayCount(args, "tasks", "任务数")
            else -> ""
        }).filter { it.isNotBlank() }.joinToString(" · ")
        val output = parse(result)
        val returned = when (toolName) {
            "terminal_exec", "terminal_read" -> listOf(numbers(output, listOf("exit_code", "offset", "next_offset", "total_bytes")),
                flags(output, listOf("has_more", "log_truncated", "output_complete", "log_available")),
                choice(output, "state", terminalStates))
            "terminal_pty_step" -> listOf(flags(output, listOf("pty", "persistent_shell", "opened")),
                choice(output, "command_completion", mapOf("unknown" to "命令完成未确认")))
            "terminal_pty_read" -> listOf(numbers(output, listOf("offset", "next_offset", "total_bytes", "wait_status")),
                flags(output, listOf("has_more", "log_truncated", "leader_exited", "scope_drain_confirmed")),
                choice(output, "state", terminalStates))
            "terminal_job_read" -> listOf(numbers(output, listOf("exit_code", "offset", "next_offset", "total_bytes")),
                flags(output, listOf("has_more", "log_truncated", "output_complete", "log_available")),
                choice(output, "state", terminalStates), choice(output, "mode", mapOf("async" to "异步任务", "daemon" to "守护任务")))
            "run_child_models" -> listOf(arrayCount(output, "results", "返回任务数"))
            "start_child_tasks" -> listOf(arrayCount(output, "tasks", "入队任务数"), flags(output, listOf("accepted", "completed")))
            "inspect_child_tasks" -> listOf(arrayCount(output, "tasks", "返回任务数"), flags(output, listOf("current_configuration_available")))
            "get_child_task", "control_child_task" -> listOf(flags(output, listOf("new_request", "settled")))
            "direct_child_task" -> listOf(flags(output, listOf("new_request")))
            "screen" -> listOf(arrayCount(output, "nodes", "节点数"), numbers(output, listOf("width", "height", "expires_after_ms")), flags(output, listOf("truncated")))
            "screen_action" -> listOf(flags(output, listOf("verified_ui_result")))
            "r08_virtual_display_step", "r08_virtual_display_read" -> listOf(numbers(output, listOf("width", "height", "frameCount", "bytes")),
                flags(output, listOf("ownerTransportReady", "displayInitialized", "hasFrame", "sourceEmpty")))
            else -> listOf(numbers(output, when (kind.first) {
                "files", "skills" -> listOf("bytes", "size", "count", "total", "offset")
                "personal_search", "apps", "memory" -> listOf("count", "total")
                "generation" -> listOf("count", "width", "height", "duration_ms")
                else -> emptyList()
            }))
        }.filter { it.isNotBlank() }.joinToString(" · ")
        val summary = when {
            kind.first == "delegation" -> params.ifBlank { "任务分工" }
            kind.first == "personal_search" -> "仅显示检索范围与数量，私密正文已隐藏"
            params.isNotBlank() -> params
            result == null -> "等待执行回执"
            else -> "已收到返回内容，状态以执行回执为准"
        }
        val target = if (kind.first in setOf("terminal", "background_job")) TerminalToolTarget.fromResult(toolName, result, output) else null
        val details = ToolCategoryDetails.project(kind.first, arguments, result, toolName, target)
        val sections = buildList {
            if (kind.first == "delegation") {
                if (params.isNotBlank()) add(ToolDetailSectionUi("任务", params))
                details.rich.prose?.let { add(ToolDetailSectionUi("结果", it.text)) }
                return@buildList
            }
            if (arguments.isNotBlank()) add(ToolDetailSectionUi("参数摘要", params.ifBlank { "自由文本、标识与敏感字段不在卡片中展开" }))
            add(ToolDetailSectionUi(if (kind.first in setOf("terminal", "background_job")) "输出摘要" else "结果摘要", returned.ifBlank { if (result == null) "尚无返回内容" else "已保存返回内容；原始正文不在卡片中展开" }))
            if (kind.first == "gui") add(ToolDetailSectionUi("操作回执", "动作已派发不等于业务目标已完成；历史观察不授予执行权"))
        }
        val operationTitle = if (kind.first == "generic" && toolName.matches(Regex("[A-Za-z][A-Za-z0-9_.-]{0,63}"))) {
            "执行其他工具 · $toolName"
        } else kind.second
        val title = target?.let { "$operationTitle · ${it.label}" } ?: operationTitle
        return Presentation(kind.first, title, summary, sections, details, target?.label)
    }
    private fun arrayCount(value: JSONObject?, key: String, label: String): String =
        value?.optJSONArray(key)?.let { "$label：${it.length()}" }.orEmpty()

    private fun flags(value: JSONObject?, keys: List<String>): String = keys.mapNotNull { key ->
        val flag = value?.opt(key) as? Boolean ?: return@mapNotNull null
        "${labels.getValue(key)}：${if (flag) "是" else "否"}"
    }.joinToString(" · ")

    private fun choice(value: JSONObject?, key: String, options: Map<String, String>): String =
        options[value?.opt(key) as? String].orEmpty()

    private val terminalStates = mapOf("starting" to "启动中", "running" to "进程运行中", "closed" to "会话已关闭",
        "exited" to "进程已退出", "unknown" to "进程状态未确认", "pending" to "排队中", "dispatching" to "派发中",
        "dispatched" to "已派发", "start_failed" to "启动失败", "cancelled_before_dispatch" to "派发前取消",
        "unknown_after_process_restart" to "进程重启后状态未确认")

    private fun parse(source: String?): JSONObject? {
        if (source == null || source.length > 65_536 || !source.trimStart().startsWith('{')) return null
        return runCatching { JSONObject(source) }.getOrNull()
    }
    private fun numbers(value: JSONObject?, keys: List<String>): String = keys.mapNotNull { key ->
        val number = value?.opt(key) as? Number ?: return@mapNotNull null
        val long = number.toLong()
        if (number.toString() != long.toString() || long !in -1_000_000_000L..1_000_000_000L) return@mapNotNull null
        "${labels[key] ?: key}：$long"
    }.joinToString(" · ")
    private val labels = mapOf("total_bytes" to "总字节数", "wait_status" to "原始等待状态", "frameCount" to "帧数", "expires_after_ms" to "观察有效毫秒",
        "pty" to "伪终端", "persistent_shell" to "持久会话", "opened" to "会话已打开", "has_more" to "还有输出", "log_truncated" to "日志已截断",
        "leader_exited" to "主进程已退出", "scope_drain_confirmed" to "进程组排空已确认", "output_complete" to "输出已读完", "log_available" to "日志可读取",
        "accepted" to "已接纳", "completed" to "子任务已完成", "current_configuration_available" to "当前委派配置可用", "settled" to "子任务已结束", "new_request" to "新请求", "truncated" to "节点已截断", "verified_ui_result" to "界面结果已核实",
        "ownerTransportReady" to "宿主通道就绪", "displayInitialized" to "显示已初始化", "hasFrame" to "已有画面", "sourceEmpty" to "画面源为空","tab_id" to "标签", "max_chars" to "字符上限", "max_bytes" to "字节上限", "offset" to "偏移", "amount" to "滚动量", "limit" to "数量上限", "max_age_hours" to "回溯小时", "hour" to "小时", "minute" to "分钟", "seconds" to "计时秒数", "index" to "音量档位", "duration_seconds" to "计时秒数", "percent" to "百分比", "exit_code" to "退出码", "bytes" to "字节数", "next_offset" to "下次偏移", "size" to "大小", "count" to "数量", "total" to "总数", "width" to "宽度", "height" to "高度", "duration_ms" to "时长毫秒")
}
