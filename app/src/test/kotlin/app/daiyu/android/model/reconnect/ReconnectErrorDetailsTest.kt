package app.daiyu.android.model.reconnect

import app.daiyu.android.domain.*
import org.junit.Assert.*
import org.junit.Test

class ReconnectErrorDetailsTest {
    @Test fun actualHttpStatusAndKnownWireCodeAreNotReplacedByLifecycleMessages() {
        val first = reconnectErrorDetails(ModelFailure.SERVER, ModelRequestDiagnostic(502))
        val next = reconnectErrorDetails(ModelFailure.SERVER, ModelRequestDiagnostic(503))
        assertTrue(first.startsWith("SERVER · HTTP 502\n\n"))
        assertTrue(next.startsWith("SERVER · HTTP 503\n\n"))
        assertNotEquals(first, next)
        assertTrue(reconnectErrorDetails(ModelFailure.SERVER, ModelRequestDiagnostic(providerCode =
            ProviderErrorCode.OVERLOADED_ERROR)).startsWith("overloaded_error\n\n"))
        for (value in listOf(first, next)) {
            assertFalse(value.contains("继续")); assertFalse(value.contains("停止"))
            assertFalse(value.contains("未自动重试")); assertFalse(value.contains("状态："))
        }
    }
    @Test fun streamErrorWithoutHttpEvidenceNeverInventsAStatus() {
        for (reason in ModelFailure.entries) assertFalse(reconnectErrorDetails(reason).contains("HTTP "))
        assertTrue(reconnectErrorDetails(ModelFailure.AUTHENTICATION).startsWith("AUTHENTICATION\n\n"))
    }
    @Test fun finalParameterFailureKeepsTheTypedExplanationAndExactField() {
        val detail = reconnectErrorDetails(ModelFailure.REQUEST, ModelRequestDiagnostic(422,
            RequestRejection.UNSUPPORTED_VALUE, RequestField.REASONING,
            unsupportedReasoningEffort = RequestReasoningEffort.LOW,
            providerCode = ProviderErrorCode.UNSUPPORTED_VALUE))
        assertTrue(detail.startsWith("unsupported_value · HTTP 422\n\n"))
        assertTrue(detail.contains("模型不支持低思考"))
        assertTrue(detail.contains("reasoning"))
    }
    @Test fun socketDiagnosticsDoNotAppearInUserFacingDetails() {
        val trace = ModelTransportDiagnostic(ModelTransportPhase.READING_STREAM, ModelTransportCause.SOCKET,
            ModelRequestKind.POST, 100, 80, 3, 40, true, false, socketDetail = ModelSocketDetail.RESET)
        val detail = reconnectErrorDetails(ModelFailure.NETWORK, ModelRequestDiagnostic(transport = trace))
        assertEquals(reconnectErrorDetails(ModelFailure.NETWORK), detail)
        assertFalse(detail.contains("诊断"))
        assertFalse(detail.contains("READING_STREAM"))
        assertFalse(detail.contains("RESET"))
        assertFalse(detail.contains("ms"))
    }

    @Test fun transportOnlyFailuresKeepTheErrorWithoutInventingHttpOrCompletion() {
        val diagnostic = ModelRequestDiagnostic(transport = ModelTransportDiagnostic(
            ModelTransportPhase.READING_STREAM, ModelTransportCause.PREMATURE_EOF, ModelRequestKind.POST,
            130, 120, 2, 80, true, false, eofSeen = true))
        val detail = reconnectErrorDetails(ModelFailure.NETWORK, diagnostic)
        assertEquals("NETWORK\n\n模型连接中断或暂时无法建立，请检查网络与服务商状态。", detail)
        assertFalse(detail.contains("HTTP"))
        assertFalse(detail.contains("已重连"))
        assertEquals(detail, reconnectErrorDetails(ModelFailure.NETWORK, diagnostic.copy(transport =
            diagnostic.transport!!.copy(cause = ModelTransportCause.TIMEOUT, eofSeen = false))))
    }

    @Test fun transportDiagnosticsDoNotRemoveProviderStatusOrFieldEvidence() {
        val diagnostic = ModelRequestDiagnostic(422, RequestRejection.UNSUPPORTED_VALUE,
            RequestField.REASONING, unsupportedReasoningEffort = RequestReasoningEffort.LOW,
            providerCode = ProviderErrorCode.UNSUPPORTED_VALUE,
            transport = ModelTransportDiagnostic(ModelTransportPhase.READING_STREAM, ModelTransportCause.SOCKET,
                ModelRequestKind.POST, 100, 80, 3, 40, true, false))
        val detail = reconnectErrorDetails(ModelFailure.REQUEST, diagnostic)
        assertEquals(reconnectErrorDetails(ModelFailure.REQUEST, diagnostic.copy(transport = null)), detail)
        assertTrue(detail.startsWith("unsupported_value · HTTP 422\n\n"))
        assertTrue(detail.contains("模型不支持低思考"))
        assertTrue(detail.contains("reasoning"))
    }

    @Test fun storedTransportDiagnosticsAreHiddenForPostAndResumeWithoutRewritingTheError() {
        val summary = "NETWORK\n\n模型连接中断或暂时无法建立，请检查网络与服务商状态。"
        for (kind in ModelRequestKind.entries) {
            val stored = summary + "\n\n诊断：${kind.name} · READING_STREAM · SOCKET" +
                "\n请求 9850ms，流读取 26608ms，完整SSE帧 600" +
                "\n末帧距中断 321ms，响应头已收到，完成标记未确认"
            assertEquals(summary, reconnectErrorDetailsForDisplay(stored))
            assertEquals(summary, reconnectErrorDetailsForDisplay(reconnectErrorDetailsForDisplay(stored)))
        }
        assertEquals(summary, reconnectErrorDetailsForDisplay(summary))
    }

    @Test fun unrelatedDiagnosticTextIsNotRemoved() {
        for (details in listOf("", "NETWORK", "REQUEST · HTTP 400\n\n诊断：服务商提供的原始错误",
            "REQUEST\n\n诊断：POST 参数无效", "REQUEST\n\n原始诊断内容保留")) {
            assertEquals(details, reconnectErrorDetailsForDisplay(details))
        }
    }
}
