package app.daiyu.android

import app.daiyu.android.domain.*
import app.daiyu.android.media.FrozenImageAttachment
import app.daiyu.android.media.MessageImageResolver
import app.daiyu.android.media.video.*
import app.daiyu.android.model.ProviderSnapshot
import app.daiyu.android.model.ResponsesModel
import app.daiyu.android.model.images.FrozenMessageImagePlan
import app.daiyu.android.model.videos.*
import app.daiyu.android.storage.ModelSettings
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.ByteArrayOutputStream
import java.net.URL
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection
import org.json.JSONObject
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Synthetic originals and authority, real request/plan/one-shot handoff; no UI, HTTP or decoding. */
class ControllerVideoPostApprovalTest {
    private val main = Thread.currentThread()
    private val settings = ModelSettings(OpenRouterVideoCapability.ENDPOINT, "fixture/model", "private-key", false)
    private val provider = ProviderSnapshot.capture(settings)
    private val run = Any()
    private val rows = listOf("u1", "u2").map { id ->
        Message(MessageId(id), ConversationId("c"), RunId("r"), Message.USER_ROLE,
            listOf(MessageBlock.Text(id, "describe")), RunStatus.COMPLETED)
    }
    private lateinit var root: File
    private lateinit var store: VideoAssetStore
    private lateinit var inputs: List<MessageVideoInput>
    private lateinit var actual: ControllerVideoModelPlan
    private lateinit var host: Host
    private lateinit var adapter: ControllerVideoPostApproval
    private val workers = mutableListOf<Waiter>()
    private val capability = OpenRouterVideoCapability.grant(settings.model, settings.requestEndpoint(), true, true)

    private val noImages = object : MessageImageResolver {
        override fun read(owner: app.daiyu.android.media.MessageImageOwner,
            reference: app.daiyu.android.media.MessageImageReference): ByteArray = error("no images")
        override fun freeze(owner: app.daiyu.android.media.MessageImageOwner,
            references: List<app.daiyu.android.media.MessageImageReference>, imageInputSupported: Boolean,
            isCancelled: () -> Boolean): List<FrozenImageAttachment> = error("no images")
    }

    @Before fun setup() {
        root = Files.createTempDirectory("controller-post").toFile()
        store = VideoAssetStore(root, VideoAttachmentLimits(), VideoInspector { _, _, _ ->
            VideoMetadata("video/mp4", 1000, 32, 24)
        })
        inputs = rows.mapIndexed { index, row ->
            val owner = MessageVideoOwner("c", row.id.value)
            MessageVideoInput(owner, List(if (index == 0) 2 else 1) { number ->
                store.prepare(owner, store.import(VideoAttachmentOwner("c", "d$index-$number"),
                    byteArrayOf(index.toByte(), number.toByte(), 7).inputStream()))
            })
        }.reversed() // Storage order is NOT the final wire order.
        actual = freeze()
        host = Host(authority())
        adapter = ControllerVideoPostApproval(host, authority())
        adapter.registerFrozenPlan(actual)
    }
    @After fun cleanup() {
        adapter.revoke()
        workers.forEach { it.thread.interrupt(); it.thread.join(3000) }
        root.deleteRecursively()
    }

    private fun freeze(): ControllerVideoModelPlan = ControllerVideoModelPlan(rows,
        FrozenMessageVideoPlan.freeze(rows, provider, capability, inputs, store,
            { MessageVideoOwner(it.conversationId.value, it.id.value) }),
        FrozenMessageImagePlan.freeze(rows, provider, noImages, { error("no images") }), inputs)

    private fun authority(runIdentity: Any = run, runEpoch: Long = 5, configEpoch: Long = 9,
        config: ModelSettings = settings, history: List<Message> = rows,
        refs: List<MessageVideoInput> = inputs) =
        ControllerVideoPostAuthority(runIdentity, runEpoch, configEpoch, config, history, refs)

