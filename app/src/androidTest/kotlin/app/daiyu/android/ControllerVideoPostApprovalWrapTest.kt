package app.daiyu.android

import android.content.ContextWrapper
import android.test.InstrumentationTestCase
import app.daiyu.android.domain.*
import app.daiyu.android.media.MessageImageAssetStore
import app.daiyu.android.media.video.*
import app.daiyu.android.model.ProviderSnapshot
import app.daiyu.android.model.ResponsesModel
import app.daiyu.android.model.reconnect.FrozenResponsesBackground
import app.daiyu.android.model.reconnect.ReconnectSettings
import app.daiyu.android.model.sessiongateway.SessionGatewayContext
import app.daiyu.android.model.videos.*
import app.daiyu.android.storage.ConversationDatabase
import app.daiyu.android.storage.ModelSettings
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import java.security.cert.Certificate
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection

/** Real SQLite/wrap freezes with synthetic originals and fake HTTPS, not provider capability proof. */
@Suppress("DEPRECATION")
class ControllerVideoPostApprovalWrapTest : InstrumentationTestCase() {
    private var db: ConversationDatabase? = null
    private var root: File? = null
    private val completed = "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[]}}\n\n"

    override fun tearDown() {
        try {
            db?.close()
            root?.deleteRecursively()
        } finally { super.tearDown() }
    }

