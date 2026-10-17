package app.daiyu.android.model

import app.daiyu.android.assistants.AssistantProfile
import app.daiyu.android.assistants.AssistantRunContext
import app.daiyu.android.assistants.AssistantStore
import app.daiyu.android.assistants.capture
import app.daiyu.android.domain.*
import app.daiyu.android.storage.ModelSettings
import java.io.ByteArrayOutputStream
import java.net.URL
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AssistantModelFactoryTest {
    private fun context(): AssistantRunContext {
        val profile = AssistantProfile("bound", "固定助手", "固定人格", enabledSkillIds = listOf("not-a-tool-grant"))
        val store = object : AssistantStore {
            override val assistants = MutableStateFlow(listOf(profile))
            override val selectedId = MutableStateFlow("some-other-assistant")
            override fun save(profile: AssistantProfile): AssistantProfile = error("unused")
            override fun delete(id: String) = error("unused")
            override fun select(id: String) = error("unused")
        }
        return store.capture(profile.id, "最终记忆")
    }

    private fun settings(protocol: ProviderProtocol, options: ProviderRequestOptions = ProviderRequestOptions()) =
        ModelSettings("https://example.invalid/v1", "fixture-model", "synthetic-test-key", false,
            protocol = protocol, requestOptions = options)

    private fun history() = listOf(Message(MessageId("u"), ConversationId("c"), RunId("r"), "user",
        listOf(MessageBlock.Text("t", "真实用户问题")), RunStatus.COMPLETED))

    @Test fun factorySelectsProtocolWithoutGrantingTools() {
        val assistant = context()
        val responses = ProviderSnapshot.capture(settings(ProviderProtocol.RESPONSES))
        val messages = ProviderSnapshot.capture(settings(ProviderProtocol.MESSAGES))
        assertTrue(AssistantModelFactory.create(responses, assistant) is ResponsesModel)
        assertTrue(AssistantModelFactory.create(messages, assistant) is MessagesModel)
        val session = app.daiyu.android.model.sessiongateway.SessionGatewayContext(ConversationId("resolved"))
        assertTrue(AssistantModelFactory.create(responses, assistant, session) is ResponsesModel)
        assertTrue(AssistantModelFactory.create(messages, assistant, session) is MessagesModel)
        assertTrue(ModelFactory.create(responses, session) is ResponsesModel)
        assertTrue(ModelFactory.create(messages, session) is MessagesModel)
        assertTrue(ModelFactory.create(responses.asModelSettings(), session) is ResponsesModel)
        assertTrue(ModelFactory.create(messages.asModelSettings(), session) is MessagesModel)
        assertTrue(responses.tools.isEmpty())
        assertTrue(messages.tools.isEmpty())
        assertEquals(listOf("not-a-tool-grant"), assistant.enabledSkillIds)
    }

    @Test fun responsesTransportUsesNativeInstructionsAndPreservesOptionsAndUserHistory() {
        val assistant = context()
        val config = settings(ProviderProtocol.RESPONSES, ProviderRequestOptions(
            customBody = """{"instructions":"必须保留的既有规则","model":"custom-model","metadata":{"trace":"kept"},"temperature":0.25}"""))
        val connection = Connection()
        val model = ResponsesModel(config, { connection }, null, assistant.systemPrompt)
        send(model.open(history()))
        val request = connection.json()
        assertEquals("必须保留的既有规则\n\n" + assistant.systemPrompt, request.getString("instructions"))
        assertFalse(request.has("system"))
        assertEquals("custom-model", request.getString("model"))
        assertEquals("kept", request.getJSONObject("metadata").getString("trace"))
        assertEquals(0.25, request.getDouble("temperature"), 0.0)
        assertTrue(request.getBoolean("stream"))
        assertFalse(request.getBoolean("store"))
        assertEquals("reasoning.encrypted_content", request.getJSONArray("include").getString(0))
        val input = request.getJSONArray("input")
        assertEquals(1, input.length())
        assertEquals("user", input.getJSONObject(0).getString("role"))
        assertEquals("真实用户问题", input.getJSONObject(0).getString("content"))
        assertFalse(request.has("tools"))
    }

    @Test fun messagesTransportUsesNativeSystemAndPreservesOptionsAndUserHistory() {
        val assistant = context()
        val config = settings(ProviderProtocol.MESSAGES, ProviderRequestOptions(
            customBody = """{"system":"必须保留的既有规则","model":"custom-model","max_tokens":123,"metadata":{"trace":"kept"}}"""))
        val connection = Connection()
        val model = MessagesModel(config, 4096, { connection }, null, assistant.systemPrompt)
        send(model.open(history()))
        val request = connection.json()
        assertEquals("必须保留的既有规则\n\n" + assistant.systemPrompt, request.getString("system"))
        assertFalse(request.has("instructions"))
        assertEquals("custom-model", request.getString("model"))
        assertEquals(123, request.getInt("max_tokens"))
        assertEquals("kept", request.getJSONObject("metadata").getString("trace"))
        assertTrue(request.getBoolean("stream"))
        val messages = request.getJSONArray("messages")
        assertEquals(1, messages.length())
        assertEquals("user", messages.getJSONObject(0).getString("role"))
        assertEquals("真实用户问题", messages.getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("text"))
        assertFalse(request.has("tools"))
    }

    @Test fun messagesStructuredSystemRetainsBlocksAndCachePolicy() {
        val original = JSONArray().put(JSONObject().put("type", "text").put("text", "existing")
            .put("cache_control", JSONObject().put("type", "ephemeral")))
        val originalBytes = original.toString()
        val body = MessagesRequest.body("model", JSONArray(), emptyList(), 99).put("system", original)
        val result = MessagesRequest.withAssistantInstructions(body, context().systemPrompt)
        val blocks = result.getJSONArray("system")
        assertEquals(2, blocks.length())
        assertEquals(originalBytes, JSONArray().put(blocks.getJSONObject(0)).toString())
        assertEquals(originalBytes, original.toString())
        assertEquals(context().systemPrompt, blocks.getJSONObject(1).getString("text"))
        assertEquals(99, result.getInt("max_tokens"))
    }

    @Test fun explicitToolDefinitionsAreNeitherAddedNorRemovedByAssistant() {
        val definitions = listOf(ToolDefinition("host_authorized", "allowed", """{"type":"object","properties":{}}"""))
        for (protocol in listOf(ProviderProtocol.RESPONSES, ProviderProtocol.MESSAGES)) {
            val connection = Connection()
            val config = settings(protocol)
            val model: ModelPort = when (protocol) {
                ProviderProtocol.RESPONSES -> ResponsesModel(config, { connection }, null, context().systemPrompt)
                else -> MessagesModel(config, 4096, { connection }, null, context().systemPrompt)
            }
            send(model.open(history(), definitions))
            val tools = connection.json().getJSONArray("tools")
            assertEquals(1, tools.length())
            assertEquals("host_authorized", tools.getJSONObject(0).getString("name"))
        }
    }

    @Test fun oldConstructorsKeepExistingNativeInstructionsWithoutAssistantDefaults() {
        for (protocol in listOf(ProviderProtocol.RESPONSES, ProviderProtocol.MESSAGES)) {
            val key = if (protocol == ProviderProtocol.RESPONSES) "instructions" else "system"
            val connection = Connection()
            val config = settings(protocol, ProviderRequestOptions(customBody = """{"$key":"existing"}"""))
            val model: ModelPort = if (protocol == ProviderProtocol.RESPONSES) ResponsesModel(config) { connection }
                else MessagesModel(config) { connection }
            send(model.open(history()))
            assertEquals("existing", connection.json().getString(key))
        }
    }

    @Test fun absentAssistantKeepsOriginalRequestShape() {
        val responses = ResponsesRequest.body("model", JSONArray(), emptyList())
        assertSame(responses, ResponsesRequest.withAssistantInstructions(responses, null))
        assertFalse(responses.has("instructions"))
        val messages = MessagesRequest.body("model", JSONArray(), emptyList(), 4096)
        assertSame(messages, MessagesRequest.withAssistantInstructions(messages, null))
        assertFalse(messages.has("system"))
    }

    @Test fun invalidExistingSystemFieldsFailBeforeConnectingRatherThanBeingOverwritten() {
        for (protocol in listOf(ProviderProtocol.RESPONSES, ProviderProtocol.MESSAGES)) {
            val key = if (protocol == ProviderProtocol.RESPONSES) "instructions" else "system"
            val config = settings(protocol, ProviderRequestOptions(customBody = """{"$key":42}"""))
            var connected = false
            val connect: (URL) -> HttpsURLConnection = { connected = true; Connection() }
            val model: ModelPort = if (protocol == ProviderProtocol.RESPONSES)
                ResponsesModel(config, connect, null, context().systemPrompt)
            else MessagesModel(config, 4096, connect, null, context().systemPrompt)
            assertThrows(IllegalArgumentException::class.java) { model.open(history()).next(emptyList()) {} }
            assertFalse(connected)
        }
    }

    private fun send(session: ModelSession) {
        // Stop after the real serialization path; no network or successful response fixture is needed.
        val failure = assertThrows(ModelRequestException::class.java) { session.next(emptyList()) {} }
        assertEquals(ModelFailure.SERVER, failure.failure)
    }

    private class Connection : HttpsURLConnection(URL("https://example.invalid/v1")) {
        private val request = ByteArrayOutputStream()
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getOutputStream() = request
        override fun getResponseCode() = 500
        fun json() = JSONObject(request.toString("UTF-8"))
    }
}
