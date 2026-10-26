package app.daiyu.android.model

import app.daiyu.android.domain.*
import app.daiyu.android.media.FrozenImageAttachment
import app.daiyu.android.media.ImageAttachmentException
import app.daiyu.android.media.ImageAttachmentFailure
import app.daiyu.android.media.ImageAttachmentLimits
import app.daiyu.android.media.ImageDimensions
import app.daiyu.android.media.ImageInspector
import app.daiyu.android.media.MessageImageResolver
import app.daiyu.android.model.images.FrozenMessageImagePlan
import app.daiyu.android.model.images.ImageInputEncoder
import app.daiyu.android.storage.ModelSettings
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.URL
import java.security.MessageDigest
import java.security.cert.Certificate
import java.util.Base64
import javax.net.ssl.HttpsURLConnection

class ImageModelInputTest {
    private val protocols = listOf(ProviderProtocol.RESPONSES, ProviderProtocol.MESSAGES, ProviderProtocol.CHAT_COMPLETIONS)
    private val rawCodeInterpreterImage = """{"type":"code_interpreter_call","id":"ci_1","container_id":"cntr_1","status":"completed","code":"print(1)","outputs":[{"type":"image","url":"https://example.invalid/x.png"}]}"""
    private val original = byteArrayOf(1, 2, 3, 4)
    private val digest = MessageDigest.getInstance("SHA-256").digest(original)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private val encoder = ImageInputEncoder(ImageInspector { ImageDimensions("image/png", 2, 2) })
    private fun endpoint(protocol: ProviderProtocol, host: String = "example.invalid") =
        "https://$host/v1/" + when (protocol) {
            ProviderProtocol.RESPONSES -> "responses"
            ProviderProtocol.MESSAGES -> "messages"
            ProviderProtocol.CHAT_COMPLETIONS -> "chat/completions"
            else -> error("fixture")
        }
    private fun settings(protocol: ProviderProtocol) = ModelSettings(
        endpoint(protocol), "fixture", "synthetic-key", false, protocol = protocol)
    private fun reference(message: String) = MessageImageReference(MessageImageOwner("c", message),
        "00000000-0000-0000-0000-000000000001", "image/png", original.size, digest, 2, 2)
    private fun row(id: String = "u") = Message(MessageId(id), ConversationId("c"), RunId("r"), "user", listOf(
        MessageBlock.Text("before", "前"), MessageBlock.Image(reference(id)), MessageBlock.Text("after", "后")
    ), RunStatus.COMPLETED)
    private fun expected(row: Message) = MessageImageOwner(row.conversationId.value, row.id.value)

