package app.daiyu.android.model.videos

import app.daiyu.android.domain.*
import app.daiyu.android.media.video.*
import app.daiyu.android.model.*
import app.daiyu.android.storage.ModelSettings
import kotlinx.coroutines.suspendCancellableCoroutine
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.resume

/** Transport-only synthetic fixtures. No real HTTP, media decoder, UI, or paid inference. */
class VideoPostApprovalTest {
    private lateinit var root: File
    private lateinit var plan: FrozenMessageVideoPlan
    private lateinit var capability: OpenRouterVideoCapability
    private val settings = ModelSettings(OpenRouterVideoCapability.ENDPOINT, "fixture/model", "synthetic", false)
    private val row = Message(MessageId("u"), ConversationId("c"), RunId("r"), "user",
        listOf(MessageBlock.Text("t", "describe")), RunStatus.COMPLETED)
    private val history = listOf(row)
    private val done = "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[]}}\n\n"
    private var connections = 0
    private val sent = mutableListOf<Connection>()

    @Before fun setup() {
        root = Files.createTempDirectory("video-post-approval").toFile()
        val store = VideoAssetStore(root, VideoAttachmentLimits(), VideoInspector { _, _, _ ->
            VideoMetadata("video/mp4", 1000, 32, 24)
        })
        val owner = MessageVideoOwner("c", "u")
        val ref = store.prepare(owner, store.import(VideoAttachmentOwner("c", "d"), byteArrayOf(1, 2, 3).inputStream()))
        capability = OpenRouterVideoCapability.grant(settings.model, settings.requestEndpoint(), true, true)
        plan = FrozenMessageVideoPlan.freeze(history, ProviderSnapshot.capture(settings), capability,
            listOf(MessageVideoInput(owner, listOf(ref))), store, { owner })
    }
    @After fun cleanup() { root.deleteRecursively() }

    private fun session(provider: VideoPostApprovalProvider? = null, toolFirst: Boolean = false): ModelSession =
        ResponsesModel(settings, {
            connections++
            Connection(if (toolFirst && connections == 1) toolTerminal() else done).also(sent::add)
        }, null, videoInputCapability = capability, videoPostApprovalProvider = provider)
            .open(history, emptyList(), plan)

    private fun rejected(action: () -> Unit): Throwable = runCatching(action).exceptionOrNull().also {
        assertNotNull("must refuse before connecting", it)
    }!!

    private fun toolTerminal(): String {
        val call = JSONObject().put("type", "function_call").put("id", "fc").put("call_id", "call")
            .put("name", "lookup").put("arguments", "{}")
        val terminal = JSONObject().put("type", "response.completed").put("response", JSONObject()
            .put("status", "completed").put("output", JSONArray().put(call)))
        return "data: ${JSONObject().put("type", "response.output_item.done").put("item", call)}\n\n" +
            "data: $terminal\n\n"
    }

    @Test fun missingDeniedAndFailedPermitNeverAcquireConnection() {
        rejected { session().next(emptyList()) {} }
        rejected { session(VideoPostApprovalProvider { null }).next(emptyList()) {} }
        rejected { session(VideoPostApprovalProvider { error("approval failed") }).next(emptyList()) {} }
        var consumes = 0
        rejected { session(VideoPostApprovalProvider { request ->
            request.bindPermitConsumption { consumes++; error("run permit expired") }
        }).next(emptyList()) {} }
        assertEquals(1, consumes)
        assertEquals(0, connections)
        assertTrue(sent.isEmpty())
    }

    @Test fun consumesAfterApprovalAndBeforeConnectionAndBindsFinalPayload() {
        var consumed = false
        lateinit var request: VideoPostRequest
        val target = ResponsesModel(settings, {
            assertTrue("consume must precede even the connection factory", consumed)
            connections++
            Connection(done).also(sent::add)
        }, null, videoInputCapability = capability, videoPostApprovalProvider = VideoPostApprovalProvider {
            request = it
            assertEquals(0, connections)
            assertFalse(consumed)
            assertSame(plan, it.videoPlan)
            assertEquals(history, it.history)
            assertEquals(1, it.postNumber)
            assertTrue(it.matchesConfiguration(settings))
            assertFalse(it.matchesConfiguration(settings.copy(apiKey = "other")))
            assertFalse(it.matchesConfiguration(settings.copy(model = "other")))
            assertFalse(it.toString().contains(settings.apiKey))
            it.bindPermitConsumption { consumed = true }
        }).open(history, emptyList(), plan)
        target.next(emptyList()) {}
        assertEquals(1, connections)
        val bytes = sent.single().sent.toByteArray()
        assertEquals(bytes.size, request.requestByteCount)
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals(hash, request.requestSha256)
        rejected { request.bindPermitConsumption {} }
        rejected { target.next(emptyList()) {} }
        assertEquals(1, connections)
    }

