package app.daiyu.android.model

import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.*
import app.daiyu.android.model.reconnect.*
import app.daiyu.android.storage.ModelSettings
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.URL
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

/** In-memory real Responses transport/decoder + Runner. Never connects to a provider. */
class ResponsesBackgroundResumeTest {
    private val settings = ModelSettings("https://api.openai.com/v1/responses", "fixture", "synthetic", false)
    private val preference = ReconnectSettings(ReconnectDurationPreset.CONTINUOUS, true)
    private fun frozen(config: ModelSettings = settings) = FrozenReconnectAdapter.freeze(preference, ProviderSnapshot.capture(config))
    private fun wire(type: String, sequence: Int, body: JSONObject = JSONObject()) =
        "data: ${body.put("type", type).put("sequence_number", sequence)}\n\n"
    private fun created(id: String = "resp_fixture") = wire("response.created", 0,
        JSONObject().put("response", JSONObject().put("id", id).put("status", "in_progress")))
    private fun delta(sequence: Int, value: String) = wire("response.output_text.delta", sequence,
        JSONObject().put("item_id", "m").put("content_index", 0).put("delta", value))
    private fun message(text: String) = JSONObject().put("type", "message").put("id", "m")
        .put("role", "assistant").put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", text)))
    private fun completed(sequence: Int, output: JSONArray = JSONArray().put(message("ab")), id: String = "resp_fixture") =
        wire("response.completed", sequence, JSONObject().put("response", JSONObject().put("id", id)
            .put("status", "completed").put("output", output)
            .put("usage", JSONObject().put("input_tokens", 10).put("output_tokens", 2))))
    private class Connection(url: URL, events: String, private val code: Int = 200) : HttpsURLConnection(url) {
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
        override fun getResponseCode() = code
        override fun getContentType() = "text/event-stream"
    }
    private class Runtime : AtomicRuntimeStore {
        val writes = mutableListOf<RunCheckpoint>()
        var failAt: Long? = null
        override fun commitCheckpoint(checkpoint: RunCheckpoint) {
            if (checkpoint.transport?.sequence == failAt && failAt != null) error("commit unknown")
            writes.add(checkpoint)
        }
        override fun reconcileCheckpoint(checkpoint: RunCheckpoint) = error("no recovery")
    }
    private fun initial() = Message(MessageId("a"), ConversationId("c"), RunId("r"), "assistant", emptyList(), RunStatus.RUNNING)
    private fun model(scripts: List<Pair<String,Int>>, connections: MutableList<Connection>): ResponsesModel =
        ResponsesModel(settings) { url ->
            val script = scripts[connections.size]
            Connection(url, script.first, script.second).also(connections::add)
        }.withBackgroundResume(requireNotNull(frozen().responsesBackground))
    private fun run(port: ModelPort, runtime: Runtime, tool: BatteryTool = BatteryTool { "ok" }): Message =
        ChatRunner(port, object : ConversationStore { override fun save(message: Message) = error("must commit atomically") },
            tool, RunnerOptions(reconnect = frozen().runnerPolicy(), runtimeStore = runtime)) {}.run(initial(), emptyList())

    @Test fun onePostThenSameIdGetAndAtomicTerminalTail() {
        val connections = mutableListOf<Connection>()
        val port = model(listOf((created() + delta(1,"a")) to 200,
            (delta(2,"b") + completed(3)) to 200), connections)
        val guarded = ModelPort { guardModelSession(port.open(it)) {} }
        val runtime = Runtime()
        val result = run(frozen().observeFailures(guarded) { fail(it) }, runtime)
        assertEquals(RunStatus.COMPLETED, result.status)
        assertEquals(listOf("POST","GET"), connections.map { it.requestMethod })
        assertEquals("https://api.openai.com/v1/responses/resp_fixture?stream=true&starting_after=1", connections[1].url.toString())
        assertEquals(0, connections[1].sent.size())
        val request = JSONObject(connections[0].sent.toString("UTF-8"))
        assertTrue(request.getBoolean("background")); assertTrue(request.getBoolean("stream")); assertFalse(request.getBoolean("store"))
        val terminal = runtime.writes.first { it.transport?.sequence == 3L }
        assertEquals(1, terminal.usageRequests.size)
        assertEquals(RequestOperation.RESUME, terminal.request!!.operation)
        assertEquals(10L, terminal.usage.single().snapshot.inputTokens)
        assertEquals("ab", (terminal.message.blocks.single() as MessageBlock.Text).text)
        assertEquals(1, terminal.message.protocolItems.size)
        assertEquals(3L, JSONObject(terminal.transport!!.decoderState).getLong("sequence"))
    }

    @Test fun noHandleAndExpiredGetNeverFallbackToPost() {
        for (scripts in listOf(listOf("" to 200), listOf(created() to 200, "" to 404), listOf(created() to 200, "" to 410))) {
            val connections = mutableListOf<Connection>()
            assertEquals(RunStatus.FAILED, run(model(scripts, connections), Runtime()).status)
            assertEquals(1, connections.count { it.requestMethod == "POST" })
            assertEquals(scripts.size, connections.size)
        }
    }

