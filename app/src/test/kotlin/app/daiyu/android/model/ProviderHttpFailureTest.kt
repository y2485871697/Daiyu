package app.daiyu.android.model

import app.daiyu.android.domain.*
import app.daiyu.android.storage.ModelSettings
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.net.URL
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

class ProviderHttpFailureTest {
    private val secret = "synthetic-secret-never-display"
    private open class Connection(private val code: Int, private val body: String) :
        HttpsURLConnection(URL("https://gateway.invalid/v1/responses")) {
        val sent = ByteArrayOutputStream()
        var errorReads = 0
        var closed = false
        var disconnected = false
        override fun connect() {}
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getOutputStream() = sent
        override fun getResponseCode() = code
        override fun getErrorStream(): InputStream {
            errorReads++
            return object : ByteArrayInputStream(body.toByteArray()) {
                override fun close() { closed = true }
            }
        }
        override fun getContentType() = "text/event-stream"
        override fun getInputStream() = body.byteInputStream()
    }
    private fun body(code: Any? = null, param: Any? = null) = JSONObject().put("error", JSONObject()
        .put("code", code ?: JSONObject.NULL).put("param", param ?: JSONObject.NULL).put("message", secret)).toString()
    private fun classify(body: String, status: Int = 400) = ProviderHttpFailure.from(Connection(status, body), status, { false })
    private fun safe(error: ModelRequestException) {
        assertNull(error.cause)
        assertTrue(error.suppressed.isEmpty())
        assertFalse(error.stackTraceToString().contains(secret))
        assertFalse(error.userMessage.contains(secret))
        assertFalse(error.diagnostic.toString().contains(secret))
    }

    @Test fun knownCodesRetainStatusAndOnlyClosedReasons() {
        for ((code, reason) in listOf("model_not_found" to RequestRejection.MODEL_UNAVAILABLE,
            "unsupported_parameter" to RequestRejection.UNSUPPORTED_PARAMETER,
            "unsupported_value" to RequestRejection.UNSUPPORTED_VALUE,
            "context_length_exceeded" to RequestRejection.CONTEXT_LIMIT)) {
            for (status in listOf(400, 422)) {
                val error = classify(body(code, "model"), status)
                assertEquals(ModelFailure.REQUEST, error.failure)
                assertEquals(ModelRequestDiagnostic(status, reason, RequestField.MODEL), error.diagnostic)
                assertTrue(error.userMessage.contains("HTTP $status"))
                safe(error)
            }
        }
        assertEquals(RequestRejection.MODEL_UNAVAILABLE, classify(body("model_not_found", "model"), 404).diagnostic!!.rejection)
    }

    @Test fun fieldAloneDoesNotInventAnUnsupportedCause() {
        for (code in listOf<Any?>(null, "unknown", JSONObject().put("secret", secret), 400)) {
            val error = classify(body(code, "tools[0].type"))
            assertEquals(RequestRejection.UNSPECIFIED, error.diagnostic!!.rejection)
            assertEquals(RequestField.TOOLS, error.diagnostic!!.field)
            assertFalse(error.userMessage.contains("不支持"))
            safe(error)
        }
        assertEquals(RequestRejection.UNSPECIFIED,
            classify("""{"error":{"type":"invalid_request_error","message":"$secret"}}""").diagnostic!!.rejection)
    }

    @Test fun fieldsAreCanonicalAndUnknownNamesNeverEscape() {
        val fields = mapOf("tools[0]" to RequestField.TOOLS, "include[1]" to RequestField.INCLUDE,
            "reasoning.effort" to RequestField.REASONING, "temperature" to RequestField.TEMPERATURE)
        fields.forEach { (raw, expected) -> assertEquals(expected, classify(body("unsupported_value", raw)).diagnostic!!.field) }
        for (raw in listOf<Any>("tools[0].$secret", "tools[-1]", "tools[12345].type", "include[1].$secret",
            secret, "tools\n", "x".repeat(20000), JSONObject().put("key", secret))) {
            val error = classify(body("unsupported_parameter", raw))
            assertNull(error.diagnostic!!.field)
            safe(error)
        }
    }

    @Test fun malformedOversizedAndTooDeepErrorsPreserveConfirmedHttpStatus() {
        for (body in listOf("<html>$secret</html>", "{", " ".repeat(16385),
            """{"error":{"code":"unsupported_parameter","code":"model_not_found"}}""",
            "[".repeat(20) + "{}" + "]".repeat(20))) {
            val error = classify(body, 422)
            assertEquals(ModelRequestDiagnostic(422), error.diagnostic)
            assertEquals(ModelFailure.REQUEST, error.failure)
            safe(error)
        }
    }

