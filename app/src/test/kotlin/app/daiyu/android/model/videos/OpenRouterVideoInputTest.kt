package app.daiyu.android.model.videos

import app.daiyu.android.domain.*
import app.daiyu.android.media.video.*
import app.daiyu.android.model.*
import app.daiyu.android.storage.ModelSettings
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import java.nio.file.Files
import java.security.cert.Certificate
import java.util.Base64
import javax.net.ssl.HttpsURLConnection

/** Synthetic bytes + fake inspector test protocol/storage contracts, not real Android decoding. */
class OpenRouterVideoInputTest {
    private lateinit var root: File
    private lateinit var store: VideoAssetStore
    private val original = byteArrayOf(5, 4, 3, 2, 1)
    private val owner = MessageVideoOwner("c", "u")
    private val row = Message(MessageId("u"), ConversationId("c"), RunId("r"), "user",
        listOf(MessageBlock.Text("t", "Describe this original video")), RunStatus.COMPLETED)
    private val settings = ModelSettings(OpenRouterVideoCapability.ENDPOINT, "fixture/exact-model", "synthetic-key", false)
    private val done = "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[]}}\n\n"

    @Before fun before() {
        root = Files.createTempDirectory("video-input-test").toFile()
        store = VideoAssetStore(root, VideoAttachmentLimits(), VideoInspector { _, _, _ ->
            VideoMetadata("video/mp4", 1000, 32, 24)
        })
    }
    @After fun after() { root.deleteRecursively() }
    private fun grant(max: Long = 16L * 1024 * 1024) = OpenRouterVideoCapability.grant(
        settings.model, settings.requestEndpoint(), true, true, maxVideoBytes = max, maxTotalBytes = max)
    // Synthetic local handoff only: production must adapt the real run permit, not this fixture.
    private val postApproval = VideoPostApprovalProvider { request ->
        val spent = java.util.concurrent.atomic.AtomicBoolean(false)
        request.bindPermitConsumption { check(spent.compareAndSet(false, true)) }
    }
    private fun reference() = store.prepare(owner, store.import(VideoAttachmentOwner("c", "d"), original.inputStream()))
    private fun freeze(ref: MessageVideoReference, cap: OpenRouterVideoCapability,
                       history: List<Message> = listOf(row), config: ModelSettings = settings,
                       cancelled: () -> Boolean = { false }) = FrozenMessageVideoPlan.freeze(
        history, ProviderSnapshot.capture(config), cap, listOf(MessageVideoInput(owner, listOf(ref))), store,
        { MessageVideoOwner(it.conversationId.value, it.id.value) }, cancelled)
    private fun rejected(work: () -> Unit): Throwable = runCatching(work).exceptionOrNull().also {
        assertNotNull("must fail closed", it)
    }!!
    private fun failure(reason: VideoAttachmentFailure, work: () -> Unit) {
        assertEquals(reason, (rejected(work) as VideoAttachmentException).failure)
    }

    private class Connection(events: String) : HttpsURLConnection(URL(OpenRouterVideoCapability.ENDPOINT)) {
        val sent = ByteArrayOutputStream()
        var disconnected = false
        private val incoming = events.byteInputStream()
        override fun connect() {}
        override fun disconnect() { disconnected = true; incoming.close() }
        override fun usingProxy() = false
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getOutputStream() = sent
        override fun getInputStream() = incoming
        override fun getResponseCode() = 200
        override fun getContentType() = "text/event-stream"
    }

    @Test fun exactOriginalSentByActualTransportNotCoverAndPlanIsFrozen() {
        val ref = reference()
        val cap = grant()
        val plan = freeze(ref, cap)
        // A frozen plan no longer reads any file, even if the source is later removed.
        assertTrue(store.discardUnreferenced(owner, ref))
        val conn = Connection(done)
        val events = mutableListOf<ModelEvent>()
        val session = ResponsesModel(settings, { conn }, null, videoInputCapability = cap, videoPostApprovalProvider = postApproval)
            .open(listOf(row), emptyList(), plan)
        assertEquals(0, conn.sent.size())
        session.next(emptyList(), events::add)
        val body = JSONObject(conn.sent.toString("UTF-8"))
        val content = body.getJSONArray("input").getJSONObject(0).getJSONArray("content")
        assertEquals(2, content.length())
        assertEquals("input_text", content.getJSONObject(0).getString("type"))
        val video = content.getJSONObject(1)
        assertEquals(setOf("type", "video_url"), video.keys().asSequence().toSet())
        assertEquals("input_video", video.getString("type"))
        assertEquals("data:video/mp4;base64," + Base64.getEncoder().encodeToString(original), video.getString("video_url"))
        assertFalse(body.toString().contains("input_file"))
        assertFalse(body.toString().contains("image/jpeg"))
        assertTrue(events.any { it is ModelEvent.ProtocolOutput })
        assertTrue(conn.disconnected)
        rejected { session.next(emptyList()) {} } // one completed request cannot replay
    }

