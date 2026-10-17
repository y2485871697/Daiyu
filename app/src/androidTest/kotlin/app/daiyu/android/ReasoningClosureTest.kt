package app.daiyu.android

import android.test.InstrumentationTestCase
import app.daiyu.android.delegation.ReasoningEffort
import app.daiyu.android.domain.*
import app.daiyu.android.model.*
import app.daiyu.android.storage.ConversationDatabase
import app.daiyu.android.storage.ModelSettings
import app.daiyu.android.ui.model.ConversationReasoningPreference
import app.daiyu.android.ui.screens.chat.conversation.ChatTurnMapper
import app.daiyu.android.ui.screens.chat.conversation.ChatTurnUi
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URL
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

/** Entirely synthetic transport. No external URL is opened and no credential is used. */
@Suppress("DEPRECATION")
class ReasoningClosureTest : InstrumentationTestCase() {
    private class Connection(body: String) : HttpsURLConnection(URL("https://example.invalid")) {
        val posted = ByteArrayOutputStream()
        private val response = body.byteInputStream()
        override fun connect() {}
        override fun disconnect() { response.close() }
        override fun usingProxy() = false
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getOutputStream() = posted
        override fun getInputStream() = response
        override fun getResponseCode() = 200
        override fun getContentType() = "text/event-stream"
        fun json() = JSONObject(posted.toString("UTF-8"))
    }
    private fun event(type: String, vararg fields: Pair<String, Any>) = JSONObject().put("type", type).also { out ->
        fields.forEach { out.put(it.first, it.second) }
    }
    private fun frames(events: List<JSONObject>) = events.joinToString("") { "data: $it\n\n" }
    private fun messages(tool: Boolean = false, terminal: Boolean = true, includeAnswer: Boolean = true): String {
        val result = mutableListOf(
            event("message_start", "message" to JSONObject().put("id", if (tool) "first" else "last").put("role", "assistant")
                .put("content", JSONArray()).put("usage", JSONObject().put("input_tokens", 7))),
            event("content_block_start", "index" to 0, "content_block" to event("thinking", "thinking" to "")),
            event("content_block_delta", "index" to 0, "delta" to event("thinking_delta", "thinking" to "reported native text")),
            event("content_block_delta", "index" to 0, "delta" to event("signature_delta", "signature" to "opaque-")),
            event("content_block_delta", "index" to 0, "delta" to event("signature_delta", "signature" to "signature")),
            event("content_block_stop", "index" to 0),
            event("content_block_start", "index" to 1, "content_block" to event("redacted_thinking", "data" to "opaque-redaction")),
            event("content_block_stop", "index" to 1),
        )
        result += if (tool) listOf(
            event("content_block_start", "index" to 2, "content_block" to event("tool_use", "id" to "call", "name" to "get_battery_status", "input" to JSONObject())),
            event("content_block_stop", "index" to 2),
        ) else if (includeAnswer) listOf(
            event("content_block_start", "index" to 2, "content_block" to event("text", "text" to "answer")),
            event("content_block_stop", "index" to 2),
        ) else emptyList()
        if (terminal) result += listOf(
            event("message_delta", "delta" to JSONObject().put("stop_reason", if (tool) "tool_use" else "end_turn"),
                "usage" to JSONObject().put("output_tokens", 9)), event("message_stop"))
        return frames(result)
    }
    private fun responses(terminal: Boolean = true): String {
        val summary = event("reasoning", "id" to "summary", "encrypted_content" to "opaque-encrypted",
            "summary" to JSONArray().put(event("summary_text", "text" to "reported summary")))
        val body = event("message", "id" to "body", "role" to "assistant", "status" to "completed",
            "content" to JSONArray().put(event("output_text", "text" to "answer")))
        val events = mutableListOf(event("response.reasoning_summary_text.delta", "item_id" to "summary", "summary_index" to 0,
            "delta" to "reported summary"))
        if (terminal) events += event("response.completed", "response" to JSONObject().put("status", "completed")
            .put("output", JSONArray().put(summary).put(body)).put("usage", JSONObject().put("input_tokens", 7).put("output_tokens", 9)))
        return frames(events)
    }
    private fun settings(protocol: ProviderProtocol) = ModelSettings("https://example.invalid", "synthetic", "synthetic-key", true,
        protocol = protocol)
    private fun preference(context: android.content.Context) = ConversationReasoningPreference(context.getSharedPreferences("conversation-ui", 0))

