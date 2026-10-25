package app.daiyu.android

import android.test.InstrumentationTestCase
import app.daiyu.android.domain.*
import app.daiyu.android.model.MessagesModel
import app.daiyu.android.model.MessagesRequest
import app.daiyu.android.model.MessagesStream
import app.daiyu.android.storage.ModelSettings
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.security.cert.Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection

/** Offline synthetic Anthropic wire fixtures; no credentials or provider access. */
@Suppress("DEPRECATION")
class MessagesTest : InstrumentationTestCase() {
    private val settings = ModelSettings("https://example.invalid/v1/messages", "fixture", "synthetic-key", true)
    private fun event(type: String, vararg fields: Pair<String, Any>) = JSONObject().put("type", type).also { value ->
        fields.forEach { value.put(it.first, it.second) }
    }
    private fun frames(events: List<JSONObject>) = events.joinToString("") { "event: ${it.getString("type")}\ndata: $it\n\n" }
    private fun start() = event("message_start", "message" to JSONObject().put("id", "msg").put("role", "assistant")
        .put("content", JSONArray()).put("usage", JSONObject().put("input_tokens", 3)
            .put("cache_read_input_tokens", 5).put("cache_creation_input_tokens", 7)))
    private fun textEvents() = listOf(
        start(), event("ping"),
        event("content_block_start", "index" to 0, "content_block" to event("text", "text" to "")),
        event("content_block_delta", "index" to 0, "delta" to event("text_delta", "text" to "hello")),
        event("content_block_stop", "index" to 0),
        event("message_delta", "delta" to JSONObject().put("stop_reason", "end_turn"), "usage" to JSONObject().put("output_tokens", 11)),
        event("message_stop"))
    private fun toolEvents(fragments: List<Any> = listOf("{\"a\":", "1}"), input: Any = JSONObject()) = listOf(
        start(), event("content_block_start", "index" to 0, "content_block" to event("tool_use", "id" to "call",
            "name" to "get_battery_status", "input" to input))) + fragments.map { fragment ->
        event("content_block_delta", "index" to 0, "delta" to event("input_json_delta", "partial_json" to fragment))
    } + listOf(
        event("content_block_stop", "index" to 0),
        event("message_delta", "delta" to JSONObject().put("stop_reason", "tool_use"), "usage" to JSONObject().put("output_tokens", 2)),
        event("message_stop"))
    private fun history(role: String, blocks: List<MessageBlock>, protocol: List<String> = emptyList(), status: RunStatus = RunStatus.COMPLETED) =
        Message(MessageId("m"), ConversationId("c"), RunId("r"), role, blocks, status, protocolItems = protocol)
    private fun expectFailure(expected: ModelFailure, task: () -> Unit) {
        try { task(); fail("expected $expected") } catch (failure: ModelRequestException) { assertEquals(expected, failure.failure) }
    }
    private fun expectRejected(task: () -> Unit) {
        try { task(); fail("expected explicit rejection") } catch (_: IllegalArgumentException) {}
    }
    private fun decode(body: String, events: MutableList<ModelEvent> = mutableListOf()): String =
        MessagesStream.read(body.reader().buffered(), { false }, events::add)

    private class Connection(body: String, private val status: Int = 200, private val block: Boolean = false,
        private val transportFailure: IOException? = null) : HttpsURLConnection(URL("https://example.invalid/v1/messages")) {
        val request = ByteArrayOutputStream()
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        var disconnected = false
        var inputClosed = false
        var outputClosed = false
        var cleanupFailure: RuntimeException? = null
        private val source = body.byteInputStream()
        private val response = object : InputStream() {
            override fun read(): Int {
                val value = source.read()
                if (value >= 0) return value
                if (block) { entered.countDown(); if (!released.await(5, TimeUnit.SECONDS)) throw IOException("fixture timeout") }
                transportFailure?.let { throw it }
                return -1
            }
            override fun close() { inputClosed = true; source.close() }
        }
        override fun connect() {}
        override fun disconnect() { disconnected = true; released.countDown(); cleanupFailure?.let { throw it } }
        override fun usingProxy() = false
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getOutputStream() = object : java.io.OutputStream() {
            override fun write(value: Int) { request.write(value) }
            override fun close() { outputClosed = true }
        }
        override fun getInputStream() = response
        override fun getResponseCode() = status
        override fun getContentType() = "text/event-stream; charset=utf-8"
    }

