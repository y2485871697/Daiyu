package app.daiyu.android.browser

import app.daiyu.android.browser.download.BrowserDownloadCoordinator
import app.daiyu.android.browser.download.BrowserDownloadReceipt
import app.daiyu.android.browser.download.BrowserPathMapper
import app.daiyu.android.browser.network.BrowserHttpDownloader
import app.daiyu.android.browser.session.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.CookieHandler
import java.net.CookieManager
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.nio.file.Files
import java.util.Collections
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class BrowserHttpDownloaderTest {
    private class Fixture : AutoCloseable {
        val root = Files.createTempDirectory("browser-http-test")
        val owner = BrowserOwner("http-test", BrowserOwnerKind.MAIN)
        val sessions = BrowserSessionRegistry().apply { open(BrowserGrant(owner, BrowserAccess.FULL)) }
        val lease = sessions.newTab(owner)
        val downloads = BrowserDownloadCoordinator(sessions, BrowserPathMapper(root))
        override fun close() { sessions.close(owner); root.toFile().deleteRecursively() }
        fun assertEmpty() { Files.list(root).use { assertEquals(0L, it.count()) } }
    }

    private data class Request(val line: String, val headers: Map<String, String>)
    private class HttpServer(private val respond: (Request, Socket) -> Unit) : AutoCloseable {
        private val server = ServerSocket(0)
        private val sockets = Collections.synchronizedList(mutableListOf<Socket>())
        val requests = LinkedBlockingQueue<Request>()
        val url: String get() = "http://127.0.0.1:${server.localPort}"
        init {
            thread(name = "http-test-accept", isDaemon = true) {
                while (!server.isClosed) {
                    val socket = try { server.accept() } catch (_: IOException) { break }
                    sockets.add(socket)
                    thread(name = "http-test-response", isDaemon = true) {
                        try {
                            socket.soTimeout = 3000
                            socket.use {
                                val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                                val line = reader.readLine() ?: return@thread
                                val headers = mutableMapOf<String, String>()
                                while (true) {
                                    val header = reader.readLine() ?: break
                                    if (header.isEmpty()) break
                                    val index = header.indexOf(':')
                                    if (index > 0) headers[header.substring(0, index).lowercase(Locale.ROOT)] = header.substring(index + 1).trim()
                                }
                                val request = Request(line, headers)
                                requests.add(request)
                                respond(request, socket)
                            }
                        } catch (_: IOException) { /* expected when client aborts */ }
                    }
                }
            }
        }
        override fun close() {
            server.close()
            synchronized(sockets) { sockets.forEach { it.close() } }
        }
    }

    private fun response(socket: Socket, status: String = "200 OK", headers: String = "Content-Length: 3\r\n", body: String = "abc") {
        val bytes = "HTTP/1.1 $status\r\n${headers}Connection: close\r\n\r\n$body".toByteArray(Charsets.ISO_8859_1)
        socket.getOutputStream().apply { write(bytes); flush() }
    }
    private fun <T> start(block: () -> T): FutureTask<T> = FutureTask(Callable { block() }).also {
        thread(name = "http-test-worker", isDaemon = true) { it.run() }
    }
    private fun <T> worker(block: () -> T): T = start(block).get(5, TimeUnit.SECONDS)
    private fun failure(task: FutureTask<*>, timeoutMillis: Long = 5000): Throwable {
        try { task.get(timeoutMillis, TimeUnit.MILLISECONDS); fail("expected failure") }
        catch (e: ExecutionException) { return e.cause!! }
        error("unreachable")
    }
    private fun rejects(block: () -> Unit): Throwable = failure(start(block))

    private open class FakeConnection(
        uri: URI,
        private val status: Int = 200,
        private val headers: Map<String, String> = emptyMap(),
        val body: InputStream = ByteArrayInputStream("abc".toByteArray()),
        private val connectAction: () -> Unit = {},
        private val disconnectAction: () -> Unit = {},
    ) : HttpURLConnection(uri.toURL()) {
        val sent = mutableMapOf<String, String>()
        val disconnected = CountDownLatch(1)
        var connectedOn: Thread? = null
        override fun setRequestProperty(key: String, value: String) { sent[key.lowercase(Locale.ROOT)] = value }
        override fun connect() { connectedOn = Thread.currentThread(); connectAction(); connected = true }
        override fun getResponseCode(): Int = status
        override fun getHeaderField(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, true) }?.value
        override fun getInputStream(): InputStream = body
        override fun getErrorStream(): InputStream? = null
        override fun usingProxy(): Boolean = false
        override fun disconnect() { disconnected.countDown(); disconnectAction() }
    }

    @Test fun realGetAndRelativeRedirectReturnCoordinatorFileAndDigest() {
        Fixture().use { f ->
            HttpServer { request, socket ->
                if (request.line.startsWith("GET /start ")) response(socket, "302 Found", "Location: /final\r\nContent-Length: 0\r\n", "")
                else response(socket)
            }.use { server ->
                BrowserHttpDownloader(f.sessions, f.downloads).use { downloader ->
                    val receipt = worker { downloader.download(f.lease, "${server.url}/start") }
                    assertEquals(3L, receipt.size)
                    assertEquals("abc", String(Files.readAllBytes(receipt.path)))
                    assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", receipt.sha256)
                    assertEquals(f.root.toRealPath(), receipt.path.parent)
                    assertTrue(receipt.path.fileName.toString().endsWith(".download"))
                    assertTrue(server.requests.poll(1, TimeUnit.SECONDS)!!.line.startsWith("GET /start "))
                    assertTrue(server.requests.poll(1, TimeUnit.SECONDS)!!.line.startsWith("GET /final "))
                    Files.list(f.root).use { assertEquals(1L, it.count()) }
                }
            }
        }
    }

    @Test fun realCrossOriginDropsAllCallerHeadersAndUsesOnlyOriginCookies() {
        Fixture().use { f ->
            HttpServer { _, socket -> response(socket) }.use { target ->
                HttpServer { _, socket -> response(socket, "307 Temporary Redirect", "Location: ${target.url}/result\r\nContent-Length: 0\r\n", "") }.use { source ->
                    val origins = Collections.synchronizedList(mutableListOf<URI>())
                    BrowserHttpDownloader(f.sessions, f.downloads, cookieForOrigin = { origin ->
                        origins.add(origin)
                        if (origin.port == URI(source.url).port) "source=private" else "target=private"
                    }).use { downloader ->
                        val receipt = worker {
                            downloader.download(f.lease, "${source.url}/secret?q=not-an-origin", mapOf("Authorization" to "Bearer private", "X-Private" to "private"))
                        }
                        val first = source.requests.poll(1, TimeUnit.SECONDS)!!
                        val second = target.requests.poll(1, TimeUnit.SECONDS)!!
                        assertEquals("Bearer private", first.headers["authorization"])
                        assertEquals("source=private", first.headers["cookie"])
                        assertNull(second.headers["authorization"])
                        assertNull(second.headers["x-private"])
                        assertEquals("target=private", second.headers["cookie"])
                        assertEquals(listOf(URI(source.url), URI(target.url)), origins.toList())
                        assertFalse(receipt.toString().contains("private"))
                    }
                }
            }
        }
    }

    @Test fun sameOriginRedirectRetainsHeadersButCrossAndBackDoesNotResurrectThem() {
        Fixture().use { f ->
            val connections = mutableListOf<FakeConnection>()
            BrowserHttpDownloader(f.sessions, f.downloads, connectionFactory = { uri ->
                val location = when (connections.size) {
                    0 -> "/same"
                    1 -> "https://other.test/"
                    2 -> "https://initial.test/back"
                    else -> null
                }
                FakeConnection(uri, if (location == null) 200 else 302, location?.let { mapOf("Location" to it) } ?: emptyMap()).also { connections.add(it) }
            }).use { downloader ->
                worker { downloader.download(f.lease, "https://initial.test/start", mapOf("Authorization" to "private")) }
                assertEquals("private", connections[0].sent["authorization"])
                assertEquals("private", connections[1].sent["authorization"])
                assertNull(connections[2].sent["authorization"])
                assertNull(connections[3].sent["authorization"])
                connections.forEach { assertTrue(it.disconnected.await(1, TimeUnit.SECONDS)) }
            }
        }
    }

    @Test fun redirectsRejectDowngradeUserinfoSchemesAndBoundedLoops() {
        for (location in listOf("http://safe.test/plain", "https://user:pass@safe.test/", "file:///tmp/file", "ftp://safe.test/file", "https://safe.test:0/")) {
            Fixture().use { f ->
                val count = AtomicInteger()
                BrowserHttpDownloader(f.sessions, f.downloads, connectionFactory = { uri ->
                    count.incrementAndGet()
                    FakeConnection(uri, 302, mapOf("Location" to location))
                }).use { downloader ->
                    rejects { downloader.download(f.lease, "https://safe.test/start") }
                    assertEquals(1, count.get()); f.assertEmpty()
                }
            }
        }
        Fixture().use { f ->
            val count = AtomicInteger()
            BrowserHttpDownloader(f.sessions, f.downloads, maxRedirects = 2, connectionFactory = { uri ->
                count.incrementAndGet(); FakeConnection(uri, 308, mapOf("Location" to "/loop"))
            }).use { downloader ->
                assertEquals("BROWSER_HTTP_REDIRECT_LIMIT", rejects { downloader.download(f.lease, "https://safe.test/") }.message)
                assertEquals(3, count.get()); f.assertEmpty()
            }
        }
    }

    @Test fun badInputHeadersAndReadOnlyLeaseFailBeforeOpeningConnection() {
        Fixture().use { f ->
            val calls = AtomicInteger()
            BrowserHttpDownloader(f.sessions, f.downloads, connectionFactory = { calls.incrementAndGet(); FakeConnection(it) }).use { downloader ->
                for (url in listOf("file:///a", "https://user:pass@example.test/", "http://example.test:65536/")) rejects { downloader.download(f.lease, url) }
                for (header in listOf("Cookie", "cOoKiE2", "Host", "Content-Length", "Proxy-Authorization", "Accept-Encoding")) {
                    rejects { downloader.download(f.lease, "http://example.test/", mapOf(header to "secret")) }
                }
                rejects { downloader.download(f.lease, "http://example.test/", mapOf("X-Test" to "a\r\nb")) }
                rejects { downloader.download(f.lease, "http://example.test/", mapOf("Authorization" to "a", "authorization" to "b")) }
                assertEquals(0, calls.get()); f.assertEmpty()
            }
        }
        val owner = BrowserOwner("readonly", BrowserOwnerKind.MAIN)
        val sessions = BrowserSessionRegistry().apply { open(BrowserGrant(owner, BrowserAccess.READ_ONLY)) }
        val root = Files.createTempDirectory("browser-http-readonly")
        try {
            val lease = sessions.newTab(owner)
            BrowserHttpDownloader(sessions, BrowserDownloadCoordinator(sessions, BrowserPathMapper(root)), connectionFactory = { error("must not open") }).use { d ->
                assertEquals("BROWSER_READ_ONLY", rejects { d.download(lease, "http://example.test/") }.message)
            }
        } finally { sessions.close(owner); root.toFile().deleteRecursively() }
    }

    @Test fun realNonSuccessEmptyAndOversizeBodiesNeverLeaveFiles() {
        data class Case(val status: String, val headers: String, val body: String)
        val cases = listOf(
            Case("404 Not Found", "Content-Length: 3\r\n", "abc"),
            Case("204 No Content", "Content-Length: 0\r\n", ""),
            Case("200 OK", "", ""),
            Case("200 OK", "Content-Length: 4\r\n", "abcd"),
            Case("200 OK", "Transfer-Encoding: chunked\r\n", "4\r\nabcd\r\n0\r\n\r\n"),
            Case("200 OK", "Content-Length: 3\r\n", "ab"),
        )
        for (case in cases) Fixture().use { f ->
            HttpServer { _, socket -> response(socket, case.status, case.headers, case.body) }.use { server ->
                BrowserHttpDownloader(f.sessions, f.downloads, maxBytes = 3).use { d ->
                    rejects { d.download(f.lease, server.url) }; f.assertEmpty()
                }
            }
        }
    }

    @Test fun realReadTimeoutIsBoundedAndCleansReservation() {
        Fixture().use { f ->
            val release = CountDownLatch(1)
            try {
                HttpServer { _, socket ->
                    response(socket, headers = "Content-Length: 3\r\n", body = "")
                    release.await(3, TimeUnit.SECONDS)
                }.use { server ->
                    BrowserHttpDownloader(f.sessions, f.downloads, readTimeoutMillis = 150).use { d ->
                        val error = rejects { d.download(f.lease, server.url) }
                        assertTrue(error is java.net.SocketTimeoutException)
                        f.assertEmpty()
                    }
                }
            } finally { release.countDown() }
        }
    }

    @Test fun cancellationDisconnectsBlockedConnectWithoutWaitingOnRegistryClose() {
        for (action in listOf("cancel", "close-owner", "takeover", "close-downloader")) Fixture().use { f ->
            val entered = CountDownLatch(1)
            val unblock = CountDownLatch(1)
            val connection = FakeConnection(URI("https://safe.test/"), connectAction = {
                entered.countDown(); check(unblock.await(3, TimeUnit.SECONDS))
            }, disconnectAction = { unblock.countDown() })
            BrowserHttpDownloader(f.sessions, f.downloads, connectTimeoutMillis = 321, readTimeoutMillis = 654, connectionFactory = { connection }).use { d ->
                val task = start { d.download(f.lease, "https://safe.test/") }
                assertTrue(entered.await(1, TimeUnit.SECONDS))
                try {
                    val cleanup = start {
                        when (action) {
                            "cancel" -> d.cancel(f.lease)
                            "close-owner" -> f.sessions.close(f.owner)
                            "takeover" -> f.sessions.takeover(f.lease)
                            else -> d.close()
                        }
                    }
                    cleanup.get(500, TimeUnit.MILLISECONDS)
                    assertTrue(connection.disconnected.await(1, TimeUnit.SECONDS))
                    failure(task); f.assertEmpty()
                    assertEquals(321, connection.connectTimeout)
                    assertEquals(654, connection.readTimeout)
                    assertFalse(connection.instanceFollowRedirects)
                    assertEquals("GET", connection.requestMethod)
                } finally { unblock.countDown() }
            }
        }
    }

    @Test fun registryCloseNeverWaitsForUnderlyingStreamCloseOrDisconnect() {
        Fixture().use { f ->
            val reading = CountDownLatch(1)
            val releaseRead = CountDownLatch(1)
            val releaseCleanup = CountDownLatch(1)
            val stream = object : InputStream() {
                override fun read(): Int {
                    reading.countDown(); check(releaseRead.await(3, TimeUnit.SECONDS)); return -1
                }
                override fun close() { releaseCleanup.await(3, TimeUnit.SECONDS) }
            }
            val connection = FakeConnection(URI("http://safe.test/"), body = stream, disconnectAction = {
                releaseRead.countDown(); releaseCleanup.await(3, TimeUnit.SECONDS)
            })
            BrowserHttpDownloader(f.sessions, f.downloads, connectionFactory = { connection }).use { d ->
                val task = start { d.download(f.lease, "http://safe.test/") }
                assertTrue(reading.await(1, TimeUnit.SECONDS))
                try {
                    start { f.sessions.close(f.owner) }.get(500, TimeUnit.MILLISECONDS)
                    failure(task); f.assertEmpty()
                } finally { releaseRead.countDown(); releaseCleanup.countDown() }
            }
        }
    }

    @Test fun realBlockedBodyIsAbortedByCancelCloseAndTakeover() {
        for (action in listOf("cancel", "close", "takeover")) Fixture().use { f ->
            val headersSent = CountDownLatch(1)
            val release = CountDownLatch(1)
            try {
                HttpServer { _, socket ->
                    response(socket, headers = "Content-Length: 3\r\n", body = "a")
                    headersSent.countDown(); release.await(4, TimeUnit.SECONDS)
                }.use { server ->
                    BrowserHttpDownloader(f.sessions, f.downloads, readTimeoutMillis = 1500).use { d ->
                        val task = start { d.download(f.lease, server.url) }
                        assertTrue(headersSent.await(1, TimeUnit.SECONDS))
                        start {
                            when (action) {
                                "cancel" -> d.cancel(f.lease)
                                "close" -> f.sessions.close(f.owner)
                                else -> f.sessions.takeover(f.lease)
                            }
                        }.get(500, TimeUnit.MILLISECONDS)
                        failure(task); f.assertEmpty()
                    }
                }
            } finally { release.countDown() }
        }
    }

    @Test fun cancelledOldGenerationCannotAbortNewLeaseAndDownloaderCloseIsPermanent() {
        Fixture().use { f ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val nextConnection = FakeConnection(URI("http://safe.test/"), connectAction = {
                entered.countDown(); check(release.await(3, TimeUnit.SECONDS))
            })
            f.sessions.closeTab(f.lease)
            val next = f.sessions.newTab(f.owner)
            BrowserHttpDownloader(f.sessions, f.downloads, connectionFactory = { nextConnection }).use { d ->
                val task = start { d.download(next, "http://safe.test/") }
                assertTrue(entered.await(1, TimeUnit.SECONDS))
                try {
                    d.cancel(f.lease)
                    assertEquals(1L, nextConnection.disconnected.count)
                    release.countDown()
                    assertEquals(3L, task.get(3, TimeUnit.SECONDS).size)
                    d.close()
                    assertEquals("BROWSER_HTTP_CLOSED", rejects { d.download(next, "http://safe.test/") }.message)
                } finally { release.countDown() }
            }
        }
    }

    @Test fun totalDeadlineBoundsConnectResponseHeadersAndStreamAcquisition() {
        // Each fake deliberately ignores platform timeouts and disconnect. The download caller
        // must still stop waiting, and the real raw call must continue owning its admission slot.
        for (stage in listOf("connect", "response-code", "content-length", "input-stream")) Fixture().use { f ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val streamClosed = CountDownLatch(1)
            val stream = object : ByteArrayInputStream("abc".toByteArray()) {
                override fun close() { streamClosed.countDown(); super.close() }
            }
            fun gate(name: String) {
                if (stage == name) { entered.countDown(); release.await() }
            }
            val connection = object : FakeConnection(URI("http://safe.test/"), body = stream) {
                override fun connect() { gate("connect"); super.connect() }
                override fun getResponseCode(): Int { gate("response-code"); return super.getResponseCode() }
                override fun getHeaderField(name: String): String? {
                    if (name == "Content-Length") gate("content-length")
                    return super.getHeaderField(name)
                }
                override fun getInputStream(): InputStream { gate("input-stream"); return super.getInputStream() }
            }
            BrowserHttpDownloader(f.sessions, f.downloads, totalTimeoutMillis = 250, maxInFlight = 1,
                connectionFactory = { connection }).use { d ->
                try {
                    val task = start { d.download(f.lease, "http://safe.test/") }
                    assertTrue("stage not entered: $stage", entered.await(1, TimeUnit.SECONDS))
                    assertEquals(stage, "BROWSER_HTTP_TIMEOUT", failure(task, 1500).message)
                    assertEquals("BROWSER_HTTP_LIMIT", rejects { d.download(f.lease, "http://safe.test/") }.message)
                    f.assertEmpty()
                } finally { release.countDown() }
                if (stage == "input-stream") {
                    // The late result is discarded, but its stream still gets exactly owned cleanup.
                    assertTrue(streamClosed.await(1, TimeUnit.SECONDS))
                }
            }
        }
    }

    @Test fun realSlowResponseHeadersUseTotalDeadlineRatherThanReadTimeout() {
        Fixture().use { f ->
            val received = CountDownLatch(1)
            val release = CountDownLatch(1)
            try {
                HttpServer { _, _ -> received.countDown(); release.await() }.use { server ->
                    BrowserHttpDownloader(f.sessions, f.downloads, readTimeoutMillis = 5000,
                        totalTimeoutMillis = 250).use { d ->
                        val task = start { d.download(f.lease, server.url) }
                        assertTrue(received.await(1, TimeUnit.SECONDS))
                        val error = failure(task, 1500)
                        // The platform read timeout is also clipped to the remaining total time;
                        // it may win the deadline race by a millisecond on a real socket.
                        assertTrue(error.message == "BROWSER_HTTP_TIMEOUT" || error is java.net.SocketTimeoutException)
                        f.assertEmpty()
                    }
                }
            } finally { release.countDown() }
        }
    }

    @Test fun repeatedCancellationCannotReplaceStillRunningRawCallsOrDuplicateCleanup() {
        Fixture().use { f ->
            val reading = CountDownLatch(2)
            val releaseRead = CountDownLatch(1)
            val cleanupStarted = CountDownLatch(4)
            val cleanupExited = CountDownLatch(4)
            val releaseCleanup = CountDownLatch(1)
            val made = AtomicInteger()
            val disconnects = AtomicInteger()
            val closes = AtomicInteger()
            val rawThreads = Collections.synchronizedSet(mutableSetOf<Thread>())
            val cleanupThreads = Collections.synchronizedSet(mutableSetOf<Thread>())
            fun cleanup(counter: AtomicInteger) {
                counter.incrementAndGet()
                cleanupThreads.add(Thread.currentThread())
                cleanupStarted.countDown()
                try { releaseCleanup.await() } finally { cleanupExited.countDown() }
            }
            BrowserHttpDownloader(f.sessions, f.downloads, maxInFlight = 2, connectionFactory = { uri ->
                made.incrementAndGet()
                FakeConnection(uri, body = object : InputStream() {
                    override fun read(): Int {
                        rawThreads.add(Thread.currentThread())
                        reading.countDown(); releaseRead.await(); return -1
                    }
                    override fun close() { cleanup(closes) }
                }, disconnectAction = { cleanup(disconnects) })
            }).use { d ->
                try {
                    val tasks = List(2) { start { d.download(f.lease, "http://safe.test/") } }
                    assertTrue(reading.await(1, TimeUnit.SECONDS))
                    start { repeat(100) { d.cancel(f.lease) } }.get(500, TimeUnit.MILLISECONDS)
                    tasks.forEach { assertEquals("BROWSER_HTTP_CANCELLED", failure(it, 1500).message) }
                    assertTrue(cleanupStarted.await(1, TimeUnit.SECONDS))
                    repeat(20) {
                        assertEquals("BROWSER_HTTP_LIMIT", rejects { d.download(f.lease, "http://safe.test/") }.message)
                    }
                    // Even once every cleanup call has exited, the two uninterruptible reads keep
                    // both slots. This catches Future.cancel/isDone being mistaken for raw exit.
                    releaseCleanup.countDown()
                    assertTrue(cleanupExited.await(1, TimeUnit.SECONDS))
                    repeat(20) {
                        d.cancel(f.lease)
                        assertEquals("BROWSER_HTTP_LIMIT", rejects { d.download(f.lease, "http://safe.test/") }.message)
                    }
                    assertEquals(2, made.get())
                    assertEquals(2, disconnects.get())
                    assertEquals(2, closes.get())
                    assertTrue(rawThreads.size <= 2)
                    assertTrue(cleanupThreads.size <= 4)
                    f.assertEmpty()
                } finally { releaseCleanup.countDown(); releaseRead.countDown() }
            }
        }
    }

    @Test fun actualCleanupExitRetainsSlotAndDownloaderCloseNeverWaitsForIt() {
        Fixture().use { f ->
            val entered = CountDownLatch(1)
            val releaseRaw = CountDownLatch(1)
            val rawExited = CountDownLatch(1)
            val cleanupEntered = CountDownLatch(1)
            val releaseCleanup = CountDownLatch(1)
            val disconnects = AtomicInteger()
            val connection = FakeConnection(URI("http://safe.test/"), connectAction = {
                entered.countDown()
                try { releaseRaw.await() } finally { rawExited.countDown() }
            }, disconnectAction = {
                disconnects.incrementAndGet()
                releaseRaw.countDown()
                cleanupEntered.countDown()
                releaseCleanup.await()
            })
            BrowserHttpDownloader(f.sessions, f.downloads, maxInFlight = 1, connectionFactory = { connection }).use { d ->
                try {
                    val task = start { d.download(f.lease, "http://safe.test/") }
                    assertTrue(entered.await(1, TimeUnit.SECONDS))
                    d.cancel(f.lease)
                    failure(task, 1500)
                    assertTrue(rawExited.await(1, TimeUnit.SECONDS))
                    assertTrue(cleanupEntered.await(1, TimeUnit.SECONDS))
                    repeat(20) {
                        assertEquals("BROWSER_HTTP_LIMIT", rejects { d.download(f.lease, "http://safe.test/") }.message)
                    }
                    start { repeat(100) { d.close(); d.cancel(f.lease) } }.get(500, TimeUnit.MILLISECONDS)
                    assertEquals(1, disconnects.get())
                    assertEquals("BROWSER_HTTP_CLOSED", rejects { d.download(f.lease, "http://safe.test/") }.message)
                    f.assertEmpty()
                } finally { releaseRaw.countDown(); releaseCleanup.countDown() }
            }
        }
    }

    @Test fun fullLeaseCancellationOfLiveOldOperationCannotTouchReusedTabSlot() {
        Fixture().use { f ->
            val oldEntered = CountDownLatch(1)
            val newEntered = CountDownLatch(1)
            val releaseOld = CountDownLatch(1)
            val releaseNew = CountDownLatch(1)
            val connections = AtomicInteger()
            val old = FakeConnection(URI("http://safe.test/"), connectAction = {
                oldEntered.countDown(); releaseOld.await()
            })
            val replacement = FakeConnection(URI("http://safe.test/"), connectAction = {
                newEntered.countDown(); releaseNew.await()
            })
            BrowserHttpDownloader(f.sessions, f.downloads, maxInFlight = 2,
                connectionFactory = { if (connections.getAndIncrement() == 0) old else replacement }).use { d ->
                try {
                    val oldTask = start { d.download(f.lease, "http://safe.test/") }
                    assertTrue(oldEntered.await(1, TimeUnit.SECONDS))
                    f.sessions.closeTab(f.lease)
                    failure(oldTask, 1500)
                    val next = f.sessions.newTab(f.owner)
                    assertEquals(f.lease.tabId, next.tabId)
                    assertNotEquals(f.lease.tabGeneration, next.tabGeneration)
                    val nextTask = start { d.download(next, "http://safe.test/") }
                    assertTrue(newEntered.await(1, TimeUnit.SECONDS))
                    repeat(100) { d.cancel(f.lease) }
                    assertEquals(1L, replacement.disconnected.count)
                    releaseNew.countDown()
                    assertEquals(3L, nextTask.get(2, TimeUnit.SECONDS).size)
                } finally { releaseOld.countDown(); releaseNew.countDown() }
            }
        }
    }

    @Test fun installedGlobalCookieHandlerFailsClosedBeforeOpeningConnection() {
        val previous = CookieHandler.getDefault()
        try {
            CookieHandler.setDefault(CookieManager())
            Fixture().use { f ->
                val opened = AtomicInteger()
                BrowserHttpDownloader(f.sessions, f.downloads, connectionFactory = {
                    opened.incrementAndGet(); FakeConnection(it)
                }).use { d ->
                    assertEquals("BROWSER_HTTP_GLOBAL_COOKIES", rejects { d.download(f.lease, "http://safe.test/") }.message)
                    assertEquals(0, opened.get())
                    f.assertEmpty()
                }
            }
        } finally { CookieHandler.setDefault(previous) }
    }

    @Test fun workerThreadGuardRejectsMainNamedThreadBeforeNetwork() {
        Fixture().use { f ->
            BrowserHttpDownloader(f.sessions, f.downloads, connectionFactory = { error("must not connect") }).use { d ->
                val error = failure(FutureTask(Callable { d.download(f.lease, "http://safe.test/") }).also { task ->
                    thread(name = "main", isDaemon = true) { task.run() }
                })
                assertEquals("BROWSER_HTTP_WORKER_REQUIRED", error.message)
                f.assertEmpty()
            }
        }
    }
}
