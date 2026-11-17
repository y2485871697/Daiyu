package app.daiyu.android.model.reconnect

import app.daiyu.android.domain.*
import org.junit.Assert.*
import org.junit.Test

class ReconnectErrorDetailsTest {
    @Test fun statusCodesAndProviderFieldsSurviveChineseFormatting() {
        val detail = reconnectErrorDetails(ModelFailure.SERVER, ModelRequestDiagnostic(503,
            providerDetail = ModelProviderDetail.sanitized(code = "vendor_overloaded", message = "Server overloaded", requestId = "req-123")))
        for (part in listOf("HTTP 503", "vendor_overloaded", "req-123", "服务商暂时过载", "服务商原文：Server overloaded")) assertTrue(part, detail.contains(part))
    }
    @Test fun transportDetailsAreChineseAndNeverInventHttpStatus() {
        val trace = ModelTransportDiagnostic(ModelTransportPhase.READING_STREAM, ModelTransportCause.SOCKET,
            ModelRequestKind.RESUME, 100, 80, 3, 40, true, false, socketDetail = ModelSocketDetail.RESET)
        val detail = reconnectErrorDetails(ModelFailure.NETWORK, ModelRequestDiagnostic(transport = trace))
        assertTrue(detail.startsWith("网络连接错误")); assertTrue(detail.contains("连接被对端重置"))
        assertTrue(detail.contains("恢复原请求")); assertTrue(detail.contains("读取流式回复"))
        assertFalse(detail.contains("HTTP ")); assertFalse(detail.contains("READING_STREAM"))
    }
    @Test fun storedDetailsAndLaterErrorsAreNeverSilentlyTruncated() {
        val stored = "NETWORK\n\n诊断：POST · READING_STREAM · SOCKET\n请求 9850ms\n\nHTTP 503"
        assertEquals(stored, reconnectErrorDetailsForDisplay(stored))
        assertEquals(stored, reconnectErrorDetailsForDisplay(reconnectErrorDetailsForDisplay(stored)))
    }
}
