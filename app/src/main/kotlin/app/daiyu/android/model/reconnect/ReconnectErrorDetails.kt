package app.daiyu.android.model.reconnect

import app.daiyu.android.domain.ModelFailure
import app.daiyu.android.domain.ModelRequestDiagnostic

internal fun reconnectErrorDetails(reason: ModelFailure, diagnostic: ModelRequestDiagnostic? = null): String =
    app.daiyu.android.domain.modelFailureDetails(reason, diagnostic?.copy(transport = null))

private val storedTransportDiagnostic = Regex(
    "\n\n(?:诊断：(POST|RESUME) · [A-Z_]+ · [A-Z_]+\n请求 |" +
        "传输诊断：(发送请求|恢复原请求) · (准备请求|发送中|等待响应头|读取流式回复|解析回复)\n网络原因：)")

internal fun reconnectErrorDetailsForDisplay(details: String): String {
    var displayed = details
    while (true) {
        val match = storedTransportDiagnostic.find(displayed) ?: return displayed
        val end = displayed.indexOf("\n\n", match.range.last + 1).takeIf { it >= 0 } ?: displayed.length
        displayed = displayed.removeRange(match.range.first, end)
    }
}
