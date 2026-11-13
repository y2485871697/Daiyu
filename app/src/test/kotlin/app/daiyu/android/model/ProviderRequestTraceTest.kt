package app.daiyu.android.model

import app.daiyu.android.domain.*
import org.junit.Assert.*
import org.junit.Test

class ProviderRequestTraceTest {
    @Test fun localDisconnectIsRequestScopedMetadataAndDoesNotChangeSocketClassification() {
        val first = ProviderRequestTrace()
        first.localDisconnectRequested()
        val socket = java.net.SocketException("private-host")
        val before = first.diagnostic(socket, ModelFailure.NETWORK)
        assertTrue(before.localDisconnectRequested)
        assertEquals(ModelTransportCause.SOCKET, before.cause)
        assertFalse(ProviderRequestTrace().diagnostic(socket, ModelFailure.NETWORK).localDisconnectRequested)
        assertFalse(before.toString().contains("private-host"))
    }

    @Test fun socketSymptomsAreClosedEnumsWithoutRetainingMessagesOrChangingRetryClassification() {
        val canary = "private-key-https://secret.invalid/?token=hidden"
        for ((symptom, detail) in listOf("Connection reset by peer" to ModelSocketDetail.RESET,
            "Socket is closed" to ModelSocketDetail.CLOSED, "Software caused connection abort" to ModelSocketDetail.ABORTED,
            "Broken pipe" to ModelSocketDetail.BROKEN_PIPE, "unexpected end of stream" to ModelSocketDetail.END_OF_STREAM,
            "Network is unreachable" to ModelSocketDetail.NETWORK_UNREACHABLE, canary to ModelSocketDetail.UNKNOWN)) {
            val socket = java.net.SocketException("$symptom $canary")
            socket.addSuppressed(IllegalStateException(canary))
            val direct = providerTransportFailure(socket, false, ProviderRequestTrace()) as ModelRequestException
            assertEquals(ModelFailure.NETWORK, direct.failure)
            assertEquals(ModelTransportCause.SOCKET, direct.diagnostic!!.transport!!.cause)
            assertEquals(detail, direct.diagnostic!!.transport!!.socketDetail)
            val wrapped = providerTransportFailure(java.io.IOException(canary, socket), false,
                ProviderRequestTrace()) as ModelRequestException
            assertEquals(ModelTransportCause.IO, wrapped.diagnostic!!.transport!!.cause)
            assertEquals(detail, wrapped.diagnostic!!.transport!!.socketDetail)
            for (safe in listOf(direct, wrapped)) {
                assertNull(safe.cause); assertTrue(safe.suppressed.isEmpty())
                assertFalse(safe.diagnostic.toString().contains(canary))
                assertFalse(safe.stackTraceToString().contains(canary))
            }
        }
    }

    @Test fun socketSymptomTraversalStopsAtCyclesAndItsFixedDepth() {
        val first = java.io.IOException("unknown")
        val second = java.io.IOException("unknown", first)
        first.initCause(second)
        assertEquals(ModelSocketDetail.UNKNOWN, ProviderRequestTrace().diagnostic(first, ModelFailure.NETWORK).socketDetail)
        var deep: Throwable = java.net.SocketException("Connection reset")
        repeat(7) { deep = java.io.IOException("unknown", deep) }
        assertEquals(ModelSocketDetail.RESET, ProviderRequestTrace().diagnostic(deep as Exception, ModelFailure.NETWORK).socketDetail)
        deep = java.io.IOException("unknown", deep)
        assertEquals(ModelSocketDetail.UNKNOWN, ProviderRequestTrace().diagnostic(deep as Exception, ModelFailure.NETWORK).socketDetail)
        for (nonSocket in listOf(java.io.FileNotFoundException("/storage/emulated/0/Broken pipe.txt"),
            java.io.IOException("Connection reset"))) {
            assertEquals(ModelSocketDetail.UNKNOWN, ProviderRequestTrace().diagnostic(nonSocket, ModelFailure.NETWORK).socketDetail)
        }
        assertEquals(ModelSocketDetail.END_OF_STREAM, ProviderRequestTrace().diagnostic(
            java.io.EOFException("unexpected end of stream"), ModelFailure.NETWORK).socketDetail)
        assertEquals(ModelSocketDetail.UNKNOWN, ProviderRequestTrace().diagnostic(
            IllegalStateException("Connection reset"), ModelFailure.PROTOCOL).socketDetail)
    }