    private inner class Resolver : MessageImageResolver {
        val owners = mutableListOf<app.daiyu.android.media.MessageImageOwner>()
        var failure: ImageAttachmentFailure? = null
        override fun read(owner: app.daiyu.android.media.MessageImageOwner,
                          reference: app.daiyu.android.media.MessageImageReference): ByteArray = error("freeze only")
        override fun freeze(owner: app.daiyu.android.media.MessageImageOwner,
                            references: List<app.daiyu.android.media.MessageImageReference>,
                            imageInputSupported: Boolean, isCancelled: () -> Boolean): List<FrozenImageAttachment> {
            assertTrue(imageInputSupported)
            owners.add(owner)
            failure?.let { throw ImageAttachmentException(it) }
            if (isCancelled()) throw ImageAttachmentException(ImageAttachmentFailure.CANCELLED)
            return references.map { FrozenImageAttachment(it.asAttachment(), original) }
        }
    }
    private fun plan(config: ModelSettings, history: List<Message>, resolver: Resolver = Resolver(),
                     capability: Boolean? = true): FrozenMessageImagePlan = FrozenMessageImagePlan.freeze(
        history, ProviderSnapshot.capture(config, config.protocol, capability), resolver, ::expected, encoder)

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
    private fun events(protocol: ProviderProtocol, tool: Boolean = false, toolInput: String = "{}",
                       responseItem: JSONObject? = null): String {
        if (protocol == ProviderProtocol.RESPONSES) {
            val call = JSONObject().put("type", "function_call").put("id", "fc")
                .put("call_id", "call").put("name", "lookup").put("arguments", toolInput)
            val output = JSONArray()
            if (tool) output.put(call)
            if (responseItem != null) output.put(responseItem)
            val done = JSONObject().put("type", "response.completed").put("response", JSONObject()
                .put("status", "completed").put("output", output))
            return (if (tool) "data: ${JSONObject().put("type", "response.output_item.done").put("item", call)}\n\n" else "") +
                "data: $done\n\n"
        }
        if (protocol == ProviderProtocol.CHAT_COMPLETIONS) {
            val delta = JSONObject().put("role", "assistant")
            if (tool) delta.put("tool_calls", JSONArray().put(JSONObject().put("index", 0).put("id", "call")
                .put("type", "function").put("function", JSONObject().put("name", "lookup").put("arguments", toolInput))))
            else delta.put("content", "ok")
            val chunk = JSONObject().put("id", "fixture").put("choices", JSONArray().put(JSONObject()
                .put("index", 0).put("delta", delta).put("finish_reason", if (tool) "tool_calls" else "stop")))
            return "data: $chunk\n\n" + "data: [DONE]\n\n"
        }
        val block = if (tool) JSONObject().put("type", "tool_use").put("id", "call")
            .put("name", "lookup").put("input", JSONObject(toolInput)) else JSONObject().put("type", "text").put("text", "ok")
        return listOf(
            JSONObject().put("type", "message_start").put("message", JSONObject().put("id", "m")
                .put("role", "assistant").put("content", JSONArray())),
            JSONObject().put("type", "content_block_start").put("index", 0).put("content_block", block),
            JSONObject().put("type", "content_block_stop").put("index", 0),
            JSONObject().put("type", "message_delta").put("delta", JSONObject().put("stop_reason", if (tool) "tool_use" else "end_turn")),
            JSONObject().put("type", "message_stop")
        ).joinToString("") { "event: ${it.getString("type")}\ndata: $it\n\n" }
    }
    private fun model(config: ModelSettings, capability: Boolean? = true,
                      connect: (URL) -> HttpsURLConnection): ModelPort = when (config.protocol) {
        ProviderProtocol.RESPONSES -> ResponsesModel(config, connect, capability)
        ProviderProtocol.MESSAGES -> MessagesModel(config, 4096, connect, capability)
        ProviderProtocol.CHAT_COMPLETIONS -> NativeCompletionModel(config, connect, imageInputSupported = capability)
        else -> error("fixture")
    }
    private fun rejected(work: () -> Unit): Throwable {
        val failure = runCatching(work).exceptionOrNull()
        assertNotNull("must fail closed", failure)
        return failure!!
    }
    @Test fun ignoredSyntaxCannotBypassRawMediaAndReplayedSyntaxRemainsStrict() {
        for (protocol in listOf(ProviderProtocol.RESPONSES, ProviderProtocol.MESSAGES)) {
            val ignored = row().copy(role = "assistant", blocks = emptyList(), status = RunStatus.STOPPED,
                protocolItems = listOf("{"))
            FrozenMessageImagePlan.rejectRawImages(listOf(ignored), protocol)
            assertNotNull(rejected { FrozenMessageImagePlan.rejectRawImages(
                listOf(ignored.copy(protocolItems = listOf(rawCodeInterpreterImage))), protocol) })
            val malformedMedia = listOf(
                "{'type':'input_image','image_url':'https://example.invalid/x.png'}",
                "{'type':'input_video','video_url':'https://example.invalid/x.mp4'}",
                "{\"type\":\"input_\\u0069mage\",\"metadata\":" + "[".repeat(130) + "0" + "]".repeat(130) + "}",
            )
            malformedMedia.forEach { raw ->
                assertTrue(rejected { FrozenMessageImagePlan.rejectRawImages(
                    listOf(ignored.copy(protocolItems = listOf(raw))), protocol) } is IllegalArgumentException)
            }
            for (status in listOf(RunStatus.COMPLETED, RunStatus.INTERRUPTED)) {
                assertTrue(rejected { FrozenMessageImagePlan.rejectRawImages(
                    listOf(ignored.copy(status = status)), protocol) } is org.json.JSONException)
            }
        }
        val user = row().copy(blocks = emptyList(), protocolItems = listOf("{"))
        FrozenMessageImagePlan.rejectRawImages(listOf(user), ProviderProtocol.RESPONSES)
        assertTrue(rejected { FrozenMessageImagePlan.rejectRawImages(
            listOf(user), ProviderProtocol.MESSAGES) } is org.json.JSONException)
    }

    private fun history(protocol: ProviderProtocol, rows: List<Message>, images: FrozenMessageImagePlan? = null): JSONArray =
        when (protocol) {
            ProviderProtocol.RESPONSES -> ResponsesRequest.history(rows, images)
            ProviderProtocol.MESSAGES -> MessagesRequest.history(rows, images)
            ProviderProtocol.CHAT_COMPLETIONS -> NativeCompletionRequest.history(protocol, rows, images = images)
            else -> error("fixture")
        }
    private fun body(config: ModelSettings, rows: JSONArray): JSONObject = when (config.protocol) {
        ProviderProtocol.RESPONSES -> ResponsesRequest.body(config.model, rows, emptyList())
        ProviderProtocol.MESSAGES -> MessagesRequest.body(config.model, rows, emptyList(), 4096)
        ProviderProtocol.CHAT_COMPLETIONS -> NativeCompletionRequest.body(config, rows, emptyList(), null)
        else -> error("fixture")
    }
    private fun arguments(protocol: ProviderProtocol, item: JSONObject): String = when (protocol) {
        ProviderProtocol.RESPONSES -> item.getString("arguments")
        ProviderProtocol.MESSAGES -> item.getJSONArray("content").getJSONObject(0).getJSONObject("input").toString()
        ProviderProtocol.CHAT_COMPLETIONS -> item.getJSONArray("tool_calls").getJSONObject(0)
            .getJSONObject("function").getString("arguments")
        else -> error("fixture")
    }