    private inner class Host(var current: ControllerVideoPostAuthority?) : ControllerVideoPostApprovalHost {
        val queue = LinkedBlockingQueue<() -> Unit>()
        val dialogs = mutableListOf<Pair<ControllerVideoPostPrompt, (Boolean) -> Unit>>()
        val cancellations = AtomicInteger()
        var reads = 0
        var snapshot: () -> ControllerVideoPostAuthority? = { current }
        override fun postToMain(action: () -> Unit) { queue.put(action) }
        override fun snapshotOnMain(): ControllerVideoPostAuthority? {
            assertSame(main, Thread.currentThread()); reads++; return snapshot()
        }
        override fun showOnMain(prompt: ControllerVideoPostPrompt, decision: (Boolean) -> Unit) {
            assertSame(main, Thread.currentThread()); dialogs += prompt to decision
        }
        override fun cancelRun() { cancellations.incrementAndGet() }
        fun pump() { assertSame(main, Thread.currentThread()); checkNotNull(queue.poll(3, TimeUnit.SECONDS))() }
    }

    private class Waiter(work: () -> VideoPostApproval?) {
        val result = AtomicReference<VideoPostApproval?>()
        val failure = AtomicReference<Throwable?>()
        val thread = Thread {
            try { result.set(work()) } catch (e: Throwable) { failure.set(e) }
        }.apply { start() }
        fun join(): VideoPostApproval? {
            thread.join(3000); assertFalse("approval must wake", thread.isAlive)
            failure.get()?.let { throw AssertionError("unexpected approval failure", it) }
            return result.get()
        }
    }
    private fun request(number: Int = 1, identity: Any = Any(), plan: ControllerVideoModelPlan = actual,
        config: ModelSettings = settings, payload: ByteArray = byteArrayOf(1, 2, 3)) =
        VideoPostRequest(config, checkNotNull(plan.videos), plan.history, identity, number, payload)
    private fun ask(request: VideoPostRequest) = Waiter { runBlocking { adapter.approve(request) } }.also(workers::add)
    private fun rejected(block: () -> Unit) { assertNotNull(runCatching(block).exceptionOrNull()) }
    private fun approve(request: VideoPostRequest): VideoPostApproval {
        val waiting = ask(request); host.pump(); host.dialogs.last().second(true)
        return checkNotNull(waiting.join())
    }

    @Test fun explicitMainPromptBindsExactPayloadCompleteOrderedRefsAndConsumesOnce() {
        val request = request(payload = byteArrayOf(6, 5, 4, 3))
        val waiting = ask(request)
        host.pump()
        assertTrue(waiting.thread.isAlive) // Displaying is not approval.
        val (prompt, decide) = host.dialogs.single()
        assertEquals(inputs.reversed().flatMap { it.references }, prompt.references)
        assertEquals(request.requestSha256, prompt.requestSha256)
        assertEquals(request.requestByteCount, prompt.requestByteCount)
        assertEquals(1, prompt.postNumber)
        assertFalse(prompt.disclosure.contains(settings.apiKey))
        decide(true)
        val handoff = checkNotNull(waiting.join())
        val reads = host.reads
        val consume = Waiter { request.consume(handoff); null }.also(workers::add)
        consume.join()
        assertEquals(reads, host.reads) // Worker never calls main/DB/run-controller at consumption.
        rejected { request.consume(handoff) }
        decide(true) // A duplicate callback cannot create another permit.
        assertEquals(0, host.cancellations.get())
    }

    @Test fun everyPostNeedsNewDialogAndOldHandoffCannotAuthorizeNextPost() {
        val identity = Any()
        val first = request(identity = identity)
        val firstPermit = approve(first)
        first.consume(firstPermit)
        val second = request(2, identity, payload = byteArrayOf(8, 9))
        val waiting = ask(second); host.pump()
        assertTrue(waiting.thread.isAlive)
        assertEquals(listOf(1, 2), host.dialogs.map { it.first.postNumber })
        host.dialogs[0].second(true)
        assertTrue(waiting.thread.isAlive)
        host.dialogs[1].second(true)
        val permit = checkNotNull(waiting.join())
        rejected { request(2, identity).consume(permit) }
        second.consume(permit)
        rejected { second.consume(firstPermit) }
    }

    @Test fun revokeAfterDialogBeforeConsumeInvalidatesPermitEvenWithRestoredSettings() {
        val request = request()
        val permit = approve(request)
        adapter.revoke()
        host.current = authority() // Same values cannot revive a retired run.
        rejected { request.consume(permit) }
        assertNull(ask(request(2, request.historyIdentity)).join())
        assertEquals(1, host.cancellations.get())
    }

