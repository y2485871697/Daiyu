package app.daiyu.android.ui.screens.terminal

import org.json.JSONObject
import java.util.Base64

internal fun terminalTaskOutput(raw: String): String {
    if (raw.isBlank()) return ""
    val value = runCatching { JSONObject(raw) }.getOrNull() ?: return raw
    val stdout = (value.opt("stdout") as? String)?.takeIf { it.isNotEmpty() }
    val encoded = (value.opt("stdout_base64") as? String)?.takeIf { it.isNotEmpty() }
    val decoded = if (stdout == null && encoded != null) runCatching {
        String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
    }.getOrNull() else null
    val error = (value.opt("error") as? String)?.takeIf { it.isNotBlank() }
    val status = when {
        value.opt("stopped") == false -> "任务尚未确认停止"
        value.opt("ok") == false && error == null -> "任务操作未完成，结果未确认"
        value.opt("history_read_only") == true -> "历史任务状态未知"
        value.optString("execution") == "unknown" -> "任务操作结果未确认"
        else -> null
    }
    val output = stdout ?: decoded
    if (output == null && error == null && status == "历史任务状态未知") return "暂无日志 · 历史任务状态未知"
    return listOfNotNull(output, error, status).distinct().joinToString("\n").ifBlank {
        if (encoded != null) "任务日志解码失败" else "已收到任务回执，未返回日志"
    }
}