    private fun content(protocol: ProviderProtocol, body: JSONObject, index: Int = 0): JSONArray =
        body.getJSONArray(if (protocol == ProviderProtocol.RESPONSES) "input" else "messages")
            .getJSONObject(index).getJSONArray("content")
    private fun assertMixed(protocol: ProviderProtocol, content: JSONArray) {
        assertEquals(3, content.length())
        assertEquals("前", content.getJSONObject(0).getString("text"))
        assertEquals("后", content.getJSONObject(2).getString("text"))
        val image = content.getJSONObject(1)
        if (protocol == ProviderProtocol.RESPONSES) {
            assertEquals("input_text", content.getJSONObject(0).getString("type"))
            assertEquals("input_image", image.getString("type"))
            assertEquals("data:image/png;base64,${Base64.getEncoder().encodeToString(original)}", image.getString("image_url"))
        } else if (protocol == ProviderProtocol.CHAT_COMPLETIONS) {
            assertEquals("text", content.getJSONObject(0).getString("type"))
            assertEquals("text", content.getJSONObject(2).getString("type"))
            assertEquals("image_url", image.getString("type"))
            val url = image.getJSONObject("image_url").getString("url")
            assertEquals("data:image/png;base64,${Base64.getEncoder().encodeToString(original)}", url)
            assertArrayEquals(original, Base64.getDecoder().decode(url.substringAfter("base64,")))
            assertFalse(image.has("source"))
        } else {
            assertEquals("text", content.getJSONObject(0).getString("type"))
            assertEquals("image", image.getString("type"))
            assertEquals("image/png", image.getJSONObject("source").getString("media_type"))
            assertArrayEquals(original, Base64.getDecoder().decode(image.getJSONObject("source").getString("data")))
        }
    }

    @Test fun actualBuildersAndAllTransportsPreserveMixedOrderAndExactOwners() {
        protocols.forEach { protocol ->
            val config = settings(protocol).copy(requestOptions = ProviderRequestOptions("""{"metadata":{"note":"中文"}}"""))
            // Same reference id in different messages must NOT be globally deduplicated.
            val history = listOf(row("first"), row("second"))
            val resolver = Resolver()
            val plan = plan(config, history, resolver)
            assertEquals(listOf("first", "second"), resolver.owners.map { it.messageId })
            val messages = history(protocol, history, plan)
            val base = body(config, messages)
            val expectedBytes = ImageRequestPreflight.bytes(config.requestOptions.apply(base, protocol), config, plan) { false }
            val conn = Connection(events(protocol))
            var connections = 0
            val session = model(config) { url -> assertEquals(config.requestEndpoint(), url.toString()); connections++; conn }
                .open(history, emptyList<ToolDefinition>(), plan)
            assertEquals(0, connections)
            session.next(emptyList()) {}
            assertEquals(1, connections)
            assertArrayEquals(expectedBytes, conn.sent.toByteArray())
            val body = JSONObject(conn.sent.toString("UTF-8"))
            assertEquals(2, body.getJSONArray(if (protocol == ProviderProtocol.RESPONSES) "input" else "messages").length())
            assertMixed(protocol, content(protocol, body, 0)); assertMixed(protocol, content(protocol, body, 1))
            assertEquals(2, resolver.owners.size) // no resolver IO after plan creation
        }
    }

    @Test fun segmentedHistoryResolvesOriginalMessageOwnersInExpandedOrder() {
        protocols.forEach { protocol ->
            val first = row("first")
            val second = row("second")
            val owner = Message(MessageId("assistant"), ConversationId("c"), RunId("r"), "assistant",
                emptyList(), RunStatus.COMPLETED, historySegments = listOf(
                    MessageHistorySegment(first.id, "user", first.blocks),
                    MessageHistorySegment(MessageId("assistant"), "assistant", listOf(MessageBlock.Text("a", "answer"))),
                    MessageHistorySegment(second.id, "user", second.blocks)))
            val history = listOf(first, owner) // standalone supplement must not duplicate the first image
            val resolver = Resolver()
            val frozen = plan(settings(protocol), history, resolver)
            assertEquals(listOf("first", "second"), resolver.owners.map { it.messageId })
            val items = history(protocol, history, frozen)
            assertEquals(3, items.length())
            val wire = JSONObject(ImageRequestPreflight.bytes(body(settings(protocol), items), settings(protocol), frozen) { false }.toString(Charsets.UTF_8))
            val serialized = wire.getJSONArray(if (protocol == ProviderProtocol.RESPONSES) "input" else "messages")
            assertMixed(protocol, serialized.getJSONObject(0).getJSONArray("content"))
            assertEquals("assistant", serialized.getJSONObject(1).getString("role"))
            assertMixed(protocol, serialized.getJSONObject(2).getJSONArray("content"))
        }
    }

