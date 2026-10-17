package app.daiyu.android.context

import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.*
import app.daiyu.android.model.*
import app.daiyu.android.storage.ModelSettings
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.URL
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

/** Real Responses parser/summary terminal adapter, entirely in-memory HTTPS fixture. */
class ProviderSummaryUsagePortTest {
    private val binding = SummaryModelBinding("summary-provider", "summary-model")
    private val parent = ParentSummaryUsage("conversation", "parent-run", "parent-message",
        RequestCheckpoint("admitted-summary-request", "admitted-summary-attempt", "parent-owner", RequestOperation.NEXT))
    private val deadlines = object : SummaryDeadlineScheduler {
        override fun nanoTime() = 0L
        override fun schedule(delayMillis: Long, expire: () -> Unit) = AutoCloseable {}
    }
    private fun config(): SummaryProviderConfiguration {
        val provider = ProviderSnapshot.capture(ModelSettings("https://example.invalid/v1", binding.modelId,
            "fixture-not-a-credential", true, protocol = ProviderProtocol.RESPONSES))
        return SummaryProviderConfiguration(binding, provider, binding.modelId, 24000, 23104, 256, 128, 512, 60000)
    }
    private fun history(conversation: String = "conversation") = listOf(Message(MessageId("synthetic-summary-input"),
        ConversationId(conversation), RunId("synthetic-not-parent-run"), "user",
        listOf(MessageBlock.Text("text", "historical evidence")), RunStatus.COMPLETED))
    private fun port(connection: Connection, usage: BoundSummaryUsagePort): SummaryModelTransport =
        ProviderSummaryModelResolver({ config() }, { connection }, deadlines, usage).resolve(binding)!!.transport

    @Test fun authoritativeUsageGoesToHostBoundPortNotSummaryProseOrSyntheticInputIds() {
        val accepted = mutableListOf<Pair<ParentSummaryUsage, RequestUsageCheckpoint>>()
        val connection = Connection()
        val usage = BoundSummaryUsagePort(parent) { owner, snapshot -> accepted.add(owner to snapshot) }
        val transport = port(connection, usage)
        val session = transport.openWithoutTools(history(), SummaryRequestDeadline(60000, deadlines::nanoTime))
        val events = mutableListOf<ModelEvent>()
        session.next(emptyList(), events::add)
        assertEquals(1, connection.posts); assertTrue(connection.disconnected)
        assertEquals(listOf(ModelEvent.TextFinal("summary-0", "summary text")), events)
        assertEquals(1, accepted.size); assertEquals(parent, accepted.single().first)
        assertEquals(parent.request, accepted.single().second.request)
        assertEquals(ModelEvent.Usage(10, 20, 5), accepted.single().second.snapshot)
        assertThrows(IllegalStateException::class.java) {
            transport.openWithoutTools(history(), SummaryRequestDeadline(60000, deadlines::nanoTime))
        }
        assertEquals(1, connection.posts)
    }

    @Test fun durablePortFailurePropagatesWithoutPostingAgainOrPublishingSummary() {
        val connection = Connection(); val events = mutableListOf<ModelEvent>()
        val usage = BoundSummaryUsagePort(parent) { _, _ -> error("fixture-commit-failed") }
        val session = port(connection, usage).openWithoutTools(history(), SummaryRequestDeadline(60000, deadlines::nanoTime))
        assertThrows(IllegalStateException::class.java) { session.next(emptyList(), events::add) }
        assertTrue(events.isEmpty()); assertEquals(1, connection.posts); assertTrue(connection.disconnected)
    }

    @Test fun wrongConversationCannotConsumeAnAdmittedHostPortOrConnect() {
        val connection = Connection()
        val usage = BoundSummaryUsagePort(parent) { _, _ -> fail("unexpected observation") }
        assertThrows(IllegalArgumentException::class.java) {
            port(connection, usage).openWithoutTools(history("foreign"), SummaryRequestDeadline(60000, deadlines::nanoTime))
        }
        assertEquals(0, connection.posts)
    }

    private class Connection : HttpsURLConnection(URL("https://example.invalid/fixture")) {
        var posts = 0
        var disconnected = false
        private val body = """event: response.completed
            |data: {"type":"response.completed","response":{"status":"completed","output":[{"type":"message","id":"final","role":"assistant","content":[{"type":"output_text","text":"summary text"}]}],"usage":{"input_tokens":10,"output_tokens":20,"input_tokens_details":{"cached_tokens":5}}}}
            |
            |""".trimMargin() + "\n"
        override fun connect() {}
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getOutputStream(): ByteArrayOutputStream { posts++; return ByteArrayOutputStream() }
        override fun getResponseCode() = 200
        override fun getContentType() = "text/event-stream"
        override fun getInputStream() = body.byteInputStream()
    }
}