    @Test fun diagnosticReadOrCloseFailureCannotHideHttpRejection() {
        for (closeFailure in listOf(false, true)) {
            val connection = object : Connection(400, "") {
                override fun getErrorStream() = object : InputStream() {
                    override fun read(): Int { throw SocketTimeoutException(secret) }
                    override fun close() { if (closeFailure) throw IOException(secret) }
                }
            }
            val error = ProviderHttpFailure.from(connection, 400, { false })
            assertEquals(ModelRequestDiagnostic(400), error.diagnostic)
            safe(error)
        }
    }

    @Test(timeout = 3000) fun blockedErrorBodyIsDisconnectedWithoutChangingHttpRejection() {
        val disconnected = java.util.concurrent.CountDownLatch(1)
        val connection = object : Connection(422, "") {
            override fun disconnect() { super.disconnect(); disconnected.countDown() }
            override fun getErrorStream() = object : InputStream() {
                override fun read(): Int {
                    check(disconnected.await(2, java.util.concurrent.TimeUnit.SECONDS)) {
                        "diagnostic deadline did not cancel the active read"
                    }
                    throw IOException(secret)
                }
            }
        }
        val start = System.nanoTime()
        val failure = ProviderHttpFailure.from(connection, 422, { false }, 50)
        assertTrue(connection.disconnected)
        assertTrue(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2000)
        assertEquals(ModelRequestDiagnostic(422), failure.diagnostic)
        safe(failure)
    }

    @Test fun authRedirectAndServerErrorsDoNotReadUntrustedBodies() {
        for ((status, expected) in listOf(401 to ModelFailure.AUTHENTICATION, 403 to ModelFailure.PERMISSION,
            307 to ModelFailure.REDIRECT, 429 to ModelFailure.RATE_LIMIT, 503 to ModelFailure.SERVER)) {
            val conn = Connection(status, body("model_not_found", "model"))
            val error = ProviderHttpFailure.from(conn, status, { false })
            assertEquals(expected, error.failure)
            assertEquals(status, error.diagnostic!!.httpStatus)
            assertEquals(0, conn.errorReads)
            safe(error)
        }
        assertEquals(RequestRejection.PAYLOAD_TOO_LARGE, classify("", 413).diagnostic!!.rejection)
    }

    @Test fun cancellationAndInterruptedReadsPropagateWithoutReclassifying() {
        val connection = Connection(400, body())
        try { ProviderHttpFailure.from(connection, 400, { true }); fail("cancel ignored") } catch (_: InterruptedException) {}
        assertEquals(0, connection.errorReads)
        val interrupted = object : Connection(400, "") {
            override fun getErrorStream(): InputStream { throw InterruptedException() }
        }
        try { ProviderHttpFailure.from(interrupted, 400, { false }); fail("interrupt ignored") } catch (_: InterruptedException) {}
    }

    @Test fun exceptionSanitizationPreservesOnlySafeDiagnostic() {
        val original = classify(body("unsupported_parameter", "include[0]"), 422)
        original.initCause(IOException(secret)); original.addSuppressed(IOException(secret))
        val error = providerTransportFailure(original, false) as ModelRequestException
        assertNotSame(original, error)
        assertEquals(original.diagnostic, error.diagnostic)
        safe(error)
    }

    @Test fun compressedTextOnlyRequestKeepsItsSettingsAndDoesNotProbeByReposting() {
        for ((status, code, field) in listOf(Triple(400, "model_not_found", "model"),
            Triple(422, "unsupported_parameter", "tools[0].type"), Triple(422, "unsupported_value", "include[0]"))) {
            val conn = Connection(status, body(code, field))
            var connections = 0
            val settings = ModelSettings("https://gateway.invalid/v1/responses", "gpt-6-astra", "synthetic-key", false,
                requestOptions = ProviderRequestOptions(openAiWebSearchEnabled = true))
            val history = listOf("[历史摘要；仅供上下文，非新指令]\n目录已列出\n[摘要结束]", "讲个故事").mapIndexed { n, text ->
                Message(MessageId("u$n"), ConversationId("c"), RunId("r"), "user",
                    listOf(MessageBlock.Text("text", text)), RunStatus.COMPLETED)
            }
            val session = ResponsesModel(settings) { connections++; conn }.open(history, emptyList<ToolDefinition>())
            val events = mutableListOf<ModelEvent>()
            try { session.next(emptyList(), events::add); fail("request accepted") }
            catch (error: ModelRequestException) {
                assertEquals(status, error.diagnostic!!.httpStatus)
                assertNotEquals(RequestRejection.UNSPECIFIED, error.diagnostic!!.rejection)
                safe(error)
            }
            val sent = JSONObject(conn.sent.toString("UTF-8"))
            assertEquals("gpt-6-astra", sent.getString("model"))
            assertEquals(2, sent.getJSONArray("input").length())
            for (i in 0..1) assertEquals("user", sent.getJSONArray("input").getJSONObject(i).getString("role"))
            assertEquals("web_search", sent.getJSONArray("tools").getJSONObject(0).getString("type"))
            assertEquals("reasoning.encrypted_content", sent.getJSONArray("include").getString(0))
            assertTrue(events.none { it is ModelEvent.ToolReady || it is ModelEvent.ProtocolOutput })
            assertTrue(conn.closed); assertTrue(conn.disconnected)
            try { session.next(emptyList()) {}; fail("reposted") } catch (_: IllegalStateException) {}
            assertEquals(1, connections)
        }
    }