    @Test fun unknownFalseOrMissingPlanIsExplicitAndHasZeroConnectionsOrImageReads() {
        protocols.forEach { protocol ->
            val config = settings(protocol)
            assertNull(ProviderSnapshot.capture(config).imageInputSupported)
            listOf<Boolean?>(null, false).forEach { capability ->
                val resolver = Resolver()
                val failure = rejected { plan(config, listOf(row()), resolver, capability) }
                assertEquals(ImageAttachmentFailure.MODEL_UNSUPPORTED, (failure as ImageAttachmentException).failure)
                assertTrue(resolver.owners.isEmpty())
                var connections = 0
                rejected { model(config, capability) { connections++; error("no network") }.open(listOf(row())) }
                assertEquals(0, connections)
            }
            var connections = 0
            rejected { model(config) { connections++; error("no network") }.open(listOf(row())) }
            assertEquals(0, connections)
            // A true-capability plan cannot upgrade a null-capability transport.
            val plan = plan(config, listOf(row()))
            rejected { model(config, null) { connections++; error("no network") }
                .open(listOf(row()), emptyList<ToolDefinition>(), plan) }
            assertEquals(0, connections)
        }
    }

    @Test fun customModelOverrideAndSnapshotMismatchFailBeforeConnect() {
        protocols.forEach { protocol ->
            val history = listOf(row())
            val config = settings(protocol).copy(requestOptions = ProviderRequestOptions("""{"model":"different"}"""))
            val plan = plan(config, history)
            var connections = 0
            val session = model(config) { connections++; error("no network") }.open(history, emptyList<ToolDefinition>(), plan)
            rejected { session.next(emptyList()) {} }
            assertEquals(0, connections)
            listOf(config.copy(model = "other"), config.copy(endpoint = endpoint(protocol, "other.invalid")))
                .forEach { changed -> rejected { model(changed) { connections++; error("no network") }
                    .open(history, emptyList<ToolDefinition>(), plan) } }
            val otherProtocol = settings(protocols.first { it != protocol })
            rejected { model(otherProtocol) { connections++; error("no network") }.open(history, emptyList<ToolDefinition>(), plan) }
            assertEquals(0, connections)
        }
    }

    @Test fun rawImagesCannotBypassCapabilityOrDuplicateLocalImages() {
        protocols.forEach { protocol ->
            val raw = if (protocol == ProviderProtocol.RESPONSES)
                """{"role":"user","content":[{"type":"input_image","image_url":"https://image.invalid"}]}"""
                else if (protocol == ProviderProtocol.CHAT_COMPLETIONS)
                    """{"role":"user","content":[{"type":"image_url","image_url":{"url":"data:image/png;base64,AQ=="}}]}"""
                else """{"role":"user","content":[{"type":"image","source":{"type":"base64","media_type":"image/png","data":"AQ=="}}]}"""
            listOf(row().copy(protocolItems = listOf(raw)), row().copy(blocks = emptyList(), protocolItems = listOf(raw)))
                .forEach { historyRow ->
                    var connections = 0
                    rejected { model(settings(protocol), null) { connections++; error("no network") }.open(listOf(historyRow)) }
                    assertEquals(0, connections)
                    val resolver = Resolver()
                    rejected { plan(settings(protocol), listOf(historyRow), resolver) }
                    assertTrue(resolver.owners.isEmpty())
                }
            val rawText = row().copy(protocolItems = listOf("""{"role":"user","content":[{"type":"text","text":"duplicate"}]}"""))
            rejected { plan(settings(protocol), listOf(rawText)) }
        }
    }

