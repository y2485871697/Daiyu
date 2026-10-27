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

    @Test fun productionRuntimeRebindsRejectedAttemptsToDistinctAdmittedRequestIdentities() {
        val owner = Message(MessageId("parent"), ConversationId("conversation"), RunId("run"), "assistant", emptyList(), RunStatus.RUNNING)
        val history = (0..7).map { n -> owner.copy(id = MessageId("u$n"), role = "user", status = RunStatus.COMPLETED,
            blocks = listOf(MessageBlock.Text("source", "original evidence ".repeat(120)))) }
        val reply = SummaryCheckpoint.marker + "\n" + SummaryCheckpoint.sections.joinToString("\n") { "## $it\n- kept" }
        val wires = listOf(Connection(rejected = true), Connection(terminalText = reply))
        val admitted = mutableListOf<RequestCheckpoint>()
        val reported = mutableListOf<Pair<RequestCheckpoint, ModelEvent.Usage>>()
        var created = 0
        var connected = 0
        val runtime = ProductionRunContext(app.daiyu.android.domain.context.CompressionPolicy(true, 4096, reserveTokens = 64, tokenTail = true),
            owner, resolve = { usage ->
                ProviderSummaryModelResolver({ config() }, { wires[connected++] }, deadlines, usage).resolve(binding)!!
            }, automatic = true)
        runtime.beginCompaction(owner)
        val result = runtime.compactRequests(history, null, runtime.localTokens(history, emptyList()), object : RunSummaryRequests {
            override fun create(): RequestCheckpoint {
                created++
                return RequestCheckpoint("request-$created", "attempt-$created", "parent-owner", RequestOperation.NEXT)
            }
            override fun admit(request: RequestCheckpoint) { admitted += request }
            override fun observe(request: RequestCheckpoint, usage: ModelEvent.Usage) { reported += request to usage }
        }, { false })
        assertNotNull(result)
        assertEquals(2, created); assertEquals(2, connected)
        assertEquals(listOf("request-1", "request-2"), admitted.map { it.requestId })
        assertEquals(listOf("attempt-1", "attempt-2"), admitted.map { it.attemptId })
        assertEquals(listOf(admitted[1] to ModelEvent.Usage(10, 20, 5)), reported)
        wires.forEach { assertEquals(1, it.posts); assertTrue(it.disconnected) }
    }

    private class Connection(private val rejected: Boolean = false, private val terminalText: String = "summary text") : HttpsURLConnection(URL("https://example.invalid/fixture")) {
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
        override fun getResponseCode() = if (rejected) 400 else 200
        override fun getErrorStream() = if (rejected)
            """{"error":{"code":"unsupported_value","param":"reasoning.effort"}}""".byteInputStream() else null
        override fun getContentType() = "text/event-stream"
        override fun getInputStream() = body.replace("summary text", org.json.JSONObject.quote(terminalText).removeSurrounding("\"")).byteInputStream()
    }
}