    fun testEveryActualOpenOverloadRegistersItsOwnPlanNotJustThePreflightPlan() {
        val token = UUID.randomUUID().toString()
        val base = testContext(instrumentation.targetContext, "video-post-wrap-$token-")
        val directory = File(base.cacheDir, "video-post-wrap-$token").apply { check(mkdirs()) }
        root = directory
        val context = object : ContextWrapper(base) { override fun getFilesDir(): File = directory }
        val database = ConversationDatabase(context).also { db = it }
        try {
            val conversation = database.create(database.selectedAssistantId()).id
            val user = Message(MessageId("user-$token"), conversation, RunId("run-$token"), Message.USER_ROLE,
                listOf(MessageBlock.Text("question", "describe")), RunStatus.COMPLETED)
            val assistant = user.copy(id = MessageId("assistant-$token"), role = Message.ASSISTANT_ROLE,
                status = RunStatus.RUNNING, blocks = emptyList())
            val store = VideoAssetStore(File(directory, "videos"), VideoAttachmentLimits(), VideoInspector { _, _, _ ->
                VideoMetadata("video/mp4", 1000, 32, 24)
            })
            val owner = MessageVideoOwner(conversation.value, user.id.value)
            val ref = store.prepare(owner, store.import(VideoAttachmentOwner(conversation.value, "draft"),
                byteArrayOf(3, 2, 1).inputStream()))
            val inputs = listOf(MessageVideoInput(owner, listOf(ref)))
            database.send(user, assistant, videoInputs = inputs)
            val settings = ModelSettings(OpenRouterVideoCapability.ENDPOINT, "fixture/model", "private-key", false)
            val provider = ProviderSnapshot.capture(settings)
            val capability = OpenRouterVideoCapability.grant(settings.model, settings.requestEndpoint(), true, true)
            val images = ControllerMessageImages(database, MessageImageAssetStore(File(directory, "images")))
            val videos = ControllerMessageVideos(database, store, images)
            val baseline = videos.preflight(videos.history.capture(conversation), provider, capability, cancelled = { false })
            val modelRows = listOf(user) // wrap omits the initial empty active assistant.
            val suppliedImages = images.freeze(modelRows, provider)
            val runIdentity = Any()
            fun authority() = ControllerVideoPostAuthority(runIdentity, 1, 2, settings, modelRows, inputs)
            val main = Thread.currentThread()
            val queue = LinkedBlockingQueue<() -> Unit>()
            val decisions = mutableListOf<(Boolean) -> Unit>()
            val host = object : ControllerVideoPostApprovalHost {
                override fun postToMain(action: () -> Unit) { queue.put(action) }
                override fun snapshotOnMain(): ControllerVideoPostAuthority {
                    assertSame(main, Thread.currentThread()); return authority()
                }
                override fun showOnMain(prompt: ControllerVideoPostPrompt, decision: (Boolean) -> Unit) {
                    assertSame(main, Thread.currentThread()); assertEquals(listOf(ref), prompt.references)
                    decisions += decision
                }
                override fun cancelRun() {}
            }
            val approval = ControllerVideoPostApproval(host, authority())
            // Exercise the DEFAULT four-argument factory without ever calling next/network.
            // Inspect only the constructed immutable model session; transport tests below retain
            // their original four-argument seam and approval checks.
            val defaultPort = videos.wrap(provider, capability, conversation, assistant.id, baseline,
                "frozen assistant instructions", { false }, postApproval = approval)
            val defaultSession = defaultPort.open(modelRows)
            assertTrue(defaultSession is RequestRetryModelSession)
            assertFalse(defaultSession is CheckpointedResumableModelSession)
            assertFalse((defaultSession as RequestRetryModelSession).canResume)
            val delegate = underlyingResponsesSession(defaultSession)
            val capturedContext = delegate.javaClass.getDeclaredField("sessionContext").apply { isAccessible = true }
                .get(delegate) as app.daiyu.android.model.sessiongateway.SessionGatewayContext
            assertEquals(conversation, capturedContext.conversationId); assertNull(capturedContext.project)
            assertEquals("frozen assistant instructions", delegate.javaClass.getDeclaredField("assistantInstructions")
                .apply { isAccessible = true }.get(delegate))
            defaultSession.cancel()
            // A preflight identity has not been registered and cannot authorize transport.
            val premature = VideoPostRequest(settings, checkNotNull(baseline.videos), baseline.history, Any(), 1, byteArrayOf(1))
            assertNull(runBlocking { approval.approve(premature) })
            val requests = mutableListOf<VideoPostRequest>()
            var connections = 0
            var published: ControllerVideoModelPlan? = null
            // Official background opt-in does not authorize the OpenRouter video endpoint.
            val background = FrozenResponsesBackground.freeze(ReconnectSettings(responsesBackgroundEnabled = true), provider)
            assertNull(background)
            val wrapped = videos.wrap(provider, capability, conversation, assistant.id, baseline, null, { false },
                postApproval = approval, onFrozenPlan = { published = it }, backgroundResume = background,
                modelFactory = { p, _, c, a ->
                    assertSame(provider, p); assertSame(capability, c); assertSame(approval, a)
                    assertNotNull(published) // Actual witness is published before the original factory.
                    ResponsesModel(settings, {
                        connections++
                        Connection(completed)
                    }, null, videoInputCapability = c, videoPostApprovalProvider = VideoPostApprovalProvider {
                        requests += it
                        checkNotNull(a).approve(it)
                    })
                })
            val openers: List<() -> ModelSession> = listOf(
                { wrapped.open(modelRows) },
                { wrapped.open(modelRows, emptySet<ReadOnlyTool>()) },
                { wrapped.open(modelRows, emptyList<ToolDefinition>()) },
                { wrapped.open(modelRows, emptyList<ToolDefinition>(), suppliedImages) },
            )
            openers.forEachIndexed { index, open ->
                published = null
                val session = open()
                assertTrue(session is RequestRetryModelSession)
                assertFalse(session is CheckpointedResumableModelSession)
                assertFalse((session as RequestRetryModelSession).canResume)
                val failure = AtomicReference<Throwable?>()
                val worker = Thread {
                    try { session.next(emptyList()) {} } catch (caught: Throwable) { failure.set(caught) }
                }.apply { start() }
                try {
                    checkNotNull(queue.poll(3, TimeUnit.SECONDS))()
                    assertEquals(index, connections)
                    decisions.last()(true)
                    worker.join(3000)
                    assertFalse(worker.isAlive)
                    failure.get()?.let { throw AssertionError("wrap transport failed", it) }
                    assertEquals(index + 1, connections)
                    assertNotSame(baseline.videos, requests.last().videoPlan)
                    assertSame(checkNotNull(published).videos, requests.last().videoPlan)
                    assertEquals(1, requests.last().postNumber)
                    assertEquals(modelRows, requests.last().history)
                } finally {
                    session.cancel(); worker.interrupt(); worker.join(3000)
                }
            }
            requests.forEachIndexed { index, request ->
                requests.take(index).forEach { previous ->
                    assertNotSame(previous.videoPlan, request.videoPlan)
                    assertNotSame(previous.historyIdentity, request.historyIdentity)
                }
            }
            assertEquals(4, decisions.size)
            approval.revoke()

            // Missing per-POST adapter fails at actual open, before the model factory/transport.
            val missing = videos.wrap(provider, capability, conversation, assistant.id, baseline, null, { false },
                modelFactory = { _, _, _, _ -> error("must not construct model") })
            val failure = runCatching { missing.open(modelRows) }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertEquals("video_post_approval_required", failure?.message)
        } finally {
            database.close(); db = null
            context.deleteDatabase("conversations.db")
            File(context.getDatabasePath("conversations.db").canonicalPath + ".runtime-owner").delete()
            context.getSharedPreferences("daiyu-assistants", 0).edit().clear().commit()
        }
    }

