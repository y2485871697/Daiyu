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
    @Test fun transportDetailsRemainInternalWithoutRemovingProviderEvidence() {
        val trace = ModelTransportDiagnostic(ModelTransportPhase.READING_STREAM, ModelTransportCause.SOCKET,
            ModelRequestKind.RESUME, 100, 80, 3, 40, true, false, socketDetail = ModelSocketDetail.RESET)
        val detail = reconnectErrorDetails(ModelFailure.NETWORK, ModelRequestDiagnostic(transport = trace))
        assertEquals(reconnectErrorDetails(ModelFailure.NETWORK), detail)
        assertFalse(detail.contains("传输诊断")); assertFalse(detail.contains("请求耗时"))
        assertFalse(detail.contains("HTTP ")); assertFalse(detail.contains("READING_STREAM"))
        assertTrue(modelFailureDetails(ModelFailure.NETWORK, ModelRequestDiagnostic(transport = trace)).contains("连接被对端重置"))
    }
    @Test fun legacyTechnicalSuffixIsHiddenButUnrelatedProviderTextRemains() {
        val summary = "NETWORK\n\n模型连接中断\n请求 ID：req-history"
        for (kind in listOf("POST", "RESUME")) {
            val stored = summary + "\n\n诊断：$kind · READING_STREAM · SOCKET\n请求 9850ms"
            assertEquals(summary, reconnectErrorDetailsForDisplay(stored))
            assertEquals(summary + "\n\nHTTP 503", reconnectErrorDetailsForDisplay(stored + "\n\nHTTP 503"))
            assertEquals(summary, reconnectErrorDetailsForDisplay(reconnectErrorDetailsForDisplay(stored)))
        }
        for (stored in listOf("诊断：服务商提供的原始错误", "REQUEST\n\n诊断：POST 参数无效",
            "REQUEST\n\n传输诊断：服务商错误原文")) assertEquals(stored, reconnectErrorDetailsForDisplay(stored))
    }
    @Test fun chineseTechnicalSuffixIsHiddenForDisplayAndCopyWhileCodesRemain() {
        val diagnostic = ModelRequestDiagnostic(503, providerDetail = ModelProviderDetail.sanitized(
            code = "vendor_busy", message = "Server overloaded", requestId = "req-latest"),
            transport = ModelTransportDiagnostic(ModelTransportPhase.READING_STREAM, ModelTransportCause.SOCKET,
                ModelRequestKind.RESUME, 100, 80, 3, 40, true, false))
        val stored = modelFailureDetails(ModelFailure.SERVER, diagnostic)
        val displayed = reconnectErrorDetailsForDisplay(stored)
        assertEquals(reconnectErrorDetails(ModelFailure.SERVER, diagnostic), displayed)
        for (value in listOf("HTTP 503", "vendor_busy", "req-latest", "Server overloaded")) assertTrue(displayed.contains(value))
        assertFalse(displayed.contains("传输诊断")); assertFalse(displayed.contains("事件帧"))
        assertTrue(stored.contains("传输诊断"))
    }
    @Test fun typedParameterReasonAndExactFieldRemainVisible() {
        val detail = reconnectErrorDetails(ModelFailure.REQUEST, ModelRequestDiagnostic(422,
            RequestRejection.UNSUPPORTED_VALUE, RequestField.REASONING,
            unsupportedReasoningEffort = RequestReasoningEffort.LOW, providerCode = ProviderErrorCode.UNSUPPORTED_VALUE))
        assertTrue(detail.startsWith("unsupported_value · HTTP 422"))
        assertTrue(detail.contains("模型不支持低思考")); assertTrue(detail.contains("reasoning"))
    }
}
