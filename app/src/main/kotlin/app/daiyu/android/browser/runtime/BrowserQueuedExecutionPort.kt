package app.daiyu.android.browser.runtime

import app.daiyu.android.browser.bridge.BrowserCommand
import app.daiyu.android.browser.bridge.BrowserExecutionPort
import app.daiyu.android.browser.bridge.BrowserOperationNotStartedException
import app.daiyu.android.browser.bridge.BrowserExecutionResult
import app.daiyu.android.browser.session.BrowserSessionRegistry
import app.daiyu.android.browser.session.BrowserTabLease
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** post must enqueue without waiting for the UI; rejection must throw. */
interface UiDispatcher {
    fun isUiThread(): Boolean
    fun post(task: () -> Unit)
}

/**
 * execute is called on the UI thread, inside the registry's admission lock. It MUST be bounded,
 * nonblocking initiation only: no I/O, waits, or synchronous cross-thread calls. Work that can take
 * time belongs behind the returned future. Do not synchronously invalidate sessions from execute.
 *
 * cancel may be called from ANY thread, including an invalidation callback. It too MUST be bounded
 * and nonblocking (enqueue UI cleanup if necessary), and MUST match the WHOLE lease, including both
 * generations and owner, not just tabId. A delayed cancellation must not touch a replacement tab.
 * These are backend obligations; a synchronous adapter cannot enforce a time bound on injected code.
 */
interface AsyncBrowserBackend {
    fun execute(command: BrowserCommand, lease: BrowserTabLease): CompletableFuture<BrowserExecutionResult>
    fun cancel(lease: BrowserTabLease)
}

/**
 * Worker-thread synchronous facade over a genuinely asynchronous UI backend. No retries are made.
 * Once backend admission begins, timeout/interruption remains Unknown and cleanup is best effort.
 * Before admission, failure seals the queue entry without retiring the untouched page.
 */
class BrowserQueuedExecutionPort(
    private val sessions: BrowserSessionRegistry,
    private val ui: UiDispatcher,
    private val backend: AsyncBrowserBackend,
    private val waitTimeoutMillis: Long = 10_000,
) : BrowserExecutionPort {
    init { require(waitTimeoutMillis in 1..60_000) { "BROWSER_WAIT_TIMEOUT_RANGE" } }

    override fun execute(command: BrowserCommand, lease: BrowserTabLease): BrowserExecutionResult {
        if (ui.isUiThread()) throw BrowserOperationNotStartedException(IllegalStateException("BROWSER_UI_THREAD_WAIT_FORBIDDEN"))
        if (Thread.currentThread().isInterrupted)
            throw BrowserOperationNotStartedException(InterruptedException("BROWSER_WAIT_INTERRUPTED"))
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitTimeoutMillis)
        val request = Request(command, lease)
        try {
            request.attach(sessions.onInvalidated(lease) {
                request.fail(IllegalStateException("BROWSER_LEASE_INVALIDATED"))
            })
            ui.post { request.start() }
            return request.result.get((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)
        } catch (e: InterruptedException) {
            request.fail(e)
            Thread.currentThread().interrupt()
            throw request.failure() ?: e
        } catch (e: Exception) {
            request.fail(e)
            throw request.failure() ?: e
        } finally {
            request.detach()
        }
    }

    private inner class Request(val command: BrowserCommand, val lease: BrowserTabLease) {
        val result = CompletableFuture<BrowserExecutionResult>()
        private val lock = Any()
        private var terminal = false
        private var backendEntered = false
        private var terminalFailure: Throwable? = null
        private var subscription: AutoCloseable? = null

        fun attach(handle: AutoCloseable) {
            val closeNow = synchronized(lock) {
                if (terminal) true else { subscription = handle; false }
            }
            if (closeNow) handle.close()
        }

        fun detach() {
            val handle = synchronized(lock) { subscription.also { subscription = null } }
            handle?.close()
        }

        fun start() {
            try {
                check(ui.isUiThread()) { "BROWSER_DISPATCH_NOT_UI" }
                // publish is essential: checking permission and then calling execute outside this
                // lock would allow close/takeover to win between admission and actual backend start.
                val pending = sessions.publish(lease) {
                    sessions.assertAllowed(lease, command.action)
                    synchronized(lock) {
                        if (terminal) null
                        else {
                            backendEntered = true
                            backend.execute(command, lease)
                        }
                    }
                } ?: return
                pending.whenComplete { value, failure ->
                    if (failure != null) fail(failure)
                    else try {
                        // Fence completion under the same registry lock, even if invalidation's
                        // callback has not run yet (e.g. an earlier cleanup callback is slow).
                        sessions.publish(lease) {
                            finish(value ?: error("BROWSER_NULL_RESULT"), null)
                        }
                    } catch (e: Exception) {
                        fail(e)
                    }
                }
            } catch (e: Exception) {
                fail(e)
            }
        }

        fun fail(failure: Throwable) = finish(null, failure)

        fun failure(): Throwable? = synchronized(lock) { terminalFailure }

        private fun finish(value: BrowserExecutionResult?, failure: Throwable?) {
            var cancelBackend = false
            val handle = synchronized(lock) {
                if (terminal) return
                terminal = true
                terminalFailure = failure?.let {
                    if (!backendEntered) BrowserOperationNotStartedException(it)
                    else if (it is BrowserOperationNotStartedException) IllegalStateException("BROWSER_BACKEND_FAILED", it)
                    else it
                }
                cancelBackend = failure != null && backendEntered
                val handle = subscription
                subscription = null
                // Wake the worker before best-effort cleanup; it must not wait for cancellation.
                if (failure != null) result.completeExceptionally(checkNotNull(terminalFailure))
                else result.complete(checkNotNull(value))
                handle
            }
            try { handle?.close() } catch (_: Exception) { /* already terminal */ }
            if (cancelBackend) {
                try { backend.cancel(lease) } catch (_: Exception) { /* outcome remains unknown */ }
            }
        }
    }
}