    @Test fun noImageToolArgumentsAndMetadataRemainBusinessJsonInHistoryAndContinuation() {
        // Deliberately resembles protocol content at several depths, but is business data.
        val business = JSONObject("""{"type":"image","image_url":"business://asset","nested":{"role":"user","content":[{"type":"input_image","image_url":"business://nested"}]}}""").toString()
        protocols.forEach { protocol ->
            val metadata = JSONObject().put("asset", JSONObject(business))
            val config = settings(protocol).copy(requestOptions = ProviderRequestOptions(
                JSONObject().put("metadata", metadata).toString()))
            val saved = if (protocol == ProviderProtocol.RESPONSES) listOf(
                JSONObject().put("type", "function_call").put("call_id", "saved-call").put("name", "lookup")
                    .put("arguments", business).put("metadata", metadata).toString(),
                JSONObject().put("type", "function_call_output").put("call_id", "saved-call")
                    .put("output", business).toString()
            ) else if (protocol == ProviderProtocol.CHAT_COMPLETIONS) listOf(
                JSONObject().put("role", "assistant").put("metadata", metadata).put("content", JSONObject.NULL)
                    .put("tool_calls", JSONArray().put(JSONObject().put("id", "saved-call").put("type", "function")
                        .put("function", JSONObject().put("name", "lookup").put("arguments", business)))).toString(),
                JSONObject().put("role", "tool").put("tool_call_id", "saved-call").put("content", business).toString()
            ) else listOf(
                JSONObject().put("role", "assistant").put("metadata", metadata).put("content", JSONArray().put(
                    JSONObject().put("type", "tool_use").put("id", "saved-call").put("name", "lookup")
                        .put("input", JSONObject(business)))).toString(),
                JSONObject().put("role", "user").put("content", JSONArray().put(
                    JSONObject().put("type", "tool_result").put("tool_use_id", "saved-call")
                        .put("content", business))).toString()
            )
            val history = listOf(row().copy(role = "assistant", blocks = emptyList(), protocolItems = saved))
            val resolver = Resolver()
            assertFalse(plan(config, history, resolver, capability = null).hasImages)
            assertTrue(resolver.owners.isEmpty())
            val built = history(protocol, history)
            assertEquals(2, built.length())
            val connections = listOf(Connection(events(protocol, true, business)), Connection(events(protocol)))
            var count = 0
            val session = model(config, null) { connections[count++] }.open(history,
                listOf(ToolDefinition("lookup", "fixture", """{"type":"object","properties":{}}""")))
            session.next(emptyList()) {}
            session.next(listOf(ToolOutput(ToolCallId("call"), business))) {}
            assertEquals(2, count)
            connections.forEach { connection ->
                val body = JSONObject(connection.sent.toString("UTF-8"))
                assertEquals(business, body.getJSONObject("metadata").getJSONObject("asset").toString())
                val item = body.getJSONArray(if (protocol == ProviderProtocol.RESPONSES) "input" else "messages")
                    .getJSONObject(0)
                assertEquals(business, item.getJSONObject("metadata").getJSONObject("asset").toString())
                val arguments = arguments(protocol, item)
                assertEquals(business, arguments)
            }
            val continued = JSONObject(connections[1].sent.toString("UTF-8"))
                .getJSONArray(if (protocol == ProviderProtocol.RESPONSES) "input" else "messages").getJSONObject(2)
            assertEquals(business, arguments(protocol, continued))
        }
    }

    @Test fun chatSupportDoesNotAuthorizeGeminiOrLoosenMalformedHistory() {
        val attachment = app.daiyu.android.media.MessageImageReference(
            app.daiyu.android.media.MessageImageOwner("c", "u"), reference("u").id,
            "image/png", original.size, digest, 2, 2).asAttachment()
        val frozen = FrozenImageAttachment(attachment, original)
        rejected { encoder.encode(ProviderProtocol.GEMINI, listOf(frozen), true) }
        rejected { encoder.encode(ProviderProtocol.UNKNOWN, listOf(frozen), true) }
        rejected { encoder.encode(ProviderProtocol.CHAT_COMPLETIONS, listOf(frozen), true, role = "assistant") }
        assertTrue(rejected { FrozenMessageImagePlan.rejectRawImages(listOf(row().copy(role = "assistant",
            blocks = emptyList(), status = RunStatus.STOPPED, protocolItems = listOf("{"))),
            ProviderProtocol.CHAT_COMPLETIONS) } is org.json.JSONException)
        val block = encoder.encode(ProviderProtocol.CHAT_COMPLETIONS, listOf(frozen), true).single()
        assertEquals("image_url", block.getString("type"))
        assertEquals("data:image/png;base64,AQIDBA==", block.getJSONObject("image_url").getString("url"))
        assertArrayEquals(original, frozen.bytes())
    }

