package app.daiyu.android

import app.daiyu.android.domain.*
import app.daiyu.android.media.FrozenImageAttachment
import app.daiyu.android.media.ImageDimensions
import app.daiyu.android.media.ImageInspector
import app.daiyu.android.media.MessageImageResolver
import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.model.ProviderSnapshot
import app.daiyu.android.model.auxiliary.AuthorizedVisionObservation
import app.daiyu.android.model.auxiliary.AuxiliaryModelCandidate
import app.daiyu.android.model.auxiliary.AuxiliaryModelSelection
import app.daiyu.android.model.images.ImageInputEncoder
import app.daiyu.android.storage.ModelSettings
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.security.MessageDigest
import java.security.cert.Certificate
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection

/** Only HTTPS IO is fake: host -> bridge -> adapter -> ResponsesModel use their real code. */
class ControllerAuxiliaryVisionTest {
    private val bytes = byteArrayOf(1, 2, 3, 4)
    private val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private val encoder = ImageInputEncoder(ImageInspector { ImageDimensions("image/png", 2, 2) })
    private val selection = AuxiliaryModelSelection(true, "aux-provider", "vision-record")

    private fun candidate(provider: String = "aux-provider", model: String = "vision-record", capability: Boolean? = true) =
        AuxiliaryModelCandidate(provider, model, ProviderSnapshot.capture(ModelSettings(
            "https://aux.invalid/v1", "aux-api-name", "fixture-aux-key", true,
            deviceStatusEnabled = true, protocol = ProviderProtocol.RESPONSES), ProviderProtocol.RESPONSES, capability))

    private fun row(id: String = "image-user", run: String = "original-image-run") = Message(
        MessageId(id), ConversationId("conversation"), RunId(run), Message.USER_ROLE,
        listOf(MessageBlock.Text("before", "task context"), MessageBlock.Image(MessageImageReference(
            MessageImageOwner("conversation", id), "00000000-0000-0000-0000-000000000001", "image/png",
            bytes.size, digest, 2, 2)), MessageBlock.Text("after", "after image")), RunStatus.COMPLETED)

    private fun authorized(raw: List<Message>) = MessageHistory.expand(raw).flatMap { message ->
        message.blocks.filterIsInstance<MessageBlock.Image>().map {
            AuthorizedVisionObservation(message.runId, "observation-${message.id.value}", it.reference)
        }
    }

    private inner class Resolver : MessageImageResolver {
        var reads = 0
        val owners = mutableListOf<app.daiyu.android.media.MessageImageOwner>()
        var beforeRead: () -> Unit = {}
        override fun read(owner: app.daiyu.android.media.MessageImageOwner,
                          reference: app.daiyu.android.media.MessageImageReference): ByteArray = error("freeze only")
        override fun freeze(owner: app.daiyu.android.media.MessageImageOwner,
                            references: List<app.daiyu.android.media.MessageImageReference>,
                            imageInputSupported: Boolean, isCancelled: () -> Boolean): List<FrozenImageAttachment> {
            beforeRead()
            assertTrue(imageInputSupported)
            assertFalse(isCancelled())
            reads++
            owners.add(owner)
            return references.map { FrozenImageAttachment(it.asAttachment(), bytes) }
        }
    }