    fun testResponsesGatewayCannotInjectReasoningAfterValidation() {
        for (field in listOf("thinking", "reasoning_effort", "output_config")) {
            var connects = 0
            val config = settings(ProviderProtocol.RESPONSES).copy(requestOptions = ProviderRequestOptions(
                sessionGateway = app.daiyu.android.model.sessiongateway.SessionGatewayRule(
                    modelPattern = "^synthetic$", pathPattern = "/responses", keyField = field)))
            val model = ResponsesModel(config, { connects++; Connection(responses()) }, null,
                sessionContext = app.daiyu.android.model.sessiongateway.SessionGatewayContext(ConversationId("conversation")))
            val user = Message(MessageId("u"), ConversationId("c"), RunId("r"), "user",
                listOf(MessageBlock.Text("u", "question")), RunStatus.COMPLETED)
            try { model.open(listOf(user)).next(emptyList()) {}; fail("gateway must reject unsupported reasoning") }
            catch (_: IllegalArgumentException) {}
            assertEquals(0, connects)
        }
    }

    fun testActualPreferenceRequestStreamDatabaseAndDisplayForBothProtocols() {
        for (protocol in listOf(ProviderProtocol.RESPONSES, ProviderProtocol.MESSAGES)) {
            val context = testContext(instrumentation.targetContext, "reasoning-${protocol.wireName}-")
            context.deleteDatabase("conversations.db")
            context.getSharedPreferences("conversation-ui", 0).edit().clear().commit()
            var db = ConversationDatabase(context)
            try {
                val conversation = db.create("default")
                preference(context).set(conversation.id.value, ReasoningEffort.LOW)
                val frozen = ReasoningRequestPolicy.freeze(ProviderSnapshot.capture(settings(protocol)), preference(context).snapshot(conversation.id.value))
                // A real persisted edit after freeze is NOT consulted by this request or its tool continuation.
                preference(context).set(conversation.id.value, ReasoningEffort.HIGH)
                val first = Connection(if (protocol == ProviderProtocol.RESPONSES) responses() else messages(tool = true))
                val second = Connection(messages())
                var opened = 0
                val model: ModelPort = if (protocol == ProviderProtocol.RESPONSES)
                    ResponsesModel(frozen.asModelSettings()) { opened++; first }
                else MessagesModel(frozen.asModelSettings()) { if (opened++ == 0) first else second }
                val user = Message(MessageId("u"), conversation.id, RunId("r"), "user", listOf(MessageBlock.Text("u", "question")), RunStatus.COMPLETED)
                val assistant = user.copy(id = MessageId("a"), role = "assistant", blocks = emptyList(), status = RunStatus.RUNNING)
                db.send(user, assistant)
                var tools = 0
                val result = ChatRunner(model, db, BatteryTool { tools++; "tool-result" }) {}.run(assistant, listOf(user))
                assertEquals(RunStatus.COMPLETED, result.status)
                val request = first.json()
                if (protocol == ProviderProtocol.RESPONSES) {
                    assertEquals("low", request.getJSONObject("reasoning").getString("effort"))
                    assertEquals("auto", request.getJSONObject("reasoning").getString("summary"))
                    assertEquals(1, opened); assertEquals(0, tools)
                } else {
                    assertEquals(2048, request.getJSONObject("thinking").getInt("budget_tokens"))
                    assertEquals(6144, request.getInt("max_tokens"))
                    assertEquals(request.getJSONObject("thinking").toString(), second.json().getJSONObject("thinking").toString())
                    assertEquals(2, opened); assertEquals(1, tools)
                    val replay = second.json().getJSONArray("messages").getJSONObject(1).getJSONArray("content")
                    assertEquals("opaque-signature", replay.getJSONObject(0).getString("signature"))
                    assertEquals("opaque-redaction", replay.getJSONObject(1).getString("data"))
                }
                val reported = result.historySegments.flatMap { it.reportedReasoning }
                assertTrue(reported.isNotEmpty())
                assertFalse(reported.any { it.text.contains("opaque") })
                val initialTurns = ChatTurnMapper.map(listOf(result)).filterIsInstance<ChatTurnUi.Assistant>()
                assertEquals("answer", initialTurns.last().finalText)
                assertEquals(reported, initialTurns.flatMap { it.reportedReasoning })
                if (protocol == ProviderProtocol.MESSAGES) assertNotNull(initialTurns.first().workProcess)
                db.close(); db = ConversationDatabase(context); db.recover()
                val restored = db.messages(conversation.id).last().second
                assertEquals(result, restored)
                assertEquals(initialTurns, ChatTurnMapper.map(listOf(restored)))
                if (protocol == ProviderProtocol.MESSAGES) {
                    val replay = MessagesRequest.history(listOf(user, restored))
                    val blocks = (0 until replay.length()).flatMap { index ->
                        val content = replay.getJSONObject(index).getJSONArray("content")
                        (0 until content.length()).map { content.getJSONObject(it) }
                    }
                    assertEquals(2, blocks.count { it.getString("type") == "thinking" && it.getString("signature") == "opaque-signature" })
                    assertEquals(1, blocks.count { it.getString("type") == "tool_result" && it.getString("content") == "tool-result" })
                    assertEquals(1, tools) // Encoding restored history is not tool execution.
                }
                assertEquals(ReasoningEffort.HIGH, preference(context).snapshot(conversation.id.value))
                assertEquals(if (protocol == ProviderProtocol.RESPONSES) 1 else 2, opened) // Restore is read-only.
            } finally { db.close(); context.deleteDatabase("conversations.db") }
        }
    }

