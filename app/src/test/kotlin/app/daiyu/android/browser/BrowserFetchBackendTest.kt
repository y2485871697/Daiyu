package app.daiyu.android.browser

import app.daiyu.android.browser.bridge.BrowserCommand
import app.daiyu.android.browser.bridge.BrowserExecutionResult
import app.daiyu.android.browser.bridge.BrowserHostBridge
import app.daiyu.android.browser.download.BrowserDownloadCoordinator
import app.daiyu.android.browser.download.BrowserPathMapper
import app.daiyu.android.browser.network.BrowserBackendRouter
import app.daiyu.android.browser.network.BrowserFetchBackend
import app.daiyu.android.browser.network.BrowserHttpDownloader
import app.daiyu.android.browser.runtime.AsyncBrowserBackend
import app.daiyu.android.browser.runtime.BrowserQueuedExecutionPort
import app.daiyu.android.browser.runtime.UiDispatcher
import app.daiyu.android.browser.session.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class BrowserFetchBackendTest {
    private class Fixture : AutoCloseable {
        val root = Files.createTempDirectory("browser-fetch-test")
        val owner = BrowserOwner("fetch-test", BrowserOwnerKind.MAIN)
        val sessions = BrowserSessionRegistry().apply { open(BrowserGrant(owner, BrowserAccess.FULL)) }
        val lease = sessions.newTab(owner)
        val coordinator = BrowserDownloadCoordinator(sessions, BrowserPathMapper(root))
        fun command(url: String = "https://safe.test/file", lease: BrowserTabLease = this.lease) =
            BrowserCommand("fetch", lease.tabId, mapOf("url" to url))
        override fun close() { sessions.close(owner); root.toFile().deleteRecursively() }
    }

    private class Server(private val respond: (Socket) -> Unit) : AutoCloseable {
        private val server = ServerSocket(0)
        private val sockets = Collections.synchronizedList(mutableListOf<Socket>())
        val requests = LinkedBlockingQueue<String>()
        val url get() = "http://127.0.0.1:${server.localPort}/file?secret=query"
        init {
            thread(name = "fetch-test-http-accept", isDaemon = true) {
                while (!server.isClosed) {
                    val socket = try { server.accept() } catch (_: IOException) { break }
                    sockets.add(socket)
                    thread(name = "fetch-test-http-response", isDaemon = true) {
                        try {
                            socket.use {
                                socket.soTimeout = 3000
                                val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                                val line = reader.readLine() ?: return@thread
                                while (true) { if (reader.readLine().isNullOrEmpty()) break }
                                requests.add(line)
                                respond(socket)
                            }
                        } catch (_: IOException) { /* client cancellation */ }
                    }
                }
            }
        }
        override fun close() {
            server.close()
            synchronized(sockets) { sockets.forEach { it.close() } }
        }
    }

    private fun respond(socket: Socket, body: String = "private fetched bytes", status: String = "200 OK") {
        val bytes = body.toByteArray(Charsets.UTF_8)
        socket.getOutputStream().apply {
            write("HTTP/1.1 $status\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(bytes)
            flush()
        }
    }

    private fun <T> call(block: () -> T): CompletableFuture<T> = CompletableFuture<T>().also { future ->
        thread(name = "fetch-test-caller", isDaemon = true) {
            try { future.complete(block()) } catch (e: Throwable) { future.completeExceptionally(e) }
        }
    }
    private fun <T> await(future: CompletableFuture<T>): T = future.get(5, TimeUnit.SECONDS)
    private fun failure(future: CompletableFuture<*>): Throwable {
        try { await(future); fail("expected exceptional completion") }
        catch (e: ExecutionException) { return e.cause!! }
        error("unreachable")
    }
    private fun rejects(block: () -> Unit) {
        try { block(); fail("expected rejection") } catch (_: IllegalArgumentException) { }
        catch (_: IllegalStateException) { }
    }
    private fun eventually(block: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!block()) {
            check(System.nanoTime() < deadline) { "condition did not become true" }
            Thread.sleep(10)
        }
    }
    private fun empty(f: Fixture): Boolean = Files.list(f.root).use { it.count() == 0L }

    private open class Connection(uri: URI, private val connectCount: AtomicInteger = AtomicInteger()) : HttpURLConnection(uri.toURL()) {
        override fun connect() { connectCount.incrementAndGet() }
        override fun getResponseCode() = 200
        override fun getHeaderField(name: String): String? = if (name.equals("Content-Length", true)) "3" else null
        override fun getInputStream() = ByteArrayInputStream("abc".toByteArray())
        override fun usingProxy() = false
        override fun disconnect() { }
    }

    @Test fun trustedRouterAndQueuedPortPerformRealGetReturningOnlyReceiptMetadata() {
        Fixture().use { f ->
            Server { respond(it, "abc") }.use { server ->
                BrowserFetchBackend(f.sessions, BrowserHttpDownloader(f.sessions, f.coordinator)).use { fetch ->
                    val webCalls = AtomicInteger()
                    val web = object : AsyncBrowserBackend {
                        override fun execute(command: BrowserCommand, lease: BrowserTabLease): CompletableFuture<BrowserExecutionResult> {
                            webCalls.incrementAndGet()
                            return CompletableFuture.completedFuture(BrowserExecutionResult("web"))
                        }
                        override fun cancel(lease: BrowserTabLease) { }
                    }
                    val queue = LinkedBlockingQueue<() -> Unit>()
                    val uiThread = Thread.currentThread()
                    val ui = object : UiDispatcher {
                        override fun isUiThread() = Thread.currentThread() === uiThread
                        override fun post(task: () -> Unit) { queue.add(task) }
                    }
                    val router = BrowserBackendRouter(fetch, web)
                    val bridge = BrowserHostBridge(f.sessions, BrowserQueuedExecutionPort(f.sessions, ui, router))
                    val pending = call { bridge.execute(f.owner, f.command(server.url)) }
                    checkNotNull(queue.poll(3, TimeUnit.SECONDS)).invoke()
                    val result = await(pending)
                    val path = java.nio.file.Paths.get(checkNotNull(result.filePath))
                    assertEquals(f.root.toRealPath(), path.parent)
                    assertEquals("abc", String(Files.readAllBytes(path)))
                    assertEquals(
                        "{\"path\":${org.json.JSONObject.quote(path.toString())},\"size\":3,\"sha256\":\"ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad\"}",
                        result.text,
                    )
                    assertFalse(result.text.contains("secret"))
                    assertEquals(0, webCalls.get())
                    assertTrue(server.requests.poll(1, TimeUnit.SECONDS)!!.startsWith("GET /file?secret=query "))
                    for (action in listOf("navigate", "get_text", "get_cookies", "set_cookies")) {
                        assertEquals("web", await(router.execute(BrowserCommand(action, f.lease.tabId), f.lease)).text)
                    }
                    assertEquals(4, webCalls.get())
                }
            }
        }
    }

    @Test fun executeUnderAdmissionLockReturnsWhileRealHttpIsStillWaiting() {
        val release = CountDownLatch(1)
        Fixture().use { f ->
            Server { release.await(5, TimeUnit.SECONDS); respond(it) }.use { server ->
                BrowserFetchBackend(f.sessions, BrowserHttpDownloader(f.sessions, f.coordinator)).use { backend ->
                    try {
                        val admittingThread = call {
                            f.sessions.publish(f.lease) { backend.execute(f.command(server.url), f.lease) }
                        }
                        val pending = admittingThread.get(1, TimeUnit.SECONDS)
                        assertNotNull(server.requests.poll(2, TimeUnit.SECONDS))
                        assertFalse(pending.isDone)
                        // Another registry admission must not wait for the ongoing network request.
                        assertEquals(f.lease, call { f.sessions.lease(f.owner, f.lease.tabId) }.get(1, TimeUnit.SECONDS))
                        release.countDown()
                        assertNotNull(await(pending).filePath)
                    } finally { release.countDown() }
                }
            }
        }
    }

    @Test fun realHttpFailureIsSanitizedAndNeverRetriedOrSentToWebview() {
        Fixture().use { f ->
            Server { respond(it, "cookie=private response", "503 Unavailable") }.use { server ->
                BrowserFetchBackend(f.sessions, BrowserHttpDownloader(f.sessions, f.coordinator)).use { backend ->
                    val problem = failure(backend.execute(f.command(server.url), f.lease))
                    assertEquals("BROWSER_FETCH_FAILED", problem.message)
                    assertNull(problem.cause)
                    assertNotNull(server.requests.poll(1, TimeUnit.SECONDS))
                    assertNull(server.requests.poll(200, TimeUnit.MILLISECONDS))
                    eventually { empty(f) }
                }
            }
        }
    }

    @Test fun closeAndInvalidationFenceRealHttpCompletionWithoutWaiting() {
        for (invalidate in listOf(false, true)) {
            val release = CountDownLatch(1)
            Fixture().use { f ->
                Server { release.await(5, TimeUnit.SECONDS); respond(it) }.use { server ->
                    BrowserFetchBackend(f.sessions, BrowserHttpDownloader(f.sessions, f.coordinator)).use { backend ->
                        try {
                            val pending = backend.execute(f.command(server.url), f.lease)
                            assertNotNull(server.requests.poll(2, TimeUnit.SECONDS))
                            call {
                                if (invalidate) f.sessions.closeTab(f.lease) else backend.close()
                            }.get(1, TimeUnit.SECONDS)
                            release.countDown()
                            assertTrue(failure(pending).message!!.startsWith("BROWSER_FETCH_"))
                            eventually { empty(f) }
                            if (!invalidate) rejects { backend.execute(f.command(server.url), f.lease) }
                        } finally { release.countDown() }
                    }
                }
            }
        }
    }

    @Test fun cancellationCannotFreeSlotUntilActualCallerExitsAndQueueIsBounded() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        Fixture().use { f ->
            val connects = AtomicInteger()
            val downloader = BrowserHttpDownloader(f.sessions, f.coordinator, cookieForOrigin = {
                entered.countDown()
                // Deliberately hostile trusted callback: ignore interrupt, like a stuck native call.
                var waiting = true
                while (waiting) {
                    try { release.await(); waiting = false } catch (_: InterruptedException) { }
                }
                null
            }, connectionFactory = { Connection(it, connects) })
            BrowserFetchBackend(f.sessions, downloader, maxWorkers = 1, maxQueued = 1).use { backend ->
                try {
                    val first = backend.execute(f.command(), f.lease)
                    assertTrue(entered.await(2, TimeUnit.SECONDS))
                    val queued = backend.execute(f.command(), f.lease)
                    backend.cancel(f.lease)
                    assertFalse(first.isDone)
                    assertFalse(queued.isDone)
                    rejects { backend.execute(f.command(), f.lease) }
                    // Future cancellation is not proof of caller exit, either.
                    assertTrue(first.cancel(true))
                    rejects { backend.execute(f.command(), f.lease) }
                    call { backend.close() }.get(1, TimeUnit.SECONDS)
                    rejects { backend.execute(f.command(), f.lease) }
                    release.countDown()
                    failure(queued)
                    assertEquals(0, connects.get())
                    eventually { empty(f) }
                } finally { release.countDown() }
            }
        }
    }

    @Test fun delayedOldLeaseCancellationDoesNotAffectReplacementOrOtherOwner() {
        Fixture().use { f ->
            f.sessions.closeTab(f.lease)
            val replacement = f.sessions.newTab(f.owner)
            val otherOwner = BrowserOwner("other", BrowserOwnerKind.CHILD)
            f.sessions.open(BrowserGrant(otherOwner, BrowserAccess.FULL))
            val other = f.sessions.newTab(otherOwner)
            val entered = CountDownLatch(2)
            val release = CountDownLatch(1)
            val downloader = BrowserHttpDownloader(f.sessions, f.coordinator, cookieForOrigin = {
                entered.countDown()
                check(release.await(3, TimeUnit.SECONDS))
                "trusted=private"
            }, connectionFactory = { Connection(it) })
            BrowserFetchBackend(f.sessions, downloader, maxWorkers = 2).use { backend ->
                try {
                    val replacementResult = backend.execute(f.command(lease = replacement), replacement)
                    val otherResult = backend.execute(f.command(lease = other), other)
                    assertTrue(entered.await(2, TimeUnit.SECONDS))
                    backend.cancel(f.lease)
                    release.countDown()
                    assertNotNull(await(replacementResult).filePath)
                    assertNotNull(await(otherResult).filePath)
                    assertFalse(await(otherResult).text.contains("trusted"))
                } finally { release.countDown() }
            }
            f.sessions.close(otherOwner)
        }
    }

    @Test fun invalidationFencesResultEvenBeforeItsCancellationCallbackRuns() {
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val releaseResponse = CountDownLatch(1)
        Fixture().use { f ->
            val earlyListener = f.sessions.onInvalidated(f.lease) {
                callbackEntered.countDown()
                check(releaseCallback.await(5, TimeUnit.SECONDS))
            }
            Server { releaseResponse.await(5, TimeUnit.SECONDS); respond(it) }.use { server ->
                BrowserFetchBackend(f.sessions, BrowserHttpDownloader(f.sessions, f.coordinator)).use { backend ->
                    try {
                        val pending = backend.execute(f.command(server.url), f.lease)
                        assertNotNull(server.requests.poll(2, TimeUnit.SECONDS))
                        val invalidating = call { f.sessions.closeTab(f.lease) }
                        assertTrue(callbackEntered.await(2, TimeUnit.SECONDS))
                        releaseResponse.countDown()
                        failure(pending)
                        eventually { empty(f) }
                        releaseCallback.countDown()
                        await(invalidating)
                    } finally {
                        releaseCallback.countDown()
                        releaseResponse.countDown()
                        earlyListener.close()
                    }
                }
            }
        }
    }

    @Test fun directBackendRejectsNonFetchExtraArgumentsAndReadOnlyLease() {
        Fixture().use { f ->
            val connects = AtomicInteger()
            BrowserFetchBackend(f.sessions, BrowserHttpDownloader(f.sessions, f.coordinator,
                connectionFactory = { Connection(it, connects) })).use { backend ->
                rejects { backend.execute(BrowserCommand("get_text", f.lease.tabId), f.lease) }
                rejects { backend.execute(BrowserCommand("fetch", f.lease.tabId, mapOf("url" to "https://safe.test", "cookie" to "private")), f.lease) }
                rejects { backend.execute(BrowserCommand("fetch", f.lease.tabId, mapOf("url" to 42)), f.lease) }
                rejects { backend.execute(BrowserCommand("fetch", 2, mapOf("url" to "https://safe.test")), f.lease) }
                val reader = BrowserOwner("reader", BrowserOwnerKind.CHILD)
                f.sessions.open(BrowserGrant(reader, BrowserAccess.READ_ONLY))
                val lease = f.sessions.newTab(reader)
                failure(backend.execute(f.command(lease = lease), lease))
                assertEquals(0, connects.get())
                f.sessions.close(reader)
            }
        }
    }

    @Test fun routerForwardsWholeLeaseCancellationToWebBackend() {
        Fixture().use { f ->
            BrowserFetchBackend(f.sessions, BrowserHttpDownloader(f.sessions, f.coordinator)).use { backend ->
                val cancellations = CopyOnWriteArrayList<BrowserTabLease>()
                val web = object : AsyncBrowserBackend {
                    override fun execute(command: BrowserCommand, lease: BrowserTabLease) =
                        CompletableFuture.completedFuture(BrowserExecutionResult("web"))
                    override fun cancel(lease: BrowserTabLease) { cancellations.add(lease) }
                }
                BrowserBackendRouter(backend, web).cancel(f.lease)
                assertEquals(listOf(f.lease), cancellations)
            }
        }
    }
    @Test fun closeReceiptTracksRawWorkerAndNativeCleanupAfterCancelledFutureAndCallerExit() {
        Fixture().use { f ->
            val entered = CountDownLatch(1)
            val rawExit = CountDownLatch(1)
            val cleanupEntered = CountDownLatch(1)
            val cleanupExit = CountDownLatch(1)
            fun ignoreInterrupts(latch: CountDownLatch) {
                while (true) {
                    try { latch.await(); return } catch (_: InterruptedException) { }
                }
            }
            val downloader = BrowserHttpDownloader(f.sessions, f.coordinator, connectionFactory = {
                object : Connection(it) {
                    override fun connect() { entered.countDown(); ignoreInterrupts(rawExit) }
                    override fun disconnect() { cleanupEntered.countDown(); ignoreInterrupts(cleanupExit) }
                }
            })
            BrowserFetchBackend(f.sessions, downloader).use { backend ->
                try {
                    val future = backend.execute(f.command(), f.lease)
                    assertTrue(entered.await(2, TimeUnit.SECONDS))
                    future.cancel(true)
                    assertTrue(future.isDone)
                    val closing = call { backend.closeWithResult() }.get(1, TimeUnit.SECONDS)
                    assertFalse(closing.drained)
                    assertTrue(cleanupEntered.await(2, TimeUnit.SECONDS))
                    eventually { "FETCH_CALLER_OR_FILES" !in backend.closeStatus().pending }
                    assertEquals(setOf("HTTP_RAW_IO_OR_CLEANUP"), backend.closeStatus().pending)
                    rawExit.countDown()
                    assertFalse(backend.closeStatus().drained) // native disconnect still blocked
                    cleanupExit.countDown()
                    eventually { backend.closeStatus().drained }
                    assertTrue(backend.closeStatus().successful)
                    assertTrue(empty(f))
                } finally { rawExit.countDown(); cleanupExit.countDown() }
            }
        }
    }

    @Test fun nativeCleanupFailureSurvivesDrainAndRepeatedCloseWithoutReplay() {
        Fixture().use { f ->
            val destroys = AtomicInteger()
            val downloader = BrowserHttpDownloader(f.sessions, f.coordinator, connectionFactory = {
                object : Connection(it) {
                    override fun disconnect() { destroys.incrementAndGet(); error("private-url-must-not-leak") }
                }
            })
            BrowserFetchBackend(f.sessions, downloader).use { backend ->
                assertNotNull(await(backend.execute(f.command(), f.lease)).filePath)
                backend.closeWithResult()
                eventually { backend.closeStatus().drained }
                assertFalse(backend.closeStatus().successful)
                assertEquals(setOf("HTTP_NATIVE_CLEANUP"), backend.closeStatus().failures)
                backend.closeWithResult()
                assertEquals(1, destroys.get())
            }
        }
    }
}
