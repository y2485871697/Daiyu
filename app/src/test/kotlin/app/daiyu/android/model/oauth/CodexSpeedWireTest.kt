package app.daiyu.android.model.oauth

import app.daiyu.android.domain.*
import app.daiyu.android.model.*
import app.daiyu.android.storage.ModelSettings
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.URL
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

class CodexSpeedWireTest {
    private val auth = CodexAuth("00000000-0000-0000-0000-000000000001")
    private fun settings(mode: GptSpeedMode?) = ModelSettings(CodexOAuthProtocol.BASE_URL,
        "gpt-fixture", "", false, responsesPath = "/responses", oauth = auth, requestOptions = ProviderRequestOptions(
            customBody = """{"service_tier":"fast","reasoning":{"effort":"high"}}""", speedMode = mode))

    private class Connection(events: String, private val status: Int = 200) : HttpsURLConnection(URL("https://fixture.invalid")) {
        val sent = ByteArrayOutputStream()
        var closed = false
        private val received = events.byteInputStream()
        override fun connect() {}
        override fun disconnect() { closed = true; received.close() }
        override fun usingProxy() = false
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getOutputStream() = sent
        override fun getInputStream() = received
        override fun getResponseCode() = status
        override fun getContentType() = "text/event-stream"
        fun body() = JSONObject(sent.toString("UTF-8"))
    }

    private fun response(tool: Boolean = false): String {
        val items = JSONArray()
        var output = ""
        if (tool) {
            val call = JSONObject().put("type", "function_call").put("id", "fc-one")
                .put("call_id", "one").put("name", "fixture").put("arguments", "{}")
            items.put(call)
            output = "data: " + JSONObject().put("type", "response.output_item.done").put("item", call) + "\n\n"
        }
        return output + "data: " + JSONObject().put("type", "response.completed").put("response",
            JSONObject().put("status", "completed").put("output", items)) + "\n\n"
    }

    private fun model(config: ModelSettings, connect: (URL) -> HttpsURLConnection) = ResponsesModel(
        settings = config, connect = connect, imageInputSupported = null, oauthHeaders = { frozen ->
            assertEquals(auth, frozen)
            mapOf("Authorization" to "Bearer synthetic-oauth", "ChatGPT-Account-ID" to "synthetic-account")
        })

    @Test fun everyExplicitReasoningEffortStaysUnchangedAcrossOAuthToolRounds() {
        for (effort in app.daiyu.android.delegation.ReasoningEffort.textChoices()) {
            val first = Connection(response(tool = true)); val second = Connection(response())
            var calls = 0
            val selected = settings(GptSpeedMode.NORMAL).copy(model = "gpt-6.1-sol",
                requestOptions = ProviderRequestOptions(speedMode = GptSpeedMode.NORMAL))
            val frozen = ReasoningRequestPolicy.freeze(selected.requestSnapshot(), effort)
            val session = model(frozen.asModelSettings()) { if (calls++ == 0) first else second }
                .open(emptyList(), listOf(ToolDefinition("fixture", "fixture", """{"type":"object","properties":{}}""")))
            session.next(emptyList()) {}
            session.next(listOf(ToolOutput(ToolCallId("one"), "result"))) {}
            assertEquals(2, calls)
            for (conn in listOf(first, second)) {
                val reasoning = conn.body().getJSONObject("reasoning")
                assertEquals(if (effort == app.daiyu.android.delegation.ReasoningEffort.OFF) "none" else effort.wireValue,
                    reasoning.getString("effort"))
                if (effort == app.daiyu.android.delegation.ReasoningEffort.OFF) assertFalse(reasoning.has("summary"))
                assertTrue(conn.closed)
            }
        }
    }

