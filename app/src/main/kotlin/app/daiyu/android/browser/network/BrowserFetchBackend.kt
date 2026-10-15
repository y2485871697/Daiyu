package app.daiyu.android.browser.network

import app.daiyu.android.browser.bridge.BrowserCommand
import app.daiyu.android.browser.bridge.BrowserExecutionResult
import app.daiyu.android.browser.download.BrowserDownloadReceipt
import app.daiyu.android.browser.runtime.AsyncBrowserBackend
import app.daiyu.android.browser.session.BrowserSessionRegistry
import app.daiyu.android.browser.session.BrowserTabLease
import java.nio.file.Files
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Production, standalone GET backend. [execute] only validates bounded arguments and submits to
 * the owned, bounded worker pool: it is safe inside the UI registry admission lock. In particular
 * neither download(), listener registration, file cleanup nor waiting happens on that caller.
 * There is no retry, WebView operation, model-supplied header or cookie capability here.
 *
 * This backend owns [downloads]; the trusted host must construct it with the SAME [sessions]
 * registry used by BrowserQueuedExecutionPort, and close it at host teardown. Cookie isolation and
 * any trusted origin-cookie capability remain exclusively BrowserHttpDownloader's responsibility.
 * An arbitrary Executor is deliberately not accepted: inline/CallerRuns executors violate admission.
 *
 * At most maxWorkers + maxQueued requests are retained, with maxWorkers actual caller workers and
 * maxQueued queued tasks. Cancellation marks state, interrupts only the still-owned caller thread,
 * and invokes the downloader's bounded cancellation. It does NOT release a slot or cancel a task
 * Future. The runnable retains its slot through real download return, listener removal and file
 * cleanup. Independently, the downloader retains its own raw-I/O/cleanup budget until those calls
 * really return, even if its download caller has already returned. Stuck calls fail closed, not by
 * spawning replacement workers. close() never awaits workers and suppresses subsequent delivery.
 */
class BrowserFetchBackend(
    private val sessions: BrowserSessionRegistry,
    private val downloads: BrowserHttpDownloader,
    maxWorkers: Int = 4,
    maxQueued: Int = 4,
) : AsyncBrowserBackend, AutoCloseable {
    private val lock = Any()
    private val requests = mutableSetOf<Request>()
    private var closed = false
    private val capacity: Int
    private val worker: ThreadPoolExecutor

    init {
        require(maxWorkers in 1..32 && maxQueued in 1..128) { "BROWSER_FETCH_POOL_RANGE" }
        capacity = maxWorkers + maxQueued
        worker = ThreadPoolExecutor(
            maxWorkers, maxWorkers, 1, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(maxQueued),
            { task -> Thread(task, "browser-fetch-worker").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy(),
        ).apply { allowCoreThreadTimeOut(true) }
    }

    override fun execute(
        command: BrowserCommand,
        lease: BrowserTabLease,
    ): CompletableFuture<BrowserExecutionResult> {
        require(command.action == "fetch") { "BROWSER_FETCH_ACTION" }
        require(command.tabId == lease.tabId) { "BROWSER_FETCH_TAB" }
        require(command.arguments.keys == setOf("url")) { "BROWSER_FETCH_ARGUMENTS" }
        val url = command.arguments["url"]
        require(url is String && url.length <= 64_000 && url.isNotBlank()) { "BROWSER_FETCH_URL" }
        val request = Request(lease, url)
        synchronized(lock) {
            check(!closed) { "BROWSER_FETCH_CLOSED" }
            check(requests.size < capacity) { "BROWSER_FETCH_LIMIT" }
            requests.add(request)
            try {
                // AbortPolicy is essential: rejected work must never run on the admitting UI thread.
                worker.execute(request)
            } catch (_: RuntimeException) {
                requests.remove(request)
                throw IllegalStateException("BROWSER_FETCH_LIMIT")
            }
        }
        return request.result
    }

    /** Complete owner + session/tab generations are compared, never the reusable integer tab ID. */
    override fun cancel(lease: BrowserTabLease) {
        synchronized(lock) {
            requests.filter { it.lease == lease }.forEach { it.markCancelled() }
        }
        downloads.cancel(lease)
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            requests.forEach { it.markCancelled() }
            // Queued tasks still run their finally blocks. shutdownNow() would strand their slots.
            worker.shutdown()
        }
        downloads.close()
    }

    private inner class Request(val lease: BrowserTabLease, val url: String) : Runnable {
        val result = CompletableFuture<BrowserExecutionResult>()
        // All fields below are guarded by the backend lock; they outlive future cancellation.
        private var cancelled = false
        private var delivered = false
        private var caller: Thread? = null

        init {
            result.whenComplete { _, _ -> if (result.isCancelled) cancel(lease) }
        }

        // Must hold lock. Interrupt is a wakeup, NOT evidence of exit; the slot remains held.
        fun markCancelled() {
            if (!delivered) {
                cancelled = true
                // Keep thread identity locked through interrupt so it cannot hit a reused worker.
                caller?.interrupt()
            }
        }

        private fun checkActive() = synchronized(lock) {
            check(!closed && !cancelled && !result.isDone) { "BROWSER_FETCH_CANCELLED" }
        }

        override fun run() {
            var registration: AutoCloseable? = null
            var receipt: BrowserDownloadReceipt? = null
            try {
                synchronized(lock) { caller = Thread.currentThread() }
                checkActive()
                sessions.assertAllowed(lease, "fetch")
                registration = sessions.onInvalidated(lease) { cancel(lease) }
                checkActive()
                // Cancellation racing the downloader's initial registration also interrupts this
                // caller: its deadline-aware wait unwinds, even if cancel() arrived before admission.
                val downloaded = downloads.download(lease, url)
                receipt = downloaded
                val summary = BrowserExecutionResult(
                    "{\"path\":${jsonString(downloaded.path.toString())},\"size\":${downloaded.size}," +
                        "\"sha256\":${jsonString(downloaded.sha256)}}",
                    downloaded.path.toString(),
                )
                // Fence even when invalidation has removed the lease but its callback is delayed.
                // No file/network I/O in this publication block. Consumers must keep synchronous
                // CompletableFuture callbacks bounded, just as for the queued execution port.
                sessions.publish(lease) {
                    synchronized(lock) {
                        checkActive()
                        delivered = true
                        if (!result.complete(summary)) {
                            delivered = false
                            error("BROWSER_FETCH_CANCELLED")
                        }
                    }
                }
            } catch (_: Exception) {
                // Do not expose socket/URI/cookie-bearing exception messages or fetched content.
                val code = synchronized(lock) {
                    if (closed || cancelled || result.isCancelled) "BROWSER_FETCH_CANCELLED"
                    else "BROWSER_FETCH_FAILED"
                }
                result.completeExceptionally(IllegalStateException(code))
            } finally {
                try {
                    if (!synchronized(lock) { delivered }) {
                        // A real receipt can exist if close/invalidation wins the return window.
                        receipt?.let { try { Files.deleteIfExists(it.path) } catch (_: Exception) { } }
                    }
                } finally {
                    try { registration?.close() } finally {
                        synchronized(lock) {
                            caller = null
                            requests.remove(this)
                            // A cancelled request must not poison the pool's next runnable.
                            Thread.interrupted()
                        }
                    }
                }
            }
        }
    }

    private companion object {
        fun jsonString(value: String): String = buildString {
            append('"')
            value.forEach { character ->
                when (character) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    in '\u0000'..'\u001f' -> {
                        append("\\u")
                        append(character.code.toString(16).padStart(4, '0'))
                    }
                    else -> append(character)
                }
            }
            append('"')
        }
    }
}
