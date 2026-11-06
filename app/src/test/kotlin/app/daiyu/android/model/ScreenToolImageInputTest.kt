package app.daiyu.android.model

import android.graphics.Bitmap
import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.*
import app.daiyu.android.context.ArchiveCodec
import app.daiyu.android.context.ProductionRunContext
import app.daiyu.android.context.TokenTailCompression
import app.daiyu.android.domain.context.CompressionPolicy
import app.daiyu.android.storage.ConversationDatabase
import app.daiyu.android.storage.MessageHistoryCodec
import app.daiyu.android.storage.RunCheckpointCodec
import app.daiyu.android.storage.SQLiteRuntimeStore
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.SQLiteMode
import org.robolectric.annotation.GraphicsMode
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
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
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
            assertEquals(ImageAttachmentFailure.INVALID_IMAGE, (failure as ImageAttachmentException).failure)
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
            val built = rows(protocol, listOf(message))
            // Responses legacy blocks serialize call/result pairs; construct one real parallel batch.
            val source = if (protocol == ProviderProtocol.RESPONSES) JSONArray().also { sorted ->
                for (kind in listOf("function_call", "function_call_output")) {
                    for (i in 0 until built.length()) if (built.getJSONObject(i).optString("type") == kind) sorted.put(built.get(i))
                }
            } else built
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
    @Test fun onlyTheVerifiedProjectionCanAuthorizeScreenImageEstimation() {
        protocols.forEach { protocol ->
            val source = rows(protocol, listOf(history(receipt())))
            val projection = ScreenToolImageInput.prepare(body(protocol, source), source, settings(protocol), true, emptyList()) { false }
            assertEquals(listOf(4 to 3), projection.imageSizes.values.toList())
            assertTrue(RequestInputEstimate.from(projection.request, null, projection.imageSizes) > 0)
            assertTrue(runCatching { RequestInputEstimate.from(projection.request, null) }.exceptionOrNull() is IllegalArgumentException)
        }
    }
    @Test fun cancellationAndModelReplacementFailBeforeEncodingAnImage() {
        val protocol = ProviderProtocol.RESPONSES
        val source = rows(protocol, listOf(history(receipt())))
        assertTrue(runCatching { ScreenToolImageInput.project(body(protocol, source), source, settings(protocol), true, emptyList()) { true } }
            .exceptionOrNull() is ImageAttachmentException)
        assertTrue(runCatching { ScreenToolImageInput.project(body(protocol, source).put("model", "other"), source, settings(protocol), true, emptyList()) { false } }
            .exceptionOrNull() is IllegalArgumentException)
    }
    private fun largePng(): ByteArray {
        val image = png(); val data = ByteArray(1_360_000)
        val type = "raNd".toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply { update(type); update(data) }.value.toInt()
        val chunk = ByteArrayOutputStream().apply {
            write(ByteBuffer.allocate(4).putInt(data.size).array()); write(type); write(data)
            write(ByteBuffer.allocate(4).putInt(crc).array())
        }.toByteArray()
        return image.copyOfRange(0, image.size - 12) + chunk + image.copyOfRange(image.size - 12, image.size)
    }
    @Test fun capacityAndWireEstimatesPricePixelsNotTheScreenshotEncoding() {
        val small = receipt(); val large = receipt(largePng())
        assertTrue(JSONObject(large).getString("data_base64").length >= 1_800_000)
        assertTrue(TokenTailCompression.textTokens(large) > 267904)
        assertEquals(TokenTailCompression.messageTokens(history(small)), TokenTailCompression.messageTokens(history(large)))
        protocols.forEach { protocol ->
            fun estimate(output: String): Long {
                val source = rows(protocol, listOf(history(output)))
                val projected = ScreenToolImageInput.prepare(body(protocol, source), source, settings(protocol), true, emptyList()) { false }
                assertEquals(1, imageCount(projected.request))
                assertFalse(projected.request.toString().contains("data_base64"))
                return RequestInputEstimate.from(projected.request, null, projected.imageSizes)
            }
            assertEquals(estimate(small), estimate(large))
        }
        assertTrue(TokenTailCompression.imageTokens(1216, 2640) > TokenTailCompression.imageTokens(4, 3))
    }

    @Test fun localCapacityMatchesLatestScreenSelectionWithoutChangingOriginalReceipts() {
        val output = receipt(largePng())
        val older = history(output, id = "old"); val newest = history(output, id = "new")
        fun metadata(message: Message, attached: Boolean): Message {
            val block = message.blocks.single() as MessageBlock.Tool
            val image = NativeScreenImage.parse("screen", args, block.result!!)!!
            val value = JSONObject(image.metadata).apply {
                if (attached) put("image_attached", true) else put("image_omitted", "superseded_screen")
            }.toString()
            return message.copy(blocks = listOf(block.copy(result = value)))
        }
        assertEquals(TokenTailCompression.historyTokens(listOf(metadata(older, false), metadata(newest, true))) +
            TokenTailCompression.imageTokens(4, 3) + TokenTailCompression.textTokens(
                NativeScreenImage.parse("screen", args, output)!!.inputLabel("new")),
            TokenTailCompression.historyTokens(listOf(older, newest)))
        val nodeOnly = history("{\"observation_id\":\"new-node\",\"nodes\":[]}", id = "node")
        assertEquals(TokenTailCompression.historyTokens(listOf(metadata(older, false), nodeOnly)),
            TokenTailCompression.historyTokens(listOf(older, nodeOnly)))
        assertEquals(output, (older.blocks.single() as MessageBlock.Tool).result)
        assertEquals(output, (newest.blocks.single() as MessageBlock.Tool).result)
    }

    @Test fun wrongToolsMalformedScreensAndRealMetadataStillConsumeTextCapacity() {
        val output = receipt(largePng())
        val invalid = listOf(
            history(output, name = "business"),
            history(output, arguments = "{\"operation\":\"tap\"}"),
            history(JSONObject(output).put("visual_actions_available", false).toString()),
            history(JSONObject(output).put("width", 5).toString()),
            history(JSONObject(output).put("data_base64", "x".repeat(1_800_000)).toString()),
            history(JSONObject(output).put("extra", "x".repeat(1_200_000)).toString())
        )
        invalid.forEach { assertTrue(TokenTailCompression.messageTokens(it) > 267904) }
        assertTrue(TokenTailCompression.messageTokens(history("x".repeat(1_200_000), name = "read")) > 267904)
    }

    @Test fun nativeChatLargePngUsesOnlyTheBoundedNativeReceiptAllowance() {
        val large = largePng()
        assertNotNull(NativeScreenImage.parse("screen", args, receipt(large)))
        assertTrue(receipt(large).length > 1_000_000)
        live(ProviderProtocol.CHAT_COMPLETIONS, receipt(large))
    }
    @Test fun largeNativeProtocolSegmentsSurviveHistoryArchiveCheckpointAndRecovery() {
        val output = receipt(largePng())
        protocols.forEach { protocol ->
            val original = history(output)
            val wire = rows(protocol, listOf(original))
            val items = (0 until wire.length()).map { wire.getJSONObject(it).toString() }
            assertTrue(items.any { it.length > 1_000_000 })
            val segment = MessageHistorySegment(original.id, original.role, original.blocks, items)
            assertEquals(listOf(segment), MessageHistoryCodec.decode(MessageHistoryCodec.encode(listOf(segment))))
            val owner = original.copy(protocolItems = items, historySegments = listOf(segment))
            assertEquals(listOf(owner), ArchiveCodec.decode(ArchiveCodec.encode(listOf(owner))))
            val checkpoint = RunCheckpoint(owner.copy(status = RunStatus.RUNNING), emptyList(), ExecutionPhase.BOUNDARY,
                owner = "owner", revision = 1)
            assertEquals(checkpoint, RunCheckpointCodec.decode(RunCheckpointCodec.encode(checkpoint)))
            val recovered = ProcessRecovery.interrupt(checkpoint)
            assertFalse(recovered.messageReconciled)
            val reconciled = recovered.copy(messageReconciled = true)
            assertEquals(reconciled, RunCheckpointCodec.decode(RunCheckpointCodec.encode(reconciled)))
            assertEquals(items, rows(protocol, listOf(owner)).let { array ->
                (0 until array.length()).map { array.getJSONObject(it).toString() }
            })
        }
    }

    @Test fun largeHistoryAllowanceRejectsBusinessOrOrphanConflictingAndDuplicateReceipts() {
        val output = receipt(largePng())
        val original = history(output)
        val wire = rows(ProviderProtocol.RESPONSES, listOf(original))
        val call = wire.getJSONObject(0).toString()
        val result = wire.getJSONObject(1).toString()
        fun rejects(items: List<String>, blocks: List<MessageBlock> = original.blocks) {
            assertFalse(ScreenToolImageInput.boundedHistoryReceipt(items, blocks, items.lastIndex))
            val segment = MessageHistorySegment(original.id, original.role, blocks, items)
            assertThrows(IllegalArgumentException::class.java) { MessageHistoryCodec.encode(listOf(segment)) }
        }
        rejects(listOf(result))
        rejects(listOf(call, call, result))
        rejects(listOf(call, result, result))
        val suffix = listOf(call, result, call)
        assertFalse(ScreenToolImageInput.boundedHistoryReceipt(suffix, original.blocks, 1))
        val messageRows = rows(ProviderProtocol.MESSAGES, listOf(original))
        val messageResult = messageRows.getJSONObject(1).getJSONArray("content")
        val good = messageResult.getJSONObject(0)
        messageRows.getJSONObject(1).put("content", JSONArray()
            .put(JSONObject(good.toString()).put("is_error", true).put("content", "failed")).put(good))
        val duplicateError = (0 until messageRows.length()).map { messageRows.getJSONObject(it).toString() }
        rejects(duplicateError)
        rejects(listOf(JSONObject(call).put("name", "business").toString(), result))
        rejects(listOf(call, result), listOf((original.blocks.single() as MessageBlock.Tool).copy(result = "different")))
        rejects(listOf(call, JSONObject(result).put("call_id", "other").toString()))
        rejects(listOf("x".repeat(1_000_001)), emptyList())
        val inflated = history(JSONObject(output).put("unrelated", "x".repeat(1_000_001)).toString())
        val inflatedRows = rows(ProviderProtocol.RESPONSES, listOf(inflated))
        rejects((0 until inflatedRows.length()).map { inflatedRows.getJSONObject(it).toString() }, inflated.blocks)
        val invalid = history(JSONObject(output).put("width", 99).toString())
        val invalidRows = rows(ProviderProtocol.RESPONSES, listOf(invalid))
        rejects((0 until invalidRows.length()).map { invalidRows.getJSONObject(it).toString() }, invalid.blocks)
        val segment = MessageHistorySegment(original.id, original.role, emptyList(), listOf("x".repeat(1_000_000)))
        assertEquals(listOf(segment), MessageHistoryCodec.decode(MessageHistoryCodec.encode(listOf(segment))))
    }

    private fun actionEvents(): String {
        val action = JSONObject().put("type", "function_call").put("id", "fc_action").put("call_id", "action")
            .put("name", "screen_action").put("arguments", "{}")
        return "data: ${JSONObject().put("type", "response.output_item.done").put("item", action)}\n\n" +
            "data: ${JSONObject().put("type", "response.completed").put("response", JSONObject().put("status", "completed")
                .put("output", JSONArray().put(action)).put("usage", JSONObject().put("input_tokens", 9734).put("output_tokens", 139)))}\n\n"
    }

    private fun runnerStorage(failProtocolCommit: Boolean) {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("conversations.db")
        val database = ConversationDatabase(context)
        var runtime: SQLiteRuntimeStore? = null
        try {
            val store = SQLiteRuntimeStore.acquire(database).also { runtime = it; it.recoverPending() }
            val conversation = database.create("default").id
            val user = Message(MessageId("user"), conversation, RunId("run"), "user",
                listOf(MessageBlock.Text("question", "synthetic screen task")), RunStatus.COMPLETED)
            val assistant = user.copy(id = MessageId("assistant"), role = "assistant", blocks = emptyList(), status = RunStatus.RUNNING)
            database.send(user, assistant)
            val output = receipt(largePng())
            val capacity = ProductionRunContext(CompressionPolicy(true, 272000, reserveTokens = 4096),
                assistant, null, automatic = false)
            assertTrue(capacity.localTokens(listOf(user, assistant.copy(blocks = history(output).blocks)), emptyList()) < capacity.sendBudgetTokens)
            var posts = 0; var actions = 0
            val port = ResponsesModel(settings(ProviderProtocol.RESPONSES), {
                Connection(when (posts++) {
                    0 -> events(ProviderProtocol.RESPONSES, true)
                    1 -> actionEvents()
                    else -> events(ProviderProtocol.RESPONSES, false)
                })
            }, true)
            val recording = object : AtomicRuntimeStore by store {
                override fun commitCheckpoint(checkpoint: RunCheckpoint) {
                    if (failProtocolCommit && checkpoint.message.protocolItems.any { it.length > 1_000_000 })
                        throw IllegalStateException("injected protocol commit failure")
                    store.commitCheckpoint(checkpoint)
                }
            }
            val runner = ChatRunner(port, database, BatteryTool { call ->
                when (call.name) {
                    "screen" -> output
                    "screen_action" -> {
                        val committed = SQLiteRuntimeStore.readCheckpointSnapshot(database.readableDatabase, assistant.id.value)!!
                        assertEquals(ExecutionPhase.TOOL_EXECUTING, committed.phase)
                        assertFalse(committed.requestUnconfirmed)
                        assertTrue(committed.message.historySegments.any { it.protocolItems.any { raw -> raw.length > 1_000_000 } })
                        assertEquals(output, (committed.message.blocks.first() as MessageBlock.Tool).result)
                        assertEquals(ToolDisposition.IN_FLIGHT, committed.tools.single { it.invocation.id == call.id }.disposition)
                        actions++; "synthetic action confirmed"
                    }
                    else -> error("unexpected tool")
                }
            }, RunnerOptions(runtimeStore = recording, contextRuntime = capacity)) {}
            if (failProtocolCommit) {
                assertThrows(Exception::class.java) { runner.startSlice(assistant, listOf(user)) }
                val kept = SQLiteRuntimeStore.readCheckpointSnapshot(database.readableDatabase, assistant.id.value)!!
                assertEquals(ExecutionPhase.STREAMING, kept.phase); assertTrue(kept.requestUnconfirmed)
                assertEquals(ToolDisposition.READY, kept.tools.single { it.invocation.name == "screen_action" }.disposition)
                assertTrue(kept.message.protocolItems.all { it.length <= 1_000_000 })
                assertEquals(139L, kept.usage.last().snapshot.outputTokens)
                assertEquals(0, actions); assertEquals(2, posts)
            } else {
                val completed = runner.startSlice(assistant, listOf(user)) as RunSlice.Completed
                assertEquals(RunStatus.COMPLETED, completed.message.status)
                assertEquals(1, actions); assertEquals(3, posts)
                assertEquals(completed.message, database.messages(conversation).last().second)
                val saved = SQLiteRuntimeStore.readCheckpointSnapshot(database.readableDatabase, assistant.id.value)!!
                assertEquals(saved, store.readCommittedCheckpoint(saved))
                assertNull(saved.context!!.capacity)
                assertEquals(output, (saved.message.blocks.first() as MessageBlock.Tool).result)
                assertTrue(saved.message.historySegments.any { it.protocolItems.any { raw -> raw.length > 1_000_000 } })
                val backup = database.exportConversationBackup(conversation)
                store.close(); runtime = null; database.close()
                context.deleteDatabase("conversations.db")
                ConversationDatabase(context).use { restored ->
                    assertEquals(1 to 2, restored.importBackup(backup))
                    assertEquals(completed.message, restored.messages(conversation).last().second)
                }
            }
        } finally {
            runtime?.close(); database.close(); context.deleteDatabase("conversations.db")
        }
    }

    @Test fun responsesLargeScreenshotCommitsBeforeTheNextSyntheticActionAndRoundTripsBackup() = runnerStorage(false)
    @Test fun failedTailProtocolCommitPreservesReadyAndUsageWithoutExecutingOrReposting() = runnerStorage(true)

}