    @Test fun protocolContentImagesStillRejectIncludingNestedToolResultsAndEnvelopes() {
        val image = """{"type":"input_image","image_url":"https://image.invalid"}"""
        val rawItems = listOf(
            """{"type":"message","role":"assistant","content":[{"type":"output_image"}]}""",
            """{"role":"user","content":[{"type":"image_url","image_url":{"url":"https://image.invalid"}}]}""",
            """{"role":"user","content":[{"type":"tool_result","tool_use_id":"call","content":[{"type":"image","source":{"type":"url","url":"https://image.invalid"}}]}]}""",
            """{"type":"function_call_output","call_id":"call","output":[$image]}""",
            """{"type":"computer_call_output","call_id":"call","output":{"type":"computer_screenshot","image_url":"https://image.invalid"}}""",
            """{"role":"user","content":[{"type":"document","source":{"type":"content","content":[{"type":"image"}]}}]}"""
        )
        rawItems.forEach { raw ->
            rejected { FrozenMessageImagePlan.rejectRawImageNode(JSONObject(raw)) }
            listOf("input", "messages", "output").forEach { field ->
                rejected { FrozenMessageImagePlan.rejectRawImageNode(JSONObject().put(field, JSONArray().put(JSONObject(raw)))) }
            }
            protocols.forEach { protocol ->
                val history = listOf(row().copy(role = "assistant", blocks = emptyList(), protocolItems = listOf(raw)))
                val resolver = Resolver()
                rejected { plan(settings(protocol), history, resolver) }
                assertTrue(resolver.owners.isEmpty())
                var connections = 0
                rejected { model(settings(protocol), null) { connections++; error("no network") }.open(history) }
                assertEquals(0, connections)
            }
        }
    }

    @Test fun codeInterpreterPluralOutputsRejectImagesAtProtocolPositions() {
        val raw = JSONObject(rawCodeInterpreterImage)
        val failure = rejected { FrozenMessageImagePlan.rejectRawImageNode(raw) }
        assertTrue(failure is IllegalArgumentException)
        assertEquals("Raw protocol image input is not authorized", failure.message)
        // Scan every output, not just the first, and scan the response-envelope position too.
        raw.put("outputs", JSONArray().put(JSONObject().put("type", "logs").put("logs", "1"))
            .put(JSONObject(rawCodeInterpreterImage).getJSONArray("outputs").getJSONObject(0)))
        rejected { FrozenMessageImagePlan.rejectRawImageNode(
            JSONObject().put("output", JSONArray().put(raw))) }
        // The same key in ordinary tool input/arguments/metadata is still business JSON.
        val business = JSONObject().put("outputs", JSONObject(rawCodeInterpreterImage).getJSONArray("outputs"))
        FrozenMessageImagePlan.rejectRawImageNode(JSONObject().put("type", "function_call")
            .put("arguments", business.toString()).put("metadata", business))
        FrozenMessageImagePlan.rejectRawImageNode(JSONObject().put("role", "assistant").put("content",
            JSONArray().put(JSONObject().put("type", "tool_use").put("input", business))))
    }

    @Test fun codeInterpreterImageHistoryRejectsBeforeAnyConnectionOrImageRead() {
        val config = settings(ProviderProtocol.RESPONSES)
        val history = listOf(row().copy(role = "assistant", blocks = emptyList(),
            protocolItems = listOf(rawCodeInterpreterImage)))
        val failure = rejected { ResponsesRequest.history(history) }
        assertTrue(failure is IllegalArgumentException)
        assertEquals("Raw protocol image input is not authorized", failure.message)
        val resolver = Resolver()
        assertEquals(failure.message, rejected { plan(config, history, resolver) }.message)
        assertTrue(resolver.owners.isEmpty())
        var connections = 0
        assertEquals(failure.message,
            rejected { model(config, null) { connections++; error("no network") }.open(history) }.message)
        assertEquals(0, connections)
    }

    @Test fun completedResponsesCodeInterpreterImageCannotEnterContinuation() {
        val protocol = ProviderProtocol.RESPONSES
        val config = settings(protocol)
        // A logs-only control confirms this completed SSE fixture permits a real tool continuation.
        listOf(false, true).forEach { hasRawImage ->
            val item = JSONObject(rawCodeInterpreterImage)
            if (!hasRawImage) item.put("outputs", JSONArray().put(JSONObject()
                .put("type", "logs").put("logs", "{\"type\":\"image\",\"url\":\"business://asset\"}")))
            val connections = listOf(Connection(events(protocol, tool = true, responseItem = item)),
                Connection(events(protocol)))
            var count = 0
            val delivered = mutableListOf<ModelEvent>()
            val session = model(config, null) { connections[count++] }.open(
                listOf(row().copy(blocks = listOf(MessageBlock.Text("text", "hello")))),
                listOf(ToolDefinition("lookup", "fixture", """{"type":"object","properties":{}}""")))
            if (hasRawImage) {
                val failure = rejected { session.next(emptyList()) { delivered.add(it) } }
                assertTrue(failure is IllegalArgumentException)
                assertEquals("Raw protocol image input is not authorized", failure.message)
                assertEquals(1, count)
                assertFalse(delivered.any { it is ModelEvent.ToolReady })
                assertFalse(delivered.any { it is ModelEvent.ProtocolOutput })
                assertEquals("Responses session cannot be replayed",
                    rejected { session.next(listOf(ToolOutput(ToolCallId("call"), "ok"))) {} }.message)
                assertEquals(1, count)
                assertEquals(0, connections[1].sent.size())
            } else {
                session.next(emptyList()) { delivered.add(it) }
                assertTrue(delivered.any { it is ModelEvent.ProtocolOutput })
                session.next(listOf(ToolOutput(ToolCallId("call"), "ok"))) {}
                assertEquals(2, count)
                val replay = JSONObject(connections[1].sent.toString("UTF-8")).getJSONArray("input")
                assertEquals("code_interpreter_call", replay.getJSONObject(2).getString("type"))
                assertEquals("logs", replay.getJSONObject(2).getJSONArray("outputs").getJSONObject(0).getString("type"))
            }
        }
    }