    @Test fun onlyCompleteDataFramesCountAndAgeUsesTheRequestMonotonicClock() {
        var time = 0L
        val trace = ProviderRequestTrace(now = { time })
        time = 10_000_000
        trace.headersReceived()
        val reader = trace.reader(": keepalive\n\ndata: secret-body\r\n\r\n".byteInputStream())
        time = 30_000_000
        SseReader.read(reader) { true }
        time = 50_000_000
        val observed = trace.diagnostic(ModelRequestException(ModelFailure.INCOMPLETE, transportInterrupted = true), ModelFailure.NETWORK)
        assertEquals(50L, observed.requestElapsedMs)
        assertEquals(40L, observed.streamElapsedMs)
        assertEquals(20L, observed.lastFrameAgeMs)
        assertEquals(1L, observed.sseFrames)
        assertTrue(observed.eofSeen)
        assertFalse(observed.partialFrame)
        assertFalse(observed.completionReceived)
        assertEquals(ModelTransportCause.PREMATURE_EOF, observed.cause)
        assertFalse(observed.toString().contains("secret-body"))
    }
    @Test fun consumerStopAndValidatedCompletionAreNotNaturalEof() {
        val trace = ProviderRequestTrace()
        SseReader.read(trace.reader("data: terminal\n\ndata: unread\n\n".byteInputStream())) { false }
        trace.completionReceived()
        val observed = trace.diagnostic(ModelRequestException(ModelFailure.PROTOCOL), ModelFailure.PROTOCOL)
        assertEquals(1L, observed.sseFrames)
        assertFalse(observed.eofSeen)
        assertFalse(observed.partialFrame)
        assertTrue(observed.completionReceived)
        assertEquals(ModelTransportPhase.DECODING, observed.phase)
        assertEquals(ModelTransportCause.PROTOCOL, observed.cause)
    }
    @Test fun safeTypeClassificationAndHttpEvidenceNeverRetainExceptionChains() {
        val failures = listOf(java.net.UnknownHostException("private-host") to ModelTransportCause.DNS,
            java.net.ConnectException("private-host") to ModelTransportCause.CONNECT,
            java.net.SocketTimeoutException("private-host") to ModelTransportCause.TIMEOUT,
            javax.net.ssl.SSLException("private-host") to ModelTransportCause.TLS,
            java.net.SocketException("private-host") to ModelTransportCause.SOCKET,
            java.io.IOException("private-host") to ModelTransportCause.IO)
        for ((failure, cause) in failures) {
            failure.addSuppressed(java.io.IOException("private-host"))
            val observed = providerTransportFailure(failure, false, ProviderRequestTrace()) as ModelRequestException
            assertEquals(cause, observed.diagnostic?.transport?.cause)
            assertNull(observed.cause)
            assertTrue(observed.suppressed.isEmpty())
            assertFalse(observed.diagnostic.toString().contains("private-host"))
        }
        val evidence = ModelRequestDiagnostic(503, providerCode = ProviderErrorCode.OVERLOADED_ERROR)
        val observed = providerTransportFailure(ModelRequestException(ModelFailure.SERVER, evidence), false,
            ProviderRequestTrace()) as ModelRequestException
        assertEquals(evidence, observed.diagnostic?.copy(transport = null))
        assertEquals(ModelTransportCause.HTTP, observed.diagnostic?.transport?.cause)
        assertNull(observed.diagnostic?.transport?.lastFrameAgeMs)
        assertEquals(0L, observed.diagnostic?.transport?.sseFrames)
    }
}