    fun testEndpointValidationAndSystemRejectionBeforeConnection() {
        assertEquals("https://example.invalid/v1/messages", MessagesRequest.endpoint(settings.copy(endpoint = "https://example.invalid")))
        assertEquals("https://example.invalid/v1/messages", MessagesRequest.endpoint(settings.copy(endpoint = "https://example.invalid/v1/")))
        for (endpoint in listOf("http://example.invalid", "https://u:p@example.invalid", "https://example.invalid?q=x",
            "https://example.invalid/#fragment", "https://example.invalid/v1/responses", "https://example.invalid/v1/chat/completions")) {
            expectRejected { MessagesModel(settings.copy(endpoint = endpoint)).open(emptyList()) }
        }
        assertEquals(settings.requestEndpoint(), MessagesRequest.endpoint(settings.copy(responsesPath = "/responses")))
        MessagesModel(settings.copy(responsesPath = "/responses")).open(emptyList())
        var opened = false
        val model = MessagesModel(settings) { opened = true; Connection(frames(textEvents())) }
        for (status in RunStatus.values()) {
            expectRejected { model.open(listOf(history("system", listOf(MessageBlock.Text("s", "do not drop")), status = status))) }
        }
        expectRejected { model.open(listOf(history("developer", emptyList()))) }
        assertFalse(opened)
    }

    fun testHistoryToolMappingAndExternalNullableResult() {
        val invocation = ToolInvocation(ToolCallId("old-call"), "get_battery_status", "{}")
        val messages = MessagesRequest.history(listOf(history("user", listOf(MessageBlock.Text("u", "question"))),
            history("assistant", listOf(MessageBlock.Text("a", "checking"), MessageBlock.Tool(invocation, "75%")))))
        assertEquals(3, messages.length())
        assertEquals("user", messages.getJSONObject(0).getString("role"))
        val assistant = messages.getJSONObject(1).getJSONArray("content")
        assertEquals("text", assistant.getJSONObject(0).getString("type"))
        assertEquals("old-call", assistant.getJSONObject(1).getString("id"))
        assertEquals("{}", assistant.getJSONObject(1).getJSONObject("input").toString())
        val result = messages.getJSONObject(2).getJSONArray("content").getJSONObject(0)
        assertEquals("tool_result", result.getString("type")); assertEquals("old-call", result.getString("tool_use_id"))
        assertEquals("75%", result.getString("content"))
        expectRejected { MessagesRequest.history(listOf(history("assistant", listOf(MessageBlock.Tool(invocation))))) }
        expectRejected { MessagesRequest.history(listOf(history("assistant", emptyList(), listOf("{\"type\":\"function_call\"}")))) }
    }

    fun testHeadersToolSchemaMultiRoundAndProtocolReplay() {
        val first = Connection(frames(toolEvents()))
        val second = Connection(frames(textEvents()))
        val connections = listOf(first, second)
        var opened = 0
        val model = MessagesModel(settings) { connections[opened++] }
        val schema = "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\"}}}"
        val definitions = listOf(ToolDefinition("get_battery_status", "fixture tool", schema))
        val session = model.open(listOf(history("user", listOf(MessageBlock.Text("u", "hi")))), definitions)
        val events = mutableListOf<ModelEvent>()
        session.next(emptyList(), events::add)
        assertEquals(1, events.filterIsInstance<ModelEvent.ToolReady>().single().invocation.let { JSONObject(it.arguments).getInt("a") })
        assertEquals("synthetic-key", first.getRequestProperty("x-api-key"))
        assertEquals("2023-06-01", first.getRequestProperty("anthropic-version"))
        assertNull(first.getRequestProperty("Authorization")); assertFalse(first.instanceFollowRedirects)
        val request = JSONObject(first.request.toString("UTF-8"))
        assertEquals(4096, request.getInt("max_tokens")); assertTrue(request.getBoolean("stream"))
        assertTrue(request.getJSONArray("tools").getJSONObject(0).getJSONObject("input_schema").getJSONObject("properties").has("x"))
        session.next(listOf(ToolOutput(ToolCallId("call"), "ok")), events::add)
        val next = JSONObject(second.request.toString("UTF-8")).getJSONArray("messages")
        assertEquals("assistant", next.getJSONObject(1).getString("role"))
        assertEquals("tool_use", next.getJSONObject(1).getJSONArray("content").getJSONObject(0).getString("type"))
        assertEquals("call", next.getJSONObject(2).getJSONArray("content").getJSONObject(0).getString("tool_use_id"))
        val saved = events.filterIsInstance<ModelEvent.ProtocolOutput>().last().items
        assertEquals(3, saved.size)
        assertEquals(3, MessagesRequest.history(listOf(history("assistant", emptyList(), saved))).length())
        assertEquals("hello", events.filterIsInstance<ModelEvent.TextFinal>().single().text)
        val usage = events.filterIsInstance<ModelEvent.Usage>().last()
        assertEquals(15L, usage.inputTokens ?: -1L); assertEquals(11L, usage.outputTokens ?: -1L); assertEquals(5L, usage.cachedTokens ?: -1L)
        for (connection in connections) { assertTrue(connection.disconnected); assertTrue(connection.inputClosed); assertTrue(connection.outputClosed) }
    }