    @Test fun fullExpandedHistoryLimitsRejectBeforeAnyResolverCallUsingEncoderConfiguration() {
        val cases = listOf(
            Triple(ImageAttachmentLimits(), (1..9).map { row("u$it") }, ImageAttachmentFailure.TOO_MANY),
            Triple(ImageAttachmentLimits(maxImages = 2), (1..3).map { row("u$it") }, ImageAttachmentFailure.TOO_MANY),
            Triple(ImageAttachmentLimits(maxDraftBytes = 7), listOf(row("first"), row("second")), ImageAttachmentFailure.TOO_LARGE),
            Triple(ImageAttachmentLimits(maxImageBytes = 4), listOf(row("first"), row("second").copy(
                blocks = listOf(MessageBlock.Image(reference("second").copy(bytes = 5))))), ImageAttachmentFailure.TOO_LARGE)
        )
        protocols.forEach { protocol ->
            cases.forEach { (limits, rows, failure) ->
                val segmented = listOf(rows.first(), row("container").copy(role = "assistant", blocks = emptyList(),
                    historySegments = rows.map { MessageHistorySegment(it.id, it.role, it.blocks) }))
                listOf(rows, segmented).forEach { history ->
                    val resolver = Resolver()
                    val limitedEncoder = ImageInputEncoder(ImageInspector { ImageDimensions("image/png", 2, 2) }, limits)
                    val failureThrown = rejected { FrozenMessageImagePlan.freeze(history,
                        ProviderSnapshot.capture(settings(protocol), protocol, true), resolver, ::expected, limitedEncoder) }
                    assertEquals(failure, (failureThrown as ImageAttachmentException).failure)
                    assertEquals("No message may be read before the full-history limit check", 0, resolver.owners.size)
                }
            }
            val resolver = Resolver()
            val exact = ImageInputEncoder(ImageInspector { ImageDimensions("image/png", 2, 2) },
                ImageAttachmentLimits(maxImages = 2, maxImageBytes = 4, maxDraftBytes = 8))
            FrozenMessageImagePlan.freeze(listOf(row("first"), row("second")),
                ProviderSnapshot.capture(settings(protocol), protocol, true), resolver, ::expected, exact)
            assertEquals(2, resolver.owners.size)
        }
    }

    @Test fun postReadBytesDigestAndDimensionsAreStillVerified() {
        protocols.forEach { protocol ->
            val ref = reference("u")
            listOf(ref.copy(bytes = 3), ref.copy(sha256 = "0".repeat(64)), ref.copy(width = 3)).forEach { invalid ->
                val resolver = Resolver()
                val failure = rejected { plan(settings(protocol), listOf(row().copy(
                    blocks = listOf(MessageBlock.Image(invalid)))), resolver) }
                assertEquals(ImageAttachmentFailure.CORRUPT, (failure as ImageAttachmentException).failure)
                assertEquals(1, resolver.owners.size)
            }
        }
    }

    @Test fun badOwnerDuplicateMissingCancellationAndMutationNeverConnect() {
        protocols.forEach { protocol ->
            val config = settings(protocol)
            val resolver = Resolver()
            val duplicate = row().copy(blocks = listOf(MessageBlock.Image(reference("u")), MessageBlock.Image(reference("u"))))
            rejected { plan(config, listOf(duplicate), resolver) }
            rejected { plan(config, listOf(row().copy(blocks = listOf(MessageBlock.Image(reference("another"))))), resolver) }
            assertTrue(resolver.owners.isEmpty())
            rejected { FrozenMessageImagePlan.freeze(listOf(row()), ProviderSnapshot.capture(config, protocol, true), resolver,
                { MessageImageOwner("c", "unauthorized") }, encoder) }
            assertTrue(resolver.owners.isEmpty())
            resolver.failure = ImageAttachmentFailure.MISSING
            assertEquals(ImageAttachmentFailure.MISSING,
                (rejected { plan(config, listOf(row()), resolver) } as ImageAttachmentException).failure)
            rejected { FrozenMessageImagePlan.freeze(listOf(row()), ProviderSnapshot.capture(config, protocol, true), Resolver(),
                ::expected, encoder) { true } }
            val mutable = row().blocks.toMutableList()
            val history = listOf(row().copy(blocks = mutable))
            val frozen = plan(config, history)
            mutable[0] = MessageBlock.Text("changed", "changed")
            var connections = 0
            rejected { model(config) { connections++; error("no network") }.open(history, emptyList<ToolDefinition>(), frozen) }
            val session = model(config) { connections++; error("no network") }
                .open(listOf(row()), emptyList<ToolDefinition>(), plan(config, listOf(row())))
            session.cancel()
            rejected { session.next(emptyList()) {} }
            assertEquals(0, connections)
        }
    }

