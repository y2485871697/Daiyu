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
) : AutoCloseable {
    private val ui = AndroidBrowserUiDispatcher()
    private val sessions = BrowserSessionRegistry()
    val webHost: AndroidOpenMinisBrowserHost
    private val cookies: BrowserCookieBackend
    private val fetch: BrowserFetchBackend
    val session: BrowserToolSession

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
        webHost = AndroidOpenMinisBrowserHost(context, sessions, ui, historyApproval,
            operationTimeoutMillis = 55_000, requestOverride = requestOverride)
        cookies = BrowserCookieBackend(sessions, ui, AndroidCookieStore(), webHost::currentUrl,
            cookieRoot, cookieEnvPath, cookieAuthorizeName)
        fetch = BrowserFetchBackend(sessions, BrowserHttpDownloader(sessions,
            BrowserDownloadCoordinator(sessions, BrowserPathMapper(downloadRoot))))
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
    }

    val currentTab: BrowserTabLease? get() = session.currentTab
    /** Worker-only for commands; the queued port rejects synchronous main-thread waits. */
    fun execute(arguments: Map<String, Any?>): BrowserOutcome = session.execute(arguments)
    /** Invalidates first; best-effort bounded backend cleanup still runs when any close throws. */
    override fun close() {
        try { session.close() } finally { try { cookies.close() } finally { fetch.close() } }
    }
}