    fun testPartialMessageDeltasKeepToolCallAndUsageThroughNextRequest() {
        val partial = toolEvents().toMutableList()
        val terminal = partial.size - 2
        partial.addAll(terminal, listOf(
            event("message_delta", "delta" to JSONObject(), "usage" to JSONObject().put("output_tokens", 1)),
            event("message_delta", "delta" to JSONObject().put("stop_reason", JSONObject.NULL))))
        partial.add(partial.size - 1, event("message_delta", "delta" to JSONObject().put("stop_reason", JSONObject.NULL),
            "usage" to JSONObject().put("output_tokens", 4)))
        val first = Connection(frames(partial))
        val second = Connection(frames(textEvents()))
        var opened = 0
        val session = MessagesModel(settings) { listOf(first, second)[opened++] }.open(emptyList())
        val events = mutableListOf<ModelEvent>()
        session.next(emptyList(), events::add)
        assertEquals("call", events.filterIsInstance<ModelEvent.ToolReady>().single().invocation.id.value)
        assertEquals(1, JSONObject(events.filterIsInstance<ModelEvent.ToolReady>().single().invocation.arguments).getInt("a"))
        assertEquals(4L, events.filterIsInstance<ModelEvent.Usage>().single().outputTokens)
        session.next(listOf(ToolOutput(ToolCallId("call"), "exact output")), events::add)
        assertEquals(JSONObject(first.request.toString("UTF-8")).getJSONArray("tools").toString(),
            JSONObject(second.request.toString("UTF-8")).getJSONArray("tools").toString())
        val messages = JSONObject(second.request.toString("UTF-8")).getJSONArray("messages")
        val result = messages.getJSONObject(messages.length() - 1).getJSONArray("content").getJSONObject(0)
        assertEquals("call", result.getString("tool_use_id"))
        assertEquals("exact output", result.getString("content"))
        val conflict = toolEvents().toMutableList()
        conflict.add(conflict.size - 1, event("message_delta", "delta" to JSONObject().put("stop_reason", "end_turn")))
        val unconfirmed = mutableListOf<ModelEvent>()
        expectFailure(ModelFailure.PROTOCOL) { decode(frames(conflict), unconfirmed) }
        assertTrue(unconfirmed.none { it is ModelEvent.ToolReady })
    }

    fun testBlankLineFramingFragmentedJsonAndTerminalRequirements() {
        val events = mutableListOf<ModelEvent>()
        val raw = decode(": comment\r\n\r\n" + frames(toolEvents()).replace("\n", "\r\n"), events)
        assertEquals(1, JSONObject(raw).getJSONArray("content").getJSONObject(0).getJSONObject("input").getInt("a"))
        for (body in listOf(frames(toolEvents().dropLast(1)), frames(toolEvents()).trimEnd(),
            frames(toolEvents().dropLast(2)))) {
            val unconfirmed = mutableListOf<ModelEvent>()
            expectFailure(ModelFailure.INCOMPLETE) { decode(body, unconfirmed) }
            assertTrue(unconfirmed.none { it is ModelEvent.ToolReady || it is ModelEvent.ProtocolOutput })
        }
        // A newline embedded by SSE multiline data is legal JSON whitespace.
        val text = frames(textEvents()).replace("data: {\"type\":\"ping\"}", "data: {\"type\":\ndata: \"ping\"}")
        decode(text)
        val malformed = toolEvents().toMutableList()
        malformed[3] = event("content_block_delta", "index" to 0, "delta" to event("input_json_delta", "partial_json" to "broken"))
        expectFailure(ModelFailure.PROTOCOL) { decode(frames(malformed)) }
        val truncated = textEvents().toMutableList()
        truncated[5] = event("message_delta", "delta" to JSONObject().put("stop_reason", "max_tokens"))
        expectFailure(ModelFailure.INCOMPLETE) { decode(frames(truncated)) }
        expectFailure(ModelFailure.PROTOCOL) { decode("data: not-json\n\n") }
    }