    fun testOfficialBackgroundConfiguresOriginalFactoryProductWithoutLosingContext() {
        // Text-only physical history exercises this host hook. It does NOT grant official video
        // support: the existing video capability remains restricted to OpenRouter.
        val token = UUID.randomUUID().toString()
        val base = testContext(instrumentation.targetContext, "background-wrap-$token-")
        val directory = File(base.cacheDir, "background-wrap-$token").apply { check(mkdirs()) }
        root = directory
        val context = object : ContextWrapper(base) { override fun getFilesDir(): File = directory }
        val database = ConversationDatabase(context).also { db = it }
        try {
            val conversation = database.create(database.selectedAssistantId()).id
            val user = Message(MessageId("user-$token"), conversation, RunId("run-$token"), Message.USER_ROLE,
                listOf(MessageBlock.Text("question", "describe")), RunStatus.COMPLETED)
            val assistant = user.copy(id = MessageId("assistant-$token"), role = Message.ASSISTANT_ROLE,
                status = RunStatus.RUNNING, blocks = emptyList())
            database.send(user, assistant)
            val settings = ModelSettings("https://api.openai.com/v1/responses", "fixture/model", "private-key", false)
            val provider = ProviderSnapshot.capture(settings)
            val background = checkNotNull(FrozenResponsesBackground.freeze(
                ReconnectSettings(responsesBackgroundEnabled = true), provider))
            val images = ControllerMessageImages(database, MessageImageAssetStore(File(directory, "images")))
            val assets = VideoAssetStore(File(directory, "videos"), VideoAttachmentLimits(), VideoInspector { _, _, _ ->
                error("No video inspection expected")
            })
            val videos = ControllerMessageVideos(database, assets, images)
            val baseline = videos.preflight(videos.history.capture(conversation), provider, null, cancelled = { false })
            val rows = listOf(user)
            val suppliedImages = images.freeze(rows, provider)
            val instructions = "frozen background instructions"
            val sessionContext = SessionGatewayContext(conversation)
            val defaultSession = videos.wrap(provider, null, conversation, assistant.id, baseline,
                instructions, { false }, backgroundResume = background).open(rows)
            try {
                assertTrue(defaultSession is CheckpointedResumableModelSession)
                val delegate = underlyingResponsesSession(defaultSession)
                val captured = delegate.javaClass.getDeclaredField("sessionContext")
                    .apply { isAccessible = true }.get(delegate) as SessionGatewayContext
                assertEquals(conversation, captured.conversationId)
                assertEquals(instructions, delegate.javaClass.getDeclaredField("assistantInstructions")
                    .apply { isAccessible = true }.get(delegate))
            } finally { defaultSession.cancel() } // No real network call.
            var published: ControllerVideoModelPlan? = null
            var factories = 0
            val connections = mutableListOf<Connection>()
            val port = videos.wrap(provider, null, conversation, assistant.id, baseline, instructions, { false },
                onFrozenPlan = { published = it }, backgroundResume = background,
                modelFactory = { actualProvider, actualInstructions, capability, approval ->
                    factories++
                    assertSame(provider, actualProvider); assertEquals(instructions, actualInstructions)
                    assertNull(capability); assertNull(approval); assertNotNull(published)
                    ResponsesModel(actualProvider.asModelSettings(), { url ->
                        val stream = "data: {\"type\":\"response.created\",\"sequence_number\":0,\"response\":{\"id\":\"resp_wrap\",\"status\":\"in_progress\"}}\n\n" +
                            "data: {\"type\":\"response.completed\",\"sequence_number\":1,\"response\":{\"id\":\"resp_wrap\",\"status\":\"completed\",\"output\":[]}}\n\n"
                        Connection(stream, url).also { connections += it }
                    }, actualProvider.imageInputSupported, actualInstructions, sessionContext = sessionContext)
                })
            val openers: List<() -> ModelSession> = listOf(
                { port.open(rows) }, { port.open(rows, emptySet<ReadOnlyTool>()) },
                { port.open(rows, emptyList<ToolDefinition>()) },
                { port.open(rows, emptyList<ToolDefinition>(), suppliedImages) },
            )
            openers.forEachIndexed { index, open ->
                published = null
                val session = open()
                try {
                    assertEquals(index + 1, factories); assertEquals(index, connections.size)
                    assertTrue(session is CheckpointedResumableModelSession)
                    val delegate = underlyingResponsesSession(session)
                    assertSame(sessionContext, delegate.javaClass.getDeclaredField("sessionContext")
                        .apply { isAccessible = true }.get(delegate))
                    assertEquals(instructions, delegate.javaClass.getDeclaredField("assistantInstructions")
                        .apply { isAccessible = true }.get(delegate))
                    val sequences = mutableListOf<Long>()
                    (session as CheckpointedResumableModelSession).nextAcknowledged(emptyList()) {
                        sequences += it.checkpoint.sequence; ModelDeliveryAck.ACCEPTED
                    }
                    assertEquals(listOf(0L, 1L), sequences)
                    assertEquals(index + 1, connections.size)
                    val connection = connections.last()
                    assertEquals(settings.requestEndpoint(), connection.url.toString())
                    assertEquals("POST", connection.requestMethod)
                    val body = org.json.JSONObject(connection.sent.toString("UTF-8"))
                    assertTrue(body.getBoolean("background")); assertTrue(body.getBoolean("stream"))
                    assertFalse(body.getBoolean("store")); assertEquals(instructions, body.getString("instructions"))
                } finally { session.cancel() }
            }
        } finally {
            database.close(); db = null
            context.deleteDatabase("conversations.db")
            File(context.getDatabasePath("conversations.db").canonicalPath + ".runtime-owner").delete()
            context.getSharedPreferences("daiyu-assistants", 0).edit().clear().commit()
        }
    }

    private fun underlyingResponsesSession(session: ModelSession): Any {
        var current: Any = session
        // Both ResponsesModel.exposed and the host guard may retain an exact session wrapper.
        while (current.javaClass.declaredFields.none { it.name == "sessionContext" }) {
            current = current.javaClass.declaredFields.single {
                ModelSession::class.java.isAssignableFrom(it.type)
            }.apply { isAccessible = true }.get(current)
        }
        return current
    }

    private class Connection(private val events: String, url: URL = URL(OpenRouterVideoCapability.ENDPOINT)) : HttpsURLConnection(url) {
        val sent = ByteArrayOutputStream()
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getOutputStream() = sent
        override fun getInputStream() = events.byteInputStream()
        override fun getResponseCode() = 200
        override fun getContentType() = "text/event-stream"
    }
}
