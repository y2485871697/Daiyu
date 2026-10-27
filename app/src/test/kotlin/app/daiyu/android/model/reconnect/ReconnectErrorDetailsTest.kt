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
}