    fun testStrictToolJsonAllowsFragmentsUnicodeWhitespaceAndNoArgumentDefaults() {
        // Split inside an escape and a surrogate pair; validation happens after concatenation.
        val fragments = listOf(" \r\n{\t\"text\":\"中\\u00", "41\\uD83D", "\\uDE00\",",
            "\"nested\":[true,false,null,{\"n\":-1.25e+2}],\"escaped\":\"\\\"\\\\\\/\\b\\f\\n\\r\\t\"}\r\n ")
        val events = mutableListOf<ModelEvent>()
        val raw = decode(frames(toolEvents(fragments)), events)
        val arguments = events.filterIsInstance<ModelEvent.ToolReady>().single().invocation.arguments
        val parsed = JSONObject(arguments)
        assertEquals("中A\uD83D\uDE00", parsed.getString("text"))
        val nested = parsed.getJSONArray("nested")
        assertTrue(nested.getBoolean(0)); assertFalse(nested.getBoolean(1)); assertTrue(nested.isNull(2))
        assertEquals(-125.0, nested.getJSONObject(3).getDouble("n"), 0.0)
        assertEquals("\"\\/\b\u000c\n\r\t", parsed.getString("escaped"))
        assertEquals(parsed.toString(), JSONObject(raw).getJSONArray("content").getJSONObject(0).getJSONObject("input").toString())
        val replaced = mutableListOf<ModelEvent>()
        decode(frames(toolEvents(listOf("{", "}"), JSONObject().put("old", true))), replaced)
        assertEquals("{}", replaced.filterIsInstance<ModelEvent.ToolReady>().single().invocation.arguments)
        for (parts in listOf(emptyList(), listOf(""), listOf("", ""))) {
            val noArguments = mutableListOf<ModelEvent>()
            decode(frames(toolEvents(parts)), noArguments)
            assertEquals("{}", noArguments.filterIsInstance<ModelEvent.ToolReady>().single().invocation.arguments)
            val initial = mutableListOf<ModelEvent>()
            decode(frames(toolEvents(parts, JSONObject().put("preserved", "中\uD83D\uDE00"))), initial)
            assertEquals("中\uD83D\uDE00", JSONObject(initial.filterIsInstance<ModelEvent.ToolReady>().single().invocation.arguments).getString("preserved"))
        }
    }

    fun testStrictToolJsonRejectsTrailingInputLenientSyntaxAndNonObjectRoots() {
        val invalid = listOf(
            "{} garbage", "{}{}", "{} \r\n {\"second\":true}", "{} []", "{}\u0000",
            "{'a':1}", "{\"a\":'text'}", "{a:1}", "{\"a\":bare}",
            "/*comment*/{}", "{}//comment", "{\"a\":1,}", "{\"a\"=1}",
            "{\"a\":01}", "{\"a\":+1}", "{\"a\":.1}", "{\"a\":1.}", "{\"a\":NaN}",
            "{\"a\":\\u1234}", "{\"a\":\"\\x41\"}", "{\"a\":\"\\u12xz\"}", "{\"a\":\"line\nfeed\"}",
            "[]", "[{}]", "null", "true", "1", "\"object\"", " \t\r\n", "\u00a0{}",
            "{\"nested\":".repeat(140) + "{}" + "}".repeat(140))
        for (json in invalid) {
            val events = mutableListOf<ModelEvent>()
            // Fragment boundaries must not bypass whole-object validation.
            expectFailure(ModelFailure.PROTOCOL) { decode(frames(toolEvents(listOf("", json.take(1), json.drop(1)))), events) }
            assertTrue(events.none { it is ModelEvent.ToolReady || it is ModelEvent.TextFinal || it is ModelEvent.Usage || it is ModelEvent.ProtocolOutput })
        }
        val connection = Connection(frames(toolEvents(listOf("{}", "{}"))))
        val events = mutableListOf<ModelEvent>()
        val session = MessagesModel(settings) { connection }.open(emptyList())
        expectFailure(ModelFailure.PROTOCOL) { session.next(emptyList(), events::add) }
        assertTrue(events.none { it is ModelEvent.ToolReady || it is ModelEvent.ProtocolOutput })
        assertTrue(connection.inputClosed); assertTrue(connection.disconnected)
    }

