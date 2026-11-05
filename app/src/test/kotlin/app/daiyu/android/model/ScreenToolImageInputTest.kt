package app.daiyu.android.model

import android.graphics.Bitmap
import app.daiyu.android.domain.*
import app.daiyu.android.media.ImageAttachmentException
import app.daiyu.android.media.ImageAttachmentFailure
import app.daiyu.android.media.NativeScreenImage
import app.daiyu.android.storage.ModelSettings
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.net.URL
import java.nio.ByteBuffer
import java.security.cert.Certificate
import java.util.Base64
import java.util.zip.CRC32
import javax.net.ssl.HttpsURLConnection

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ScreenToolImageInputTest {
    private val protocols = listOf(ProviderProtocol.RESPONSES, ProviderProtocol.MESSAGES, ProviderProtocol.CHAT_COMPLETIONS)
    private val args = """{"operation":"observe"}"""
    private val definition = ToolDefinition("screen", "fixture", """{"type":"object","properties":{}}""", false)
    private fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)); bitmap.recycle(); stream.toByteArray()
        }
    }
    private fun receipt(bytes: ByteArray = png(), capture: String = "capture"): String = JSONObject()
        .put("screen_read_mode", "visual_fallback").put("node_tree_available", false)
        .put("node_failure_code", "APPLICATION_WINDOW_NOT_ACTIVE_NOT_EXECUTED")
        .put("visual_actions_available", true).put("capture_id", capture).put("width", 4).put("height", 3)
        .put("mime_type", "image/png").put("data_base64", Base64.getEncoder().encodeToString(bytes)).toString()
    private fun settings(protocol: ProviderProtocol): ModelSettings = ModelSettings(
        "https://example.invalid/v1/" + when (protocol) {
            ProviderProtocol.RESPONSES -> "responses"
            ProviderProtocol.MESSAGES -> "messages"
            else -> "chat/completions"
        }, "fixture", "synthetic-key", false, protocol = protocol)
    private fun history(output: String, name: String = "screen", arguments: String = args, id: String = "call") =
        Message(MessageId(id), ConversationId("c"), RunId("r"), "assistant", listOf(
            MessageBlock.Tool(ToolInvocation(ToolCallId(id), name, arguments), output)), RunStatus.COMPLETED)
    private fun rows(protocol: ProviderProtocol, history: List<Message>): JSONArray = when (protocol) {
        ProviderProtocol.RESPONSES -> ResponsesRequest.history(history)
        ProviderProtocol.MESSAGES -> MessagesRequest.history(history)
        else -> NativeCompletionRequest.history(protocol, history)
    }
    private fun body(protocol: ProviderProtocol, rows: JSONArray): JSONObject = when (protocol) {
        ProviderProtocol.RESPONSES -> ResponsesRequest.body("fixture", rows, listOf(definition))
        ProviderProtocol.MESSAGES -> MessagesRequest.body("fixture", rows, listOf(definition), 4096)
        else -> NativeCompletionRequest.body(settings(protocol), rows, listOf(definition), null)
    }
    private fun imageCount(node: Any?): Int = when (node) {
        is JSONObject -> (if (node.optString("type") in setOf("input_image", "image", "image_url")) 1 else 0) +
            node.keys().asSequence().sumOf { imageCount(node.opt(it)) }
        is JSONArray -> (0 until node.length()).sumOf { imageCount(node.opt(it)) }
        else -> 0
    }
    private class Connection(events: String) : HttpsURLConnection(URL("https://example.invalid/fixture")) {
        val sent = ByteArrayOutputStream()
        private val incoming = events.byteInputStream()
        override fun connect() {}
        override fun disconnect() { incoming.close() }
        override fun usingProxy() = false
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getOutputStream() = sent
        override fun getInputStream() = incoming
        override fun getResponseCode() = 200
        override fun getContentType() = "text/event-stream"
    }
    private fun events(protocol: ProviderProtocol, tool: Boolean): String {
        if (protocol == ProviderProtocol.RESPONSES) {
            val call = JSONObject().put("type", "function_call").put("id", "fc").put("call_id", "call")
                .put("name", "screen").put("arguments", args)
            return (if (tool) "data: ${JSONObject().put("type", "response.output_item.done").put("item", call)}\n\n" else "") +
                "data: ${JSONObject().put("type", "response.completed").put("response", JSONObject().put("status", "completed")
                    .put("output", JSONArray().apply { if (tool) put(call) }))}\n\n"
        }
        if (protocol == ProviderProtocol.CHAT_COMPLETIONS) {
            val delta = JSONObject().put("role", "assistant")
            if (tool) delta.put("tool_calls", JSONArray().put(JSONObject().put("index", 0).put("id", "call")
                .put("type", "function").put("function", JSONObject().put("name", "screen").put("arguments", args))))
            else delta.put("content", "ok")
            return "data: ${JSONObject().put("id", "fixture").put("choices", JSONArray().put(JSONObject().put("index", 0)
                .put("delta", delta).put("finish_reason", if (tool) "tool_calls" else "stop")))}\n\ndata: [DONE]\n\n"
        }
        val block = if (tool) JSONObject().put("type", "tool_use").put("id", "call").put("name", "screen")
            .put("input", JSONObject(args)) else JSONObject().put("type", "text").put("text", "ok")
        return listOf(
            JSONObject().put("type", "message_start").put("message", JSONObject().put("id", "m").put("role", "assistant").put("content", JSONArray())),
            JSONObject().put("type", "content_block_start").put("index", 0).put("content_block", block),
            JSONObject().put("type", "content_block_stop").put("index", 0),
            JSONObject().put("type", "message_delta").put("delta", JSONObject().put("stop_reason", if (tool) "tool_use" else "end_turn")),
            JSONObject().put("type", "message_stop")
        ).joinToString("") { "event: ${it.getString("type")}\ndata: $it\n\n" }
    }
    private fun model(protocol: ProviderProtocol, capability: Boolean?, connect: (URL) -> HttpsURLConnection): ModelPort = when (protocol) {
        ProviderProtocol.RESPONSES -> ResponsesModel(settings(protocol), connect, capability)
        ProviderProtocol.MESSAGES -> MessagesModel(settings(protocol), 4096, connect, capability)
        else -> NativeCompletionModel(settings(protocol), connect, imageInputSupported = capability)
    }
    private fun live(protocol: ProviderProtocol, output: String): List<Connection> {
        val connections = mutableListOf<Connection>()
        val session = model(protocol, true) { Connection(events(protocol, connections.isEmpty())).also(connections::add) }
            .open(emptyList(), listOf(definition))
        session.next(emptyList()) {}
        val delivered = mutableListOf<ModelEvent>()
        session.next(listOf(ToolOutput(ToolCallId("call"), output)), delivered::add)
        assertEquals(2, connections.size)
        val wire = JSONObject(connections.last().sent.toString("UTF-8"))
        assertEquals(1, imageCount(wire)); assertFalse(wire.toString().contains("data_base64"))
        val saved = delivered.filterIsInstance<ModelEvent.ProtocolOutput>().last().items.joinToString()
        assertTrue(saved.contains("data_base64")); assertFalse(saved.contains("image_attached"))
        session.cancel()
        return connections
    }

    @Test fun liveObserveFallbackBecomesNativeImageForAllThreeTransports() {
        protocols.forEach { protocol ->
            val bytes = png(); val output = receipt(bytes)
            val sent = live(protocol, output).last().sent.toString("UTF-8")
            assertTrue(sent.contains(Base64.getEncoder().encodeToString(bytes)))
            assertTrue(sent.contains("image_attached"))
        }
    }
    @Test fun explicitNodeBoundScreenshotUsesTheSameNativeImagePath() {
        protocols.forEach { protocol ->
            val output = JSONObject(receipt()).apply {
                remove("capture_id"); put("observation_id", "obs")
                remove("screen_read_mode"); remove("node_tree_available"); remove("node_failure_code")
            }.toString()
            val source = rows(protocol, listOf(history(output, arguments = """{"operation":"screenshot","observation_id":"obs"}""")))
            val projected = ScreenToolImageInput.project(body(protocol, source), source, settings(protocol), true, emptyList()) { false }
            assertEquals(1, imageCount(projected)); assertFalse(projected.toString().contains("data_base64"))
        }
    }
    @Test fun completedScreenshotHistoryReplaysPixelsWithoutPersistingProjectedImages() {
        protocols.forEach { protocol ->
            val output = receipt()
            val calls = mutableListOf<Connection>()
            val session = model(protocol, true) { Connection(events(protocol, false)).also(calls::add) }
                .open(listOf(history(output)), listOf(definition))
            session.next(emptyList()) {}
            assertEquals(1, imageCount(JSONObject(calls.single().sent.toString("UTF-8"))))
            assertTrue(output.contains("data_base64")); session.cancel()
        }
    }
    @Test fun unsupportedOrUnknownVisionCapabilityFailsBeforeTheContinuationPost() {
        protocols.forEach { protocol -> for (capability in listOf(false, null)) {
            val calls = mutableListOf<Connection>()
            val session = model(protocol, capability) { Connection(events(protocol, true)).also(calls::add) }
                .open(emptyList(), listOf(definition))
            session.next(emptyList()) {}
            val failure = runCatching { session.next(listOf(ToolOutput(ToolCallId("call"), receipt()))) {} }.exceptionOrNull()
            assertTrue(failure.toString(), failure is ImageAttachmentException)
            assertEquals(ImageAttachmentFailure.MODEL_UNSUPPORTED, (failure as ImageAttachmentException).failure)
            assertEquals(1, calls.size); session.cancel()
        } }
    }
    @Test fun aTruncatedPngHeaderNeverReachesTheProvider() {
        protocols.forEach { protocol ->
            val calls = mutableListOf<Connection>()
            val session = model(protocol, true) { Connection(events(protocol, true)).also(calls::add) }
                .open(emptyList(), listOf(definition))
            session.next(emptyList()) {}
            val output = receipt(png().copyOf(33))
            assertNotNull(NativeScreenImage.parse("screen", args, output))
            val failure = runCatching { session.next(listOf(ToolOutput(ToolCallId("call"), output))) {} }.exceptionOrNull()
            assertTrue(failure.toString(), failure is ImageAttachmentException)
            assertEquals(1, calls.size); session.cancel()
        }
    }
    @Test fun onlyLatestScreenImageIsSentAndOldBase64NeverBecomesText() {
        protocols.forEach { protocol ->
            val source = rows(protocol, (0 until 12).map { history(receipt(capture = "capture-$it"), id = "call-$it") })
            val original = source.toString()
            val projected = ScreenToolImageInput.project(body(protocol, source), source, settings(protocol), true, emptyList()) { false }
            assertEquals(1, imageCount(projected)); assertFalse(projected.toString().contains("data_base64"))
            assertTrue(projected.toString().contains("superseded_screen")); assertEquals(original, source.toString())
        }
    }
    @Test fun aNewerNodeObservationDoesNotAttachAnOlderScreenImage() {
        protocols.forEach { protocol ->
            val source = rows(protocol, listOf(history(receipt(), id = "old"),
                history("""{"observation_id":"nodes","nodes":[]}""", id = "new")))
            val projected = ScreenToolImageInput.project(body(protocol, source), source, settings(protocol), false, emptyList()) { false }
            assertEquals(0, imageCount(projected)); assertFalse(projected.toString().contains("data_base64"))
        }
    }
    @Test fun businessToolsMalformedObserveAndUnmatchedOutputsCannotBecomeImages() {
        protocols.forEach { protocol ->
            val output = receipt()
            val cases = listOf(history(output, name = "other"), history(output, arguments = """{"operation":"tap"}"""),
                history(JSONObject(output).apply { remove("screen_read_mode") }.toString()),
                history(JSONObject(output).put("width", 9).toString()))
            cases.forEach { item ->
                val source = rows(protocol, listOf(item)); val request = body(protocol, source)
                val projected = ScreenToolImageInput.project(request, source, settings(protocol), true, emptyList()) { false }
                assertEquals(0, imageCount(projected)); assertEquals(request.toString(), projected.toString())
            }
            val source = rows(protocol, listOf(history(output)))
            val injected = rows(protocol, listOf(history(receipt(capture = "different"))))
            val projected = ScreenToolImageInput.project(body(protocol, injected), source, settings(protocol), true, emptyList()) { false }
            assertEquals(0, imageCount(projected))
        }
    }
    @Test fun businessCallsCannotUseTheNativeScreenshotReceiptAllowance() {
        val protocol = ProviderProtocol.CHAT_COMPLETIONS
        val output = receipt()
        val source = rows(protocol, listOf(history(output, name = "other")))
        val result = source.getJSONObject(source.length() - 1)
        val calls = JSONArray()
        for (i in 0 until source.length() - 1) calls.put(source.get(i))
        assertFalse(ScreenToolImageInput.boundedNativeReceipt(calls, protocol, result))
        val real = rows(protocol, listOf(history(output)))
        val realCalls = JSONArray()
        for (i in 0 until real.length() - 1) realCalls.put(real.get(i))
        assertTrue(ScreenToolImageInput.boundedNativeReceipt(realCalls, protocol, real.getJSONObject(real.length() - 1)))
    }
    @Test fun imageRowsFollowTheWholeToolResultBatch() {
        for (protocol in listOf(ProviderProtocol.RESPONSES, ProviderProtocol.CHAT_COMPLETIONS)) {
            val message = history(receipt()).copy(blocks = listOf(
                MessageBlock.Tool(ToolInvocation(ToolCallId("call"), "screen", args), receipt()),
                MessageBlock.Tool(ToolInvocation(ToolCallId("other"), "other", "{}"), "ok")))
            val source = rows(protocol, listOf(message))
            val request = ScreenToolImageInput.project(body(protocol, source), source, settings(protocol), true, emptyList()) { false }
            val result = request.getJSONArray(if (protocol == ProviderProtocol.RESPONSES) "input" else "messages")
            assertEquals("user", result.getJSONObject(result.length() - 1).optString("role"))
            val before = result.getJSONObject(result.length() - 2)
            assertEquals(if (protocol == ProviderProtocol.RESPONSES) "function_call_output" else "tool",
                before.optString(if (protocol == ProviderProtocol.RESPONSES) "type" else "role"))
        }
    }
    @Test fun messagesSplitResultRowsReceiveTheImageOnlyAfterTheLastResult() {
        val protocol = ProviderProtocol.MESSAGES
        val calls = JSONArray().put(JSONObject().put("type", "tool_use").put("id", "call").put("name", "screen").put("input", JSONObject(args)))
            .put(JSONObject().put("type", "tool_use").put("id", "other").put("name", "other").put("input", JSONObject()))
        val source = JSONArray().put(MessagesRequest.message("assistant", calls))
            .put(MessagesRequest.message("user", JSONArray().put(MessagesRequest.result("call", receipt()))))
            .put(MessagesRequest.message("user", JSONArray().put(MessagesRequest.result("other", "ok"))))
        val original = source.toString()
        val projected = ScreenToolImageInput.project(body(protocol, source), source, settings(protocol), true, emptyList()) { false }
        val messages = projected.getJSONArray("messages")
        assertEquals(1, messages.getJSONObject(1).getJSONArray("content").length())
        val content = messages.getJSONObject(2).getJSONArray("content")
        assertEquals("tool_result", content.getJSONObject(0).getString("type"))
        assertEquals("text", content.getJSONObject(1).getString("type"))
        assertEquals("image", content.getJSONObject(2).getString("type"))
        assertEquals(1, imageCount(projected)); assertEquals(original, source.toString())
    }
    @Test fun cancellationAndModelReplacementFailBeforeEncodingAnImage() {
        val protocol = ProviderProtocol.RESPONSES
        val source = rows(protocol, listOf(history(receipt())))
        assertTrue(runCatching { ScreenToolImageInput.project(body(protocol, source), source, settings(protocol), true, emptyList()) { true } }
            .exceptionOrNull() is ImageAttachmentException)
        assertTrue(runCatching { ScreenToolImageInput.project(body(protocol, source).put("model", "other"), source, settings(protocol), true, emptyList()) { false } }
            .exceptionOrNull() is IllegalArgumentException)
    }
    @Test fun nativeChatLargePngUsesOnlyTheBoundedNativeReceiptAllowance() {
        val image = png(); val data = ByteArray(1_050_000)
        val type = "raNd".toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply { update(type); update(data) }.value.toInt()
        val chunk = ByteArrayOutputStream().apply {
            write(ByteBuffer.allocate(4).putInt(data.size).array()); write(type); write(data)
            write(ByteBuffer.allocate(4).putInt(crc).array())
        }.toByteArray()
        val large = image.copyOfRange(0, image.size - 12) + chunk + image.copyOfRange(image.size - 12, image.size)
        assertNotNull(NativeScreenImage.parse("screen", args, receipt(large)))
        assertTrue(receipt(large).length > 1_000_000)
        live(ProviderProtocol.CHAT_COMPLETIONS, receipt(large))
    }
}
