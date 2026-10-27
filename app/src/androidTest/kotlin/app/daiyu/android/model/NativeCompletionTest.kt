package app.daiyu.android.model

import android.test.InstrumentationTestCase
import app.daiyu.android.domain.*
import app.daiyu.android.model.reconnect.ReconnectCapability
import app.daiyu.android.model.reconnect.ReconnectCapabilities
import app.daiyu.android.model.sessiongateway.SessionGatewayContext
import app.daiyu.android.model.catalog.*
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection

/** Real production transport with offline HTTPS doubles, never catalogue-only inference evidence. */
@Suppress("DEPRECATION")
class NativeCompletionTest : InstrumentationTestCase() {
    private val protocols = listOf(ProviderProtocol.CHAT_COMPLETIONS, ProviderProtocol.GEMINI)
    private fun settings(protocol: ProviderProtocol) = ModelSettings("https://provider.invalid", "fixture", "synthetic-key", true, protocol = protocol)
    private fun frame(value: Any) = "data: $value\n\n"
    private fun chat(delta: JSONObject, reason: Any = JSONObject.NULL) = JSONObject().put("id", "completion")
        .put("choices", JSONArray().put(JSONObject().put("index", 0).put("delta", delta).put("finish_reason", reason)))
    private fun text(protocol: ProviderProtocol): String = if (protocol == ProviderProtocol.CHAT_COMPLETIONS)
        frame(chat(JSONObject().put("role", "assistant").put("content", "hel"))) +
            frame(chat(JSONObject().put("content", "lo"), "stop")) +
            frame(JSONObject().put("id", "completion").put("choices", JSONArray()).put("usage", JSONObject()
                .put("prompt_tokens", 10).put("completion_tokens", 3).put("prompt_tokens_details", JSONObject().put("cached_tokens", 4)))) + frame("[DONE]")
        else frame(gemini(JSONArray().put(JSONObject().put("text", "hel")))) +
            frame(gemini(JSONArray().put(JSONObject().put("text", "lo")), "STOP").put("usageMetadata", JSONObject()
                .put("promptTokenCount", 10).put("candidatesTokenCount", 2).put("thoughtsTokenCount", 1).put("cachedContentTokenCount", 4)))
    private fun gemini(parts: JSONArray, reason: String? = null) = JSONObject().put("candidates", JSONArray().put(JSONObject()
        .put("index", 0).put("content", JSONObject().put("role", "model").put("parts", parts)).also { if (reason != null) it.put("finishReason", reason) }))
    private fun tool(protocol: ProviderProtocol): String = if (protocol == ProviderProtocol.CHAT_COMPLETIONS) {
        fun delta(arguments: String, initial: Boolean) = JSONObject().put("tool_calls", JSONArray().put(JSONObject().put("index", 0)
            .also { if (initial) it.put("id", "call").put("type", "function") }.put("function", JSONObject()
                .also { if (initial) it.put("name", "get_battery_status") }.put("arguments", arguments))))
        frame(chat(delta("{\"x\":", true))) + frame(chat(delta("1}", false), "tool_calls")) + frame("[DONE]")
    } else frame(gemini(JSONArray().put(JSONObject().put("thoughtSignature", "opaque-signature").put("functionCall", JSONObject()
        .put("id", "call").put("name", "get_battery_status").put("args", JSONObject().put("x", 1)))), "STOP"))
    private fun history(role: String, blocks: List<MessageBlock>, raw: List<String> = emptyList(), status: RunStatus = RunStatus.COMPLETED) =
        Message(MessageId(if (role == "user") "user" else "assistant"), ConversationId("c"), RunId("r"), role, blocks, status, protocolItems = raw)
    private fun input() = listOf(history("user", listOf(MessageBlock.Text("input", "question"))))
    private fun rejected(task: () -> Unit) { try { task(); fail("must reject") } catch (_: IllegalArgumentException) {} }
    private fun failure(expected: ModelFailure, task: () -> Unit) {
        try { task(); fail("must fail $expected") } catch (e: ModelRequestException) { assertEquals(expected, e.failure) }
    }
    private fun decode(protocol: ProviderProtocol, body: String, events: MutableList<ModelEvent>) =
        NativeCompletionStream.read(protocol, body.reader().buffered(), { false }, events::add)