    @Test fun grantsRequireBothTrustedCapabilityAndPermissionAndExactOfficialEndpoint() {
        for ((supported, enabled) in listOf(false to true, true to false, false to false)) {
            rejected { OpenRouterVideoCapability.grant(settings.model, settings.requestEndpoint(), supported, enabled) }
        }
        for (endpoint in listOf("https://example.invalid/api/v1/responses", "https://openrouter.ai/api/v1/messages",
            "https://openrouter.ai/api/v1/responses?x=1", "http://openrouter.ai/api/v1/responses")) {
            rejected { OpenRouterVideoCapability.grant(settings.model, endpoint, true, true) }
        }
        val ref = reference()
        val cap = grant()
        for (config in listOf(settings.copy(model = "fixture/other"), settings.copy(endpoint = "https://example.invalid/responses"),
            settings.copy(protocol = ProviderProtocol.MESSAGES, endpoint = "https://openrouter.ai/api/v1/messages"))) {
            rejected { freeze(ref, cap, config = config) }
        }
        val plan = freeze(ref, cap)
        var connections = 0
        val connect: (URL) -> HttpsURLConnection = { connections++; Connection(done) }
        rejected { ResponsesModel(settings, connect, null).open(listOf(row), emptyList(), plan) }
        rejected { ResponsesModel(settings, connect, null, videoInputCapability = grant()).open(listOf(row), emptyList(), plan) }
        rejected { ResponsesModel(settings.copy(model = "different"), connect, null, videoInputCapability = cap, videoPostApprovalProvider = postApproval)
            .open(listOf(row), emptyList(), plan) }
        rejected { ResponsesModel(settings, connect, null, videoInputCapability = cap, videoPostApprovalProvider = postApproval)
            .open(listOf(row.copy(blocks = emptyList())), emptyList(), plan) }
        assertEquals(0, connections)
    }

    @Test fun metadataBudgetsOwnersAndOriginalCorruptionFailBeforeSending() {
        val ref = reference()
        failure(VideoAttachmentFailure.TOO_LARGE) { freeze(ref, grant(4)) }
        rejected { freeze(ref.copy(owner = owner.copy(messageId = "other")), grant()) }
        rejected { freeze(ref, grant(), history = listOf(row.copy(role = "assistant"))) }
        failure(VideoAttachmentFailure.UNSUPPORTED_VIDEO) { freeze(ref.copy(mime = "video/quicktime"), grant()) }
        failure(VideoAttachmentFailure.CORRUPT) { freeze(ref.copy(width = 33), grant()) }
        failure(VideoAttachmentFailure.CANCELLED) { freeze(ref, grant(), cancelled = { true }) }
        // Same-length on-disk modification is not allowed to become a wire payload.
        File(File(File(root, owner.directoryName()), ref.id), "original.video").writeBytes(byteArrayOf(0, 0, 0, 0, 0))
        failure(VideoAttachmentFailure.CORRUPT) { freeze(ref, grant()) }
    }

    @Test fun allHistoryBudgetsCheckedBeforeAnyOriginalIsRead() {
        val ref = reference()
        val otherRow = row.copy(id = MessageId("u2"))
        val other = ref.copy(owner = MessageVideoOwner("c", "u2"), bytes = 6)
        val cap = grant(10)
        // First original is missing: aggregate overflow must still be detected first.
        assertTrue(store.discardUnreferenced(owner, ref))
        failure(VideoAttachmentFailure.TOO_LARGE) {
            FrozenMessageVideoPlan.freeze(listOf(row, otherRow), ProviderSnapshot.capture(settings), cap,
                listOf(MessageVideoInput(owner, listOf(ref)), MessageVideoInput(other.owner, listOf(other))), store,
                { MessageVideoOwner(it.conversationId.value, it.id.value) })
        }
    }

