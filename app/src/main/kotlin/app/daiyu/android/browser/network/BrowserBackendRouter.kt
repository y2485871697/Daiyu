package app.daiyu.android.browser.network

import app.daiyu.android.browser.bridge.BrowserCommand
import app.daiyu.android.browser.bridge.BrowserExecutionResult
import app.daiyu.android.browser.runtime.AsyncBrowserBackend
import app.daiyu.android.browser.session.BrowserTabLease
import java.util.concurrent.CompletableFuture

/**
 * Trusted host composition, not a model-selectable transport or a cookie bridge.
 *
 * Construct BrowserHttpDownloader(sessions, downloadCoordinator, ...) in the trusted host, then
 * BrowserFetchBackend(sessions, httpDownloader), then BrowserBackendRouter(fetchBackend, webBackend).
 * Pass this router as BrowserQueuedExecutionPort(sessions, uiDispatcher, router)'s backend and use
 * that port in BrowserHostBridge / BrowserToolSession. All components must use the same registry.
 * Host teardown must close fetchBackend and the host-owned WebView backend; the router does not
 * acquire lifecycle or cookie ownership. Never call the synchronous HTTP downloader from execute.
 *
 * Only the exact "fetch" action goes to independent GET. Every other validated action, including
 * get_cookies/set_cookies, goes unchanged to the WebView backend. The trusted web backend must obey
 * AsyncBrowserBackend's bounded/nonblocking execute and cancel contract. No failure is retried or
 * redirected to the other backend. Host/bridge validation and lease admission remain authoritative.
 */
class BrowserBackendRouter(
    private val downloads: BrowserFetchBackend,
    private val webBackend: AsyncBrowserBackend,
) : AsyncBrowserBackend {
    override fun execute(
        command: BrowserCommand,
        lease: BrowserTabLease,
    ): CompletableFuture<BrowserExecutionResult> =
        if (command.action == "fetch") downloads.execute(command, lease)
        else webBackend.execute(command, lease)

    override fun cancel(lease: BrowserTabLease) {
        // Both backends may own concurrent requests. Forward the WHOLE lease to both even if one
        // cleanup rejects; neither a generation-reused tab nor another owner is selected here.
        try { downloads.cancel(lease) } finally { webBackend.cancel(lease) }
    }
}