    fun testStrictToolJsonRejectsMissingAndWrongTypedWireFields() {
        for (value in listOf(JSONObject.NULL, 7, true, JSONArray(), JSONObject())) {
            val events = mutableListOf<ModelEvent>()
            expectFailure(ModelFailure.PROTOCOL) { decode(frames(toolEvents(listOf(value))), events) }
            assertTrue(events.none { it is ModelEvent.ToolReady })
        }
        for (value in listOf(JSONObject.NULL, "{}", JSONArray(), 7, false)) {
            expectFailure(ModelFailure.PROTOCOL) { decode(frames(toolEvents(emptyList(), value))) }
        }
        val missingInput = toolEvents(emptyList()).toMutableList()
        missingInput[1].getJSONObject("content_block").remove("input")
        expectFailure(ModelFailure.PROTOCOL) { decode(frames(missingInput)) }
        val missingFragment = toolEvents().toMutableList()
        missingFragment[2].getJSONObject("delta").remove("partial_json")
        expectFailure(ModelFailure.PROTOCOL) { decode(frames(missingFragment)) }
    }

    fun testStrictObjectParsingAlsoProtectsSseHistoryArgumentsAndSchemas() {
        for (data in listOf("{\"type\":\"ping\"} garbage", "{\"type\":\"ping\"}{}", "{'type':'ping'}", "[]")) {
            expectFailure(ModelFailure.PROTOCOL) { decode("data: $data\n\n" + frames(textEvents())) }
        }
        val saved = MessagesRequest.message("assistant", JSONArray().put(event("text", "text" to "saved"))).toString()
        for (invalid in listOf("{} garbage", "{}{}", "{'a':1}", "[]")) {
            fun rejected(task: () -> Unit) {
                try { task(); fail("strict object required") } catch (_: org.json.JSONException) {}
            }
            rejected { MessagesRequest.history(listOf(history("assistant", emptyList(), listOf(saved + invalid)))) }
            rejected { MessagesRequest.history(listOf(history("assistant", listOf(MessageBlock.Tool(
                ToolInvocation(ToolCallId("old"), "get_battery_status", invalid), "ok"))))) }
            rejected { MessagesRequest.body("fixture", JSONArray(), listOf(ToolDefinition("tool", "fixture", invalid)), 10) }
        }
        assertEquals(1, MessagesRequest.history(listOf(history("assistant", emptyList(), listOf(" \r\n$saved\t ")))).length())
    }

    /** depth is the number of containers, including the root object ({} is depth 1). */
    private fun nestedObject(depth: Int): String = "{\"n\":".repeat(depth - 1) + "{}" + "}".repeat(depth - 1)
    private fun nestedArrays(depth: Int): String = "{\"n\":" + "[".repeat(depth - 1) + "0" + "]".repeat(depth - 1) + "}"
    private fun expectJsonRejected(task: () -> Unit) {
        try { task(); fail("expected depth rejection") } catch (_: org.json.JSONException) {}
    }

    fun testExactContainerDepthLimitsForSseHistoryArgumentsAndSchemas() {
        // Literal assertions keep changes to policy from silently moving all test boundaries.
        assertEquals(128, MessagesRequest.MAX_JSON_CONTAINER_DEPTH)
        assertEquals(126, MessagesRequest.MAX_MESSAGE_CONTAINER_DEPTH)
        assertEquals(123, MessagesRequest.MAX_TOOL_INPUT_CONTAINER_DEPTH)
        assertEquals(125, MessagesRequest.MAX_SCHEMA_CONTAINER_DEPTH)
        for (nested in listOf<(Int) -> String>(::nestedObject, ::nestedArrays)) {
            MessagesRequest.jsonObject(nested(128))
            expectJsonRejected { MessagesRequest.jsonObject(nested(129)) }
            // Even an ignored ping field must respect the SSE root's 128-container limit.
            fun ping(depth: Int) = "data: {\"type\":\"ping\",\"extra\":" + nested(depth - 1) + "}\n\n"
            decode(ping(128) + frames(textEvents()))
            expectFailure(ModelFailure.PROTOCOL) { decode(ping(129) + frames(textEvents())) }

            fun saved(depth: Int) = "{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"extra\":" + nested(depth - 1) + "}"
            val replay = MessagesRequest.history(listOf(history("assistant", emptyList(), listOf(saved(126)))))
            val post = MessagesRequest.body("fixture", replay, emptyList(), 10)
            MessagesRequest.jsonObject(post.toString()) // POST root + messages array + saved message = 128.
            expectJsonRejected { MessagesRequest.history(listOf(history("assistant", emptyList(), listOf(saved(127))))) }
            // The body entry must not bypass its limit with an already-materialized messages array.
            expectJsonRejected { MessagesRequest.body("fixture", JSONArray().put(JSONObject(saved(127))), emptyList(), 10) }

            fun argumentHistory(depth: Int) = listOf(history("assistant", listOf(MessageBlock.Tool(
                ToolInvocation(ToolCallId("old"), "tool", nested(depth)), "ok"))))
            val arguments = MessagesRequest.history(argumentHistory(123))
            MessagesRequest.jsonObject(MessagesRequest.body("fixture", arguments, emptyList(), 10).toString())
            expectJsonRejected { MessagesRequest.history(argumentHistory(124)) }

            fun schema(depth: Int) = "{\"type\":\"object\",\"extra\":" + nested(depth - 1) + "}"
            val body = MessagesRequest.body("fixture", JSONArray(), listOf(ToolDefinition("tool", "fixture", schema(125))), 10)
            MessagesRequest.jsonObject(body.toString())
            expectJsonRejected { MessagesRequest.body("fixture", JSONArray(), listOf(ToolDefinition("tool", "fixture", schema(126))), 10) }
        }
    }

