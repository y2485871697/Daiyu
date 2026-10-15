package app.daiyu.android.browser.network

import app.daiyu.android.browser.download.BrowserDownloadCoordinator
import app.daiyu.android.browser.download.BrowserDownloadReceipt
import app.daiyu.android.browser.download.BrowserDownloadTicket
import app.daiyu.android.browser.session.BrowserSessionRegistry
import app.daiyu.android.browser.session.BrowserTabLease
import java.io.InputStream
import java.net.CookieHandler
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Standalone GET adapter, NOT a registered browser tool or a BrowserExecutionPort implementation.
 * A host port may call [download] on its I/O worker; Android's main thread is rejected.
 * No network/file I/O is performed inside BrowserSessionRegistry.publish.
 *
 * [cookieForOrigin] is a trusted HOST capability, not a model argument. It receives only a normalized
 * scheme/host/effective-port origin (no path/query/fragment). It must be bounded/non-UI-blocking and
 * return cookies authorized for that origin, or null. Cookies are never returned in a receipt/log.
 * The host must leave the process CookieHandler null for this adapter's lifetime: HttpURLConnection
 * has no portable per-connection cookie-handler override. We fail closed if one is installed rather
 * than mutating global cookie policy. A supplied [connectionFactory] must likewise return a fresh,
 * unconnected, cookie/auth-isolated connection promptly, without doing I/O itself.
 *
 * Invalidation/cancel/close only mark state and enqueue bounded cleanup. All blocking network
 * stages (including connect, response headers and stream acquisition) use deadline-aware waits.
 * The coordinator receives a stream whose close is nonblocking. Disconnect and stream close run
 * independently: close can otherwise wait on a blocked read lock.
 *
 * Per instance: at most maxInFlight raw I/O tasks, 2 * maxInFlight cleanup workers and at most
 * 2 * maxInFlight * (maxRedirects + 1) queued cleanup tasks. Each connection is disconnected once
 * and each acquired stream closed once, including late acquisition after cancellation. An operation
 * retains its admission slot until the caller AND all its actual I/O/cleanup calls have returned;
 * neither Future cancellation nor a Future's done flag is used as evidence of thread termination.
 * Redirects bound the number of resources per operation. Permanently stuck injected connections
 * can exhaust this budget (fail closed), but cannot cause replacement threads to grow without bound.
 * close never waits for them; idle workers expire, and closed/drained pools are shut down.
 */
