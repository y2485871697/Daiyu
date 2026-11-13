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