    fun testMaximumToolDepthStreamProtocolOutputAndHistoryReplay() {
        for (nested in listOf<(Int) -> String>(::nestedObject, ::nestedArrays)) {
            val maximum = nested(123)
            // Exercise fragments and retained initial input, then a real result/next request.
            for (initial in listOf(false, true)) {
                val first = Connection(frames(if (initial) toolEvents(emptyList(), JSONObject(maximum))
                    else toolEvents(listOf(maximum.take(17), maximum.drop(17)))))
                val second = Connection(frames(textEvents()))
                val connections = listOf(first, second)
                var opened = 0
                val session = MessagesModel(settings) { connections[opened++] }.open(emptyList())
                val events = mutableListOf<ModelEvent>()
                session.next(emptyList(), events::add)
                val invocation = events.filterIsInstance<ModelEvent.ToolReady>().single().invocation
                assertEquals(JSONObject(maximum).toString(), invocation.arguments)
                val firstSaved = events.filterIsInstance<ModelEvent.ProtocolOutput>().single().items
                val firstReplay = MessagesRequest.history(listOf(history("assistant", emptyList(), firstSaved)))
                assertEquals(invocation.arguments, firstReplay.getJSONObject(0).getJSONArray("content")
                    .getJSONObject(0).getJSONObject("input").toString())
                MessagesRequest.jsonObject(MessagesRequest.body("fixture", firstReplay, emptyList(), 10).toString())
                session.next(listOf(ToolOutput(invocation.id, "ok")), events::add)
                val saved = events.filterIsInstance<ModelEvent.ProtocolOutput>().last().items
                assertEquals(3, saved.size)
                val replay = MessagesRequest.history(listOf(history("assistant", emptyList(), saved)))
                assertEquals(3, replay.length())
                MessagesRequest.jsonObject(MessagesRequest.body("fixture", replay, emptyList(), 10).toString())
                MessagesRequest.jsonObject(second.request.toString("UTF-8"))
                for (connection in connections) { assertTrue(connection.inputClosed); assertTrue(connection.disconnected) }
            }
        }
    }

    fun testFirstRejectedToolDepthAndRetainedWireFieldsNeverConfirmTools() {
        for (nested in listOf<(Int) -> String>(::nestedObject, ::nestedArrays)) {
            val tooDeep = nested(124)
            val retainedExtra = toolEvents(emptyList()).toMutableList()
            // SSE depth 127 is legal, but saved-message depth 128 would exceed its 126 budget.
            retainedExtra[1].getJSONObject("content_block").put("extra", JSONObject(nested(125)))
            for (fixture in listOf(toolEvents(listOf(tooDeep)), toolEvents(emptyList(), JSONObject(tooDeep)),
                toolEvents(listOf("{}"), JSONObject(tooDeep)), retainedExtra)) {
                val connection = Connection(frames(fixture))
                val events = mutableListOf<ModelEvent>()
                val session = MessagesModel(settings) { connection }.open(emptyList())
                expectFailure(ModelFailure.PROTOCOL) { session.next(emptyList(), events::add) }
                assertTrue(events.none { it is ModelEvent.ToolReady || it is ModelEvent.ProtocolOutput || it is ModelEvent.Usage })
                assertTrue(connection.inputClosed); assertTrue(connection.disconnected)
            }
        }
    }