    @Test fun oldApprovalCannotAuthorizeToolContinuationEvenWithSameVideo() {
        var asks = 0
        var consumes = 0
        lateinit var first: VideoPostApproval
        val target = session(VideoPostApprovalProvider { request ->
            asks++
            if (asks == 1) request.bindPermitConsumption { consumes++ }.also { first = it } else first
        }, toolFirst = true)
        target.next(emptyList()) {}
        rejected { target.next(listOf(ToolOutput(ToolCallId("call"), "ok"))) {} }
        assertEquals(2, asks)
        assertEquals(1, consumes)
        assertEquals(1, connections)
    }

    @Test fun freshHandoffCannotReuseAlreadySpentHostPermit() {
        val spent = AtomicBoolean(false)
        val target = session(VideoPostApprovalProvider { request ->
            request.bindPermitConsumption { check(spent.compareAndSet(false, true)) }
        }, toolFirst = true)
        target.next(emptyList()) {}
        rejected { target.next(listOf(ToolOutput(ToolCallId("call"), "result"))) {} }
        assertEquals(1, connections)
    }

    @Test fun eachToolPostNeedsNewPermitAndIncludesOriginalAgain() {
        val requests = mutableListOf<VideoPostRequest>()
        var consumed = 0
        val target = session(VideoPostApprovalProvider { request ->
            requests += request
            val spent = AtomicBoolean(false) // Synthetic stand-in for a NEW host permit per dialog.
            request.bindPermitConsumption { check(spent.compareAndSet(false, true)); consumed++ }
        }, toolFirst = true)
        target.next(emptyList()) {}
        target.next(listOf(ToolOutput(ToolCallId("call"), "result"))) {}
        assertEquals(2, connections)
        assertEquals(2, consumed)
        assertEquals(listOf(1, 2), requests.map { it.postNumber })
        assertNotSame(requests[0], requests[1])
        assertSame(requests[0].historyIdentity, requests[1].historyIdentity)
        assertNotEquals(requests[0].requestSha256, requests[1].requestSha256)
        val inputs = sent.map { JSONObject(it.sent.toString("UTF-8")).getJSONArray("input") }
        assertEquals(inputs[0].getJSONObject(0).toString(), inputs[1].getJSONObject(0).toString())
        assertEquals("input_video", inputs[1].getJSONObject(0).getJSONArray("content").getJSONObject(1).getString("type"))
    }

    @Test fun identicalHistoryInAnotherSessionCannotReuseApproval() {
        lateinit var first: VideoPostApproval
        lateinit var firstIdentity: Any
        session(VideoPostApprovalProvider { request ->
            firstIdentity = request.historyIdentity
            request.bindPermitConsumption {}.also { first = it }
        }).next(emptyList()) {}
        rejected { session(VideoPostApprovalProvider { request ->
            assertNotSame(firstIdentity, request.historyIdentity)
            first
        }).next(emptyList()) {} }
        assertEquals(1, connections)
    }

    @Test fun cancelledBeforeNextDoesNotEvenAskForApproval() {
        var asks = 0
        val target = session(VideoPostApprovalProvider { asks++; it.bindPermitConsumption {} })
        target.cancel()
        assertTrue(rejected { target.next(emptyList()) {} } is InterruptedException)
        assertEquals(0, asks)
        assertEquals(0, connections)
    }