    @Test fun duplicateAndCountLimitsAreCheckedBeforeOpeningSources() {
        val ref = reference()
        val cap = grant()
        assertTrue(store.discardUnreferenced(owner, ref))
        fun withReferences(references: List<MessageVideoReference>) = FrozenMessageVideoPlan.freeze(
            listOf(row), ProviderSnapshot.capture(settings), cap, listOf(MessageVideoInput(owner, references)), store,
            { owner })
        assertTrue(rejected { withReferences(listOf(ref, ref)) } is IllegalArgumentException)
        val five = (1..5).map { ref.copy(id = "00000000-0000-0000-0000-00000000000$it") }
        failure(VideoAttachmentFailure.TOO_MANY) { withReferences(five) }
    }

    @Test fun segmentedHistoryBindsVideoToOriginalUserNotAssistantContainer() {
        val ref = reference()
        val cap = grant()
        val container = Message(MessageId("a"), ConversationId("c"), RunId("r"), "assistant", emptyList(),
            RunStatus.COMPLETED, historySegments = listOf(
                MessageHistorySegment(row.id, "user", row.blocks),
                MessageHistorySegment(MessageId("a"), "assistant", listOf(MessageBlock.Text("a", "answer")))))
        val history = listOf(row, container)
        val plan = freeze(ref, cap, history)
        val conn = Connection(done)
        ResponsesModel(settings, { conn }, null, videoInputCapability = cap, videoPostApprovalProvider = postApproval).open(history, emptyList(), plan)
            .next(emptyList()) {}
        val input = JSONObject(conn.sent.toString("UTF-8")).getJSONArray("input")
        assertEquals(2, input.length())
        assertEquals("input_video", input.getJSONObject(0).getJSONArray("content").getJSONObject(1).getString("type"))
        assertEquals("assistant", input.getJSONObject(1).getString("role"))
    }

    @Test fun rawProtocolVideosAreRejectedByBothExistingProtocolsAndCustomBody() {
        val raw = """{"role":"user","content":[{"type":"input_video","video_url":"data:video/mp4;base64,AQ=="}]}"""
        val history = listOf(row.copy(protocolItems = listOf(raw)))
        rejected { ResponsesModel(settings, { error("no connect") }).open(history) }
        val messages = settings.copy(protocol = ProviderProtocol.MESSAGES, endpoint = "https://example.invalid/messages")
        rejected { MessagesModel(messages, 4096, { error("no connect") }, null).open(history) }
        val custom = settings.copy(requestOptions = ProviderRequestOptions(raw))
        rejected { ResponsesModel(custom, { error("no connect") }).open(listOf(row)) }
    }

    private fun forbiddenVideoBodies() = listOf(
        """{"model":"other"}""",
        """{"model":"fixture/exact-model"}""", // even redundant custom routing is not permitted
        """{"models":["fixture/exact-model","unauthorized/fallback"]}""",
        """{"models":null}""",
        """{"provider":{"order":["another-provider"],"allow_fallbacks":true}}""",
        """{"route":"fallback"}""",
        """{"plugins":[{"id":"auto-router"}]}""",
        """{"plugins":[]}""",
        """{"routing":{"model":"unauthorized/fallback"}}""",
        """{"options":{"provider":{"order":["another-provider"]}}}""",
        """{"reasoning":{"route":"fallback"}}""",
        """{"temperature":{"provider":{"order":["another-provider"]}}}""",
        """{"top_p":[{"plugins":[{"id":"auto-router"}]}]}""",
        """{"max_output_tokens":{"models":["unauthorized/fallback"]}}""",
        """{"service_tier":"auto"}""",
        """{"future_gateway_extension":true}""",
    )

