package app.daiyu.android.ui.screens.chat.conversation

/** Select the recorded input and one readable result before composing. */
internal data class ToolBodyLayout(
    val fields: List<ToolFieldUi>,
    val inputs: List<ToolDisclosureUi>,
    val documents: List<ToolDisclosureUi>,
    val prose: ToolDisclosureUi?,
    val plainDocuments: Boolean,
)

internal fun toolBodyLayout(category: String, details: ToolCategoryDetailsUi): ToolBodyLayout {
    val rich = details.rich
    if (rich.bodyRestricted) return ToolBodyLayout(emptyList(), emptyList(), emptyList(), rich.prose, true)
    if (category in setOf("terminal", "background_job")) {
        val commands = rich.documents.filter { it.title in setOf("命令", "会话输入") }.map {
            it.copy(title = if (it.title == "命令") "执行命令" else "终端输入")
        }
        val outputs = rich.documents.filter {
            it.title in setOf("标准输出", "标准错误", "已收到输出", "任务日志", "守护日志", "终端输出")
        }
        val output = outputs.takeIf { it.isNotEmpty() }?.let {
            ToolDisclosureUi("终端输出", it.filter { doc -> doc.text.isNotEmpty() }.joinToString("\n") { doc -> doc.text }, true)
        }
        val prose = rich.prose?.takeIf { output == null || rich.failed }
        val targetFields = details.fields.filter {
            it.label in setOf("执行环境", "Android 宿主权限")
        }.distinct()
        return ToolBodyLayout(targetFields, emptyList(), commands + listOfNotNull(output), prose, false)
    }
    val inputTitles = when (category) {
        "memory", "personal_search" -> setOf("检索条件")
        "skills" -> setOf("技能查询", "资源")
        "files" -> setOf("路径", "范围", "来源", "目标", "授权文件URI", "授权目录URI")
        "browser" -> setOf("搜索条件", "页面 · 已去除认证和查询参数")
        "apps" -> setOf("应用查询", "外部链接 · 不自动打开")
        "generation" -> setOf("提示摘要", "文本摘要")
        "speech" -> setOf("文本摘要")
        "context" -> setOf("历史查询")
        "delegation", "child_control" -> setOf("任务说明", "指导内容")
        else -> emptySet()
    }
    val labels = when (category) {
        "memory", "personal_search", "files", "workspace", "browser", "apps", "delegation", "child_control", "image_read", "speech", "gui" -> setOf("列表范围")
        "skills" -> setOf("技能", "资源", "技能数", "列表范围")
        "generation" -> setOf("生成阶段", "生成进度", "列表范围")
        "mcp" -> setOf("原工具", "列表范围")
        "device_read", "system_action", "context", "user_question" -> emptySet()
        else -> (rich.fields + details.fields).map { it.label }.toSet()
    }
    val allInputTitles = setOf("检索条件", "技能查询", "资源", "路径", "范围", "来源", "目标", "匹配",
        "授权文件URI", "授权目录URI", "替换前", "替换后", "新名称", "工作目录", "命令", "会话输入",
        "搜索条件", "页面 · 已去除认证和查询参数", "目标元素", "下载目标", "外部链接 · 不自动打开", "应用查询",
        "提示摘要", "文本摘要", "历史查询", "压缩范围", "保留内容", "读取来源", "视觉问题", "任务说明", "指导内容")
    val inputs = rich.documents.filter { it.title in inputTitles }
    var documents = rich.documents.filterNot { it.title in allInputTitles }
    var prose = rich.prose
    if (category in setOf("device_read", "system_action")) {
        documents = documents.filterNot { it.text == prose?.text }
        if (category == "system_action") documents = emptyList()
    }
    prose?.let { current -> if (documents.any { it.text == current.text }) prose = null }
    if (category == "context" && documents.isEmpty() && rich.entries.isEmpty() &&
        prose?.text == "已收到回执") {
        val reported = (rich.fields + details.fields).filter {
            it.label in setOf("请求数", "已报告输入", "已报告输出", "已报告缓存", "窗口占用", "来源")
        }.distinct()
        if (reported.isNotEmpty()) prose = ToolDisclosureUi("", reported.joinToString("\n") { "${it.label}：${it.value}" })
    }
    return ToolBodyLayout((rich.fields + details.fields).filter { it.label in labels }.distinct(), inputs,
        documents, prose, category in setOf("memory", "skills", "workspace", "browser", "image_read", "gui", "apps",
            "device_read", "system_action", "personal_search", "generation", "speech", "mcp", "context", "user_question", "generic") || category !in ToolCardPresentations.categories)
}