    @Test fun continuationRetainsImagesExactlyOnceWithoutReresolving() {
        protocols.forEach { protocol ->
            val config = settings(protocol)
            val history = listOf(row())
            val resolver = Resolver()
            val frozen = plan(config, history, resolver)
            val connections = listOf(Connection(events(protocol, true)), Connection(events(protocol)))
            var count = 0
            val session = model(config) { connections[count++] }.open(history,
                listOf(ToolDefinition("lookup", "fixture", """{"type":"object","properties":{}}""")), frozen)
            session.next(emptyList()) {}
            session.next(listOf(ToolOutput(ToolCallId("call"), "结果"))) {}
            assertEquals(2, count)
            connections.forEach { assertMixed(protocol, content(protocol, JSONObject(it.sent.toString("UTF-8")))) }
            assertEquals(1, resolver.owners.size)
        }
    }

    @Test fun initialFinalUtf8EnvelopeIncludesOptionsAndFailsWithZeroConnections() {
        protocols.forEach { protocol ->
            val config = settings(protocol).copy(requestOptions = ProviderRequestOptions("""{"metadata":{"note":"选项"}}"""))
            // Character count is below the ceiling; the final UTF-8 envelope is above it.
            val history = listOf(row().copy(blocks = row().blocks +
                MessageBlock.Text("large", "界".repeat(ImageRequestPreflight.MAX_REQUEST_BYTES / 3))))
            var connections = 0
            val session = model(config) { connections++; error("no network") }.open(history,
                emptyList<ToolDefinition>(), plan(config, history))
            val failure = rejected { session.next(emptyList()) {} }
            assertEquals(ImageAttachmentFailure.TOO_LARGE, (failure as ImageAttachmentException).failure)
            assertEquals(0, connections)
        }
    }

    @Test fun oversizedContinuationFailsBeforeSecondConnectionForResponsesAndMessages() {
        listOf(ProviderProtocol.RESPONSES, ProviderProtocol.MESSAGES).forEach { protocol ->
            val config = settings(protocol)
            val history = listOf(row())
            var count = 0
            val first = Connection(events(protocol, true))
            val session = model(config) { count++; first }.open(history,
                listOf(ToolDefinition("lookup", "fixture", """{"type":"object","properties":{}}""")), plan(config, history))
            session.next(emptyList()) {}
            assertEquals(1, count)
            val failure = rejected { session.next(listOf(ToolOutput(ToolCallId("call"), "x".repeat(ImageRequestPreflight.MAX_REQUEST_BYTES)))) {} }
            assertEquals(ImageAttachmentFailure.TOO_LARGE, (failure as ImageAttachmentException).failure)
            assertEquals(1, count)
            rejected { session.next(listOf(ToolOutput(ToolCallId("call"), "small"))) {} }
            assertEquals(1, count) // failed preflight does not become an automatic retry
        }
    }

    @Test fun chatOversizedNativeResultFailsBeforeSecondConnection() {
        val protocol = ProviderProtocol.CHAT_COMPLETIONS
        val config = settings(protocol)
        val history = listOf(row())
        var count = 0
        val first = Connection(events(protocol, true))
        val session = model(config) { count++; first }.open(history,
            listOf(ToolDefinition("lookup", "fixture", """{"type":"object","properties":{}}""")), plan(config, history))
        session.next(emptyList()) {}
        assertEquals(1, count)
        val limit = 1_000_000
        val overhead = NativeCompletionRequest.result(protocol, "call", "lookup", "").toString().length
        val output = "x".repeat(limit + 1 - overhead)
        assertEquals(limit + 1, NativeCompletionRequest.result(protocol, "call", "lookup", output).toString().length)
        val failure = rejected { session.next(listOf(ToolOutput(ToolCallId("call"), output))) {} }
        assertTrue(failure is IllegalArgumentException)
        assertEquals("工具结果超过原生历史存储上限", failure.message)
        assertEquals(1, count)
        rejected { session.next(listOf(ToolOutput(ToolCallId("call"), "small"))) {} }
        assertEquals(1, count)
    }

}