    @Test fun actualNewPlanRetiresOldWaitAndStaleCallbackDoesNotApproveOrCancelNewRequest() {
        val old = ask(request()); host.pump()
        val oldDecision = host.dialogs.single().second
        val nextPlan = freeze()
        assertNotSame(actual.videos, nextPlan.videos)
        adapter.registerFrozenPlan(nextPlan)
        assertNull(old.join())
        assertNull(ask(request()).join()) // Equal-valued old plan is not the registered identity.
        val next = request(plan = nextPlan)
        val waiting = ask(next); host.pump()
        oldDecision(true)
        oldDecision(false)
        assertTrue(waiting.thread.isAlive)
        assertEquals(0, host.cancellations.get())
        host.dialogs.last().second(true)
        next.consume(checkNotNull(waiting.join()))
        rejected { adapter.registerFrozenPlan(actual) }
    }

    @Test fun concurrentApprovalAndReplayNumbersOrSessionIdentityFailClosed() {
        val identity = Any()
        val first = request(identity = identity)
        val waiting = ask(first); host.pump()
        assertNull(ask(request(2, identity)).join()) // No replacement of the pending dialog.
        host.dialogs.single().second(true)
        first.consume(checkNotNull(waiting.join()))
        assertNull(ask(request(1, identity)).join())
        assertNull(ask(request(3, identity)).join())
        assertNull(ask(request(2, Any())).join())
        assertNull(ask(request(2, identity, config = settings.copy(apiKey = "changed"))).join())
        val second = request(2, identity)
        second.consume(approve(second))
    }

    @Test fun authorityIsReadAgainOnPositiveActionAndRejectsEpochHistoryAndInventoryChanges() {
        val variants = listOf(
            authority(runIdentity = Any()), authority(runEpoch = 6), authority(configEpoch = 10),
            authority(config = settings.copy(apiKey = "changed")),
            authority(history = rows.map { it.copy(blocks = listOf(MessageBlock.Text("changed", "changed"))) }),
            authority(refs = inputs.map { MessageVideoInput(it.owner, it.references.reversed()) }),
            authority(refs = inputs.dropLast(1)),
        )
        variants.forEach { changed ->
            val localHost = Host(authority())
            val localAdapter = ControllerVideoPostApproval(localHost, authority())
            localAdapter.registerFrozenPlan(actual)
            val waiting = Waiter { runBlocking { localAdapter.approve(request()) } }.also(workers::add)
            localHost.pump()
            localHost.current = changed
            localHost.dialogs.single().second(true)
            assertNull(waiting.join())
            assertEquals(1, localHost.cancellations.get())
        }
    }

    @Test fun rejectionAndCoroutineCancellationWakeWaitingWorkerAndIgnoreLateCallbacks() {
        val waiting = ask(request()); host.pump()
        host.dialogs.single().second(false)
        assertNull(waiting.join())
        host.dialogs.single().second(true)
        assertEquals(1, host.cancellations.get())

        val localHost = Host(authority())
        val localAdapter = ControllerVideoPostApproval(localHost, authority())
        localAdapter.registerFrozenPlan(actual)
        val cancelled = Waiter { runBlocking { localAdapter.approve(request()) } }.also(workers::add)
        localHost.pump()
        cancelled.thread.interrupt()
        cancelled.thread.join(3000)
        assertFalse(cancelled.thread.isAlive)
        assertNotNull(cancelled.failure.get())
        localHost.dialogs.single().second(true)
        assertEquals(1, localHost.cancellations.get())
    }

    @Test fun expiredOriginalRequestCannotBeBoundByLatePositiveCallback() {
        val original = request()
        val waiting = ask(original); host.pump()
        original.invalidate()
        host.dialogs.single().second(true)
        assertNull(waiting.join())
        assertEquals(1, host.cancellations.get())
    }

