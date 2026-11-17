package app.daiyu.android.domain

import org.junit.Assert.*
import org.junit.Test

class ModelFailureDetailsTest {
    @Test fun chineseDetailsKeepRawCodesAndUsefulNetworkEvidence() {
        val detail = ModelRequestDiagnostic(429, providerDetail = ModelProviderDetail.sanitized(
            code = "quota_exceeded_123", type = "insufficient_quota", message = "Insufficient balance", requestId = "req-42"),
            transport = ModelTransportDiagnostic(ModelTransportPhase.READING_STREAM, ModelTransportCause.SOCKET,
                ModelRequestKind.RESUME, 1200, 1000, 3, 400, true, false, socketDetail = ModelSocketDetail.RESET))
        val value = modelFailureDetails(ModelFailure.RATE_LIMIT, detail)
        for (part in listOf("HTTP 429", "quota_exceeded_123", "req-42", "账户余额或可用额度不足", "恢复原请求", "读取流式回复", "连接被对端重置", "1200 毫秒", "服务商原文：Insufficient balance")) assertTrue(part, value.contains(part))
        assertFalse(value.contains("READING_STREAM")); assertFalse(value.contains("true"))
        assertTrue(modelFailureDetails(ModelFailure.NETWORK).startsWith("网络连接错误\n"))
    }
    @Test fun selectedFieldsAreBoundedAndCredentialsAreRemovedBeforeGenericRules() {
        val input = "联系 Alice https://internal.invalid/A123"
        val detail = ModelProviderDetail.sanitized(code = "E123", message = "Rejected $input Cookie: sid=AbCdEf123; token=HiddenValue", secrets = listOf(input, "AbCdEf123", "HiddenValue"))!!
        for (privateValue in listOf("Alice", "internal.invalid", "AbCdEf123", "HiddenValue")) assertFalse(detail.toString().contains(privateValue))
        assertEquals("E123", detail.code)
        assertNull(ModelProviderDetail.sanitized(message = "x".repeat(4097)))
        assertNull(ModelProviderDetail.sanitized(message = "private", secrets = listOf("")))
        assertEquals("NETWORK", ModelRequestException(ModelFailure.NETWORK).detailedMessage.substringAfter("本地错误码：").lineSequence().first())
    }
    @Test fun childDetailsUseOneBoundAcrossRunningAndFailedRecords() {
        val owner = app.daiyu.android.domain.delegation.ChildRunOwner("conversation", "run", "group", 1, "child", "message")
        val record = app.daiyu.android.domain.delegation.ChildTaskRecord(owner, "parent", "owner", 0, "profile", "task")
        val limit = app.daiyu.android.domain.delegation.ChildTaskRecord.MAX_DETAIL_CHARS
        for (length in listOf(201, limit)) assertEquals(length, record.copy(detail = "详".repeat(length)).detail!!.length)
        try { record.copy(detail = "详".repeat(limit + 1)); fail("oversized details accepted") }
        catch (_: IllegalArgumentException) {}
    }

    @Test fun deadlineRetainsLastDetailedCauseWhileKeepingItsShortNotice() {
        val cause = modelFailureDetails(ModelFailure.SERVER, ModelRequestDiagnostic(503))
        val failure = TransportReconnectDeadlineException(failureDetails = cause)
        assertTrue(failure.detailedMessage.startsWith(failure.userMessage + "\n\n"))
        assertTrue(failure.detailedMessage.contains("HTTP 503"))
    }
}