    @Test fun commitUnknownDoesNotAckOrIssueGet() {
        val connections = mutableListOf<Connection>()
        val runtime = Runtime().apply { failAt = 1L }
        run(model(listOf((created() + delta(1,"a") + completed(2)) to 200), connections), runtime)
        assertEquals(1, connections.size)
        assertTrue(runtime.writes.none { it.transport?.sequence == 1L || it.transport?.sequence == 2L })
    }

    @Test fun completedTailSinkFailureDoesNotAckCompleted() {
        val connections = mutableListOf<Connection>()
        val session = model(listOf((created() + completed(1)) to 200), connections).open(emptyList()) as CheckpointedResumableModelSession
        val acknowledgements = mutableListOf<Long>()
        try {
            session.nextAcknowledged(emptyList()) { batch ->
                if (batch.events.any { it is ModelEvent.ProtocolOutput }) {
                    assertTrue(batch.events.any { it is ModelEvent.Usage })
                    error("sink failure at tail")
                }
                acknowledgements.add(batch.checkpoint.sequence); ModelDeliveryAck.ACCEPTED
            }
            fail("must fail")
        } catch (_: IllegalStateException) {}
        assertEquals(listOf(0L), acknowledgements)
        assertFalse(session.canResume)
        assertEquals(1, connections.size)
    }

    @Test fun toolReadyBeforeDisconnectExecutesOnlyAfterClosureAndOnlyOnce() {
        val call = JSONObject().put("type","function_call").put("id","fc").put("call_id","call")
            .put("name","lookup").put("arguments","{}").put("status","completed")
        val connections = mutableListOf<Connection>()
        val scripts = listOf((created() + wire("response.output_item.done",1,JSONObject().put("item",call))) to 200,
            completed(2,JSONArray().put(call)) to 200,
            (created("resp_second") + completed(1,JSONArray(),"resp_second")) to 200)
        var executions = 0
        val runtime = Runtime()
        val result = run(model(scripts,connections),runtime,BatteryTool { executions++; "done" })
        assertEquals(RunStatus.COMPLETED,result.status)
        assertEquals(1,executions)
        assertEquals(listOf("POST","GET","POST"),connections.map { it.requestMethod })
        assertEquals(2,runtime.writes.last().usageRequests.size) // GET is an attempt, not a paid generation.
    }

    @Test fun rejectedWireSequenceRestoresDecoderAndSeenIsNotAcknowledged() {
        val state = ResponsesStream.State()
        try {
            state.read((created() + delta(1,"a")).reader().buffered(), { false }, prefix = emptyList(),
                acknowledgedSink = { if (it.checkpoint.sequence == 1L) ModelDeliveryAck.REJECTED else ModelDeliveryAck.ACCEPTED })
            fail("expected explicit rejection")
        } catch (_: ModelDeliveryRejectedException) {}
        assertEquals(0L,state.acknowledgedSequence); assertTrue(state.canResume)
        val events = mutableListOf<ModelEvent>()
        state.read((delta(1,"a") + delta(2,"b") + completed(3)).reader().buffered(), { false },
            prefix = emptyList(), acknowledgedSink = { events.addAll(it.events); ModelDeliveryAck.ACCEPTED })
        assertEquals(3L,state.acknowledgedSequence)
        assertEquals("ab",events.filterIsInstance<ModelEvent.TextFinal>().last().text)
    }

    @Test fun optInIsOffByDefaultAndExactOfficialEndpointOnly() {
        assertFalse(ReconnectSettings().responsesBackgroundEnabled)
        assertNull(FrozenResponsesBackground.freeze(ReconnectSettings(),ProviderSnapshot.capture(settings)))
        for (endpoint in listOf("https://gateway.invalid/v1/responses","https://api.openai.com:443/v1/responses","https://api.openai.com/v1/other")) {
            assertNull(frozen(settings.copy(endpoint = endpoint)).responsesBackground)
        }
        assertNotNull(frozen().responsesBackground)
        assertEquals(preference,ReconnectSettingsCodec.decode(ReconnectSettingsCodec.encode(preference)))
        assertFalse(ReconnectSettingsCodec.decode("v1:continuous").responsesBackgroundEnabled)
        for (protocol in listOf(ProviderProtocol.RESPONSES,ProviderProtocol.MESSAGES,ProviderProtocol.CHAT_COMPLETIONS,ProviderProtocol.GEMINI)) {
            assertFalse(ReconnectCapabilities.forProtocol(protocol).supported)
            assertEquals(0,FrozenReconnectAdapter.freeze(preference,protocol).runnerPolicy().maxAttempts)
            assertNull(FrozenReconnectAdapter.freeze(preference,protocol).runnerPolicy().budgetFactory)
        }
    }
}