    @Test fun cancelSuspendedDialogInvalidatesAlreadyBoundAndLateCallbacks() {
        val waiting = CountDownLatch(1)
        val pending = AtomicReference<VideoPostRequest>()
        val continuation = AtomicReference<kotlinx.coroutines.CancellableContinuation<VideoPostApproval?>>()
        val approval = AtomicReference<VideoPostApproval>()
        var consumed = 0
        val target = session(VideoPostApprovalProvider { request ->
            pending.set(request)
            approval.set(request.bindPermitConsumption { consumed++ })
            suspendCancellableCoroutine { callback ->
                continuation.set(callback)
                waiting.countDown()
            }
        })
        val failure = AtomicReference<Throwable>()
        val worker = Thread {
            try { target.next(emptyList()) {} } catch (caught: Throwable) { failure.set(caught) }
        }
        worker.start()
        try {
            assertTrue(waiting.await(3, TimeUnit.SECONDS))
            target.cancel()
            worker.join(3000)
            assertFalse("cancel must unblock a suspended approval", worker.isAlive)
            assertTrue(failure.get() is InterruptedException)
            rejected { pending.get().bindPermitConsumption {} }
            // Simulate a dialog callback arriving after cancellation. It cannot reconnect.
            continuation.get().resume(approval.get())
            assertEquals(0, connections)
            assertEquals(0, consumed)
        } finally {
            target.cancel()
            worker.interrupt()
            worker.join(3000)
        }
    }

    @Test fun cancellationInsidePermitConsumptionStillPreventsConnection() {
        lateinit var target: ModelSession
        var consumed = 0
        target = session(VideoPostApprovalProvider { request ->
            request.bindPermitConsumption { consumed++; target.cancel() }
        })
        assertTrue(rejected { target.next(emptyList()) {} } is InterruptedException)
        assertEquals(1, consumed)
        assertEquals(0, connections)
    }