    fun testTruncatedAndCancelledStreamsPersistOnlyReportedPartialTextAndNeverReplay() {
        for (protocol in listOf(ProviderProtocol.RESPONSES, ProviderProtocol.MESSAGES)) for (cancel in listOf(false, true)) {
            // Both partial fixtures stop after public reasoning, before any answer is delivered.
            // Messages text is delivered immediately, not gated on the terminal frame.
            val connection = Connection(if (protocol == ProviderProtocol.RESPONSES) responses(terminal = false)
                else messages(terminal = false, includeAnswer = false))
            var opened = 0
            lateinit var session: ModelSession
            val delegate: ModelPort = if (protocol == ProviderProtocol.RESPONSES) ResponsesModel(settings(protocol)) { opened++; connection }
                else MessagesModel(settings(protocol)) { opened++; connection }
            val wrapped = ModelPort { history ->
                session = delegate.open(history)
                object : ModelSession {
                    override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) = session.next(outputs) {
                        sink(it)
                        if (cancel && (it is ModelEvent.ReasoningDelta || it is ModelEvent.ReasoningFinal)) session.cancel()
                    }
                    override fun cancel() = session.cancel()
                }
            }
            val context = testContext(instrumentation.targetContext, "reasoning-partial-${protocol.wireName}-$cancel-")
            context.deleteDatabase("conversations.db")
            var db = ConversationDatabase(context)
            try {
                val conversation = db.create("default")
                val initial = Message(MessageId("a"), conversation.id, RunId("r"), "assistant", emptyList(), RunStatus.RUNNING)
                val user = initial.copy(id = MessageId("u"), role = "user", status = RunStatus.COMPLETED,
                    blocks = listOf(MessageBlock.Text("u", "question")))
                db.send(user, initial)
                var toolCalls = 0
                val result = ChatRunner(wrapped, db, BatteryTool { toolCalls++; "unused" }) {}.run(initial, listOf(user))
                assertTrue(result.status != RunStatus.COMPLETED)
                assertEquals(1, opened); assertEquals(0, toolCalls)
                assertTrue(result.protocolItems.isEmpty())
                assertTrue(result.historySegments.flatMap { it.reportedReasoning }.isNotEmpty())
                db.close(); db = ConversationDatabase(context); db.recover()
                val restored = db.messages(conversation.id).last().second
                assertEquals(result, restored)
                val turns = ChatTurnMapper.map(listOf(restored)).filterIsInstance<ChatTurnUi.Assistant>()
                assertTrue(turns.flatMap { it.reportedReasoning }.isNotEmpty())
                assertTrue(turns.all { it.finalText.isEmpty() && it.workProcess == null })
                try { session.next(emptyList()) {}; fail("failed/cancelled session reposted") } catch (_: Exception) {}
                assertEquals(1, opened)
            } finally { db.close(); context.deleteDatabase("conversations.db") }
        }
    }

    fun testReasoningFreezeDoesNotRewriteSavedOptionsOrGptSpeedAndRejectsConflicts() {
        val original = settings(ProviderProtocol.RESPONSES).copy(model = "gpt-fixture",
            requestOptions = ProviderRequestOptions(customBody = "{\"temperature\":0.7}", speedMode = GptSpeedMode.FAST))
        val snapshot = ProviderSnapshot.capture(original)
        val frozen = ReasoningRequestPolicy.freeze(snapshot, ReasoningEffort.LOW)
        assertEquals(original, snapshot.asModelSettings())
        assertEquals("{\"temperature\":0.7}", original.requestOptions.customBody)
        assertEquals(GptSpeedMode.FAST, frozen.requestOptions.speedMode)
        assertEquals(original.requestOptions.sessionGateway, frozen.requestOptions.sessionGateway)
        assertEquals(0.7, JSONObject(frozen.requestOptions.customBody).getDouble("temperature"), 0.0)
        assertEquals("low", JSONObject(frozen.requestOptions.customBody).getJSONObject("reasoning").getString("effort"))
        for (protocol in listOf(ProviderProtocol.RESPONSES, ProviderProtocol.MESSAGES)) {
            val explicit = settings(protocol).copy(requestOptions = ProviderRequestOptions(customBody = if (protocol == ProviderProtocol.RESPONSES)
                "{\"reasoning\":{\"effort\":\"low\"}}" else "{\"thinking\":{\"type\":\"disabled\"}}"))
            try { ReasoningRequestPolicy.freeze(ProviderSnapshot.capture(explicit), ReasoningEffort.HIGH); fail("custom conflict ignored") }
            catch (failure: IllegalArgumentException) { assertTrue(checkNotNull(failure.message).contains("冲突")) }
            assertEquals(explicit.requestOptions.customBody, ReasoningRequestPolicy.freeze(ProviderSnapshot.capture(explicit), ReasoningEffort.DEFAULT)
                .requestOptions.customBody)
        }
    }

    fun testNativeThinkingRequiresSignatureAndNeverLeaksOpaqueFieldsIntoDisplay() {
        val unsigned = messages(tool = true).split("\n\n").filterNot { it.contains("signature_delta") }.joinToString("\n\n")
        val events = mutableListOf<ModelEvent>()
        try {
            MessagesStream.read(unsigned.reader().buffered(), { false }, events::add)
            fail("unsigned thinking was confirmed")
        } catch (failure: ModelRequestException) { assertEquals(ModelFailure.PROTOCOL, failure.failure) }
        assertTrue(events.filterIsInstance<ModelEvent.ReasoningDelta>().isNotEmpty())
        assertTrue(events.none { it is ModelEvent.ToolReady || it is ModelEvent.ProtocolOutput || it is ModelEvent.Usage })
        val redacted = frames(listOf(
            event("message_start", "message" to JSONObject().put("id", "redacted").put("role", "assistant").put("content", JSONArray())),
            event("content_block_start", "index" to 0, "content_block" to event("redacted_thinking", "data" to "opaque-only")),
            event("content_block_stop", "index" to 0),
            event("message_delta", "delta" to JSONObject().put("stop_reason", "end_turn")), event("message_stop")))
        val onlyOpaque = mutableListOf<ModelEvent>()
        val saved = MessagesStream.read(redacted.reader().buffered(), { false }, onlyOpaque::add)
        assertEquals("opaque-only", JSONObject(saved).getJSONArray("content").getJSONObject(0).getString("data"))
        assertTrue(onlyOpaque.none { it is ModelEvent.ReasoningDelta || it is ModelEvent.ReasoningFinal || it is ModelEvent.TextDelta || it is ModelEvent.TextFinal })
    }

    fun testInvalidAndUnsupportedPoliciesFailBeforeConnection() {
        val cases = listOf(
            ProviderProtocol.MESSAGES to "{\"thinking\":{\"type\":\"adaptive\"}}",
            ProviderProtocol.MESSAGES to "{\"thinking\":{\"type\":\"enabled\",\"budget_tokens\":4096},\"max_tokens\":4096}",
            ProviderProtocol.MESSAGES to "{\"thinking\":{\"type\":\"enabled\",\"budget_tokens\":1024},\"temperature\":0.5}",
            ProviderProtocol.RESPONSES to "{\"reasoning\":{\"effort\":\"max\"}}",
            ProviderProtocol.RESPONSES to "{\"thinking\":{\"type\":\"enabled\",\"budget_tokens\":1024}}",
        )
        for ((protocol, body) in cases) {
            var opened = 0
            val config = settings(protocol).copy(requestOptions = ProviderRequestOptions(customBody = body))
            val model: ModelPort = if (protocol == ProviderProtocol.RESPONSES) ResponsesModel(config) { opened++; Connection(responses()) }
                else MessagesModel(config) { opened++; Connection(messages()) }
            val session = model.open(emptyList())
            try { session.next(emptyList()) {}; fail("unsupported reasoning silently ignored") } catch (_: IllegalArgumentException) {}
            assertEquals(0, opened)
            try { session.next(emptyList()) {}; fail("failed request reposted") } catch (_: IllegalStateException) {}
            assertEquals(0, opened)
        }
    }
}