    @Test fun realResponsesTransportAsksAndConsumesForEveryToolPost() {
        val requests = mutableListOf<VideoPostRequest>()
        val sent = mutableListOf<Connection>()
        val target = ResponsesModel(settings, {
            assertNotSame(main, Thread.currentThread())
            Connection(if (sent.isEmpty()) toolEvents else completedEvents).also(sent::add)
        }, null, videoInputCapability = capability, videoPostApprovalProvider = VideoPostApprovalProvider {
            requests += it
            adapter.approve(it)
        }).open(rows, emptyList(), checkNotNull(actual.videos), actual.images)
        val first = Waiter { target.next(emptyList()) {}; null }.also(workers::add)
        host.pump()
        assertTrue(sent.isEmpty())
        host.dialogs.last().second(true)
        first.join()
        assertEquals(1, sent.size)
        val second = Waiter {
            target.next(listOf(ToolOutput(ToolCallId("call"), "result"))) {}; null
        }.also(workers::add)
        host.pump()
        assertEquals(1, sent.size)
        host.dialogs.first().second(true)
        assertTrue(second.thread.isAlive)
        host.dialogs.last().second(true)
        second.join()
        assertEquals(2, sent.size)
        assertNotSame(requests[0], requests[1])
        assertSame(requests[0].historyIdentity, requests[1].historyIdentity)
        assertSame(actual.videos, requests[0].videoPlan)
        assertEquals(listOf(1, 2), requests.map { it.postNumber })
        assertNotEquals(requests[0].requestSha256, requests[1].requestSha256)
        sent.zip(requests).forEach { (connection, post) ->
            assertEquals(post.requestByteCount, connection.sent.size())
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(connection.sent.toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(post.requestSha256, hash)
            val input = JSONObject(connection.sent.toString("UTF-8")).getJSONArray("input")
            assertEquals("input_video", input.getJSONObject(0).getJSONArray("content").getJSONObject(1).getString("type"))
        }
    }

    @Test fun realTransportCannotConnectIfRevokedBetweenPositiveActionAndConsumption() {
        val connections = AtomicInteger()
        val approved = CountDownLatch(1)
        val release = CountDownLatch(1)
        val target = ResponsesModel(settings, {
            connections.incrementAndGet(); Connection(completedEvents)
        }, null, videoInputCapability = capability, videoPostApprovalProvider = VideoPostApprovalProvider {
            val handoff = adapter.approve(it)
            approved.countDown()
            check(release.await(3, TimeUnit.SECONDS))
            handoff
        }).open(rows, emptyList(), checkNotNull(actual.videos))
        val worker = Waiter { target.next(emptyList()) {}; null }.also(workers::add)
        try {
            host.pump(); host.dialogs.single().second(true)
            assertTrue(approved.await(3, TimeUnit.SECONDS))
            adapter.revoke()
        } finally { release.countDown() }
        worker.thread.join(3000)
        assertFalse(worker.thread.isAlive)
        assertNotNull(worker.failure.get())
        assertEquals(0, connections.get())
    }

    private val completedEvents = "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[]}}\n\n"
    private val toolEvents = "data: {\"type\":\"response.output_item.done\",\"item\":{\"type\":\"function_call\",\"id\":\"fc\",\"call_id\":\"call\",\"name\":\"lookup\",\"arguments\":\"{}\"}}\n\n" +
        "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[{\"type\":\"function_call\",\"id\":\"fc\",\"call_id\":\"call\",\"name\":\"lookup\",\"arguments\":\"{}\"}]}}\n\n"

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

    @Test fun revokeDoesNotWaitForMainDispatchOrBlockedAuthorityRead() {
        val beforeDispatch = ask(request())
        adapter.revoke()
        assertNull(beforeDispatch.join())
        host.pump() // An already queued action is harmless after revocation.
        assertTrue(host.dialogs.isEmpty())

        val localHost = Host(authority())
        val localAdapter = ControllerVideoPostApproval(localHost, authority())
        localAdapter.registerFrozenPlan(actual)
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val revoked = CountDownLatch(1)
        localHost.snapshot = {
            entered.countDown()
            assertTrue(released.await(3, TimeUnit.SECONDS))
            authority()
        }
        val waiting = Waiter { runBlocking { localAdapter.approve(request()) } }.also(workers::add)
        val revoker = Thread {
            try {
                check(entered.await(3, TimeUnit.SECONDS))
                localAdapter.revoke()
                revoked.countDown()
            } finally { released.countDown() }
        }.apply { start() }
        localHost.pump() // Simulated DB blocking on main, while revoke runs independently.
        revoker.join(3000)
        assertTrue(revoked.await(1, TimeUnit.SECONDS))
        assertNull(waiting.join())
        assertTrue(localHost.dialogs.isEmpty())
    }
}
