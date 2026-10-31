package app.daiyu.android.model

import app.daiyu.android.domain.*
import app.daiyu.android.storage.ModelSettings
import app.daiyu.android.title.TitleModelCoordinator
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.URL
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

class DaiyuPromptCacheWireTest {
    private class Connection : HttpsURLConnection(URL("https://fixture.invalid/v1")) {
        val sent = ByteArrayOutputStream()
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getOutputStream() = sent
        override fun getResponseCode() = 500
        override fun getErrorStream() = "{\"error\":{\"message\":\"offline fixture\"}}".byteInputStream()
        fun body() = JSONObject(sent.toString("UTF-8"))
    }
    private fun settings(protocol: ProviderProtocol, value: DaiyuPromptCacheRetention) =
        ModelSettings("https://fixture.invalid/v1", "fixture-model", "synthetic-key", false,
            protocol = protocol, requestOptions = ProviderRequestOptions(promptCacheRetention = value))
    private fun send(model: ModelPort): JSONObject {
        val history = listOf(Message(MessageId("u"), ConversationId("c"), RunId("r"), "user",
            listOf(MessageBlock.Text("u", "question")), RunStatus.COMPLETED))
        model.open(history).next(emptyList()) {}
        error("offline connection must fail")
    }
    private fun capture(config: ModelSettings, summary: Boolean = false): JSONObject {
        val wire = Connection()
        val model = if (summary) SummaryProviderFactory.create(config.requestSnapshot(), 128, "summary rules", connect = { wire })
            else when (config.protocol) {
                ProviderProtocol.MESSAGES -> MessagesModel(config, 128, { wire }, null, "assistant rules")
                ProviderProtocol.RESPONSES -> ResponsesModel(config, { wire }, null, "assistant rules")
                else -> NativeCompletionModel(config, { wire }, "assistant rules", null)
            }
        assertThrows(ModelRequestException::class.java) { send(model) }
        return wire.body()
    }
    @Test fun actualMessagesPostUsesAllThreeChoicesWithoutSendingTheLocalKey() {
        for (value in DaiyuPromptCacheRetention.entries) {
            val body = capture(settings(ProviderProtocol.MESSAGES, value))
            assertFalse(body.has(DaiyuPromptCacheRetention.STORAGE_KEY))
            assertEquals(128, body.getInt("max_tokens"))
            if (value == DaiyuPromptCacheRetention.NONE) {
                assertEquals("assistant rules", body.getString("system"))
                assertFalse(body.toString().contains("cache_control"))
            } else {
                assertEquals("assistant rules", body.getJSONArray("system").getJSONObject(0).getString("text"))
                val block = body.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(0)
                assertEquals(value.wireValue, block.getJSONObject("cache_control").getString("ttl"))
            }
        }
    }
    @Test fun titleSnapshotsRetainEveryChoiceAndStillStripChatBody() {
        for (value in DaiyuPromptCacheRetention.entries) {
            val source = settings(ProviderProtocol.MESSAGES, value).copy(requestOptions = ProviderRequestOptions(
                customBody = """{"temperature":0.25}""", promptCacheRetention = value)).requestSnapshot()
            val title = TitleModelCoordinator.requestSnapshot(source)
            assertEquals(value, title.requestOptions.promptCacheRetention)
            assertFalse(title.requestOptions.customBody.contains("temperature"))
        }
    }
    @Test fun summaryTransportPreservesEveryChoice() {
        for (value in DaiyuPromptCacheRetention.entries) {
            val body = capture(settings(ProviderProtocol.MESSAGES, value), summary = true)
            if (value == DaiyuPromptCacheRetention.NONE) assertFalse(body.toString().contains("cache_control"))
            else assertEquals(value.wireValue, body.getJSONArray("system").getJSONObject(0).getJSONObject("cache_control").getString("ttl"))
        }
    }
    @Test fun otherProtocolsDoNotAcquireDaiyuCacheFields() {
        for (protocol in listOf(ProviderProtocol.RESPONSES, ProviderProtocol.CHAT_COMPLETIONS, ProviderProtocol.GEMINI)) {
            val body = capture(settings(protocol, DaiyuPromptCacheRetention.ONE_HOUR))
            assertFalse(body.toString().contains("cache_control"))
            assertFalse(body.has(DaiyuPromptCacheRetention.STORAGE_KEY))
        }
    }
}
