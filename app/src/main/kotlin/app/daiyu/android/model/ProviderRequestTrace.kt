package app.daiyu.android.model

import app.daiyu.android.domain.*
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/** One request-local, raw-free observation. It cannot retry, cancel or accept model output. */
internal class ProviderRequestTrace(
    private val kind: ModelRequestKind = ModelRequestKind.POST,
    private val now: () -> Long = System::nanoTime,
) {
    private val started = now()
    private var streamStarted: Long? = null
    private var lastFrame: Long? = null
    private var frames = 0L
    private var headersSeen = false
    private var partialFrame = false
    private var eof = false
    private var completed = false
    @Volatile private var locallyDisconnected = false
    var phase = ModelTransportPhase.PREPARING

    fun localDisconnectRequested() { locallyDisconnected = true }
    fun headersReceived() { headersSeen = true }
    fun reader(input: InputStream): BufferedReader {
        streamStarted = now()
        phase = ModelTransportPhase.READING_STREAM
        return Reader(input, this)
    }
    fun frameReceived() {
        frames++
        lastFrame = now()
        phase = ModelTransportPhase.DECODING
    }
    fun reading() { phase = ModelTransportPhase.READING_STREAM }
    fun endOfStream(partial: Boolean) { eof = true; partialFrame = partial }
    fun completionReceived() { completed = true; phase = ModelTransportPhase.DECODING }

    fun diagnostic(failure: Exception, reason: ModelFailure): ModelTransportDiagnostic {
        val at = now()
        fun elapsed(from: Long) = ((at - from) / 1_000_000).coerceAtLeast(0)
        val cause = when {
            partialFrame -> ModelTransportCause.PARTIAL_SSE_FRAME
            eof && !completed -> ModelTransportCause.PREMATURE_EOF
            failure is java.net.UnknownHostException -> ModelTransportCause.DNS
            failure is java.net.ConnectException -> ModelTransportCause.CONNECT
            failure is java.net.SocketTimeoutException -> ModelTransportCause.TIMEOUT
            failure is javax.net.ssl.SSLException -> ModelTransportCause.TLS
            failure is java.net.SocketException -> ModelTransportCause.SOCKET
            failure is java.io.IOException -> ModelTransportCause.IO
            failure is ModelRequestException && failure.diagnostic?.httpStatus != null -> ModelTransportCause.HTTP
            failure is ModelRequestException && failure.diagnostic?.providerCode != null -> ModelTransportCause.PROVIDER_REJECTION
            reason == ModelFailure.PROTOCOL -> ModelTransportCause.PROTOCOL
            reason == ModelFailure.CONFIGURATION -> ModelTransportCause.CONFIGURATION
            failure is ModelRequestException && failure.transportInterrupted -> ModelTransportCause.PREMATURE_EOF
            else -> ModelTransportCause.PROVIDER_REJECTION
        }
        return ModelTransportDiagnostic(phase, cause, kind, elapsed(started), streamStarted?.let(::elapsed),
            frames, lastFrame?.let(::elapsed), headersSeen, completed, eofSeen = eof, partialFrame = partialFrame,
            localDisconnectRequested = locallyDisconnected, socketDetail = socketDetail(failure))
    }

    private fun socketDetail(failure: Throwable): ModelSocketDetail {
        val seen = java.util.IdentityHashMap<Throwable, Boolean>()
        var current: Throwable? = failure
        repeat(8) {
            val cause = current ?: return ModelSocketDetail.UNKNOWN
            if (seen.put(cause, true) != null) return ModelSocketDetail.UNKNOWN
            if (cause is java.net.SocketException || cause is java.io.EOFException) {
                // Inspect fixed symptoms locally; no exception text or chain leaves this function.
                val message = cause.message?.take(512)?.lowercase(java.util.Locale.ROOT).orEmpty()
                val detail = when {
                    "connection reset" in message -> ModelSocketDetail.RESET
                    "socket closed" in message || "socket is closed" in message -> ModelSocketDetail.CLOSED
                    "connection abort" in message -> ModelSocketDetail.ABORTED
                    "broken pipe" in message -> ModelSocketDetail.BROKEN_PIPE
                    "unexpected end of stream" in message -> ModelSocketDetail.END_OF_STREAM
                    "network is unreachable" in message -> ModelSocketDetail.NETWORK_UNREACHABLE
                    else -> ModelSocketDetail.UNKNOWN
                }
                if (detail != ModelSocketDetail.UNKNOWN) return detail
            }
            current = cause.cause
        }
        return ModelSocketDetail.UNKNOWN
    }

    internal class Reader(input: InputStream, val trace: ProviderRequestTrace) :
        BufferedReader(InputStreamReader(input, Charsets.UTF_8))
}