class BrowserHttpDownloader(
    private val sessions: BrowserSessionRegistry,
    private val downloads: BrowserDownloadCoordinator,
    private val connectTimeoutMillis: Int = 10_000,
    private val readTimeoutMillis: Int = 10_000,
    private val maxBytes: Long = 16L * 1024 * 1024,
    private val totalTimeoutMillis: Int = 60_000,
    private val maxRedirects: Int = 5,
    private val maxInFlight: Int = 4,
    private val cookieForOrigin: ((URI) -> String?)? = null,
    private val connectionFactory: (URI) -> HttpURLConnection = {
        it.toURL().openConnection() as HttpURLConnection
    },
) : AutoCloseable {
    private val lock = Any()
    private val operations = mutableSetOf<Operation>()
    private var closed = false

    init {
        require(connectTimeoutMillis in 1..60_000 && readTimeoutMillis in 1..60_000)
        require(maxBytes > 0 && maxRedirects in 0..20 && maxInFlight in 1..32)
        require(totalTimeoutMillis in 1..300_000)
    }

    private val ioExecutor = pool(maxInFlight, maxInFlight, "browser-http-io")
    private val cleanupExecutor = pool(
        2 * maxInFlight, 2 * maxInFlight * (maxRedirects + 1), "browser-http-cleanup",
    )

    private fun drained(operation: Operation) {
        synchronized(lock) {
            operations.remove(operation)
            shutdownIfDrained()
        }
    }

    // Called with the admission lock. No I/O, waits or executor task interruption.
    private fun shutdownIfDrained() {
        if (closed && operations.isEmpty()) {
            ioExecutor.shutdown()
            cleanupExecutor.shutdown()
        }
    }

    /** Returns only the coordinator's real path, size and SHA-256, never a synthesized URI. */
    fun download(
        lease: BrowserTabLease,
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): BrowserDownloadReceipt {
        requireWorkerThread()
        sessions.assertAllowed(lease, "fetch")
        var uri = checkedUri(URI(url))
        val requestHeaders = checkedHeaders(headers)
        val initialOrigin = origin(uri)
        var forwardHeaders = true
        val operation = Operation(lease, totalTimeoutMillis)
        synchronized(lock) {
            check(!closed) { "BROWSER_HTTP_CLOSED" }
            check(operations.size < maxInFlight) { "BROWSER_HTTP_LIMIT" }
            operations.add(operation)
        }
        var registration: AutoCloseable? = null
        var ticket: BrowserDownloadTicket? = null
        var receipt: BrowserDownloadReceipt? = null
        var succeeded = false
        try {
            registration = sessions.onInvalidated(lease) { operation.cancel() }
            var redirects = 0
            while (true) {
                operation.checkActive()
                sessions.assertAllowed(lease, "fetch")
                check(CookieHandler.getDefault() == null) { "BROWSER_HTTP_GLOBAL_COOKIES" }
                val target = uri
                val resource = operation.await {
                    Resource(connectionFactory(target), operation).also { operation.attach(it) }
                }
                val connection = resource.connection
                try {
                    operation.checkActive()
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = minOf(connectTimeoutMillis, operation.remainingMillis())
                    connection.readTimeout = minOf(readTimeoutMillis, operation.remainingMillis())
                    connection.requestMethod = "GET"
                    connection.doInput = true
                    connection.doOutput = false
                    connection.useCaches = false
                    connection.setRequestProperty("Accept-Encoding", "identity")
                    if (forwardHeaders) requestHeaders.forEach { (name, value) ->
                        connection.setRequestProperty(name, value)
                    }
                    cookieForOrigin?.invoke(origin(uri))?.let { cookie ->
                        require(validValue(cookie)) { "BROWSER_HTTP_COOKIE_INVALID" }
                        if (cookie.isNotEmpty()) connection.setRequestProperty("Cookie", cookie)
                    }
                    operation.checkActive()
                    check(CookieHandler.getDefault() == null) { "BROWSER_HTTP_GLOBAL_COOKIES" }
                    operation.await { connection.connect() }
                    operation.checkActive()
                    val status = operation.await { connection.responseCode }
                    operation.checkActive()
                    if (status in redirectStatuses) {
                        check(redirects < maxRedirects) { "BROWSER_HTTP_REDIRECT_LIMIT" }
                        val location = operation.await { connection.getHeaderField("Location") }
                        check(!location.isNullOrBlank()) { "BROWSER_HTTP_REDIRECT_LOCATION" }
                        val next = checkedUri(uri.resolve(URI(location)))
                        check(!(uri.scheme == "https" && next.scheme == "http")) { "BROWSER_HTTP_DOWNGRADE" }
                        // Once an origin boundary was crossed, never resurrect caller headers, even
                        // if a later hop returns to the initial origin. Origin cookies are re-queried.
                        forwardHeaders = forwardHeaders && origin(next) == initialOrigin
                        uri = next
                        redirects++
                        continue
                    }
                    check(status in 200..299) { "BROWSER_HTTP_STATUS" }
                    val length = operation.await { contentLength(connection) }
                    check(length != 0L) { "BROWSER_HTTP_EMPTY" }
                    check(length == null || length <= maxBytes) { "BROWSER_HTTP_TOO_LARGE" }
                    operation.checkActive()
                    val input = operation.await {
                        // Attach on the raw worker, not its waiter: a stream acquired after the
                        // deadline must still be closed, even though its result is discarded.
                        connection.inputStream.also { resource.attach(it) }
                    }
                    operation.checkActive()
                    // Reserve only after a final successful GET, not for redirects/error responses.
                    val reserved = downloads.reserve(lease)
                    ticket = reserved
                    operation.checkActive()
                    val written = downloads.write(reserved, ResponseStream(operation, resource, input, length))
                    receipt = written
                    // Coordinator already published its own result. This final bounded gate handles
                    // explicit cancel, downloader.close, and invalidation in the return window.
                    val result = sessions.publish(lease) { operation.complete(written) }
                    succeeded = true
                    return result
                } finally {
                    resource.finish()
                    operation.detach(resource)
                }
            }
        } finally {
            try {
                if (!succeeded) {
                    // Also finishes a resource whose factory result lost the deadline/cancel race.
                    operation.cancel()
                    // A receipt can exist if invalidation won just after coordinator publication.
                    // It belongs to this operation, never to a newer lease's download.
                    try { receipt?.let { Files.deleteIfExists(it.path) } }
                    finally { ticket?.let { downloads.cancel(it) } }
                }
            } finally {
                try { registration?.close() }
                finally {
                    // This caller returning is NOT evidence that the raw socket or its cleanup
                    // calls returned. Their separately held references retain the admission slot.
                    operation.release()
                }
            }
        }
    }

    /** Full lease identity includes session/tab generations; an old lease cannot abort its successor. */
    fun cancel(lease: BrowserTabLease) {
        val selected = synchronized(lock) { operations.filter { it.lease == lease } }
        selected.forEach { it.cancel() }
    }

    /** Does not await network shutdown; existing workers perform file cleanup before they exit. */
    override fun close() {
        val selected = synchronized(lock) {
            closed = true
            shutdownIfDrained()
            operations.toList()
        }
        selected.forEach { it.cancel() }
    }

    private inner class Operation(val lease: BrowserTabLease, totalTimeoutMillis: Int) {
        private val stateLock = Any()
        private val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(totalTimeoutMillis.toLong())
        private var cancellation: String? = null
        private var completed = false
        private var resource: Resource? = null
        // One initial reference is owned by the download caller. Resources and actual submitted
        // calls add references; cancellation never releases somebody else's reference.
        private var references = 1

        fun retain() = synchronized(stateLock) {
            check(references > 0)
            references++
        }
        fun release() {
            val done = synchronized(stateLock) { --references == 0 }
            if (done) drained(this)
        }
        fun checkActive() = synchronized(stateLock) {
            check(cancellation == null) { cancellation ?: "BROWSER_HTTP_CANCELLED" }
            check(deadline - System.nanoTime() > 0) { "BROWSER_HTTP_TIMEOUT" }
        }
        fun remainingMillis(): Int {
            checkActive()
            return TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceAtLeast(1).toInt()
        }
        fun attach(value: Resource) {
            val abort = synchronized(stateLock) {
                resource = value
                cancellation != null || deadline - System.nanoTime() <= 0
            }
            if (abort) value.finish()
        }
        fun detach(value: Resource) = synchronized(stateLock) {
            if (resource === value) resource = null
        }
        fun cancel() {
            val active = synchronized(stateLock) {
                if (completed) return
                if (cancellation == null) cancellation = "BROWSER_HTTP_CANCELLED"
                resource
            }
            active?.finish()
        }
        fun complete(receipt: BrowserDownloadReceipt): BrowserDownloadReceipt = synchronized(stateLock) {
            checkActive()
            completed = true
            receipt
        }

        fun <T> await(action: () -> T): T {
            checkActive()
            val ready = CountDownLatch(1)
            var result: T? = null
            var failure: Throwable? = null
            retain()
            try {
                ioExecutor.execute {
                    try {
                        checkActive()
                        result = action()
                    } catch (e: Throwable) {
                        failure = e
                    } finally {
                        // The raw action has really returned. Do NOT cancel its Future and treat
                        // that as termination: many socket implementations ignore interruption.
                        release()
                        ready.countDown()
                    }
                }
            } catch (e: RuntimeException) {
                release()
                throw e
            }
            while (true) {
                checkActive()
                if (ready.await(minOf(25, remainingMillis()).toLong(), TimeUnit.MILLISECONDS)) {
                    checkActive()
                    failure?.let { throw it }
                    @Suppress("UNCHECKED_CAST")
                    return result as T
                }
            }
        }

        fun cleanup(action: () -> Unit) {
            retain()
            try {
                cleanupExecutor.execute {
                    try { action() } catch (_: Exception) { /* no secrets/logs; best effort */ }
                    finally { release() }
                }
            } catch (e: RuntimeException) {
                release()
                throw e
            }
        }
    }

    private class Resource(val connection: HttpURLConnection, private val operation: Operation) {
        private val resourceLock = Any()
        private var input: InputStream? = null
        private var finished = false
        private var closeSubmitted = false
        init { operation.retain() }

        fun attach(stream: InputStream) = synchronized(resourceLock) {
            check(input == null)
            input = stream
            if (finished) closeInput()
        }
        fun finish() = synchronized(resourceLock) {
            if (!finished) {
                finished = true
                // Claim and enqueue while holding the resource monitor, so concurrent finish
                // cannot release its lifetime reference before cancellation has retained tasks.
                operation.cleanup { connection.disconnect() }
                closeInput()
                operation.release()
            }
        }
        private fun closeInput() {
            val stream = input ?: return
            if (!closeSubmitted) {
                closeSubmitted = true
                operation.cleanup { stream.close() }
            }
            // Do not query errorStream here: an error/redirect connection is disconnected without
            // acquiring another response stream, and every stream we do acquire is closed above.
        }
    }

    private inner class ResponseStream(
        private val operation: Operation,
        private val resource: Resource,
        private val input: InputStream,
        private val expectedLength: Long?,
    ) : InputStream() {
        private val closed = AtomicBoolean()
        private var count = 0L
        override fun read(): Int {
            val single = ByteArray(1)
            return if (read(single, 0, 1) == -1) -1 else single[0].toInt() and 255
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (offset < 0 || length < 0 || offset > buffer.size - length) throw IndexOutOfBoundsException()
            if (length == 0) return 0
            operation.checkActive()
            check(!closed.get()) { "BROWSER_HTTP_STREAM_CLOSED" }
            // Read at most one byte beyond the allowance, including for chunked/unknown-size bodies.
            val remaining = maxBytes - count
            val requested = minOf(8192, if (remaining >= length) length else (remaining + 1).toInt())
            // A private buffer prevents a cancelled raw read from later touching the coordinator's
            // buffer. Polling lets its writer abort promptly even if platform disconnect/close waits
            // for the raw read lock. Only one read task per live stream is outstanding.
            val bytes = ByteArray(requested)
            val size = operation.await { input.read(bytes, 0, requested) }
            operation.checkActive()
            if (size > 0) System.arraycopy(bytes, 0, buffer, offset, size)
            if (size < 0) {
                check(count > 0) { "BROWSER_HTTP_EMPTY" }
                check(expectedLength == null || count == expectedLength) { "BROWSER_HTTP_TRUNCATED" }
            } else {
                check(size > 0) { "BROWSER_HTTP_NO_PROGRESS" }
                check(count <= maxBytes - size) { "BROWSER_HTTP_TOO_LARGE" }
                count += size
                check(expectedLength == null || count <= expectedLength) { "BROWSER_HTTP_LENGTH" }
            }
            return size
        }
        override fun close() {
            if (closed.compareAndSet(false, true)) resource.finish()
        }
    }

    companion object {
        private val redirectStatuses = setOf(301, 302, 303, 307, 308)
        private fun pool(workers: Int, queued: Int, name: String): ThreadPoolExecutor =
            ThreadPoolExecutor(
                workers, workers, 1, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(queued),
                { task -> Thread(task, name).apply { isDaemon = true } },
                ThreadPoolExecutor.AbortPolicy(),
            ).apply { allowCoreThreadTimeOut(true) }
        private val forbiddenHeaders = setOf(
            "cookie", "cookie2", "host", "connection", "proxy-authorization", "proxy-connection",
            "content-length", "transfer-encoding", "accept-encoding", "upgrade", "trailer", "te",
        )
        private fun checkedHeaders(headers: Map<String, String>): Map<String, String> {
            require(headers.size <= 32) { "BROWSER_HTTP_HEADERS" }
            val copy = headers.toMap()
            require(copy.keys.map { it.lowercase(Locale.ROOT) }.distinct().size == copy.size) { "BROWSER_HTTP_HEADERS" }
            copy.forEach { (name, value) ->
                require(name.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) &&
                    name.lowercase(Locale.ROOT) !in forbiddenHeaders && validValue(value)) { "BROWSER_HTTP_HEADERS" }
            }
            return copy
        }
        private fun validValue(value: String): Boolean =
            value.length <= 16_384 && value.all { it == '\t' || it.code in 32..126 || it.code in 128..255 }

        private fun checkedUri(value: URI): URI {
            val scheme = value.scheme?.lowercase(Locale.ROOT)
            require(scheme in setOf("http", "https") && !value.isOpaque &&
                !value.host.isNullOrBlank() && value.rawUserInfo == null &&
                (value.port == -1 || value.port in 1..65535)) { "BROWSER_HTTP_URL_NOT_ALLOWED" }
            // Strip fragments without decoding/re-encoding the path or query.
            return URI(value.toASCIIString().substringBefore('#').replaceFirst(Regex("^[^:]+:"), "$scheme:"))
        }
        private fun origin(uri: URI): URI = URI(
            uri.scheme, null, uri.host.lowercase(Locale.ROOT),
            if (uri.port != -1) uri.port else if (uri.scheme == "https") 443 else 80,
            null, null, null,
        )
        private fun contentLength(connection: HttpURLConnection): Long? {
            val header = connection.getHeaderField("Content-Length") ?: return null
            val length = header.trim().toLongOrNull()
            check(length != null && length >= 0) { "BROWSER_HTTP_LENGTH" }
            return length
        }
        private fun requireWorkerThread() {
            check(Thread.currentThread().name != "main") { "BROWSER_HTTP_WORKER_REQUIRED" }
            // Avoid a dependency on Android stubs for pure JVM tests; ART reports Dalvik as VM name.
            if (System.getProperty("java.vm.name") in setOf("Dalvik", "ART")) {
                val looper = Class.forName("android.os.Looper")
                val current = looper.getMethod("myLooper").invoke(null)
                val main = looper.getMethod("getMainLooper").invoke(null)
                check(main == null || current !== main) { "BROWSER_HTTP_WORKER_REQUIRED" }
            }
        }
    }
}