    @Test fun oauthModesReachTheActualPostWithMatchingFinalRoutingHints() {
        for ((mode, tier) in listOf(GptSpeedMode.NORMAL to null, GptSpeedMode.FAST to "priority",
            GptSpeedMode.ULTRA_FAST to "ultrafast")) {
            val config = settings(mode)
            val original = config.requestSnapshot()
            val conn = Connection(response())
            var connections = 0
            model(config) { url ->
                assertEquals("https://chatgpt.com/backend-api/codex/responses", url.toString())
                connections++; conn
            }.open(emptyList()).next(emptyList()) {}
            assertEquals(1, connections)
            val body = conn.body()
            if (tier == null) assertFalse(body.has("service_tier")) else assertEquals(tier, body.getString("service_tier"))
            assertEquals("model=gpt-fixture" + if (tier == null) "" else ";tier=$tier",
                conn.getRequestProperty("x-codex-routing-hint"))
            assertEquals("Bearer synthetic-oauth", conn.getRequestProperty("Authorization"))
            assertEquals("synthetic-account", conn.getRequestProperty("ChatGPT-Account-ID"))
            assertEquals("POST", conn.requestMethod)
            assertEquals("application/json", conn.getRequestProperty("Content-Type"))
            assertFalse(conn.instanceFollowRedirects)
            assertEquals("high", body.getJSONObject("reasoning").getString("effort"))
            assertEquals(original, config.requestSnapshot())
            assertEquals(mode, ProviderRequestOptions.decode(config.requestOptions.encode()).speedMode)
            assertTrue(conn.closed)
        }
    }

    @Test fun oauthUltraFastStaysFrozenAcrossToolRounds() {
        val first = Connection(response(tool = true)); val second = Connection(response())
        var calls = 0
        val config = settings(GptSpeedMode.ULTRA_FAST)
        val session = model(config.requestSnapshot().asModelSettings()) { if (calls++ == 0) first else second }
            .open(emptyList(), listOf(ToolDefinition("fixture", "fixture", """{"type":"object","properties":{}}""")))
        session.next(emptyList()) {}
        session.next(listOf(ToolOutput(ToolCallId("one"), "result"))) {}
        assertEquals(2, calls)
        for (conn in listOf(first, second)) {
            assertEquals("ultrafast", conn.body().getString("service_tier"))
            assertEquals("model=gpt-fixture;tier=ultrafast", conn.getRequestProperty("x-codex-routing-hint"))
        }
        val input = second.body().getJSONArray("input")
        val results = (0 until input.length()).map { input.getJSONObject(it) }
            .filter { it.optString("type") == "function_call_output" }
        assertEquals(1, results.size)
        assertEquals("result", results.single().getString("output"))
    }

    @Test fun accountOrTierRejectionNeverRetriesOrDowngrades() {
        for (status in listOf(400, 403, 429)) {
            var calls = 0
            val conn = Connection("", status)
            val session = model(settings(GptSpeedMode.ULTRA_FAST)) { calls++; conn }.open(emptyList())
            assertThrows(ModelRequestException::class.java) { session.next(emptyList()) {} }
            assertEquals(1, calls)
            assertEquals("ultrafast", conn.body().getString("service_tier"))
            assertTrue(conn.closed)
            assertThrows(IllegalStateException::class.java) { session.next(emptyList()) {} }
            assertEquals(1, calls)
        }
    }

    @Test fun gatewayKeepsItsTiersAndDoesNotReceiveOAuthRoutingHeaders() {
        for ((mode, tier) in listOf(GptSpeedMode.NORMAL to "default", GptSpeedMode.FAST to "fast",
            GptSpeedMode.ULTRA_FAST to "ultrafast")) {
            val config = settings(mode).copy(endpoint = "https://relay.invalid/v1/responses", oauth = null, apiKey = "synthetic-key")
            val conn = Connection(response())
            ResponsesModel(config) { conn }.open(emptyList()).next(emptyList()) {}
            assertEquals(tier, conn.body().getString("service_tier"))
            assertNull(conn.getRequestProperty("x-codex-routing-hint"))
            assertEquals("Bearer synthetic-key", conn.getRequestProperty("Authorization"))
        }
    }
}
