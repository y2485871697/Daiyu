package app.daiyu.android.ui.screens.chat.conversation

import org.json.JSONObject
import java.util.Locale

internal fun childTaskStatusText(status: String): String = when (status) {
    "执行中" -> "进行中"
    "结果未知" -> "状态未确认"
    else -> status
}

internal fun childTaskProgressText(result: String?, progress: String?): String? =
    result?.takeIf { it.isNotBlank() } ?: progress?.takeIf { it.isNotBlank() }

/** Recorded task snapshots are read-only; tool success is not a task status. */
internal fun childTaskReceiptEntries(output: JSONObject?, clean: (String) -> String): List<ToolRichEntryUi> {
    fun text(row: JSONObject, key: String) = (row.opt(key) as? String)?.takeIf { it.isNotBlank() }
    fun entry(row: JSONObject): ToolRichEntryUi {
        val recorded = row.optString("status").uppercase(Locale.ROOT)
        val observed = row.opt("live_state_available") == true && row.opt("settled") == false
        val status = when {
            row.opt("journal_unconfirmed") == true || row.optString("detail") == "child_result_unconfirmed" -> "状态未确认"
            recorded == "UNKNOWN" -> "状态未确认"
            recorded == "COMPLETED" -> "已完成"
            recorded == "FAILED" -> "失败"
            recorded == "NOT_EXECUTED" -> "未执行"
            observed && row.opt("stop_requested") == true -> "停止中"
            observed && row.optString("pause_state") == "PAUSED" -> "已暂停"
            observed && row.optString("pause_state") == "PENDING" -> "暂停中"
            observed && (row.optString("queue_state") == "PENDING" || recorded == "QUEUED") -> "排队中"
            observed && recorded == "RUNNING" -> "进行中"
            else -> "状态未确认"
        }
        val progress = childTaskProgressText(text(row, "text") ?: text(row, "result"),
            text(row, "progress") ?: text(row, "partial_result") ?: text(row, "partial") ?: text(row, "checkpoint"))
            ?: when (row.optString("outcome")) {
                "RESUMED" -> "已提交继续任务的请求"
                "PAUSED", "PENDING" -> "已提交暂停任务的请求"
                "REQUESTED" -> "已提交停止任务的请求"
                else -> null
            }
        return ToolRichEntryUi(clean(text(row, "agent_name") ?: "子代理").take(80), listOf(ToolFieldUi("状态", status)),
            listOfNotNull(progress?.let { ToolDisclosureUi("任务进度", clean(it)) }))
    }
    if (output == null) return emptyList()
    val rows = output.optJSONArray("tasks") ?: output.optJSONArray("results")
    if (rows != null) return (0 until minOf(rows.length(), 32)).mapNotNull { rows.optJSONObject(it)?.let(::entry) }
    return if (listOf("child_run_id", "status", "text", "result", "progress", "partial_result", "partial", "checkpoint").any(output::has)) listOf(entry(output)) else emptyList()
}

/** Configuration describes this inspection, never tasks created after it. */
internal fun childTaskInspectionSummary(output: JSONObject, clean: (String) -> String): String {
    val tasks = output.optJSONArray("tasks") ?: return "暂无任务记录"
    if (tasks.length() != 0 || output.opt("current_configuration_available") !is Boolean) return "暂无任务记录"
    if (output.opt("current_configuration_available") != true) return "本次未能读取可用子代理配置\n暂无任务记录"
    val metadata = output.optJSONArray("available_profile_metadata")
    val names = (0 until minOf(metadata?.length() ?: 0, 32)).mapNotNull { index ->
        (metadata?.optJSONObject(index)?.opt("name") as? String)?.takeIf { it.isNotBlank() }
            ?.let { clean(it).take(40) }
    }
    val profiles = output.optJSONArray("available_profiles")
    val count = (0 until minOf(profiles?.length() ?: 0, 32)).mapNotNull { index ->
        (profiles?.opt(index) as? String)?.takeIf { it.isNotBlank() }
    }.distinct().size
    val configuration = when {
        names.isNotEmpty() -> "可用子代理：" + names.take(4).joinToString("、") + if (names.size > 4) "等${names.size}个" else ""
        count > 0 -> "有${count}个可用子代理"
        metadata != null || profiles != null -> "没有可用子代理"
        else -> return "暂无任务记录"
    }
    return "本次查看时${configuration}\n暂无已派发任务记录"
}
