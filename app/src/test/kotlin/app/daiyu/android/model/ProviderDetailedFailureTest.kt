package app.daiyu.android.model

import app.daiyu.android.domain.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.OutputStream

class ProviderDetailedFailureTest {
    private class Connection(val status: Int, val body: String) : ModelHttpConnection {
        override var requestMethod = "POST"
        override var doOutput = true
        override var instanceFollowRedirects = false
        override var connectTimeout = 0
        override var readTimeout = 0
        override val contentType = "application/json"
        override val inputStream get() = ByteArrayInputStream(body.toByteArray())
        override val errorStream get() = inputStream
        override val diagnosticRedactions = providerHeaderRedactions("Cookie", "sid=AbCdEf123; csrf=ZyX654321") + "bare-key-123"
        override fun responseHeader(name: String) = if (name == "x-request-id") "req-456" else null
        override fun setRequestProperty(name: String, value: String) {}
        override fun send(size: Long, write: (OutputStream) -> Unit) {}
        override fun responseCode(onRequestSent: () -> Unit): Int { onRequestSent(); return status }
        override fun disconnect() {}
    }
    @Test fun allHttpErrorStatusesKeepUnknownProviderCodesAndRequestIdWithoutSecrets() {
        for (status in listOf(400, 401, 403, 404, 413, 422, 429, 500, 503)) {
            val conn = Connection(status, """{"error":{"code":"vendor_901","type":"billing_error","message":"Insufficient balance ZyX654321 bare-key-123"}}""")
            val error = ProviderHttpFailure.from(conn, status, { false })
            assertEquals(status, error.diagnostic!!.httpStatus)
            assertEquals("vendor_901", error.diagnostic!!.providerDetail!!.code)
            assertEquals("req-456", error.diagnostic!!.providerDetail!!.requestId)
            assertTrue(error.detailedMessage.contains("余额"))
            assertFalse(error.detailedMessage.contains("ZyX654321")); assertFalse(error.detailedMessage.contains("bare-key-123"))
        }
    }
    @Test fun scalarErrorsAndGeminiStatusAreVisibleWithoutInventingSseHttpStatus() {
        val scalar = ProviderHttpFailure.from(Connection(402, """{"error":"Balance depleted"}"""), 402, { false })
        assertEquals("Balance depleted", scalar.diagnostic!!.providerDetail!!.message)
        val gemini = ProviderHttpFailure.event(JSONObject("""{"code":429,"status":"RESOURCE_EXHAUSTED","message":"Quota exceeded"}"""))
        assertEquals(ModelFailure.RATE_LIMIT, gemini.failure)
        assertEquals("429", gemini.diagnostic!!.providerDetail!!.code)
        assertEquals("RESOURCE_EXHAUSTED", gemini.diagnostic!!.providerDetail!!.type)
        assertNull(gemini.diagnostic!!.httpStatus)
    }
    @Test fun sseRedactsRawInputBeforeUrlAndCredentialNormalizationAcrossThreeDecoders() {
        val input = "联系 Alice https://internal.invalid/A123"
        val error = JSONObject().put("code", "vendor_reject").put("message", "Rejected $input 林小姐 ZyX654321")
        val frames = listOf(
            ProviderProtocol.RESPONSES to JSONObject().put("type", "error").put("error", error),
            ProviderProtocol.MESSAGES to JSONObject().put("type", "error").put("error", JSONObject(error.toString()).put("type", "invalid_request_error")),
            ProviderProtocol.CHAT_COMPLETIONS to JSONObject().put("error", error),
            ProviderProtocol.GEMINI to JSONObject().put("error", error))
        for ((protocol, frame) in frames) {
            val trace = ProviderRequestTrace().also { it.inheritRedactions(listOf(input, "林小姐", "ZyX654321")) }
            val reader = trace.reader(("data: $frame\n\n").byteInputStream())
            try {
                when (protocol) {
                    ProviderProtocol.RESPONSES -> ResponsesStream.read(reader, { false }) {}
                    ProviderProtocol.MESSAGES -> MessagesStream.read(reader, { false }) {}
                    else -> NativeCompletionStream.read(protocol, reader, { false }) {}
                }
                fail("expected provider failure")
            } catch (failure: ModelRequestException) {
                for (secret in listOf("Alice", "internal.invalid", "林小姐", "ZyX654321")) assertFalse(protocol.name + secret, failure.detailedMessage.contains(secret))
                assertEquals("vendor_reject", failure.diagnostic!!.providerDetail!!.code)
            }
        }
    }
    @Test fun requestCollectionIncludesShortChineseAndFailsClosedAtItsBound() {
        val request = JSONObject().put("input", "林小姐")
        assertTrue(providerRequestRedactions(request).contains("林小姐"))
        val array = org.json.JSONArray(); repeat(8194) { array.put("value$it") }
        assertTrue("" in providerRequestRedactions(JSONObject().put("input", array)))
    }
    @Test fun resumedRequestReusesOriginalPostRedactionsBeforeHttpErrorParsing() {
        val input = "联系 Alice https://internal.invalid/A123"
        val error = ProviderHttpFailure.from(Connection(503, JSONObject().put("error", JSONObject().put("message", input)).toString()),
            503, { false }, extraRedactions = listOf(input))
        assertFalse(error.detailedMessage.contains("Alice")); assertFalse(error.detailedMessage.contains("internal.invalid"))
    }
}