    private class Connection(body: String, private val status: Int = 200, private val content: String = "text/event-stream", private val block: Boolean = false) :
        HttpsURLConnection(URL("https://provider.invalid")) {
        val request = ByteArrayOutputStream()
        val entered = CountDownLatch(1); val released = CountDownLatch(1)
        var disconnected = false; var inputClosed = false; var outputClosed = false
        private val source = body.byteInputStream()
        private val response = object : InputStream() {
            override fun read(): Int {
                val value = source.read()
                if (value >= 0) return value
                if (block) { entered.countDown(); if (!released.await(5, TimeUnit.SECONDS)) throw IOException("fixture timeout") }
                return -1
            }
            override fun close() { inputClosed = true; source.close() }
        }
        override fun connect() {}
        override fun disconnect() { disconnected = true; released.countDown() }
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
        override fun getContentType() = content
    }

    fun testGatewayCannotInjectUnsupportedReasoningAfterCustomBodyValidation() {
        var posts = 0
        val config = settings(ProviderProtocol.CHAT_COMPLETIONS).copy(requestOptions = ProviderRequestOptions(
            sessionGateway = app.daiyu.android.model.sessiongateway.SessionGatewayRule(
                modelPattern = "^fixture$", pathPattern = "/chat/completions", keyField = "reasoning_effort")))
        rejected {
            NativeCompletionModel(config, { posts++; Connection(text(ProviderProtocol.CHAT_COMPLETIONS)) },
                sessionContext = SessionGatewayContext(ConversationId("conversation")))
                .open(input()).next(emptyList()) {}
        }
        assertEquals(0, posts)
    }