    private class Session : ModelSession {
        var cancels = 0
        var nexts = 0
        var outputs: List<ToolOutput>? = null
        var failNext = false
        override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
            nexts++
            this.outputs = outputs
            if (failNext) throw IOException("fixture failure")
            sink(ModelEvent.TextFinal("main", "answer"))
        }
        override fun cancel() { cancels++ }
    }

    private class Main : ModelPort {
        val kinds = mutableListOf<String>()
        val histories = mutableListOf<List<Message>>()
        val sessions = mutableListOf<Session>()
        var tools: Set<ReadOnlyTool>? = null
        var definitions: List<ToolDefinition>? = null
        private fun accept(history: List<Message>, kind: String): ModelSession {
            kinds.add(kind)
            histories.add(history)
            return Session().also { sessions.add(it) }
        }
        override fun open(history: List<Message>) = accept(history, "plain")
        override fun open(history: List<Message>, tools: Set<ReadOnlyTool>): ModelSession {
            this.tools = tools
            return accept(history, "tools")
        }
        override fun open(history: List<Message>, definitions: List<ToolDefinition>): ModelSession {
            this.definitions = definitions
            return accept(history, "definitions")
        }
    }

    private class Connection(private val block: Boolean = false, private val status: Int = 200,
                             events: String = COMPLETE) : HttpsURLConnection(URL("https://fixture.invalid")) {
        val sent = ByteArrayOutputStream()
        val reading = CountDownLatch(1)
        private val stopped = CountDownLatch(1)
        @Volatile var disconnects = 0
        private val source = ByteArrayInputStream(events.toByteArray(Charsets.UTF_8))
        private val incoming = object : InputStream() {
            override fun read(): Int {
                reading.countDown()
                if (block) {
                    if (!stopped.await(5, TimeUnit.SECONDS)) throw IOException("fixture timeout")
                    throw IOException("fixture disconnected")
                }
                return source.read()
            }
        }
        override fun connect() {}
        override fun disconnect() { disconnects++; stopped.countDown() }
        override fun usingProxy() = false
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getOutputStream() = sent
        override fun getInputStream() = incoming
        override fun getResponseCode() = status
        override fun getContentType() = "text/event-stream"
    }

    private fun host(main: ModelPort = Main(), resolver: Resolver = Resolver(),
                     authorize: (List<Message>) -> List<AuthorizedVisionObservation> = ::authorized,
                     connect: (URL) -> HttpsURLConnection = { Connection() }) =
        ControllerAuxiliaryVision.create(main, selection, listOf(candidate()), resolver, authorize, connect, encoder)

    private fun rejected(work: () -> Unit): Throwable = runCatching(work).exceptionOrNull()
        ?: throw AssertionError("expected rejection")

    @Test fun allThreeOpenOverloadsKeepToolsAndOnlySendTextProjectionToMain() {
        val main = Main()
        val resolver = Resolver()
        val connection = Connection()
        var checks = 0
        var posts = 0
        val candidates = mutableListOf(candidate("main-provider", "main-record"), candidate())
        val selected = candidates.last().snapshot
        val host = ControllerAuxiliaryVision.create(main, selection, candidates, resolver, {
            checks++
            authorized(it)
        }, { url ->
            assertEquals(selected.asModelSettings().requestEndpoint(), url.toString())
            posts++
            connection
        }, encoder)
        candidates.clear() // The already-resolved binding cannot read edited registry state.
        val source = listOf(row())
        val original = source.toList()
        val tools = setOf(ReadOnlyTool.BATTERY)
        val definitions = listOf(ToolDefinition("custom", "description", "{}", true))
        try {
            host.open(source)
            host.open(source, tools)
            val session = host.open(source, definitions)
            val output = listOf(ToolOutput(ToolCallId("call"), "tool result"))
            val events = mutableListOf<ModelEvent>()
            session.next(output, events::add)
            assertEquals(listOf("plain", "tools", "definitions"), main.kinds)
            assertEquals(tools, main.tools)
            assertEquals(definitions, main.definitions)
            assertSame(output, main.sessions.last().outputs)
            assertEquals(listOf(ModelEvent.TextFinal("main", "answer")), events)
            assertEquals(3, checks) // Cached observations never cache authorization.
            assertEquals(1, posts)
            assertEquals(1, resolver.reads)
            assertEquals(original, source)
            assertTrue(source.single().blocks[1] is MessageBlock.Image)
            main.histories.forEach { projected ->
                assertTrue(projected.single().blocks.all { it is MessageBlock.Text })
                assertEquals(source.single().blocks.first(), projected.single().blocks.first())
                assertEquals(source.single().blocks.last(), projected.single().blocks.last())
                assertTrue((projected.single().blocks[1] as MessageBlock.Text).text.endsWith("visible evidence"))
                assertEquals(RunId("original-image-run"), projected.single().runId)
            }
            val body = JSONObject(connection.sent.toString("UTF-8"))
            assertEquals("aux-api-name", body.getString("model"))
            assertEquals("Bearer fixture-aux-key", connection.getRequestProperty("Authorization"))
            assertFalse(body.has("tools"))
            assertFalse(body.has("tool_choice"))
        } finally { host.close() }
        assertTrue(main.sessions.all { it.cancels > 0 })
    }

    @Test fun bindingResolutionNeverUsesMainOrFallback() {
        val main = Main()
        val resolver = Resolver()
        var auth = 0
        var posts = 0
        val cases = listOf(
            selection.copy(enabled = false) to listOf(candidate()),
            selection.copy(providerId = "") to listOf(candidate()),
            selection to emptyList(),
            selection to listOf(candidate("main-provider", "main-record")),
            selection to listOf(candidate(), candidate()),
            selection to listOf(candidate().copy(providerEnabled = false)),
            selection to listOf(candidate().copy(modelEnabled = false)),
            selection to listOf(candidate(capability = false)),
            selection to listOf(candidate(capability = null)),
        )
        cases.forEach { (selected, registry) ->
            rejected { ControllerAuxiliaryVision.create(main, selected, registry, resolver,
                { auth++; authorized(it) }, { posts++; Connection() }, encoder) }
        }
        assertEquals(0, auth)
        assertEquals(0, posts)
        assertEquals(0, resolver.reads)
        assertTrue(main.kinds.isEmpty())
    }

    @Test fun batchAuthorizationReceivesOriginalOwnersBeforeExpansionAndBeforeAnyIO() {
        val supplement = row("supplement", "source-run")
        val owner = Message(MessageId("assistant-owner"), supplement.conversationId, supplement.runId,
            Message.ASSISTANT_ROLE, emptyList(), RunStatus.COMPLETED, historySegments = listOf(
                MessageHistorySegment(supplement.id, Message.USER_ROLE, supplement.blocks),
                MessageHistorySegment(MessageId("assistant-owner"), Message.ASSISTANT_ROLE,
                    listOf(MessageBlock.Text("answer", "old answer")))))
        val raw = listOf(supplement, owner)
        var authorizedBatch = false
        val resolver = Resolver().also { it.beforeRead = { assertTrue(authorizedBatch) } }
        val main = Main()
        val host = host(main, resolver, { received ->
            assertEquals(raw, received)
            assertEquals(2, received.size)
            assertEquals(2, received.last().historySegments.size)
            assertTrue(rejected { (received as MutableList<Message>).clear() } is UnsupportedOperationException)
            assertTrue(rejected { (received.last().historySegments as MutableList<MessageHistorySegment>).clear() }
                is UnsupportedOperationException)
            authorizedBatch = true
            authorized(received)
        })
        try {
            host.open(raw)
            assertEquals("supplement", resolver.owners.single().messageId)
            assertEquals(2, main.histories.single().size)
            assertEquals(RunId("source-run"), main.histories.single().first().runId)
            assertEquals(raw, listOf(supplement, owner))
            assertTrue(owner.historySegments.first().blocks[1] is MessageBlock.Image)
        } finally { host.close() }
    }

    @Test fun completeBatchAndOriginalSourceRunAreRequiredBeforeEvenFirstImageRead() {
        val raw = listOf(row("first"), row("second"))
        val expected = authorized(raw)
        val invalid = listOf(expected.take(1), expected + expected.first(),
            expected.map { it.copy(sourceRunId = RunId("current-parent-run")) }, expected.reversed())
        invalid.forEach { identities ->
            val resolver = Resolver()
            val main = Main()
            var posts = 0
            val host = host(main, resolver, { identities }, { posts++; Connection() })
            try { rejected { host.open(raw) } } finally { host.close() }
            assertEquals(0, resolver.reads)
            assertEquals(0, posts)
            assertTrue(main.histories.isEmpty())
        }
    }

    @Test fun deniedLaterRowAndRevokedCachedAuthorizationCannotLeakOrPost() {
        val resolver = Resolver()
        val main = Main()
        var checks = 0
        var posts = 0
        var deny = true
        val host = host(main, resolver, { raw ->
            checks++
            if (deny) throw SecurityException("batch denied")
            authorized(raw)
        }, { posts++; Connection() })
        rejected { host.open(listOf(row("first"), row("denied-second"))) }
        assertEquals(0, resolver.reads)
        assertEquals(0, posts)
        host.close()

        val cached = this.host(main, resolver, { raw ->
            checks++
            if (deny) throw SecurityException("revoked")
            authorized(raw)
        }, { posts++; Connection() })
        try {
            deny = false
            cached.open(listOf(row()))
            deny = true
            rejected { cached.open(listOf(row())) }
            rejected { cached.open(listOf(row())) } // sticky failure; not an implicit retry
            assertEquals(3, checks)
            assertEquals(1, posts)
            assertEquals(1, resolver.reads)
            assertEquals(1, main.histories.size)
        } finally { cached.close() }
    }

    @Test fun firstAuxiliaryHttpFailureSealsRunWithoutRetryOrMainFallback() {
        listOf(Connection(status = 401), Connection(events = "data: {\"type\":\"response.output_text.delta\",\"item_id\":\"a\",\"delta\":\"partial\"}\n\n"))
            .forEach { connection ->
                val main = Main()
                var posts = 0
                var checks = 0
                val host = host(main, authorize = { checks++; authorized(it) }, connect = { posts++; connection })
                try {
                    rejected { host.open(listOf(row())) }
                    rejected { host.open(listOf(row())) }
                    assertEquals(1, posts)
                    assertEquals(1, checks)
                    assertTrue(main.histories.isEmpty())
                } finally { host.close() }
            }
    }

    @Test fun laterObservationFailureCannotPublishPartialProjectionOrRetryCachedRun() {
        val main = Main()
        val raw = listOf(row("first"), row("second"))
        var posts = 0
        val host = host(main, connect = {
            posts++
            if (posts == 1) Connection() else Connection(status = 500)
        })
        try {
            rejected { host.open(raw) }
            assertEquals(2, posts)
            rejected { host.open(raw) }
            assertEquals(2, posts)
            assertTrue(main.histories.isEmpty())
            assertTrue(raw.all { it.blocks[1] is MessageBlock.Image })
        } finally { host.close() }
    }

    @Test fun mainOpenFailureIsNotRetriedEvenForTextOnlyHistory() {
        var opens = 0
        var checks = 0
        var posts = 0
        val host = host(ModelPort { opens++; throw IOException("main open failed") },
            authorize = { checks++; authorized(it) }, connect = { posts++; Connection() })
        try {
            rejected { host.open(emptyList()) }
            rejected { host.open(emptyList()) }
            assertEquals(1, opens)
            assertEquals(1, checks)
            assertEquals(0, posts)
        } finally { host.close() }
    }

    @Test fun closeBeforeOpenAndFrozenPlanOverloadCannotBypassAuthorizationRoute() {
        val main = Main()
        var checks = 0
        val host = host(main, authorize = { checks++; authorized(it) })
        val opaquePlan = object : ModelImagePlan {}
        assertTrue(rejected { host.open(emptyList(), emptyList<ToolDefinition>(), opaquePlan) }
            is UnsupportedOperationException)
        host.close()
        assertTrue(rejected { host.open(emptyList()) } is CancellationException)
        assertEquals(0, checks)
        assertTrue(main.histories.isEmpty())
    }

    @Test fun mainNextFailureAndSinkFailureCannotAutomaticallyReopenOrReplay() {
        listOf(false, true).forEach { sinkFailure ->
            val main = Main()
            val host = host(main)
            try {
                val session = host.open(emptyList())
                main.sessions.single().failNext = !sinkFailure
                rejected { session.next(emptyList()) { if (sinkFailure) error("sink failed") } }
                rejected { session.next(emptyList()) {} }
                rejected { host.open(emptyList()) }
                assertEquals(1, main.sessions.single().nexts)
                assertEquals(1, main.histories.size)
                assertTrue(main.sessions.single().cancels > 0)
            } finally { host.close() }
        }
    }

    @Test fun cancelWhileMainOpenBlocksReturnsBeforeDrainAndCancelsLateSessionOnlyInThisRun() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        val late = Session()
        val failure = AtomicReference<Throwable?>()
        val host = host(ModelPort {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            late
        })
        val worker = Thread {
            try { host.open(emptyList()); failure.set(AssertionError("late open escaped")) }
            catch (error: Throwable) { failure.set(error) }
            finally { done.countDown() }
        }
        worker.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            host.cancel()
            assertEquals(1L, done.count) // cancel is not a join/drain.
            rejected { host.open(emptyList()) }
            val other = this.host()
            try { other.open(emptyList()) } finally { other.close() }
        } finally {
            release.countDown()
            assertTrue(done.await(5, TimeUnit.SECONDS))
            host.close()
            worker.join(5_000)
        }
        assertTrue(failure.get() is CancellationException)
        assertTrue(late.cancels > 0)
        assertEquals(0, late.nexts)
    }

    @Test fun cancelDuringAuxiliaryReadDisconnectsAdapterAndNeverOpensMain() {
        val connection = Connection(block = true)
        val main = Main()
        val host = host(main, connect = { connection })
        val failure = AtomicReference<Throwable?>()
        val done = CountDownLatch(1)
        val worker = Thread {
            try { host.open(listOf(row())); failure.set(AssertionError("cancelled open escaped")) }
            catch (error: Throwable) { failure.set(error) }
            finally { done.countDown() }
        }
        worker.start()
        try {
            assertTrue(connection.reading.await(5, TimeUnit.SECONDS))
            host.cancel()
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertTrue(failure.get() is CancellationException)
            assertTrue(connection.disconnects > 0)
            assertTrue(main.histories.isEmpty())
        } finally { host.close(); worker.join(5_000) }
    }

    companion object {
        private const val COMPLETE = "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"id\":\"observation\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"visible evidence\"}]}]}}\n\n"
    }
}
