package app.daiyu.android.model

import app.daiyu.android.domain.ModelFailure
import app.daiyu.android.domain.ModelRequestDiagnostic
import app.daiyu.android.domain.ModelRequestException
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
             diagnosticTimeoutMillis: Long = 1500): ModelRequestException {
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
        if (status in setOf(400, 404, 422)) {
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
                        try { connection.disconnect() } catch (_: Exception) {}
                    }
                }, diagnosticTimeoutMillis)
                connection.errorStream?.useProviderResource { stream ->
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
                        detail = diagnostic(root.optJSONObject("error"), status)
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
        return ModelRequestException(failure, detail)
    }

    fun event(error: JSONObject?): ModelRequestException =
        ModelRequestException(ModelFailure.REQUEST, diagnostic(error, null))

    private fun diagnostic(error: JSONObject?, status: Int?): ModelRequestDiagnostic {
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
            raw in setOf("reasoning.effort", "reasoning.summary") -> RequestField.REASONING
            else -> null
        }
        return ModelRequestDiagnostic(status, rejection, field)
    }

    private fun checkCancelled(cancelled: () -> Boolean) {
        if (cancelled() || Thread.currentThread().isInterrupted) throw InterruptedException()
    }
}
