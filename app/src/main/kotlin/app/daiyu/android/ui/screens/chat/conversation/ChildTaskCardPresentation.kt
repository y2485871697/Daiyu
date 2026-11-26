package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.domain.delegation.ChildTaskRecord
import app.daiyu.android.domain.delegation.ChildTaskStatus
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

internal fun childTaskInspectionSummary(output: JSONObject): String {
    val counts = output.optJSONObject("status_counts")
    val byStatus = if (counts != null) {
        val value = output.opt("total_count") as? Number ?: return "任务状态暂不可用"
        val total = value.toLong()
        if (value.toString() != total.toString() || total !in 0L..Int.MAX_VALUE.toLong()) return "任务状态暂不可用"
        val entries = buildMap {
            for (key in counts.keys()) {
                val number = counts.opt(key) as? Number ?: return "任务状态暂不可用"
                val count = number.toLong()
                if (number.toString() != count.toString() || count !in 1L..total) return "任务状态暂不可用"
                put(key, count)
            }
        }
        if (entries.values.sum() != total) return "任务状态暂不可用"
        entries
    } else {
        val tasks = output.optJSONArray("tasks") ?: return "任务状态暂不可用"
        (0 until tasks.length()).map { index ->
            val row = tasks.optJSONObject(index) ?: return "任务状态暂不可用"
            val recorded = row.optString("status").uppercase(Locale.ROOT)
            when {
                row.opt("journal_unconfirmed") == true || row.optString("detail") == "child_result_unconfirmed" -> "UNKNOWN"
                recorded in setOf("UNKNOWN", "COMPLETED", "FAILED", "NOT_EXECUTED") -> recorded
                recorded !in setOf("RUNNING", "QUEUED") -> "UNKNOWN"
                row.opt("live_state_available") == true && row.opt("settled") == false -> when {
                    row.opt("stop_requested") == true -> "STOPPING"
                    row.optString("pause_state") == "PAUSED" -> "PAUSED"
                    row.optString("pause_state") == "PENDING" -> "PAUSING"
                    row.optString("queue_state") == "PENDING" || recorded == "QUEUED" -> "QUEUED"
                    else -> "RUNNING"
                }
                else -> "UNKNOWN"
            }
        }.groupingBy { it }.eachCount().mapValues { it.value.toLong() }
    }
    if (byStatus.isEmpty()) return "暂无任务"
    val labels = linkedMapOf("UNKNOWN" to "状态未确认", "COMPLETED" to "成功", "FAILED" to "失败",
        "NOT_EXECUTED" to "未执行", "RUNNING" to "进行中", "QUEUED" to "排队中",
        "PAUSED" to "已暂停", "PAUSING" to "暂停中", "STOPPING" to "停止中")
    return labels.mapNotNull { (status, label) ->
        val count = if (status == "UNKNOWN") byStatus.filterKeys { it == "UNKNOWN" || it !in labels }.values.sum()
            else byStatus[status] ?: 0L
        if (count > 0) "${count}个子代理•$label" else null
    }.joinToString("\n")
}

/** Configuration belongs only to the configuration-query tool, not task history. */
internal fun childTaskConfigurationSummary(output: JSONObject, clean: (String) -> String): String {
    if (output.opt("current_configuration_available") != true) return "本次未能读取可用子代理配置"
    val metadata = output.optJSONArray("available_profile_metadata")
    val names = (0 until minOf(metadata?.length() ?: 0, 32)).mapNotNull { index ->
        (metadata?.optJSONObject(index)?.opt("name") as? String)?.takeIf { it.isNotBlank() }
            ?.let { clean(it).take(40) }
    }
    val profiles = output.optJSONArray("available_profiles")
    val count = (0 until minOf(profiles?.length() ?: 0, 32)).mapNotNull { index ->
        (profiles?.opt(index) as? String)?.takeIf { it.isNotBlank() }
    }.distinct().size
    return when {
        names.isNotEmpty() -> "可用子代理：" + names.take(4).joinToString("、") + if (names.size > 4) "等${names.size}个" else ""
        count > 0 -> "${count}个可用子代理"
        metadata != null || profiles != null -> "没有可用子代理"
        else -> "本次未能读取可用子代理配置"
    }
}

/** Map only the fixed stored failure classification, never display raw provider diagnostics here. */
internal fun childTaskUnconfirmedNetworkNotice(record: ChildTaskRecord): String? =
    if (record.status == ChildTaskStatus.UNKNOWN &&
        record.detail?.lineSequence()?.firstOrNull()?.endsWith(";phase=MODEL_REQUEST;type=NETWORK") == true)
        "模型请求发生网络错误，未收到可确认的结果；未自动重派。"
    else null
