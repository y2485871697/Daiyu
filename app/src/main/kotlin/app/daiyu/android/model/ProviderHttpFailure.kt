package app.daiyu.android.model

import app.daiyu.android.domain.ModelProviderDetail
import app.daiyu.android.domain.ModelFailure
import app.daiyu.android.domain.ModelRequestDiagnostic
import app.daiyu.android.domain.ModelRequestException
import app.daiyu.android.domain.ProviderErrorCode
import app.daiyu.android.domain.RequestField
import app.daiyu.android.domain.RequestRejection
import org.json.JSONObject
import java.net.HttpURLConnection
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.atomic.AtomicBoolean

internal object ProviderHttpFailure {
    private const val MAX_ERROR_BYTES = 16 * 1024
    private val indexedTools = Regex("tools\\[[0-9]{1,4}](?:\\.type)?")
    private val indexedInclude = Regex("include\\[[0-9]{1,4}]")

    fun from(connection: HttpURLConnection, status: Int, cancelled: () -> Boolean,
             diagnosticTimeoutMillis: Long = 1500, request: JSONObject? = null, extraRedactions: Collection<String> = emptyList()): ModelRequestException =
        from({ connection.errorStream }, connection::disconnect, status, cancelled, diagnosticTimeoutMillis, request,
            connection::getHeaderField, runCatching { connection.requestProperties.flatMap { (name, values) -> values.flatMap { providerHeaderRedactions(name, it) } } }.getOrDefault(emptyList()) + extraRedactions)

    fun from(connection: ModelHttpConnection, status: Int, cancelled: () -> Boolean,
             diagnosticTimeoutMillis: Long = 1500, request: JSONObject? = null, extraRedactions: Collection<String> = emptyList()): ModelRequestException =
        from({ connection.errorStream }, connection::disconnect, status, cancelled, diagnosticTimeoutMillis, request,
            connection::responseHeader, connection.diagnosticRedactions + extraRedactions)

    private fun from(errorStream: () -> java.io.InputStream?, disconnect: () -> Unit, status: Int,
                     cancelled: () -> Boolean, diagnosticTimeoutMillis: Long, request: JSONObject?,
                     responseHeader: (String) -> String?, redactions: Collection<String>): ModelRequestException {
        val failure = when (status) {
            401 -> ModelFailure.AUTHENTICATION
            403 -> ModelFailure.PERMISSION
            404, 405, 410 -> ModelFailure.ENDPOINT
            429 -> ModelFailure.RATE_LIMIT
            in 300..399 -> ModelFailure.REDIRECT
            in 500..599 -> ModelFailure.SERVER
            else -> ModelFailure.REQUEST
        }
        var detail = ModelRequestDiagnostic(status, if (status == 413)
            RequestRejection.PAYLOAD_TOO_LARGE else RequestRejection.UNSPECIFIED)
        // A failed diagnostic read must not replace a confirmed HTTP rejection with a network error.
        if (status in 400..599) {
            checkCancelled(cancelled)
            val expired = AtomicBoolean(false)
            var timer: Timer? = null
            try {
                require(diagnosticTimeoutMillis in 1..1500)
                // The connection is already established; changing readTimeout cannot bound its current socket.
                timer = Timer("model-error-diagnostic", true)
                timer.schedule(object : TimerTask() {
                    override fun run() {
                        expired.set(true)
                        try { disconnect() } catch (_: Exception) {}
                    }
                }, diagnosticTimeoutMillis)
                val secrets = redactions + providerRequestRedactions(request)
                val headerId = requestId(responseHeader)
                detail = detail.copy(providerDetail = ModelProviderDetail.sanitized(requestId = headerId, secrets = secrets))
                errorStream()?.useProviderResource { stream ->
                    val bytes = ByteArray(MAX_ERROR_BYTES + 1)
                    var count = 0
                    var complete = false
                    while (count < bytes.size) {
                        checkCancelled(cancelled)
                        if (expired.get()) break
                        val read = stream.read(bytes, count, bytes.size - count)
                        if (read < 0) { complete = true; break }
                        if (read == 0) break
                        count += read
                    }
                    checkCancelled(cancelled)
                    if (complete && count <= MAX_ERROR_BYTES && !expired.get()) {
                        val root = MessagesRequest.jsonObject(String(bytes, 0, count, Charsets.UTF_8), 16, rejectDuplicateKeys = true)
                        val error = root.optJSONObject("error") ?: root
                        val parsed = diagnostic(error, status, request)
                        val semantic = if (status in setOf(400, 404, 422)) parsed
                            else detail.copy(providerCode = providerCode(error))
                        detail = semantic.copy(providerDetail = providerDetail(error,
                            headerId ?: scalar(error, "request_id") ?: scalar(root, "request_id"), secrets,
                            (root.opt("error") as? String)))
                    }
                }
            } catch (cancelled: InterruptedException) {
                throw cancelled
            } catch (cancelled: java.util.concurrent.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                checkCancelled(cancelled)
            } finally {
                timer?.cancel()
            }
        }
        checkCancelled(cancelled)
        return ModelRequestException(if (detail.rejection == RequestRejection.CONTEXT_LIMIT) ModelFailure.CONTEXT_LIMIT else failure, detail)
    }

