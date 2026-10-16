package app.daiyu.android.model.generation

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

class GenerationIoTest {
    @Test fun downloadDoesNotLeakBearerToCdnAndValidatesBeforeCommit() {
        val fixture = GenerationFixture()
        fixture.http.respond = { request -> if (request.method == "POST")
            jsonResponse("{\"data\":[{\"url\":\"https://cdn.example/a?signature=s\"}]}")
            else GenerationHttpResponse(200, byteArrayOf(1, 2, 3), "image/png")
        }
        val plan = fixture.images.prepare(generationRequest())
        val result = fixture.images.submit(plan) as Submission.Completed
        assertTrue(fixture.io.downloadAndCommit(plan, result.outputs.single()) is GenerationAssetResult.Committed)
        assertEquals("GET", fixture.http.requests.last().method)
        assertTrue(fixture.http.requests.last().headers.isEmpty())
        assertEquals(1, fixture.assets.inspections)
        assertEquals(1, fixture.assets.commits.size)
        assertEquals(1, fixture.http.requests.count { it.method == "POST" })
    }

    @Test fun deniedOrInvalidAssetNeverCommitsAndCompletedSourceRemainsAvailable() {
        val fixture = GenerationFixture(allowed = { false })
        fixture.http.respond = { jsonResponse("{\"data\":[{\"url\":\"https://cdn.example/a\"}]}") }
        val plan = fixture.images.prepare(generationRequest())
        val result = fixture.images.submit(plan) as Submission.Completed
        assertEquals(GenerationAssetResult.Failed(GenerationFailure.DOWNLOAD_DENIED),
            fixture.io.downloadAndCommit(plan, result.outputs.single()))
        assertEquals(1, fixture.http.requests.size)
        assertNotNull(result.outputs.single().source)
        fixture.assets.reject = true
        val inline = GenerationOutput(0, GenerationSource.Inline(byteArrayOf(1), "image/png"))
        assertEquals(GenerationAssetResult.Failed(GenerationFailure.ASSET_REJECTED), fixture.io.downloadAndCommit(plan, inline))
        assertTrue(fixture.assets.commits.isEmpty())
    }

    @Test fun cancellationBeforeAndAfterPostHasDifferentExecutionMeaning() {
        var cancel = true
        val fixture = GenerationFixture(cancelled = { cancel })
        assertEquals(Submission.NotExecuted(GenerationFailure.CANCELLED), fixture.images.submit(generationRequest()))
        assertTrue(fixture.store.rows.isEmpty())
        assertTrue(fixture.http.requests.isEmpty())
        cancel = false
        fixture.http.respond = {
            cancel = true
            jsonResponse("{\"data\":[{\"b64_json\":\"AQID\"}]}")
        }
        assertEquals(Submission.Unknown(GenerationFailure.CANCELLED), fixture.images.submit(generationRequest()))
        cancel = false
        assertEquals(Submission.Unknown(GenerationFailure.CANCELLED), fixture.images.submit(generationRequest()))
        assertEquals(1, fixture.http.requests.size)
    }

    @Test fun transportUsesFixedLengthStreamingDisablesRedirectsAndClosesResources() {
        val connection = StubConnection()
        val transport = UrlConnectionGenerationHttp { connection }
        val response = transport.execute(GenerationHttpRequest("POST", "https://api.example/v1/videos",
            mapOf("Authorization" to "Bearer secret-key"), byteArrayOf(1, 2, 3), 100))
        assertEquals(200, response.statusCode)
        assertEquals(3, connection.fixedLength)
        assertFalse(connection.instanceFollowRedirects)
        assertFalse(connection.useCaches)
        assertEquals("POST", connection.requestMethod)
        assertArrayEquals(byteArrayOf(1, 2, 3), connection.written.toByteArray())
        assertTrue(connection.outputClosed)
        assertTrue(connection.inputClosed)
        assertTrue(connection.disconnected)
    }

    @Test fun transportDoesNotFollowRedirectOrReadErrorBodyAndBoundsReadBytes() {
        val redirect = StubConnection().apply { status = 307 }
        var calls = 0
        val transport = UrlConnectionGenerationHttp { calls++; redirect }
        assertEquals(307, transport.execute(GenerationHttpRequest("POST", "https://api.example/v1/videos",
            emptyMap(), byteArrayOf(1), 100)).statusCode)
        assertEquals(1, calls)
        assertFalse(redirect.inputOpened)
        assertTrue(redirect.disconnected)
        val oversized = StubConnection().apply { payload = ByteArray(101) }
        try {
            UrlConnectionGenerationHttp { oversized }.execute(GenerationHttpRequest("GET", "https://cdn.example/a",
                emptyMap(), null, 100))
            fail("oversized body accepted")
        } catch (_: IllegalArgumentException) { /* bounded before storing response */ }
        assertTrue(oversized.inputClosed)
        assertTrue(oversized.disconnected)
    }

    private class StubConnection : HttpURLConnection(URL("https://api.example/v1/videos")) {
        var status = 200
        var payload = "{}".toByteArray()
        var fixedLength = -1
        val written = ByteArrayOutputStream()
        var outputClosed = false
        var inputOpened = false
        var inputClosed = false
        var disconnected = false
        override fun connect() = Unit
        override fun usingProxy(): Boolean = false
        override fun disconnect() { disconnected = true }
        override fun getResponseCode(): Int = status
        override fun getContentLengthLong(): Long = -1
        override fun getContentType(): String = "application/json"
        override fun setFixedLengthStreamingMode(contentLength: Int) {
            fixedLength = contentLength
            super.setFixedLengthStreamingMode(contentLength)
        }
        override fun getOutputStream(): OutputStream {
            assertTrue(fixedLength >= 0)
            assertFalse(instanceFollowRedirects)
            return object : OutputStream() {
                override fun write(value: Int) { written.write(value) }
                override fun close() { outputClosed = true }
            }
        }
        override fun getInputStream(): InputStream {
            inputOpened = true
            return object : ByteArrayInputStream(payload) {
                override fun close() { inputClosed = true; super.close() }
            }
        }
        override fun getErrorStream(): InputStream? = null
    }
}