    fun testProductionRequestNativeInstructionsHeadersAndUsage() {
        protocols.forEach { protocol ->
            val config = settings(protocol).copy(requestOptions = if (protocol == ProviderProtocol.CHAT_COMPLETIONS)
                ProviderRequestOptions(sessionGateway = app.daiyu.android.model.sessiongateway.SessionGatewayRule(
                    modelPattern = "^fixture$", pathPattern = "/chat/completions")) else ProviderRequestOptions())
            val conn = Connection(text(protocol))
            var posts = 0; var url = ""
            val model = NativeCompletionModel(config, { url = it.toString(); posts++; conn }, "frozen system", SessionGatewayContext(ConversationId("conversation")))
            val events = mutableListOf<ModelEvent>()
            val session = model.open(input(), emptyList<ToolDefinition>())
            session.next(emptyList(), events::add)
            assertEquals(1, posts)
            assertEquals(if (protocol == ProviderProtocol.CHAT_COMPLETIONS) "https://provider.invalid/v1/chat/completions" else "https://provider.invalid/v1beta/models/fixture:streamGenerateContent?alt=sse", url)
            assertEquals("POST", conn.requestMethod); assertFalse(conn.instanceFollowRedirects)
            assertEquals("conversation", conn.getRequestProperty("session-id")); assertEquals("conversation", conn.getRequestProperty("thread-id"))
            assertEquals("application/json", conn.getRequestProperty("Content-Type"))
            val request = JSONObject(conn.request.toString("UTF-8"))
            if (protocol == ProviderProtocol.CHAT_COMPLETIONS) {
                assertEquals("Bearer synthetic-key", conn.getRequestProperty("Authorization")); assertNull(conn.getRequestProperty("x-goog-api-key"))
                assertEquals("fixture", request.getString("model")); assertTrue(request.getBoolean("stream"))
                assertEquals("conversation", request.getString("prompt_cache_key"))
                assertEquals("system", request.getJSONArray("messages").getJSONObject(0).getString("role"))
                assertEquals("frozen system", request.getJSONArray("messages").getJSONObject(0).getString("content"))
                assertTrue(request.getJSONObject("stream_options").getBoolean("include_usage"))
            } else {
                assertEquals("synthetic-key", conn.getRequestProperty("x-goog-api-key")); assertNull(conn.getRequestProperty("Authorization"))
                assertFalse(request.has("model")); assertFalse(request.has("stream"))
                assertEquals("frozen system", request.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text"))
                assertEquals("question", request.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text"))
            }
            assertEquals(listOf("hel", "lo"), events.filterIsInstance<ModelEvent.TextDelta>().map { it.text })
            assertEquals("hello", events.filterIsInstance<ModelEvent.TextFinal>().single().text)
            assertEquals(ModelEvent.Usage(10, 3, 4), events.filterIsInstance<ModelEvent.Usage>().single())
            assertEquals(1, events.filterIsInstance<ModelEvent.ProtocolOutput>().single().items.size)
            assertTrue(conn.inputClosed && conn.outputClosed && conn.disconnected)
            assertEquals(ReconnectCapability.REQUEST_RETRY, ReconnectCapabilities.forProtocol(protocol))
            assertTrue(session is RequestRetryModelSession)
            assertFalse(session is CheckpointedResumableModelSession)
            assertFalse((session as RequestRetryModelSession).canResume)
            try { session.next(emptyList()) {}; fail("must not repost completed request") } catch (_: IllegalStateException) {}
            assertEquals(1, posts)
        }
    }

    fun testChatReasoningSurvivesActualSecondPostWithTheSameFrozenEffort() {
        val protocol = ProviderProtocol.CHAT_COMPLETIONS
        val thought = frame(chat(JSONObject().put("reasoning_content", "reported thought")))
        val connections = listOf(Connection(thought + tool(protocol)), Connection(text(protocol)))
        val frozen = ReasoningRequestPolicy.freeze(settings(protocol).requestSnapshot(), app.daiyu.android.delegation.ReasoningEffort.HIGH)
        var posts = 0
        val session = NativeCompletionModel(frozen.asModelSettings(), { connections[posts++] }).open(input(),
            listOf(ToolDefinition("get_battery_status", "battery", "{\"type\":\"object\",\"properties\":{}}")))
        val events = mutableListOf<ModelEvent>()
        session.next(emptyList(), events::add)
        val call = events.filterIsInstance<ModelEvent.ToolReady>().single().invocation
        assertEquals("reported thought", events.filterIsInstance<ModelEvent.ReasoningFinal>().single().text)
        session.next(listOf(ToolOutput(call.id, "ok")), events::add)
        assertEquals(2, posts)
        connections.forEach { assertEquals("high", JSONObject(it.request.toString("UTF-8")).getString("reasoning_effort")) }
        val second = JSONObject(connections[1].request.toString("UTF-8"))
        assertEquals("reported thought", second.getJSONArray("messages").getJSONObject(1).getString("reasoning_content"))
        assertTrue(connections.all { it.disconnected && it.inputClosed && it.outputClosed })
    }

    fun testToolsNativeContinuationSavedHistoryAndIdMatching() {
        protocols.forEach { protocol ->
            val connections = listOf(Connection(tool(protocol)), Connection(text(protocol)))
            var posts = 0
            val model = NativeCompletionModel(settings(protocol), { connections[posts++] })
            val session = model.open(input(), listOf(ToolDefinition("get_battery_status", "battery", "{\"type\":\"object\",\"properties\":{}}")))
            val events = mutableListOf<ModelEvent>()
            session.next(emptyList(), events::add)
            val invocation = events.filterIsInstance<ModelEvent.ToolReady>().single().invocation
            assertEquals("call", invocation.id.value); assertEquals(1, JSONObject(invocation.arguments).getInt("x"))
            rejected { session.next(listOf(ToolOutput(ToolCallId("wrong"), "ok")), events::add) }
            rejected { session.next(listOf(ToolOutput(invocation.id, "ok"), ToolOutput(invocation.id, "ok")), events::add) }
            assertEquals(1, posts)
            session.next(listOf(ToolOutput(invocation.id, "ok")), events::add)
            val second = JSONObject(connections[1].request.toString("UTF-8"))
            val rows = second.getJSONArray(if (protocol == ProviderProtocol.CHAT_COMPLETIONS) "messages" else "contents")
            assertEquals(3, rows.length())
            if (protocol == ProviderProtocol.CHAT_COMPLETIONS) {
                assertEquals("call", rows.getJSONObject(1).getJSONArray("tool_calls").getJSONObject(0).getString("id"))
                assertEquals("tool", rows.getJSONObject(2).getString("role")); assertEquals("call", rows.getJSONObject(2).getString("tool_call_id"))
                assertEquals("ok", rows.getJSONObject(2).getString("content"))
                assertTrue(second.getJSONArray("tools").getJSONObject(0).getJSONObject("function").has("parameters"))
            } else {
                val part = rows.getJSONObject(1).getJSONArray("parts").getJSONObject(0)
                assertEquals("opaque-signature", part.getString("thoughtSignature"))
                val result = rows.getJSONObject(2).getJSONArray("parts").getJSONObject(0).getJSONObject("functionResponse")
                assertEquals("call", result.getString("id")); assertEquals("get_battery_status", result.getString("name"))
                assertEquals("ok", result.getJSONObject("response").getString("output"))
                assertTrue(second.getJSONArray("tools").getJSONObject(0).getJSONArray("functionDeclarations").getJSONObject(0).has("parametersJsonSchema"))
            }
            val saved = events.filterIsInstance<ModelEvent.ProtocolOutput>().last().items
            assertEquals(3, saved.size)
            val replay = NativeCompletionRequest.history(protocol, listOf(history("assistant", listOf(MessageBlock.Tool(invocation, "ok")), saved)))
            assertEquals(3, replay.length())
            rejected { NativeCompletionRequest.history(protocol, listOf(history("assistant", listOf(MessageBlock.Tool(invocation, "different")), saved))) }
            rejected { NativeCompletionRequest.history(protocol, listOf(history("assistant", emptyList(), listOf("{\"type\":\"function_call\"}")))) }
            val blocks = NativeCompletionRequest.history(protocol, listOf(history("assistant", listOf(MessageBlock.Tool(invocation, "ok")))))
            assertEquals(2, blocks.length())
            rejected { NativeCompletionRequest.history(protocol, listOf(history("assistant", listOf(MessageBlock.Tool(invocation))))) }
        }
    }

    fun testEofTruncationBadFinishAndSafetyFramesNeverConfirmTools() {
        protocols.forEach { protocol ->
            val events = mutableListOf<ModelEvent>()
            val incomplete = if (protocol == ProviderProtocol.CHAT_COMPLETIONS) tool(protocol).removeSuffix(frame("[DONE]"))
                else tool(protocol).replace(",\"finishReason\":\"STOP\"", "").replace("\"finishReason\":\"STOP\",", "")
            failure(ModelFailure.INCOMPLETE) { decode(protocol, incomplete, events) }
            assertTrue(events.none { it is ModelEvent.ToolReady || it is ModelEvent.TextFinal || it is ModelEvent.Usage })
            failure(ModelFailure.INCOMPLETE) { decode(protocol, tool(protocol).trimEnd(), mutableListOf()) }
            val limited = if (protocol == ProviderProtocol.CHAT_COMPLETIONS) text(protocol).replace("\"stop\"", "\"length\"")
                else text(protocol).replace("\"STOP\"", "\"MAX_TOKENS\"")
            failure(ModelFailure.INCOMPLETE) { decode(protocol, limited, mutableListOf()) }
            failure(ModelFailure.PROTOCOL) { decode(protocol, frame("{'invalid':true}"), mutableListOf()) }
            failure(ModelFailure.PROTOCOL) { decode(protocol, frame("x".repeat(1_048_577)), mutableListOf()) }
        }
    }

    fun testGeminiNativeTextToolTextPartOrderIsNotFlattenedAcrossTheCall() {
        val parts = JSONArray().put(JSONObject().put("text", "before"))
            .put(JSONObject().put("functionCall", JSONObject().put("id", "call").put("name", "get_battery_status").put("args", JSONObject())))
            .put(JSONObject().put("text", "after"))
        val events = mutableListOf<ModelEvent>()
        decode(ProviderProtocol.GEMINI, frame(gemini(parts, "STOP")), events)
        val committed = events.filter { it is ModelEvent.TextFinal || it is ModelEvent.ToolReady }
        assertEquals(3, committed.size)
        assertEquals("before", (committed[0] as ModelEvent.TextFinal).text)
        assertTrue(committed[1] is ModelEvent.ToolReady)
        assertEquals("after", (committed[2] as ModelEvent.TextFinal).text)
        assertTrue((committed[0] as ModelEvent.TextFinal).itemId != (committed[2] as ModelEvent.TextFinal).itemId)
    }

    fun testExistingRunnerExecutesOneConfirmedToolAndNeverRunsTruncatedTool() {
        protocols.forEach { protocol ->
            var posts = 0; var toolRuns = 0
            val model = NativeCompletionModel(settings(protocol), { if (posts++ == 0) Connection(tool(protocol)) else Connection(text(protocol)) })
            val saved = mutableListOf<Message>()
            val store = object : ConversationStore { override fun save(message: Message) { saved.add(message) } }
            val initial = history("assistant", emptyList(), status = RunStatus.RUNNING)
            val completed = ChatRunner(model, store, BatteryTool { toolRuns++; "ok" }) {}.run(initial, input())
            assertEquals(RunStatus.COMPLETED, completed.status); assertEquals(1, toolRuns); assertEquals(2, posts)
            assertTrue(completed.blocks.filterIsInstance<MessageBlock.Text>().any { it.text == "hello" })
            val replay = NativeCompletionRequest.history(protocol, listOf(completed))
            assertEquals(3, replay.length())
            var failedPosts = 0; var failedTools = 0
            val partial = if (protocol == ProviderProtocol.CHAT_COMPLETIONS) tool(protocol).removeSuffix(frame("[DONE]"))
                else frame(gemini(JSONArray().put(JSONObject().put("functionCall", JSONObject().put("id", "call").put("name", "get_battery_status").put("args", JSONObject())))))
            val broken = NativeCompletionModel(settings(protocol), { failedPosts++; Connection(partial) })
            val interrupted = ChatRunner(broken, store, BatteryTool { failedTools++; "must not execute" }) {}.run(initial, input())
            assertEquals(RunStatus.FAILED, interrupted.status); assertEquals(0, failedTools); assertEquals(1, failedPosts)
        }
    }

    fun testGeminiMissingCallIdGetsOneStableNativePairAndParallelResultsShareOneUserTurn() {
        val parts = JSONArray().put(JSONObject().put("functionCall", JSONObject().put("name", "get_battery_status").put("args", JSONObject())))
            .put(JSONObject().put("functionCall", JSONObject().put("id", "second").put("name", "device_status").put("args", JSONObject())))
        val first = Connection(frame(gemini(parts, "STOP"))); val second = Connection(text(ProviderProtocol.GEMINI))
        var posts = 0
        val session = NativeCompletionModel(settings(ProviderProtocol.GEMINI), { if (posts++ == 0) first else second }).open(input())
        val events = mutableListOf<ModelEvent>()
        session.next(emptyList(), events::add)
        val calls = events.filterIsInstance<ModelEvent.ToolReady>().map { it.invocation }
        assertEquals(2, calls.size); assertTrue(calls[0].id.value.startsWith("daiyu-"))
        session.next(calls.map { ToolOutput(it.id, "ok") }, events::add)
        val rows = JSONObject(second.request.toString("UTF-8")).getJSONArray("contents")
        assertEquals(3, rows.length())
        val nativeCalls = rows.getJSONObject(1).getJSONArray("parts")
        val results = rows.getJSONObject(2).getJSONArray("parts")
        assertEquals(2, results.length())
        calls.forEachIndexed { index, call ->
            assertEquals(call.id.value, nativeCalls.getJSONObject(index).getJSONObject("functionCall").getString("id"))
            assertEquals(call.id.value, results.getJSONObject(index).getJSONObject("functionResponse").getString("id"))
        }
    }

    fun testTruncatedProductionRequestCannotRepostAndUsageIsNotInvented() {
        protocols.forEach { protocol ->
            var posts = 0
            val partial = if (protocol == ProviderProtocol.CHAT_COMPLETIONS) frame(chat(JSONObject().put("content", "partial")))
                else frame(gemini(JSONArray().put(JSONObject().put("text", "partial"))))
            val session = NativeCompletionModel(settings(protocol), { posts++; Connection(partial) }).open(input())
            val events = mutableListOf<ModelEvent>()
            failure(ModelFailure.NETWORK) { session.next(emptyList(), events::add) }
            assertTrue(session is RequestRetryModelSession)
            assertFalse(session is CheckpointedResumableModelSession)
            assertTrue((session as RequestRetryModelSession).canResume)
            assertEquals(ReconnectCapability.REQUEST_RETRY, ReconnectCapabilities.forSession(session))
            assertEquals("partial", events.filterIsInstance<ModelEvent.TextDelta>().single().text)
            assertTrue(events.none { it is ModelEvent.TextFinal || it is ModelEvent.ToolReady || it is ModelEvent.Usage || it is ModelEvent.ProtocolOutput })
            try { session.next(emptyList()) {}; fail("no retry after EOF") } catch (_: IllegalStateException) {}
            assertEquals(1, posts)
            val noUsage = mutableListOf<ModelEvent>()
            decode(protocol, tool(protocol), noUsage)
            assertTrue(noUsage.none { it is ModelEvent.Usage })
        }
    }

    fun testUnknownUsageFieldsDoNotCreateUsageAndInvalidCountsDoNotConfirmTools() {
        protocols.forEach { protocol ->
            val unknown = if (protocol == ProviderProtocol.CHAT_COMPLETIONS)
                frame(chat(JSONObject().put("content", "answer"), "stop").put("usage", JSONObject().put("vendor_count", 17))) + frame("[DONE]")
            else frame(gemini(JSONArray().put(JSONObject().put("text", "answer")), "STOP").put("usageMetadata", JSONObject().put("vendor_count", 17)))
            val events = mutableListOf<ModelEvent>(); decode(protocol, unknown, events)
            assertTrue(events.none { it is ModelEvent.Usage })
            val bad = if (protocol == ProviderProtocol.CHAT_COMPLETIONS)
                tool(protocol).removeSuffix(frame("[DONE]")) + frame(JSONObject().put("id", "completion").put("choices", JSONArray())
                    .put("usage", JSONObject().put("prompt_tokens", -1))) + frame("[DONE]")
            else {
                val chunk = JSONObject(tool(protocol).removePrefix("data: ").trim())
                frame(chunk.put("usageMetadata", JSONObject().put("promptTokenCount", -1)))
            }
            val rejected = mutableListOf<ModelEvent>()
            failure(ModelFailure.PROTOCOL) { decode(protocol, bad, rejected) }
            assertTrue(rejected.none { it is ModelEvent.ToolReady || it is ModelEvent.Usage })
        }
    }

    fun testHttpFailuresAreOnePostAndCannotReplay() {
        protocols.forEach { protocol ->
            listOf(401 to ModelFailure.AUTHENTICATION, 403 to ModelFailure.PERMISSION, 404 to ModelFailure.ENDPOINT,
                429 to ModelFailure.RATE_LIMIT, 302 to ModelFailure.REDIRECT, 503 to ModelFailure.SERVER).forEach { (status, expected) ->
                var posts = 0
                val conn = Connection("secret provider error", status)
                val session = NativeCompletionModel(settings(protocol), { posts++; conn }).open(input())
                failure(expected) { session.next(emptyList()) {} }
                try { session.next(emptyList()) {}; fail("no retry") } catch (_: IllegalStateException) {}
                assertEquals(1, posts); assertTrue(conn.disconnected)
            }
            val wrongType = NativeCompletionModel(settings(protocol), { Connection(text(protocol), content = "application/json") }).open(input())
            failure(ModelFailure.PROTOCOL) { wrongType.next(emptyList()) {} }
        }
    }

    fun testGeminiGenerationConfigAndThinkingAreStrictlyValidated() {
        val protocol = ProviderProtocol.GEMINI
        val invalid = listOf("null", "\"not an object\"", "[]", "false", "3",
            """{"thinkingConfig":null}""", """{"thinkingConfig":[]}""",
            """{"thinkingConfig":{"thinkingBudget":"1024"}}""",
            """{"thinkingConfig":{"thinkingBudget":-2}}""",
            """{"thinkingConfig":{"thinkingLevel":"unknown"}}""",
            """{"thinkingConfig":{"includeThoughts":"true"}}""",
            """{"thinkingConfig":{"thinkingBudget":1024,"thinkingLevel":"high"}}""",
            """{"thinkingConfig":{"unknown":true}}""")
        invalid.forEach { value ->
            val options = ProviderRequestOptions("{\"generationConfig\":$value}")
            rejected { options.validate(protocol) }
            rejected { options.apply(NativeCompletionRequest.body(settings(protocol), JSONArray(), emptyList(), null), protocol) }
            var posts = 0
            rejected {
                NativeCompletionModel(settings(protocol).copy(requestOptions = options), { posts++; Connection(text(protocol)) })
                    .open(input()).next(emptyList()) {}
            }
            assertEquals(0, posts)
        }
        listOf("{}", "{\"candidateCount\":1,\"maxOutputTokens\":64}",
            """{"thinkingConfig":{}}""", """{"thinkingConfig":{"thinkingBudget":1024}}""",
            """{"thinkingConfig":{"thinkingLevel":"high","includeThoughts":true}}"""
        ).forEach { value ->
            val options = ProviderRequestOptions("{\"generationConfig\":$value}")
            options.validate(protocol)
            val merged = options.apply(NativeCompletionRequest.body(settings(protocol), JSONArray(), emptyList(), null), protocol)
            assertEquals(JSONObject(value).toString(), merged.getJSONObject("generationConfig").toString())
        }
    }

    fun testMergedChatReasoningPolicyRejectsBeforeConnecting() {
        var posts = 0
        val config = settings(ProviderProtocol.CHAT_COMPLETIONS).copy(
            requestOptions = ProviderRequestOptions("{\"reasoning_effort\":false}"))
        rejected {
            NativeCompletionModel(config, { posts++; Connection(text(ProviderProtocol.CHAT_COMPLETIONS)) })
                .open(input()).next(emptyList()) {}
        }
        assertEquals(0, posts)
    }

    fun testConcurrentNextCannotBothPassReplayGuardOrPost() {
        protocols.forEach { protocol ->
            val posts = AtomicInteger()
            val session = NativeCompletionModel(settings(protocol), { posts.incrementAndGet(); Connection(text(protocol)) }).open(input())
            val atValidation = CountDownLatch(1)
            val releaseValidation = CountDownLatch(1)
            // Pause after next's replay check but BEFORE it mutates started/usable. Merely blocking
            // the HTTP read would miss the original race because usable is already false by then.
            val heldOutputs = object : AbstractList<ToolOutput>() {
                override val size: Int get() {
                    check(Thread.holdsLock(session)) { "next must own the session monitor before validation" }
                    atValidation.countDown()
                    check(releaseValidation.await(5, TimeUnit.SECONDS)) { "fixture validation timeout" }
                    return 0
                }
                override fun get(index: Int): ToolOutput = throw IndexOutOfBoundsException()
            }
            val firstFailure = AtomicReference<Throwable>()
            val secondFailure = AtomicReference<Throwable>()
            val secondAttempted = CountDownLatch(1)
            val secondCompleted = CountDownLatch(1)
            val first = Thread { try { session.next(heldOutputs) {} } catch (failure: Throwable) { firstFailure.set(failure) } }
            val second = Thread {
                secondAttempted.countDown()
                try { session.next(emptyList()) {} } catch (failure: Throwable) { secondFailure.set(failure) }
                finally { secondCompleted.countDown() }
            }
            try {
                first.start()
                assertTrue(atValidation.await(3, TimeUnit.SECONDS))
                second.start()
                assertTrue(secondAttempted.await(3, TimeUnit.SECONDS))
                assertFalse("second next must not complete while validation owns the session monitor",
                    secondCompleted.await(200, TimeUnit.MILLISECONDS))
                assertTrue(first.isAlive)
            } finally {
                releaseValidation.countDown()
                first.join(5000); second.join(5000)
                session.cancel()
            }
            assertFalse(first.isAlive); assertFalse(second.isAlive)
            assertNull(firstFailure.get())
            assertTrue(secondFailure.get() is IllegalStateException)
            assertEquals(1, posts.get())
        }
    }

    fun testCancellationBeforeAndDuringReadIsPrimaryWithZeroRetry() {
        protocols.forEach { protocol ->
            var posts = 0
            val conn = Connection("", block = true)
            val session = NativeCompletionModel(settings(protocol), { posts++; conn }).open(input())
            val thrown = AtomicReference<Throwable>()
            val thread = Thread { try { session.next(emptyList()) {} } catch (failure: Throwable) { thrown.set(failure) } }
            val cancelReturned = CountDownLatch(1)
            val canceller = Thread { try { session.cancel() } finally { cancelReturned.countDown() } }
            var cancelledPromptly = false
            try {
                thread.start(); assertTrue(conn.entered.await(3, TimeUnit.SECONDS))
                canceller.start()
                cancelledPromptly = cancelReturned.await(1, TimeUnit.SECONDS)
            } finally {
                // Always release the offline read, even if cancel incorrectly waits on next's monitor.
                conn.released.countDown()
                thread.join(5000); canceller.join(5000)
            }
            assertTrue("cancel must not wait for the network-held next monitor", cancelledPromptly)
            assertFalse(canceller.isAlive)
            assertFalse(thread.isAlive); assertTrue(thrown.get() is InterruptedException)
            assertTrue(conn.disconnected && conn.inputClosed); assertEquals(1, posts)
            try { session.next(emptyList()) {}; fail("cancelled") } catch (_: InterruptedException) {}
            assertEquals(1, posts)
            val before = NativeCompletionModel(settings(protocol), { posts++; conn }).open(input())
            before.cancel(); try { before.next(emptyList()) {}; fail("cancelled before connect") } catch (_: InterruptedException) {}
            assertEquals(1, posts)
        }
    }

    fun testSinkExceptionIdentityEvenWhenSinkCancels() {
        protocols.forEach { protocol ->
            val conn = Connection(text(protocol)); val session = NativeCompletionModel(settings(protocol), { conn }).open(input())
            val expected = IllegalArgumentException("caller-owned")
            try { session.next(emptyList()) { event ->
                if (event is ModelEvent.TextDelta) { session.cancel(); throw expected }
            }; fail("sink failure") }
            catch (actual: Exception) { assertSame(expected, actual) }
            assertTrue(conn.disconnected)
        }
    }

    fun testNativeProductionHistorySurvivesSqliteReopenAndTextBackupWithoutMessagesFallback() {
        protocols.forEach { protocol ->
            val prefix = "native-history-${java.util.UUID.randomUUID()}-"
            val source = app.daiyu.android.testContext(instrumentation.targetContext, "${prefix}source-")
            val target = app.daiyu.android.testContext(instrumentation.targetContext, "${prefix}target-")
            var db = app.daiyu.android.storage.ConversationDatabase(source)
            var restored = app.daiyu.android.storage.ConversationDatabase(target)
            try {
                val conversation = db.create("default")
                val user = input().single().copy(conversationId = conversation.id)
                val initial = history("assistant", emptyList(), status = RunStatus.RUNNING).copy(conversationId = conversation.id)
                db.send(user, initial)
                var posts = 0
                val model = NativeCompletionModel(settings(protocol), { if (posts++ == 0) Connection(tool(protocol)) else Connection(text(protocol)) })
                val completed = ChatRunner(model, db, BatteryTool { "ok" }) {}.run(initial, listOf(user))
                assertEquals(RunStatus.COMPLETED, completed.status)
                val backup = db.exportBackup()
                db.close(); db = app.daiyu.android.storage.ConversationDatabase(source)
                assertEquals(completed, db.messages(conversation.id).last().second)
                restored.preflightBackup(backup); assertEquals(1 to 2, restored.importBackup(backup))
                restored.close(); restored = app.daiyu.android.storage.ConversationDatabase(target)
                val imported = restored.messages(conversation.id).last().second
                assertEquals(completed, imported)
                assertEquals(4, NativeCompletionRequest.history(protocol, listOf(user, imported)).length())
                assertEquals(backup, restored.exportBackup())
                // A confirmed call with no completed local result remains pending in backups, never fabricated.
                val call = mutableListOf<ModelEvent>()
                val raw = decode(protocol, tool(protocol), call).toString()
                val invocation = call.filterIsInstance<ModelEvent.ToolReady>().single().invocation
                val pendingConversation = db.create("default")
                val pendingRun = RunId("pending-run")
                val pendingUser = user.copy(id = MessageId("pending-user"), conversationId = pendingConversation.id, runId = pendingRun)
                val pendingInitial = initial.copy(id = MessageId("pending-assistant"), conversationId = pendingConversation.id, runId = pendingRun)
                db.send(pendingUser, pendingInitial)
                val pending = pendingInitial.copy(status = RunStatus.STOPPED, blocks = listOf(MessageBlock.Tool(invocation)),
                    protocolItems = listOf(raw), historySegments = emptyList())
                db.save(pending)
                val pendingBackup = db.exportBackup()
                restored.preflightBackup(pendingBackup)
                assertEquals(1, NativeCompletionRequest.history(protocol, listOf(pending.copy(status = RunStatus.COMPLETED)), allowPending = true).length())
            } finally {
                db.close(); restored.close(); source.deleteDatabase("conversations.db"); target.deleteDatabase("conversations.db")
            }
        }
    }

    fun testSettingsFactoryCatalogAndUnsafeEndpointPreflight() {
        protocols.forEach { protocol ->
            val value = settings(protocol)
            assertTrue(ModelFactory.create(value.requestSnapshot()) is NativeCompletionModel)
            assertTrue(ModelFactory.create(value.requestSnapshot(), "title instructions") is NativeCompletionModel)
            assertTrue(ModelFactory.create(value.requestSnapshot(), SessionGatewayContext(ConversationId("c"))) is NativeCompletionModel)
            assertTrue(ModelFactory.create(value, protocol.wireName) is NativeCompletionModel)
            assertEquals(if (protocol == ProviderProtocol.GEMINI) RemoteModelProtocol.GEMINI else RemoteModelProtocol.OPENAI, catalogRequest(value).protocol)
            val explicit = value.copy(endpoint = value.requestEndpoint().substringBefore('?'))
            assertEquals(if (protocol == ProviderProtocol.GEMINI) "https://provider.invalid/v1beta/models" else "https://provider.invalid/v1/models",
                catalogRequest(explicit).let { remoteModelsEndpoint(it.baseUrl, it.protocol) })
            for (endpoint in listOf("http://provider.invalid", "https://u:p@provider.invalid", "https://provider.invalid?q=secret", "https://provider.invalid/#bad", "https://provider.invalid:0", "https://provider.invalid/v1/responses"))
                rejected { NativeCompletionModel(value.copy(endpoint = endpoint), { fail("must not connect"); Connection("") }).open(input()) }
            assertEquals(value.requestEndpoint(), value.copy(responsesPath = "/other").requestEndpoint())
            if (protocol == ProviderProtocol.GEMINI)
                rejected { SummaryProviderFactory.validate(value.requestSnapshot()) }
            else SummaryProviderFactory.validate(value.requestSnapshot())
        }
        listOf(ModelFailure.ENDPOINT, ModelFailure.REQUEST, ModelFailure.PROTOCOL).forEach { assertFalse(it.userMessage.contains("Responses")) }
    }
}
