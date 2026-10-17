package app.daiyu.android.browser.openminis

import android.content.Context
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import app.daiyu.android.browser.bridge.BrowserCommand
import app.daiyu.android.browser.bridge.BrowserExecutionResult
import app.daiyu.android.browser.bridge.BrowserOutcome
import app.daiyu.android.browser.cookie.AndroidCookieStore
import app.daiyu.android.browser.cookie.BrowserCookieBackend
import app.daiyu.android.browser.download.BrowserDownloadCoordinator
import app.daiyu.android.browser.download.BrowserPathMapper
import app.daiyu.android.browser.host.BrowserToolSession
import app.daiyu.android.browser.network.BrowserCloseResult
import app.daiyu.android.browser.network.BrowserFetchBackend
import app.daiyu.android.browser.network.BrowserHttpDownloader
import app.daiyu.android.browser.runtime.AndroidBrowserUiDispatcher
import app.daiyu.android.browser.runtime.AsyncBrowserBackend
import app.daiyu.android.browser.runtime.BrowserQueuedExecutionPort
import app.daiyu.android.browser.session.BrowserGrant
import app.daiyu.android.browser.session.BrowserSessionRegistry
import app.daiyu.android.browser.session.BrowserTabLease
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

/** One trusted run/owner, one registry, one frozen grant. Construct on Android main Looper.
 * privateRoot must already be app-private; mapping and cookie-name access are explicit host grants.
 * This is the actual production composition, not a tool registry or a replacement grant factory.
 * Never create this per tool call: stop/child termination closes this same run-owned instance.
 */
