package app.daiyu.android.browser.cookie

import app.daiyu.android.browser.bridge.BrowserCommand
import app.daiyu.android.browser.bridge.BrowserExecutionResult
import app.daiyu.android.browser.download.BrowserPathMapper
import app.daiyu.android.browser.network.BrowserCloseResult
import app.daiyu.android.browser.runtime.AsyncBrowserBackend
import app.daiyu.android.browser.runtime.UiDispatcher
import app.daiyu.android.browser.session.BrowserSessionRegistry
import app.daiyu.android.browser.session.BrowserTabLease
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Host-only cookie capability, not a generic browser adapter. privateRoot MUST be an existing,
 * app-private directory, not writable/renameable by pages, tools or other OS users. envPath maps
 * the actual file to an accessible private tool-environment path (or throws); no mapping is guessed.
 * authorizeName is a frozen host grant, NOT a model predicate. WebView's cookie store is shared:
 * lease isolation alone does not authorize access to another tenant's site credentials.
 *
 * currentUrl MUST resolve the WHOLE lease to its live WebView on ui, returning null for a stale or
 * missing resource. No model origin argument exists. io MUST be a serial FIFO queue that enqueues,
 * never runs inline or waits, and accepts at least 512 tasks (or is unbounded).
 * Keep this backend alive until host cleanup is drained; close/cancel enqueue deletion without
 * waiting. CookieManager side effects already submitted cannot be rolled back. Failure is unknown,
 * never permission to replay. Successful offloads live until lease cancellation/invalidation/close.
 */