    @Test fun cancelReturnsWhilePermitConsumptionIsBlockedAndNeverConnectsAfterRelease() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val consumes = AtomicInteger()
        val target = session(VideoPostApprovalProvider { request ->
            request.bindPermitConsumption {
                consumes.incrementAndGet()
                entered.countDown()
                release.await() // Real host operation blocked on a DB/gate, not a suspended dialog.
            }
        })
        cancelWhileBlocked(target, entered, release)
        assertEquals(1, consumes.get())
        assertEquals(0, connections)
        assertTrue(sent.isEmpty()) // No connection, POST, output stream or body after release.
    }

    @Test fun cancelReturnsWhileConnectionFactoryIsBlockedAndDisconnectsLateResultWithoutPost() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val consumes = AtomicInteger()
        val lateConnection = Connection(done)
        val target = ResponsesModel(settings, {
            assertEquals("permit must be consumed before the factory", 1, consumes.get())
            connections++
            entered.countDown()
            release.await() // Factory is external and is not assumed cancellable.
            lateConnection.also(sent::add)
        }, null, videoInputCapability = capability, videoPostApprovalProvider = VideoPostApprovalProvider { request ->
            request.bindPermitConsumption { consumes.incrementAndGet() }
        }).open(history, emptyList(), plan)
        cancelWhileBlocked(target, entered, release)
        assertEquals(1, consumes.get())
        assertEquals(1, connections) // Factory entry is not a network POST.
        assertSame(lateConnection, sent.single())
        assertTrue("late unpublishable connection must be cleaned up", lateConnection.disconnected.get())
        assertEquals(0, lateConnection.postMethods.get())
        assertEquals(0, lateConnection.outputRequests.get())
        assertEquals(0, lateConnection.sent.size())
    }

    /** Cancellation must finish on another thread BEFORE releasing the actual blocking operation.
     * Joining next only after release deliberately does not treat cancel as a worker drain.
     */
    private fun cancelWhileBlocked(target: ModelSession, entered: CountDownLatch, release: CountDownLatch) {
        val failure = AtomicReference<Throwable>()
        val cancelFailure = AtomicReference<Throwable>()
        val cancelReturned = CountDownLatch(1)
        val worker = Thread {
            try { target.next(emptyList()) {} } catch (caught: Throwable) { failure.set(caught) }
        }
        val canceller = Thread {
            try { target.cancel() } catch (caught: Throwable) { cancelFailure.set(caught) }
            finally { cancelReturned.countDown() }
        }
        var stoppedAfterRelease = false
        worker.start()
        try {
            assertTrue("worker must reach the blocking operation", entered.await(3, TimeUnit.SECONDS))
            canceller.start()
            assertTrue("cancel must not wait for consume/connect to return", cancelReturned.await(3, TimeUnit.SECONDS))
            assertNull(cancelFailure.get())
            assertTrue("cancel signals cancellation rather than draining the blocked worker", worker.isAlive)
        } finally {
            release.countDown()
            worker.join(3000)
            stoppedAfterRelease = !worker.isAlive
            if (worker.isAlive) {
                worker.interrupt()
                worker.join(3000)
            }
            if (canceller.state != Thread.State.NEW) canceller.join(3000)
        }
        assertTrue("released worker must terminate without being interrupted by test cleanup", stoppedAfterRelease)
        assertFalse("released worker must terminate", worker.isAlive)
        assertFalse("cancel thread must terminate", canceller.isAlive)
        assertTrue("released operation must fail as cancellation", failure.get() is InterruptedException)
    }

    @Test fun hostInvalidationDuringDialogIsCheckedAtConsumption() {
        // The host run permit owns configuration/history/restore epochs; transport invokes its
        // consumption after the dialog, not at bind time. No stored boolean is substituted.
        for (reason in listOf("config", "history", "restore", "run")) {
            val current = AtomicBoolean(true)
            val target = session(VideoPostApprovalProvider { request ->
                val bound = request.bindPermitConsumption { check(current.get()) { reason } }
                current.set(false)
                bound
            })
            rejected { target.next(emptyList()) {} }
        }
        assertEquals(0, connections)
    }

    @Test fun textAndImageFreeCallsDoNotAskForVideoApproval() {
        var asks = 0
        val model = ResponsesModel(settings, {
            connections++; Connection(done).also(sent::add)
        }, null, videoPostApprovalProvider = VideoPostApprovalProvider { asks++; error("must not run") })
        model.open(history).next(emptyList()) {}
        assertEquals(0, asks)
        assertEquals(1, connections)
    }

    @Test fun ordinaryFrozenImageStillUsesImagePathWithoutVideoDialog() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val ref = MessageImageReference(MessageImageOwner("c", "u"),
            "00000000-0000-0000-0000-000000000001", "image/png", bytes.size, hash, 2, 2)
        val rows = listOf(row.copy(blocks = row.blocks + MessageBlock.Image(ref)))
        val resolver = object : app.daiyu.android.media.MessageImageResolver {
            override fun read(owner: app.daiyu.android.media.MessageImageOwner,
                              reference: app.daiyu.android.media.MessageImageReference): ByteArray = error("freeze only")
            override fun freeze(owner: app.daiyu.android.media.MessageImageOwner,
                                references: List<app.daiyu.android.media.MessageImageReference>,
                                imageInputSupported: Boolean, isCancelled: () -> Boolean) = references.map {
                app.daiyu.android.media.FrozenImageAttachment(it.asAttachment(), bytes)
            }
        }
        val encoder = app.daiyu.android.model.images.ImageInputEncoder(
            app.daiyu.android.media.ImageInspector { app.daiyu.android.media.ImageDimensions("image/png", 2, 2) })
        val images = app.daiyu.android.model.images.FrozenMessageImagePlan.freeze(rows,
            ProviderSnapshot.capture(settings, settings.protocol, true), resolver, { ref.owner }, encoder)
        var asks = 0
        val model = ResponsesModel(settings, { connections++; Connection(done).also(sent::add) }, true,
            videoPostApprovalProvider = VideoPostApprovalProvider { asks++; error("not a video request") })
        model.open(rows, emptyList(), images).next(emptyList()) {}
        assertEquals(0, asks)
        assertEquals(1, connections)
        val content = JSONObject(sent.single().sent.toString("UTF-8")).getJSONArray("input")
            .getJSONObject(0).getJSONArray("content")
        assertEquals("input_image", content.getJSONObject(1).getString("type"))
    }

    private class Connection(private val events: String) : HttpsURLConnection(URL(OpenRouterVideoCapability.ENDPOINT)) {
        val sent = ByteArrayOutputStream()
        val disconnected = AtomicBoolean(false)
        val postMethods = AtomicInteger()
        val outputRequests = AtomicInteger()
        override fun connect() {}
        override fun disconnect() { disconnected.set(true) }
        override fun setRequestMethod(method: String) {
            super.setRequestMethod(method)
            if (method == "POST") postMethods.incrementAndGet()
        }
        override fun usingProxy() = false
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getOutputStream(): ByteArrayOutputStream {
            outputRequests.incrementAndGet()
            return sent
        }
        override fun getInputStream() = events.byteInputStream()
        override fun getResponseCode() = 200
        override fun getContentType() = "text/event-stream"
    }
}