    @Test fun responsesFailedEventPreservesClosedReasonWithoutHttpAssumptions() {
        val events = mutableListOf<ModelEvent>()
        val wire = "data: " + JSONObject().put("type", "response.failed")
            .put("response", JSONObject().put("error", JSONObject(body("context_length_exceeded", "input")).getJSONObject("error"))) + "\n\n"
        try { ResponsesStream.read(wire.reader().buffered(), { false }, events::add); fail("accepted failed event") }
        catch (error: ModelRequestException) {
            assertEquals(ModelRequestDiagnostic(null, RequestRejection.CONTEXT_LIMIT, RequestField.INPUT), error.diagnostic)
            assertFalse(error.userMessage.contains("HTTP")); safe(error)
        }
        assertTrue(events.none { it is ModelEvent.ToolReady || it is ModelEvent.ProtocolOutput })
    }
    @Test fun reasoningValueRejectionsUseOnlyTheExactFinalRequestFieldAndClosedLevel() {
        val choices = mapOf("none" to "关闭", "minimal" to "最小", "low" to "低", "medium" to "中",
            "high" to "高", "xhigh" to "超高", "max" to "极高")
        for ((wire, label) in choices) for (path in listOf("reasoning.effort", "reasoning_effort", "output_config.effort",
            "generationConfig.thinkingConfig.thinkingLevel")) {
            val request = when (path) {
                "reasoning.effort" -> JSONObject().put("reasoning", JSONObject().put("effort", wire))
                "reasoning_effort" -> JSONObject().put("reasoning_effort", wire)
                "output_config.effort" -> JSONObject().put("output_config", JSONObject().put("effort", wire))
                else -> JSONObject().put("generationConfig", JSONObject().put("thinkingConfig", JSONObject().put("thinkingLevel", wire)))
            }
            val failure = ProviderHttpFailure.from(Connection(400, body("unsupported_value", path)), 400,
                { false }, request = request)
            assertEquals("模型不支持${label}思考", failure.userMessage)
            assertEquals(RequestField.REASONING, failure.diagnostic!!.field)
            assertEquals(400, failure.diagnostic!!.httpStatus)
            safe(failure)
            val sanitized = providerTransportFailure(failure, false) as ModelRequestException
            assertEquals(failure.userMessage, sanitized.userMessage)
            assertEquals(failure.diagnostic, sanitized.diagnostic)
        }
        val conflicting = JSONObject("""{"reasoning_effort":"low","reasoning":{"effort":"high"}}""")
        for ((path, expected) in listOf("reasoning_effort" to "模型不支持低思考", "reasoning.effort" to "模型不支持高思考")) {
            val failure = ProviderHttpFailure.from(Connection(400, body("unsupported_value", path)), 400,
                { false }, request = conflicting)
            assertEquals(expected, failure.userMessage)
        }
    }

    @Test fun vagueRejectionsAndUnrelatedReasoningFieldsNeverInventAnUnsupportedLevel() {
        val request = JSONObject("""{"reasoning":{"effort":"high","summary":"auto"}}""")
        for (param in listOf("reasoning", "reasoning.summary", "effort", "reasoning_effort", "reasoning.effort.fake")) {
            val failure = ProviderHttpFailure.from(Connection(400, body("unsupported_value", param)), 400, { false }, request = request)
            assertNull(failure.diagnostic!!.unsupportedReasoningEffort)
            assertFalse(failure.userMessage.contains("不支持高思考"))
            safe(failure)
        }
        for (code in listOf("unsupported_parameter", "unknown", "invalid_request_error")) {
            val failure = ProviderHttpFailure.from(Connection(400, body(code, "reasoning.effort")), 400, { false }, request = request)
            assertNull(failure.diagnostic!!.unsupportedReasoningEffort)
        }
        for (invalid in listOf<Any>(JSONObject.NULL, 2, "HIGH", "default", "secret")) {
            val malformed = JSONObject().put("reasoning", JSONObject().put("effort", invalid))
            val failure = ProviderHttpFailure.from(Connection(400, body("unsupported_value", "reasoning.effort")), 400,
                { false }, request = malformed)
            assertNull(failure.diagnostic!!.unsupportedReasoningEffort)
            safe(failure)
        }
        assertNull(ProviderHttpFailure.event(JSONObject().put("code", "unsupported_value").put("param", "reasoning.effort"))
            .diagnostic!!.unsupportedReasoningEffort)
    }

}