    @Test fun routingAndNestedExtensionsFailBeforeReadingOriginalOrConnecting() {
        val ref = reference()
        val cap = grant()
        val plan = freeze(ref, cap)
        assertTrue(store.discardUnreferenced(owner, ref))
        var connections = 0
        var ownerChecks = 0
        val forbidden = forbiddenVideoBodies().map { ProviderRequestOptions(it) } + listOf(
            ProviderRequestOptions(speedMode = GptSpeedMode.FAST), ProviderRequestOptions(codex = true),
            ProviderRequestOptions("""{"temperature":null}"""),
            ProviderRequestOptions("""{"temperature":2.1}"""),
            ProviderRequestOptions("""{"top_p":-0.1}"""),
            ProviderRequestOptions("""{"max_output_tokens":0}"""),
            ProviderRequestOptions("""{"max_output_tokens":1.5}"""),
        )
        for (options in forbidden) {
            val config = settings.copy(requestOptions = options)
            // Missing original would yield a storage error if preflight attempted to read it.
            assertTrue(rejected {
                FrozenMessageVideoPlan.freeze(listOf(row), ProviderSnapshot.capture(config), cap,
                    listOf(MessageVideoInput(owner, listOf(ref))), store,
                    { ownerChecks++; owner })
            } is IllegalArgumentException)
            // A valid plan cannot be reused to bypass the new settings preflight either.
            assertTrue(rejected {
                ResponsesModel(config, { connections++; Connection(done) }, null, videoInputCapability = cap, videoPostApprovalProvider = postApproval)
                    .open(listOf(row), emptyList(), plan).next(emptyList()) {}
            } is IllegalArgumentException)
        }
        assertEquals(0, ownerChecks)
        assertEquals(0, connections)
    }

    @Test fun finalPayloadRechecksAllowlistOnInitialAndToolContinuationRounds() {
        val cap = grant()
        val plan = freeze(reference(), cap)
        val input = ResponsesRequest.history(listOf(row), videos = plan)
        for (continuation in listOf(false, true)) {
            if (continuation) input.put(JSONObject().put("type", "function_call_output")
                .put("call_id", "call").put("output", "result"))
            val baseline = ResponsesRequest.body(settings.model, input, emptyList())
            VideoRequestPolicy.disableReasoning(baseline)
            plan.validateRequest(settings, baseline, input)
            for (raw in forbiddenVideoBodies().filterNot { it == """{"model":"fixture/exact-model"}""" }) {
                val request = JSONObject(baseline.toString())
                val patch = JSONObject(raw)
                patch.keys().forEach { key -> request.put(key, patch.get(key)) }
                assertTrue(rejected { plan.validateRequest(settings, request, input) } is IllegalArgumentException)
            }
            for (key in listOf("stream", "store", "include", "input")) {
                val request = JSONObject(baseline.toString()).put(key, JSONObject.NULL)
                assertTrue(rejected { plan.validateRequest(settings, request, input) } is IllegalArgumentException)
            }
        }
    }

    @Test fun ordinaryNonVideoRequestsRetainFlexibleRoutingOptions() {
        val config = settings.copy(requestOptions = ProviderRequestOptions(
            """{"models":["fixture/exact-model","other"],"provider":{"allow_fallbacks":true},"route":"fallback","plugins":[{"id":"auto-router"}]}"""))
        val conn = Connection(done)
        ResponsesModel(config, { conn }).open(listOf(row)).next(emptyList()) {}
        val body = JSONObject(conn.sent.toString("UTF-8"))
        assertEquals("other", body.getJSONArray("models").getString(1))
        assertTrue(body.getJSONObject("provider").getBoolean("allow_fallbacks"))
        assertEquals("fallback", body.getString("route"))
        assertEquals("auto-router", body.getJSONArray("plugins").getJSONObject(0).getString("id"))
    }

    @Test fun cancellationNeverConnects() {
        val cap = grant()
        val plan = freeze(reference(), cap)
        var connections = 0
        val cancelled = ResponsesModel(settings, { connections++; Connection(done) }, null, videoInputCapability = cap, videoPostApprovalProvider = postApproval)
            .open(listOf(row), emptyList(), plan)
        cancelled.cancel()
        assertTrue(rejected { cancelled.next(emptyList()) {} } is InterruptedException)
        assertEquals(0, connections)
    }

