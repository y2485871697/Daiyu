package app.daiyu.android.model

import app.daiyu.android.domain.*
import app.daiyu.android.media.*
import app.daiyu.android.model.images.*
import app.daiyu.android.storage.ModelSettings
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.URL
import java.security.MessageDigest
import java.security.cert.Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

class OfficialImageFilesTest {
    private val settings = ModelSettings("https://api.openai.com/v1/responses", "fixture", "synthetic-key", false)
    private val bytes = byteArrayOf(1, 2, 3, 4)
    private val owner = app.daiyu.android.domain.MessageImageOwner("c", "u")
    private val row = Message(MessageId("u"), ConversationId("c"), RunId("r"), "user", listOf(
        MessageBlock.Image(app.daiyu.android.domain.MessageImageReference(owner, "00000000-0000-0000-0000-000000000001", "image/png",
            bytes.size, MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }, 2, 2))), RunStatus.COMPLETED)
    private fun plan(config: ModelSettings) = FrozenMessageImagePlan.freeze(listOf(row),
        ProviderSnapshot.capture(config, config.protocol, true), object : MessageImageResolver {
            override fun read(owner: app.daiyu.android.media.MessageImageOwner, reference: app.daiyu.android.media.MessageImageReference) = bytes.copyOf()
            override fun freeze(owner: app.daiyu.android.media.MessageImageOwner, references: List<app.daiyu.android.media.MessageImageReference>,
                imageInputSupported: Boolean, isCancelled: () -> Boolean) = references.map { FrozenImageAttachment(it.asAttachment(), bytes) }
        }, { owner }, ImageInputEncoder(ImageInspector { ImageDimensions("image/png", 2, 2) }))
    private fun done(tool: Boolean): String {
        val output = JSONArray()
        val call = JSONObject().put("type", "function_call").put("id", "fc").put("call_id", "call").put("name", "lookup").put("arguments", "{}")
        if (tool) output.put(call)
        return (if (tool) "data: ${JSONObject().put("type", "response.output_item.done").put("item", call)}\n\n" else "") +
            "data: ${JSONObject().put("type", "response.completed").put("response", JSONObject().put("status", "completed").put("output", output))}\n\n"
    }
    private class Connection(url: URL, response: String, private val content: String = "application/json") : HttpsURLConnection(url) {
        val sent = ByteArrayOutputStream()
        private val input = response.byteInputStream()
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getOutputStream(): java.io.OutputStream { assertTrue(fixedContentLengthLong > 0); return sent }
        override fun getInputStream() = input
        override fun getResponseCode() = 200
        override fun getContentType() = content
    }
    @Test fun actualResponsesTransportUploadsOnceAcrossToolRoundsAndUsesFileId() {
        val upload = Connection(URL("https://api.openai.com/v1/files"), "{\"id\":\"file-fixture\"}")
        val first = Connection(URL(settings.endpoint), done(true), "text/event-stream")
        val second = Connection(URL(settings.endpoint), done(false), "text/event-stream")
        val deleted = CountDownLatch(1)
        var uploads = 0; var posts = 0
        val session = ResponsesModel(settings, { url ->
            when (url.path) {
                "/v1/files" -> { uploads++; upload }
                "/v1/responses" -> if (posts++ == 0) first else second
                "/v1/files/file-fixture" -> { deleted.countDown(); Connection(url, "{}") }
                else -> error("unexpected endpoint")
            }
        }, true).open(listOf(row), listOf(ToolDefinition("lookup", "fixture", "{}")), plan(settings))
        session.next(emptyList()) {}
        assertEquals(1, uploads); assertEquals(1L, deleted.count)
        session.next(listOf(ToolOutput(ToolCallId("call"), "ok"))) {}
        assertEquals(1, uploads); assertEquals(2, posts)
        for (connection in listOf(first, second)) {
            val image = JSONObject(connection.sent.toString("UTF-8")).getJSONArray("input").getJSONObject(0)
                .getJSONArray("content").getJSONObject(0)
            assertEquals("input_image", image.getString("type")); assertEquals("file-fixture", image.getString("file_id"))
            assertFalse(image.has("image_url"))
        }
        assertTrue(upload.sent.toString("UTF-8").contains("name=\"purpose\"\r\n\r\nvision"))
        assertTrue(upload.sent.toString("UTF-8").contains("expires_after[seconds]"))
        assertTrue(deleted.await(3, TimeUnit.SECONDS))
    }
    @Test fun failedContinuationPreflightDeletesPreviouslyUploadedFile() {
        val deleted = CountDownLatch(1)
        var posts = 0
        val session = ResponsesModel(settings, { url ->
            when (url.path) {
                "/v1/files" -> Connection(url, "{\"id\":\"file-fixture\"}")
                "/v1/responses" -> { posts++; Connection(url, done(true), "text/event-stream") }
                "/v1/files/file-fixture" -> { deleted.countDown(); Connection(url, "{}") }
                else -> error("unexpected endpoint")
            }
        }, true).open(listOf(row), listOf(ToolDefinition("lookup", "fixture", "{}")), plan(settings))
        session.next(emptyList()) {}
        assertEquals(1L, deleted.count)
        assertThrows(ImageAttachmentException::class.java) {
            session.next(listOf(ToolOutput(ToolCallId("call"), "x".repeat(ImageRequestPreflight.MAX_REQUEST_BYTES + 1)))) {}
        }
        assertEquals(1, posts)
        assertTrue(deleted.await(3, TimeUnit.SECONDS))
    }
    @Test fun protocolDeliveryFailureDeletesUploadedFileEvenWithPendingCalls() {
        val deleted = CountDownLatch(1)
        val session = ResponsesModel(settings, { url ->
            when (url.path) {
                "/v1/files" -> Connection(url, "{\"id\":\"file-fixture\"}")
                "/v1/responses" -> Connection(url, done(true), "text/event-stream")
                "/v1/files/file-fixture" -> { deleted.countDown(); Connection(url, "{}") }
                else -> error("unexpected endpoint")
            }
        }, true).open(listOf(row), listOf(ToolDefinition("lookup", "fixture", "{}")), plan(settings))
        val failure = IllegalStateException("fixture delivery failure")
        val actual = assertThrows(IllegalStateException::class.java) {
            session.next(emptyList()) { if (it is ModelEvent.ProtocolOutput) throw failure }
        }
        assertSame(failure, actual)
        assertTrue(deleted.await(3, TimeUnit.SECONDS))
    }
    @Test fun incompatibleEndpointsAndHeadersNeverClaimFileCapability() {
        assertTrue(OfficialImageFiles.supported(settings))
        for (config in listOf(settings.copy(endpoint = "https://gateway.invalid/v1/responses"),
            settings.copy(apiKey = ""), settings.copy(protocol = ProviderProtocol.MESSAGES),
            settings.copy(customHeaders = ProviderHeaders.parse("{\"OpenAI-Project\":\"project-fixture\"}")))) {
            assertFalse(OfficialImageFiles.supported(config))
        }
    }
    @Test fun changedModelFailsBeforeAnyUploadConnection() {
        val config = settings.copy(requestOptions = ProviderRequestOptions("{\"model\":\"other\"}"))
        var connections = 0
        val session = ResponsesModel(config, { connections++; error("must not connect") }, true)
            .open(listOf(row), emptyList<ToolDefinition>(), plan(config))
        assertThrows(IllegalArgumentException::class.java) { session.next(emptyList()) {} }
        assertEquals(0, connections)
    }
}