class BrowserCookieBackend(
    private val sessions: BrowserSessionRegistry,
    private val ui: UiDispatcher,
    private val store: CookieStore,
    private val currentUrl: (BrowserTabLease) -> String?,
    privateRoot: Path,
    private val envPath: (BrowserTabLease, Path) -> String,
    private val authorizeName: (BrowserTabLease, String, String) -> Boolean,
    private val io: Executor = defaultIo(),
    private val envFileWriter: CookieEnvFileWriter = PosixCookieEnvFileWriter,
) : AsyncBrowserBackend, AutoCloseable {
    private val paths = BrowserPathMapper(privateRoot)
    private val lock = Any()
    private val requests = mutableSetOf<Request>()
    private var closed = false
    private val closeFailures = mutableSetOf<String>()

    fun closeWithResult(): BrowserCloseResult { close(); return closeStatus() }
    fun closeStatus(): BrowserCloseResult = synchronized(lock) {
        BrowserCloseResult(closed,
            if (requests.isEmpty()) emptySet() else setOf("COOKIE_WORK_OR_FILES"),
            closeFailures.toSet())
    }

    override fun execute(command: BrowserCommand, lease: BrowserTabLease): CompletableFuture<BrowserExecutionResult> {
        val request = Request(command, lease)
        synchronized(lock) {
            if (closed || requests.size >= 128) {
                request.result.completeExceptionally(IllegalStateException("COOKIE_UNAVAILABLE"))
                return request.result
            }
            requests.add(request)
        }
        // Called inside the UI admission lock: no WebView/CookieManager, callbacks, JSON or I/O here.
        try { ui.post { try { request.start() } finally { request.workReturned() } } }
        catch (_: Exception) { request.fail(); request.workReturned() }
        return request.result
    }
    override fun cancel(lease: BrowserTabLease) {
        val selected = synchronized(lock) { requests.filter { it.lease == lease }.onEach { it.cancelled = true } }
        selected.forEach { it.scheduleCleanup() }
    }
    override fun close() {
        val selected = synchronized(lock) { closed = true; requests.toList().onEach { it.cancelled = true } }
        selected.forEach { it.scheduleCleanup() }
        // Do not shut down the queue: an already-running writer still owns cleanup work.
    }

    private inner class Request(val command: BrowserCommand, val lease: BrowserTabLease) {
        val result = CompletableFuture<BrowserExecutionResult>()
        @Volatile var cancelled = false
        @Volatile private var subscription: AutoCloseable? = null
        @Volatile private var target: Path? = null
        @Volatile private var staging: Path? = null
        private var origin: CookieOrigin? = null
        private var pendingWrites: List<CookieWrite> = emptyList()
        private var nextWrite = 0
        private var terminal = false
        private var cleanupQueued = false
        // Initial reference belongs to the posted start, not its result Future.
        private var work = 1
        private fun retainWork() = synchronized(lock) { work++ }
        fun workReturned() {
            val clean = synchronized(lock) { work--; cancelled || (terminal && target == null) }
            if (clean) scheduleCleanup()
        }
        private fun submitWriter(action: () -> Unit) {
            retainWork()
            try { io.execute { try { action() } finally { workReturned() } } }
            catch (failure: Exception) { workReturned(); throw failure }
        }
        private var targetOwned = false // only touched on the serial I/O queue
        init { result.whenComplete { _, _ -> if (result.isCancelled) fail() } }

        fun start() {
            try {
                check(ui.isUiThread())
                live()
                require(command.tabId == null || command.tabId == lease.tabId)
                require(command.action in setOf("get_cookies", "set_cookies"))
                val handle = sessions.onInvalidated(lease) { cancel(lease) }
                val detach = synchronized(lock) {
                    if (cancelled || terminal) true else { subscription = handle; false }
                }
                if (detach) handle.close()
                live()
                val page = BrowserCookiePolicy.origin(currentUrl(lease))
                origin = page
                if (command.action == "get_cookies") {
                    val (keywords, fuzzy) = BrowserCookiePolicy.keywords(command.arguments)
                    val raw = sessions.startNative(lease, command.action) {
                        sameOrigin()
                        store.getCookie(page.url)
                    }
                    val pairs = BrowserCookiePolicy.read(raw).filter { pair ->
                        authorizeName(lease, page.url, pair.name) && (keywords.isEmpty() || keywords.any {
                            if (fuzzy) pair.name.contains(it, ignoreCase = true) else pair.name.equals(it, ignoreCase = true)
                        })
                    }
                    live()
                    val bytes = BrowserCookiePolicy.env(pairs)
                    val names = pairs.map { it.name }
                    submitWriter { writeEnv(bytes, names) }
                } else {
                    pendingWrites = BrowserCookiePolicy.writes(command.arguments, page)
                    require(pendingWrites.all { authorizeName(lease, page.url, it.name) })
                    writeNext()
                }
            } catch (_: Exception) { fail() }
        }
        private fun live() {
            check(!cancelled && !synchronized(lock) { terminal })
            sessions.assertAllowed(lease, command.action)
        }
        private fun sameOrigin() {
            check(ui.isUiThread())
            live()
            check(BrowserCookiePolicy.origin(currentUrl(lease)) == origin)
        }
        private fun writeNext() {
            try {
                sameOrigin()
                if (nextWrite == pendingWrites.size) {
                    finish(BrowserExecutionResult("Cookies updated: ${pendingWrites.size}. Values withheld."), keepFile = false)
                    return
                }
                val index = nextWrite
                val header = pendingWrites[index].header
                sessions.startNative(lease, command.action) {
                    sameOrigin()
                    retainWork()
                    val returned = AtomicBoolean()
                    fun releaseCallback() { if (returned.compareAndSet(false, true)) workReturned() }
                    try {
                        store.setCookie(checkNotNull(origin).url, header) { accepted ->
                            // A cancelled Future is not evidence that this native callback returned.
                            try { ui.post {
                                try {
                                    sameOrigin()
                                    check(index == nextWrite && accepted)
                                    nextWrite++
                                    writeNext()
                                } catch (_: Exception) { fail() }
                                finally { releaseCallback() }
                            } } catch (_: Exception) { fail(); releaseCallback() }
                        }
                    } catch (failure: Exception) { releaseCallback(); throw failure }
                }
            } catch (_: Exception) { fail() }
        }
        private fun writeEnv(bytes: ByteArray, names: List<String>) {
            try {
                live()
                val id = UUID.randomUUID().toString()
                val part = paths.privateFile("$id.part")
                val file = paths.privateFile("$id.env")
                staging = part
                target = file
                envFileWriter.create(part)
                paths.recheckPrivate(part)
                envFileWriter.write(part, bytes)
                live()
                envFileWriter.verify(part)
                paths.recheckPrivate(part)
                paths.recheckPrivate(file)
                Files.move(part, file, StandardCopyOption.ATOMIC_MOVE)
                targetOwned = true
                live()
                val mapped = envPath(lease, file)
                require(mapped.startsWith('/') && mapped.length <= 4096 && mapped.all { it.code >= 0x20 && it.code != 0x7f })
                require(!mapped.contains('\\') && mapped.split('/').drop(1).none { it.isEmpty() || it == "." || it == ".." })
                val text = "Cookies available: ${names.size}. Names: ${names.joinToString(", ")}. Values withheld; private env: $mapped"
                retainWork()
                try { ui.post {
                    try {
                        sameOrigin()
                        finish(BrowserExecutionResult(text, mapped), keepFile = true)
                    } catch (_: Exception) { fail() }
                    finally { workReturned() }
                } } catch (failure: Exception) { workReturned(); throw failure }
            } catch (_: Exception) { fail() }
            finally { bytes.fill(0) }
        }
        private fun finish(value: BrowserExecutionResult, keepFile: Boolean) {
            sessions.publish(lease) {
                synchronized(lock) {
                    check(!cancelled && !terminal)
                    terminal = true
                    // Completion is fenced at this point; callbacks must not do I/O in admission.
                    result.complete(value)
                }
            }
            pendingWrites = emptyList()
            if (!keepFile) scheduleCleanup()
        }
        fun fail() {
            synchronized(lock) { cancelled = true }
            scheduleCleanup()
        }
        fun scheduleCleanup() {
            val enqueue = synchronized(lock) {
                if (cleanupQueued) false else { cleanupQueued = true; true }
            }
            if (!enqueue) return
            try { io.execute { cleanup() } } catch (_: Exception) {
                // Queue capacity exceeds the maximum live request count; normal close cannot reject.
                synchronized(lock) { cleanupQueued = false; closeFailures.add("COOKIE_CLEANUP_QUEUE") }
                result.completeExceptionally(IllegalStateException("COOKIE_UNAVAILABLE"))
            }
        }
        private fun cleanup() {
            result.completeExceptionally(IllegalStateException("COOKIE_UNAVAILABLE"))
            var clean = true
            try { subscription?.close(); subscription = null }
            catch (_: Exception) {
                clean = false
                synchronized(lock) { closeFailures.add("COOKIE_SUBSCRIPTION_CLEANUP") }
            }
            pendingWrites = emptyList()
            for (path in listOfNotNull(staging, target.takeIf { targetOwned })) {
                try { Files.deleteIfExists(paths.recheckPrivate(path)) } catch (_: Exception) {
                    clean = false
                    synchronized(lock) { closeFailures.add("COOKIE_FILE_DELETE") }
                }
            }
            synchronized(lock) {
                if (clean && work == 0) requests.remove(this)
                // Retain failed cleanup for a later explicit close/cancel, never replay a cookie action.
                cleanupQueued = false
                terminal = true
            }
        }
    }
    companion object {
        private fun defaultIo(): Executor = ThreadPoolExecutor(
            1, 1, 15, TimeUnit.SECONDS, ArrayBlockingQueue(512),
            { task -> Thread(task, "browser-cookie-private-io").apply { isDaemon = true } },
        ).apply { allowCoreThreadTimeOut(true) }
    }
}