    fun event(error: JSONObject?, secrets: Collection<String> = emptyList(), requestId: String? = null): ModelRequestException {
        val kinds = setOf(error?.opt("type") as? String, error?.opt("code") as? String, error?.opt("status") as? String)
        val status = (error?.opt("code") as? Number)?.toInt()
        val failure = when {
            status == 401 || kinds.any { it in setOf("authentication_error", "invalid_api_key", "UNAUTHENTICATED") } -> ModelFailure.AUTHENTICATION
            status == 403 || kinds.any { it in setOf("permission_error", "permission_denied", "PERMISSION_DENIED") } -> ModelFailure.PERMISSION
            status == 429 || kinds.any { it in setOf("rate_limit_error", "rate_limit_exceeded", "insufficient_quota", "RESOURCE_EXHAUSTED") } -> ModelFailure.RATE_LIMIT
            status != null && status in 500..599 || kinds.any { it in setOf("api_error", "server_error", "overloaded_error", "UNAVAILABLE", "INTERNAL") } -> ModelFailure.SERVER
            else -> ModelFailure.REQUEST
        }
        val detail = if (failure == ModelFailure.REQUEST) diagnostic(error, null)
            else ModelRequestDiagnostic(providerCode = providerCode(error))
        return ModelRequestException(if (detail.rejection == RequestRejection.CONTEXT_LIMIT) ModelFailure.CONTEXT_LIMIT else failure, detail.copy(providerDetail = providerDetail(error,
            requestId ?: scalar(error, "request_id"), secrets)))
    }

    private fun diagnostic(error: JSONObject?, status: Int?, request: JSONObject? = null): ModelRequestDiagnostic {
        val rejection = when (error?.opt("code") as? String) {
            "model_not_found" -> RequestRejection.MODEL_UNAVAILABLE
            "unsupported_parameter" -> RequestRejection.UNSUPPORTED_PARAMETER
            "unsupported_value" -> RequestRejection.UNSUPPORTED_VALUE
            "context_length_exceeded" -> RequestRejection.CONTEXT_LIMIT
            else -> RequestRejection.UNSPECIFIED
        }
        val raw = error?.opt("param") as? String
        val field = RequestField.entries.firstOrNull { it.wireName == raw } ?: when {
            raw != null && raw.length <= 32 && indexedTools.matches(raw) -> RequestField.TOOLS
            raw != null && raw.length <= 32 && indexedInclude.matches(raw) -> RequestField.INCLUDE
            raw in setOf("reasoning.effort", "reasoning.summary", "reasoning_effort", "output_config.effort",
                "generationConfig.thinkingConfig.thinkingLevel") -> RequestField.REASONING
            else -> null
        }
        val effortField = raw in setOf("reasoning", "reasoning.effort", "reasoning_effort", "thinking",
            "thinking.type", "output_config.effort", "enable_thinking", "thinking_mode")
        val message = (error?.opt("message") as? String).orEmpty().take(MAX_ERROR_BYTES).lowercase()
        val invalidThinkingValue = error?.isNull("code") == true && error.opt("type") == "invalid_request_error" ||
            error?.opt("code") in setOf("invalid_request_error", "invalid_value", "invalid_parameter_value")
        val valueRejected = rejection == RequestRejection.UNSPECIFIED && invalidThinkingValue &&
            (effortField || raw == null && listOf("reasoning.effort", "reasoning_effort", "thinking").any(message::contains)) &&
            listOf("not supported", "unsupported", "supported values", "must be", "cannot be disabled", "only supports").any(message::contains) &&
            listOf("none", "disabled", "unsupported value", "invalid value", "supported values", "only supports", "must be enabled").any(message::contains)
        val reasoningRejected = effortField && rejection == RequestRejection.UNSUPPORTED_VALUE || valueRejected
        val effort = if (status in setOf(400, 422) && rejection == RequestRejection.UNSUPPORTED_VALUE && request != null && raw != null)
            ReasoningRequestPolicy.wireEffort(request, raw) else null
        return ModelRequestDiagnostic(status,
            if (valueRejected && rejection == RequestRejection.UNSPECIFIED) RequestRejection.UNSUPPORTED_VALUE else rejection,
            if (reasoningRejected) RequestField.REASONING else field,
            unsupportedReasoningEffort = effort, reasoningLevelRejected = reasoningRejected,
            providerCode = providerCode(error))
    }

    internal fun requestId(header: (String) -> String?): String? {
        for (name in listOf("x-request-id", "request-id", "anthropic-request-id", "x-goog-request-id")) {
            val value = try { header(name) }
            catch (failure: InterruptedException) { throw failure }
            catch (failure: java.util.concurrent.CancellationException) { throw failure }
            catch (_: Exception) { null }
            if (!value.isNullOrBlank() && value.length <= 128) return value
        }
        return null
    }

    private fun scalar(error: JSONObject?, key: String): String? = when (val value = error?.opt(key)) {
        is String -> value
        is Number -> value.toString()
        else -> null
    }

    private fun providerDetail(error: JSONObject?, requestId: String?, secrets: Collection<String> = emptyList(), fallbackMessage: String? = null) =
        ModelProviderDetail.sanitized(code = scalar(error, "code"), type = scalar(error, "type") ?: scalar(error, "status"),
            message = scalar(error, "message") ?: scalar(error, "msg") ?: scalar(error, "error_description") ?: fallbackMessage,
            requestId = requestId, secrets = secrets)

    private fun providerCode(error: JSONObject?): ProviderErrorCode? =
        sequenceOf(error?.opt("code"), error?.opt("type"))
            .mapNotNull { rawCode -> ProviderErrorCode.entries.firstOrNull { it.wireName == rawCode } }.firstOrNull()

    private fun checkCancelled(cancelled: () -> Boolean) {
        if (cancelled() || Thread.currentThread().isInterrupted) throw InterruptedException()
    }
}
