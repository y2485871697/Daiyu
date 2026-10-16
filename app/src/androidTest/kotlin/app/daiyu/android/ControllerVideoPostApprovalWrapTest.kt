package app.daiyu.android

import android.content.ContextWrapper
import android.test.InstrumentationTestCase
import app.daiyu.android.domain.*
import app.daiyu.android.media.MessageImageAssetStore
import app.daiyu.android.media.video.*
import app.daiyu.android.model.ProviderSnapshot
import app.daiyu.android.model.ResponsesModel
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
            // A preflight identity has not been registered and cannot authorize transport.
            val premature = VideoPostRequest(settings, checkNotNull(baseline.videos), baseline.history, Any(), 1, byteArrayOf(1))
            assertNull(runBlocking { approval.approve(premature) })
            val requests = mutableListOf<VideoPostRequest>()
            var connections = 0
            val wrapped = videos.wrap(provider, capability, conversation, assistant.id, baseline, null, { false },
                postApproval = approval, modelFactory = { p, _, c, a ->
                    assertSame(provider, p); assertSame(capability, c); assertSame(approval, a)
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
                val session = open()
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

    private class Connection(private val events: String) : HttpsURLConnection(URL(OpenRouterVideoCapability.ENDPOINT)) {
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