    @Test fun truncatedStreamCannotEmitCommittedProtocolHistoryOrReplay() {
        val cap = grant()
        val plan = freeze(reference(), cap)
        val conn = Connection("data: {\"type\":\"response.output_text.delta\",\"item_id\":\"i\",\"content_index\":0,\"delta\":\"partial\"}\n\n")
        val session = ResponsesModel(settings, { conn }, null, videoInputCapability = cap, videoPostApprovalProvider = postApproval).open(listOf(row), emptyList(), plan)
        val events = mutableListOf<ModelEvent>()
        val failure = rejected { session.next(emptyList(), events::add) }
        assertEquals(ModelFailure.INCOMPLETE, (failure as ModelRequestException).failure)
        assertTrue(events.any { it is ModelEvent.TextFinal && it.text == "partial" })
        assertFalse(events.any { it is ModelEvent.ProtocolOutput })
        rejected { session.next(emptyList()) {} }
        assertTrue(conn.disconnected)
    }

    @Test fun toolContinuationKeepsVideoAndEncryptedReasoningWithoutRefreezing() {
        val cap = grant()
        val config = settings.copy(requestOptions = ProviderRequestOptions(
            """{"temperature":0.25,"top_p":0.9,"max_output_tokens":512}"""))
        val ref = reference()
        val plan = freeze(ref, cap, config = config)
        assertTrue(store.discardUnreferenced(owner, ref))
        val call = JSONObject().put("type", "function_call").put("id", "fc").put("call_id", "call")
            .put("name", "lookup").put("arguments", "{}")
        val reasoning = JSONObject().put("type", "reasoning").put("id", "reasoning")
            .put("encrypted_content", "opaque-fixture").put("summary", JSONArray())
        val terminal = JSONObject().put("type", "response.completed").put("response", JSONObject()
            .put("status", "completed").put("output", JSONArray().put(reasoning).put(call)))
        val first = Connection("data: ${JSONObject().put("type", "response.output_item.done").put("item", call)}\n\n" +
            "data: $terminal\n\n")
        val second = Connection(done)
        var connects = 0
        val session = ResponsesModel(config, { if (connects++ == 0) first else second }, null, videoInputCapability = cap, videoPostApprovalProvider = postApproval)
            .open(listOf(row), emptyList(), plan)
        session.next(emptyList()) {}
        // Routing-looking JSON inside a tool's plain-text result is data, not request options.
        val toolResult = """{"models":["not-a-recipient"],"provider":{"route":"text only"}}"""
        session.next(listOf(ToolOutput(ToolCallId("call"), toolResult))) {}
        for (connection in listOf(first, second)) {
            val body = JSONObject(connection.sent.toString("UTF-8"))
            assertEquals(settings.model, body.getString("model"))
            assertEquals(0.25, body.getDouble("temperature"), 0.0)
            assertEquals(0.9, body.getDouble("top_p"), 0.0)
            assertEquals(512, body.getInt("max_output_tokens"))
            assertEquals(setOf("model", "input", "stream", "store", "include", "temperature", "top_p", "max_output_tokens", "reasoning"),
                body.keys().asSequence().toSet())
            assertEquals("none", body.getJSONObject("reasoning").getString("effort"))
        }
        val firstInput = JSONObject(first.sent.toString("UTF-8")).getJSONArray("input")
        val nextInput = JSONObject(second.sent.toString("UTF-8")).getJSONArray("input")
        assertEquals(firstInput.getJSONObject(0).toString(), nextInput.getJSONObject(0).toString())
        assertEquals("opaque-fixture", nextInput.getJSONObject(1).getString("encrypted_content"))
        assertEquals("function_call_output", nextInput.getJSONObject(3).getString("type"))
        assertEquals(toolResult, nextInput.getJSONObject(3).getString("output"))
        assertEquals(2, connects)
    }

    @Test fun wholeRequestBudgetRemainsInForce() {
        val request = JSONObject().put("input", JSONArray().put("x".repeat(ImageRequestPreflight.MAX_REQUEST_BYTES)))
        // No connection or provider inference: exercise the shared final serialized-byte limit.
        val result = rejected { ImageRequestPreflight.bytes(request, settings, null) { false } }
        assertEquals(app.daiyu.android.media.ImageAttachmentFailure.TOO_LARGE,
            (result as app.daiyu.android.media.ImageAttachmentException).failure)
    }
}