    fun testHttpAndSseErrorsAreSanitizedAndClosedWithoutRetry() {
        val failures = mapOf(301 to ModelFailure.REDIRECT, 401 to ModelFailure.AUTHENTICATION, 403 to ModelFailure.PERMISSION,
            404 to ModelFailure.ENDPOINT, 405 to ModelFailure.ENDPOINT, 429 to ModelFailure.RATE_LIMIT,
            500 to ModelFailure.SERVER, 400 to ModelFailure.REQUEST)
        failures.forEach { (status, expected) ->
            val connection = Connection("private service body", status)
            var opened = 0
            val session = MessagesModel(settings) { opened++; connection }.open(emptyList())
            expectFailure(expected) { session.next(emptyList()) {
                assertTrue("only local estimate is allowed on HTTP error", it is ModelEvent.InputEstimate)
            } }
            assertEquals(1, opened); assertTrue(connection.disconnected); assertFalse(connection.instanceFollowRedirects)
        }
        for ((type, expected) in listOf("overloaded_error" to ModelFailure.SERVER, "rate_limit_error" to ModelFailure.RATE_LIMIT)) {
            val connection = Connection(frames(listOf(start(), event("error", "error" to JSONObject().put("type", type).put("message", "private")))))
            expectFailure(expected) { MessagesModel(settings) { connection }.open(emptyList()).next(emptyList()) {} }
            assertTrue(connection.inputClosed); assertTrue(connection.disconnected)
        }
    }

    fun testCancellationBeforeConnectionAndDuringRead() {
        var opened = 0
        val before = MessagesModel(settings) { opened++; Connection("") }.open(emptyList())
        before.cancel()
        try { before.next(emptyList()) {}; fail("cancelled") } catch (_: InterruptedException) {}
        assertEquals(0, opened)
        val connection = Connection(frames(textEvents().take(4)), block = true, transportFailure = IOException("synthetic"))
        val session = MessagesModel(settings) { opened++; connection }.open(emptyList())
        val failure = AtomicReference<Throwable?>()
        val events = mutableListOf<ModelEvent>()
        val thread = Thread { try { session.next(emptyList(), events::add) } catch (caught: Throwable) { failure.set(caught) } }
        thread.isDaemon = true; thread.start()
        try {
            assertTrue(connection.entered.await(5, TimeUnit.SECONDS))
            session.cancel(); thread.join(5_000)
            assertFalse(thread.isAlive); assertTrue(failure.get() is InterruptedException)
            assertTrue(connection.inputClosed); assertTrue(connection.disconnected)
            assertTrue(events.none { it is ModelEvent.ToolReady || it is ModelEvent.ProtocolOutput || it is ModelEvent.TextFinal })
        } finally { session.cancel(); connection.released.countDown(); thread.join(5_000) }
    }

    fun testTransportFailureMappingAndNoRepost() {
        for ((transport, expected) in listOf(
            java.net.SocketTimeoutException("private") to ModelFailure.TIMEOUT,
            javax.net.ssl.SSLException("private") to ModelFailure.TLS,
            IOException("private") to ModelFailure.NETWORK)) {
            val connection = Connection("", transportFailure = transport)
            var opened = 0
            val session = MessagesModel(settings) { opened++; connection }.open(emptyList())
            expectFailure(expected) { session.next(emptyList()) {} }
            try { session.next(emptyList()) {}; fail("must not repost") } catch (_: IllegalStateException) {}
            assertEquals(1, opened); assertTrue(connection.inputClosed); assertTrue(connection.disconnected)
        }
    }

    fun testToolOutputMismatchDoesNotSendAndReadOnlySchemas() {
        val first = Connection(frames(toolEvents()))
        val second = Connection(frames(textEvents()))
        var opened = 0
        val session = MessagesModel(settings) { if (opened++ == 0) first else second }.open(emptyList(), setOf(ReadOnlyTool.BATTERY))
        session.next(emptyList()) {}
        val tool = JSONObject(first.request.toString("UTF-8")).getJSONArray("tools").getJSONObject(0)
        assertEquals("get_battery_status", tool.getString("name"))
        assertEquals("object", tool.getJSONObject("input_schema").getString("type"))
        for (outputs in listOf(emptyList(), listOf(ToolOutput(ToolCallId("wrong"), "no")),
            listOf(ToolOutput(ToolCallId("call"), "one"), ToolOutput(ToolCallId("call"), "duplicate")))) {
            expectRejected { session.next(outputs) {} }
            assertEquals(1, opened)
        }
        session.next(listOf(ToolOutput(ToolCallId("call"), "ok"))) {}
        assertEquals(2, opened)
    }