class AndroidBrowserRunHost(
    context: Context,
    grant: BrowserGrant,
    privateRoot: Path,
    cookieEnvPath: (BrowserTabLease, Path) -> String,
    cookieAuthorizeName: (BrowserTabLease, String, String) -> Boolean,
    historyApproval: OpenMinisHistoryApproval = OpenMinisHistoryApproval.DENY,
    requestOverride: ((WebResourceRequest) -> WebResourceResponse?)? = null,
    onDownloadCompleted: (app.daiyu.android.browser.download.BrowserCompletedDownload) -> Unit = {},
) : AutoCloseable {
    private val ui = AndroidBrowserUiDispatcher()
    private val sessions = BrowserSessionRegistry()
    val webHost: AndroidOpenMinisBrowserHost
    private val cookies: BrowserCookieBackend
    private val fetch: BrowserFetchBackend
    val session: BrowserToolSession
    private val closeStarted = AtomicBoolean()
    private val invalidationFinished = AtomicBoolean()
    private val closeFailures = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    init {
        check(Looper.myLooper() == Looper.getMainLooper()) { "BROWSER_UI_REQUIRED" }
        // Fail before acquiring executors/WebView capability if the grant is disabled.
        sessions.open(grant)
        val root = privateRoot.toRealPath()
        require(root.startsWith(context.filesDir.toPath().toRealPath()) ||
            root.startsWith(context.cacheDir.toPath().toRealPath())) { "BROWSER_PRIVATE_ROOT_REQUIRED" }
        require(Files.isDirectory(root)) { "BROWSER_PRIVATE_ROOT_REQUIRED" }
        val downloadRoot = Files.createDirectories(root.resolve("downloads"))
        val cookieRoot = Files.createDirectories(root.resolve("cookie-offloads"))
        var partialWeb: AndroidOpenMinisBrowserHost? = null
        var partialCookies: BrowserCookieBackend? = null
        var partialHttp: BrowserHttpDownloader? = null
        var partialFetch: BrowserFetchBackend? = null
        try {
            webHost = AndroidOpenMinisBrowserHost(context, sessions, ui, historyApproval,
                operationTimeoutMillis = 55_000, requestOverride = requestOverride).also { partialWeb = it }
            cookies = BrowserCookieBackend(sessions, ui, AndroidCookieStore(), webHost::currentUrl,
                cookieRoot, cookieEnvPath, cookieAuthorizeName).also { partialCookies = it }
            val http = BrowserHttpDownloader(sessions,
                BrowserDownloadCoordinator(sessions, BrowserPathMapper(downloadRoot)).also {
                    it.observeCompleted(onDownloadCompleted)
                }).also { partialHttp = it }
            fetch = BrowserFetchBackend(sessions, http).also { partialFetch = it }
            val router = object : AsyncBrowserBackend {
                override fun execute(command: BrowserCommand, lease: BrowserTabLease): CompletableFuture<BrowserExecutionResult> =
                    when (command.action) {
                        "fetch" -> fetch.execute(command, lease)
                        "get_cookies", "set_cookies" -> cookies.execute(command, lease)
                        else -> webHost.execute(command, lease)
                    }
                override fun cancel(lease: BrowserTabLease) {
                    try { fetch.cancel(lease) } finally {
                        try { cookies.cancel(lease) } finally { webHost.cancel(lease) }
                    }
                }
            }
            session = BrowserToolSession(grant, sessions, webHost,
                BrowserQueuedExecutionPort(sessions, ui, router, waitTimeoutMillis = 60_000),
                preflight = OpenMinisCommandMapper::preflight)
        } catch (failure: Exception) {
            // The exception owns the partially built cleanup graph; callers can still poll/retry
            // rejected enqueues. Do not discard a downloader just because its wrapper failed.
            val cleanup = BrowserRunConstructionException(failure) {
                val errors = mutableSetOf("BROWSER_RUN_CONSTRUCTION")
                try { sessions.close(grant.owner) } catch (_: Exception) { errors.add("BROWSER_RUN_INVALIDATION") }
                var result = BrowserCloseResult(true, failures = errors)
                fun collect(code: String, action: () -> BrowserCloseResult) {
                    result = result.merge(try { action() } catch (_: Exception) {
                        BrowserCloseResult(true, pending = setOf(code), failures = setOf(code))
                    })
                }
                partialWeb?.let { collect("WEBVIEW_CLOSE") { it.closeWithResult() } }
                partialCookies?.let { collect("COOKIE_CLOSE") { it.closeWithResult() } }
                partialFetch?.let { collect("FETCH_CLOSE") { it.closeWithResult() } }
                    ?: partialHttp?.let { collect("HTTP_CLOSE") { it.closeWithResult() } }
                result
            }
            cleanup.closeWithResult()
            throw cleanup
        }
    }

    val currentTab: BrowserTabLease? get() = session.currentTab
    /** Worker-only for commands; the queued port rejects synchronous main-thread waits. */
    fun execute(arguments: Map<String, Any?>): BrowserOutcome = session.execute(arguments)
    /** Compatibility entry point: a request, never an assertion that native I/O has drained. */
    override fun close() { closeWithResult() }

    /** Nonblocking. Repeated calls retry rejected queues/file deletion, never native destroy. */
    fun closeWithResult(): BrowserCloseResult {
        if (closeStarted.compareAndSet(false, true)) {
            try { session.close() } catch (_: Exception) { closeFailures.add("BROWSER_RUN_INVALIDATION") }
            finally { invalidationFinished.set(true) }
        }
        try { webHost.closeWithResult() } catch (_: Exception) { closeFailures.add("WEBVIEW_CLOSE") }
        try { cookies.closeWithResult() } catch (_: Exception) { closeFailures.add("COOKIE_CLOSE") }
        try { fetch.closeWithResult() } catch (_: Exception) { closeFailures.add("FETCH_CLOSE") }
        return closeStatus()
    }

    /** Safe to poll from any thread. No Looper pumping, Future waits or new I/O. */
    fun closeStatus(): BrowserCloseResult =
        BrowserCloseResult(closeStarted.get(),
            pending = if (closeStarted.get() && !invalidationFinished.get()) setOf("BROWSER_RUN_INVALIDATION") else emptySet(),
            failures = closeFailures.toSet())
            .merge(webHost.closeStatus()).merge(cookies.closeStatus()).merge(fetch.closeStatus())
}

/** A failed constructor still owns acquired capabilities until this receipt is drained.
 * Trusted host only: do not serialize the cause (it may contain a private path).
 */
class BrowserRunConstructionException internal constructor(
    cause: Exception,
    private val cleanup: () -> BrowserCloseResult,
) : IllegalStateException("BROWSER_RUN_CONSTRUCTION_FAILED", cause) {
    fun closeWithResult(): BrowserCloseResult = cleanup()
}
