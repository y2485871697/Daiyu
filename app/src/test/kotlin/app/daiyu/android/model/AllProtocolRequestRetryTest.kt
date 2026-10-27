package app.daiyu.android.model

import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.*
import app.daiyu.android.model.reconnect.*
import app.daiyu.android.model.oauth.*
import app.daiyu.android.storage.ModelSettings
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.URL
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

class AllProtocolRequestRetryTest {
    private class Connection(url: URL, events: String, private val status: Int = 200) : HttpsURLConnection(url) {
        val sent = ByteArrayOutputStream()
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getOutputStream() = sent
        private val incoming = events.byteInputStream()
        override fun getInputStream() = incoming
        override fun getResponseCode() = status
        override fun getContentType() = "text/event-stream"
    }
    private fun frame(type: String, body: JSONObject = JSONObject()) = "data: ${body.put("type", type)}\n\n"
    private fun response(text: String, partial: Boolean): String {
        val delta = frame("response.output_text.delta", JSONObject().put("item_id", "m").put("content_index", 0).put("delta", text))
        if (partial) return delta + "data: {\"type\":"
        val message = JSONObject().put("id", "m").put("type", "message").put("role", "assistant")
            .put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", text)))
        return delta + frame("response.completed", JSONObject().put("response", JSONObject().put("status", "completed").put("output", JSONArray().put(message))))
    }
    private fun messages(text: String, partial: Boolean): String = buildString {
        append(frame("message_start", JSONObject().put("message", JSONObject().put("id", "m").put("role", "assistant").put("content", JSONArray()))))
        append(frame("content_block_start", JSONObject().put("index", 0).put("content_block", JSONObject().put("type", "text").put("text", text))))
        append(frame("content_block_stop", JSONObject().put("index", 0)))
        if (!partial) {
            append(frame("message_delta", JSONObject().put("delta", JSONObject().put("stop_reason", "end_turn"))))
            append(frame("message_stop"))
        }
    }
    private fun native(protocol: ProviderProtocol, text: String, partial: Boolean): String {
        val body = if (protocol == ProviderProtocol.GEMINI) JSONObject().put("candidates", JSONArray().put(JSONObject()
            .put("content", JSONObject().put("role", "model").put("parts", JSONArray().put(JSONObject().put("text", text))))
            .also { if (!partial) it.put("finishReason", "STOP") }))
        else JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", JSONObject().put("content", text))
            .put("finish_reason", if (partial) JSONObject.NULL else "stop")))
        return "data: $body\n\n" + if (protocol == ProviderProtocol.CHAT_COMPLETIONS && !partial) "data: [DONE]\n\n" else ""
    }
    private fun stream(protocol: ProviderProtocol, text: String, partial: Boolean) = when (protocol) {
        ProviderProtocol.RESPONSES -> response(text, partial)
        ProviderProtocol.MESSAGES -> messages(text, partial)
        else -> native(protocol, text, partial)
    }
    private fun run(config: ModelSettings, raw: ModelPort, duration: ReconnectDurationPreset): Message {
        val frozen = FrozenReconnectAdapter.freeze(ReconnectSettings(duration), ProviderSnapshot.capture(config))
        val port = frozen.observeFailures(raw) { error("known production protocols must not report unsupported") }
        return ChatRunner(port, object : ConversationStore { override fun save(message: Message) {} },
            BatteryTool { error("no local tools") }, RunnerOptions(reconnect = frozen.runnerPolicy())) {}.run(
            Message(MessageId("a"), ConversationId("c"), RunId("r"), "assistant", emptyList(), RunStatus.RUNNING), emptyList())
    }
    @Test fun everyProtocolRetriesCurrentPostWithBackgroundOffAndReplacesPartialText() {
        for (protocol in ProviderProtocol.entries.filter { it != ProviderProtocol.UNKNOWN }) {
            val config = ModelSettings("https://fixture.invalid/v1", "fixture", "synthetic", false, protocol = protocol)
            val connections = mutableListOf<Connection>()
            val connect: (URL) -> HttpsURLConnection = { url -> Connection(url, stream(protocol,
                if (connections.isEmpty()) "discard" else "fresh", connections.isEmpty())).also(connections::add) }
            val raw = when (protocol) {
                ProviderProtocol.RESPONSES -> ResponsesModel(config, connect)
                ProviderProtocol.MESSAGES -> MessagesModel(config, connect)
                else -> NativeCompletionModel(config, connect)
            }
            val result = run(config, raw, ReconnectDurationPreset.WINDOW_30S)
            assertEquals(protocol.name, RunStatus.COMPLETED, result.status)
            assertEquals(protocol.name, listOf("fresh"), result.blocks.filterIsInstance<MessageBlock.Text>().map { it.text })
            assertEquals(2, connections.size)
            assertEquals(connections[0].url, connections[1].url)
            assertEquals(connections[0].sent.toString("UTF-8"), connections[1].sent.toString("UTF-8"))
            assertEquals(listOf("POST", "POST"), connections.map { it.requestMethod })
        }
    }
    @Test fun everyProtocolPauseAndFastResumeRetriesInsteadOfSilentlyLosingFinalText() {
        for (protocol in ProviderProtocol.entries.filter { it != ProviderProtocol.UNKNOWN }) {
            val config = ModelSettings("https://fixture.invalid/v1", "fixture", "synthetic", false, protocol = protocol)
            val connections = mutableListOf<Connection>()
            val connect: (URL) -> HttpsURLConnection = { url -> Connection(url,
                stream(protocol, if (connections.isEmpty()) "discard" else "fresh", false)).also(connections::add) }
            val raw = when (protocol) {
                ProviderProtocol.RESPONSES -> ResponsesModel(config, connect)
                ProviderProtocol.MESSAGES -> MessagesModel(config, connect)
                else -> NativeCompletionModel(config, connect)
            }
            lateinit var runner: ChatRunner
            var paused = false
            fun wrap(session: ModelSession): ModelSession {
                val retained = session as RequestRetryModelSession
                return object : RequestRetryModelSession {
                    override val canResume: Boolean get() = retained.canResume
                    override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) = retained.next(outputs) { event ->
                        if (!paused && (event is ModelEvent.TextDelta || event is ModelEvent.TextFinal)) {
                            paused = true
                            assertTrue(runner.pause())
                            assertTrue(runner.resume())
                        }
                        sink(event)
                    }
                    override fun resume(sink: (ModelEvent) -> Unit) = retained.resume(sink)
                    override fun disconnectTransport() = retained.disconnectTransport()
                    override fun cancel() = retained.cancel()
                }
            }
            val port = object : ModelPort {
                override fun open(history: List<Message>) = wrap(raw.open(history))
                override fun open(history: List<Message>, tools: Set<ReadOnlyTool>) = wrap(raw.open(history, tools))
                override fun open(history: List<Message>, definitions: List<ToolDefinition>) = wrap(raw.open(history, definitions))
            }
            val frozen = FrozenReconnectAdapter.freeze(ReconnectSettings(ReconnectDurationPreset.WINDOW_30S), ProviderSnapshot.capture(config))
            runner = ChatRunner(port, object : ConversationStore { override fun save(message: Message) {} },
                BatteryTool { error("no tools") }, RunnerOptions(reconnect = frozen.runnerPolicy())) {}
            val result = runner.run(Message(MessageId("a"), ConversationId("c"), RunId("r"), "assistant", emptyList(), RunStatus.RUNNING), emptyList())
            assertTrue(protocol.name, paused)
            assertEquals(protocol.name, RunStatus.COMPLETED, result.status)
            assertEquals(protocol.name, listOf("fresh"), result.blocks.filterIsInstance<MessageBlock.Text>().map { it.text })
            assertEquals(protocol.name, 2, connections.size)
        }
    }
    @Test fun noneNeverRetriesEvenThoughProductionSessionSupportsIt() {
        for (protocol in ProviderProtocol.entries.filter { it != ProviderProtocol.UNKNOWN }) {
            val config = ModelSettings("https://fixture.invalid/v1", "fixture", "synthetic", false, protocol = protocol)
            var requests = 0
            val connect: (URL) -> HttpsURLConnection = { url -> requests++; Connection(url, stream(protocol, "partial", true)) }
            val raw = when (protocol) {
                ProviderProtocol.RESPONSES -> ResponsesModel(config, connect)
                ProviderProtocol.MESSAGES -> MessagesModel(config, connect)
                else -> NativeCompletionModel(config, connect)
            }
            val result = run(config, raw, ReconnectDurationPreset.NONE)
            assertEquals(protocol.name, RunStatus.FAILED, result.status)
            assertEquals(protocol.name, 1, requests)
        }
    }
    @Test fun noneNeverRefreshesOAuthAfterRejectedRequest() {
        val auth = CodexAuth("00000000-0000-0000-0000-000000000001")
        val config = ModelSettings(CodexOAuthProtocol.BASE_URL, "gpt-fixture", "", false, responsesPath = "/responses", oauth = auth)
        var requests = 0
        val raw = ResponsesModel(config, { url -> requests++; Connection(url, "", 401) }, null,
            oauthHeaders = { mapOf("Authorization" to "Bearer old") },
            oauthRefresh = { _, _ -> error("NONE must not refresh rejected credentials") })
        assertEquals(RunStatus.FAILED, run(config, raw, ReconnectDurationPreset.NONE).status)
        assertEquals(1, requests)
    }
    @Test fun failedForcedOAuthRefreshNeverReturnsToRejectedCredentialsOrPostsAgain() {
        val auth = CodexAuth("00000000-0000-0000-0000-000000000001")
        val config = ModelSettings(CodexOAuthProtocol.BASE_URL, "gpt-fixture", "", false, responsesPath = "/responses", oauth = auth)
        for (reason in listOf(CodexOAuthFailureReason.NETWORK, CodexOAuthFailureReason.TIMEOUT,
            CodexOAuthFailureReason.SERVER, CodexOAuthFailureReason.RATE_LIMITED, CodexOAuthFailureReason.AUTHORIZATION_EXPIRED)) {
            var requests = 0
            var refreshes = 0
            var ordinary = 0
            val raw = ResponsesModel(config, { url -> requests++; Connection(url, "", 401) }, null,
                oauthHeaders = { ordinary++; mapOf("Authorization" to "Bearer rejected") },
                oauthRefresh = { _, _ -> refreshes++; throw CodexOAuthFailure(reason, "refresh") })
            assertEquals(reason.name, RunStatus.FAILED, run(config, raw, ReconnectDurationPreset.WINDOW_30S).status)
            assertEquals(reason.name, 1, requests)
            assertEquals(reason.name, 1, ordinary)
            assertEquals(reason.name, 1, refreshes)
        }
    }
    @Test fun oauth401RefreshesOnceAndKeepsOriginalEndpointAndRequest() {
        val auth = CodexAuth("00000000-0000-0000-0000-000000000001")
        val config = ModelSettings(CodexOAuthProtocol.BASE_URL, "gpt-fixture", "", false, responsesPath = "/responses", oauth = auth)
        for (secondStatus in listOf(200, 401)) {
            val connections = mutableListOf<Connection>(); var refreshes = 0
            val raw = ResponsesModel(config, { url -> Connection(url, response("fresh", false),
                if (connections.isEmpty()) 401 else secondStatus).also(connections::add) }, null,
                oauthHeaders = { mapOf("Authorization" to "Bearer old") },
                oauthRefresh = { frozen, rejected -> assertEquals(auth, frozen); assertEquals("Bearer old", rejected)
                    refreshes++; mapOf("Authorization" to "Bearer rotated") })
            val result = run(config, raw, ReconnectDurationPreset.WINDOW_30S)
            assertEquals(if (secondStatus == 200) RunStatus.COMPLETED else RunStatus.FAILED, result.status)
            assertEquals(2, connections.size); assertEquals(1, refreshes)
            assertEquals("Bearer rotated", connections[1].getRequestProperty("Authorization"))
            assertEquals(connections[0].url, connections[1].url)
            assertEquals(connections[0].url, connections[1].url)
            assertEquals(connections[0].sent.toString("UTF-8"), connections[1].sent.toString("UTF-8"))
        }
    }
}