    fun testSinkIdentityCleanupAndCancellationAtTerminalDelivery() {
        val connection = Connection(frames(textEvents()))
        connection.cleanupFailure = IllegalStateException("cleanup")
        val session = MessagesModel(settings) { connection }.open(emptyList())
        val original = org.json.JSONException("caller-owned")
        try {
            session.next(emptyList()) { event ->
                if (event is ModelEvent.TextFinal) { session.cancel(); throw original }
            }
            fail("sink exception")
        } catch (caught: Exception) { assertSame(original, caught) }
        assertTrue(connection.inputClosed); assertTrue(connection.disconnected)
        val terminal = Connection(frames(toolEvents()))
        val terminalSession = MessagesModel(settings) { terminal }.open(emptyList())
        val events = mutableListOf<ModelEvent>()
        try {
            terminalSession.next(emptyList()) { event -> events.add(event); if (event is ModelEvent.ToolReady) terminalSession.cancel() }
            fail("cancel after terminal frame")
        } catch (_: InterruptedException) {}
        assertTrue(events.none { it is ModelEvent.ProtocolOutput || it is ModelEvent.Usage })
        assertTrue(terminal.inputClosed); assertTrue(terminal.disconnected)
    }
    fun testSegmentedReopenReplaysConfirmedCallAndCompletedResultExactlyOnce() {
        for(alreadySaved in listOf(false,true)) {
            val first=Connection(frames(toolEvents()))
            val events=mutableListOf<ModelEvent>()
            MessagesModel(settings) {first}.open(emptyList()).next(emptyList(),events::add)
            val invocation=events.filterIsInstance<ModelEvent.ToolReady>().single().invocation
            val saved=events.filterIsInstance<ModelEvent.ProtocolOutput>().single().items
            val result=MessagesRequest.message("user",JSONArray().put(MessagesRequest.result(invocation.id.value,"done"))).toString()
            val protocol=if(alreadySaved) saved+result else saved
            val completed=MessageBlock.Tool(invocation,"done")
            val owner=history("assistant",listOf(completed),protocol)
            val extra=owner.copy(id=MessageId("supplement"),role="user",blocks=listOf(MessageBlock.Text("u","extra")),protocolItems=emptyList())
            val segmented=owner.copy(blocks=listOf(MessageBlock.Text("projection","must not send")),protocolItems=emptyList(),
                historySegments=listOf(
                    MessageHistorySegment(owner.id,"assistant",listOf(completed),protocol),
                    MessageHistorySegment(extra.id,"user",extra.blocks),
                    MessageHistorySegment(owner.id,"assistant",listOf(MessageBlock.Text("tail","tail")))))
            val second=Connection(frames(textEvents()))
            MessagesModel(settings) {second}.open(listOf(extra,segmented)).next(emptyList()) {}
            val messages=JSONObject(second.request.toString("UTF-8")).getJSONArray("messages")
            assertEquals(4,messages.length())
            assertEquals(listOf("assistant","user","user","assistant"),(0 until messages.length()).map {messages.getJSONObject(it).getString("role")})
            assertEquals(invocation.id.value,messages.getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("id"))
            val output=messages.getJSONObject(1).getJSONArray("content")
            assertEquals(1,output.length());assertEquals("tool_result",output.getJSONObject(0).getString("type"))
            assertEquals(invocation.id.value,output.getJSONObject(0).getString("tool_use_id"));assertEquals("done",output.getJSONObject(0).getString("content"))
            assertEquals("extra",messages.getJSONObject(2).getJSONArray("content").getJSONObject(0).getString("text"))
            assertEquals("tail",messages.getJSONObject(3).getJSONArray("content").getJSONObject(0).getString("text"))
            for(connection in listOf(first,second)) {
                assertTrue(connection.inputClosed);assertTrue(connection.outputClosed);assertTrue(connection.disconnected)
            }
        }
    }

    fun testSavedMessagesProtocolIsRejectedByResponsesBeforeConnection() {
        for(wire in listOf(textEvents(),toolEvents())) {
            val connection=Connection(frames(wire))
            val events=mutableListOf<ModelEvent>()
            MessagesModel(settings) {connection}.open(emptyList()).next(emptyList(),events::add)
            val saved=events.filterIsInstance<ModelEvent.ProtocolOutput>().single().items
            var responsesConnections=0
            val responses=app.daiyu.android.model.ResponsesModel(settings.copy(
                endpoint="https://example.invalid/v1/responses",
                protocol=app.daiyu.android.model.ProviderProtocol.RESPONSES)) {
                responsesConnections++;Connection("")
            }
            expectRejected {responses.open(listOf(history("assistant",listOf(MessageBlock.Text("fallback","must not convert")),saved)))}
            assertEquals(0,responsesConnections)
            assertTrue(connection.inputClosed);assertTrue(connection.outputClosed);assertTrue(connection.disconnected)
        }
    }

}
