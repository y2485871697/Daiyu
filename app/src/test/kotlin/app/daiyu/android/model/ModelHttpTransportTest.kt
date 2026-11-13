package app.daiyu.android.model

import app.daiyu.android.domain.ModelFailure
import app.daiyu.android.domain.ModelTransportPhase
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ModelHttpTransportTest {
    private fun open(server: MockWebServer) = ModelHttpTransport.OkHttpModelConnection(
        server.url("/model").toUrl(), ModelHttpTransport.client)
    private fun post(connection: ModelHttpConnection, value: String = "{\"model\":\"fixture\"}") {
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Accept", "text/event-stream")
        val bytes = value.toByteArray()
        connection.send(bytes.size.toLong()) { it.write(bytes) }
    }
    @Test fun clientMatchesOriginalTimeoutsWithoutImplicitReplayOrRedirect() {
        val client = ModelHttpTransport.client
        assertEquals(20_000, client.connectTimeoutMillis)
        assertEquals(600_000, client.readTimeoutMillis)
        assertEquals(120_000, client.writeTimeoutMillis)
        assertEquals(0, client.callTimeoutMillis)
        assertFalse(client.retryOnConnectionFailure)
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
    }
    @Test fun sendsFixedLengthJsonAndReadsChunkedCrOnlySseWithoutReexecuting() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setChunkedBody("\uFEFFdata: hello\r\rdata: world\r\r", 3))
            val connection = open(server)
            val trace = ProviderRequestTrace()
            try {
                post(connection)
                trace.phase = ModelTransportPhase.SENDING
                assertEquals(200, connection.responseCode { trace.phase = ModelTransportPhase.WAITING_HEADERS })
                assertEquals(ModelTransportPhase.WAITING_HEADERS, trace.phase)
                assertEquals("text/event-stream", connection.contentType)
                assertEquals(200, connection.responseCode())
                val frames = mutableListOf<String>()
                connection.inputStream.bufferedReader().use { SseReader.read(it) { frame -> frames.add(frame); true } }
                assertEquals(listOf("hello", "world"), frames)
                val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                assertEquals("application/json", request.getHeader("Content-Type"))
                assertEquals("{\"model\":\"fixture\"}", request.body.readUtf8())
                assertEquals("19", request.getHeader("Content-Length"))
                assertEquals(1, server.requestCount)
            } finally { connection.disconnect() }
        }
    }
    @Test fun postAndCursorGetNeverReplay503OrFollowRedirect() {
        for (method in listOf("POST", "GET")) for (status in listOf(503, 307, 408, 421)) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(status).setHeader("Retry-After", "0")
                    .setHeader("Location", server.url("/other")))
                server.enqueue(MockResponse().setBody("must-not-run"))
                val connection = open(server)
                try {
                    if (method == "POST") post(connection)
                    assertEquals(status, connection.responseCode())
                    assertEquals(1, server.requestCount)
                } finally { connection.disconnect() }
            }
        }
    }
    @Test fun disconnectBeforeExecuteDoesNotSendAndFailureCannotBeExecutedAgain() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("must-not-run"))
            val connection = open(server)
            post(connection)
            connection.disconnect()
            try { connection.responseCode(); fail("cancelled") } catch (_: IOException) {}
            assertNull(connection.errorStream)
            try { connection.responseCode(); fail("already attempted") } catch (_: IllegalStateException) {}
            assertEquals(0, server.requestCount)
        }
    }
    @Test fun networkFailureAndCleanupDoNotReplayTheRequest() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            val connection = open(server)
            try {
                post(connection)
                try { connection.responseCode(); fail("disconnected") } catch (_: IOException) {}
                assertNull(connection.errorStream)
                try { connection.responseCode(); fail("already attempted") } catch (_: IllegalStateException) {}
                assertEquals(1, server.requestCount)
            } finally { connection.disconnect() }
        }
    }
    @Test fun cancelWaitingHeadersUnblocksOnlyItsOwnCall() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val blocked = open(server)
            val worker = Executors.newSingleThreadExecutor()
            try {
                post(blocked)
                val future = worker.submit<Boolean> {
                    try { blocked.responseCode(); false } catch (_: IOException) { true }
                }
                assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
                blocked.disconnect()
                assertTrue(future.get(3, TimeUnit.SECONDS))
                server.enqueue(MockResponse().setBody("alive"))
                val other = open(server)
                try { assertEquals(200, other.responseCode()); assertEquals("alive", other.inputStream.reader().readText()) }
                finally { other.disconnect() }
            } finally { blocked.disconnect(); worker.shutdownNow() }
        }
    }
    @Test fun diagnosticDeadlineCancelsRealBodyReadAndKeepsConfirmedHttpRejection() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(400).setBody("{\"error\":{}}").setBodyDelay(5, TimeUnit.SECONDS))
            val connection = open(server)
            try {
                post(connection)
                assertEquals(400, connection.responseCode())
                val started = System.nanoTime()
                val failure = ProviderHttpFailure.from(connection, 400, { false }, diagnosticTimeoutMillis = 100)
                assertEquals(ModelFailure.REQUEST, failure.failure)
                assertEquals(400, failure.diagnostic?.httpStatus)
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 3000)
                assertEquals(1, server.requestCount)
            } finally { connection.disconnect() }
        }
    }
    @Test fun writerFailureStaysInSendingAndDoesNotReplay() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse())
            val connection = open(server)
            val trace = ProviderRequestTrace()
            try {
                post(connection)
                connection.send(19) { throw IOException("fixture") }
                trace.phase = ModelTransportPhase.SENDING
                try { connection.responseCode { trace.phase = ModelTransportPhase.WAITING_HEADERS }; fail("writer failed") }
                catch (_: IOException) {}
                assertEquals(ModelTransportPhase.SENDING, trace.phase)
                assertNull(connection.errorStream)
            } finally { connection.disconnect() }
        }
    }
}
