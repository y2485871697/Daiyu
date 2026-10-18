package app.daiyu.android.browser.openminis

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.http.SslError
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import app.daiyu.android.browser.bridge.BrowserCommand
import app.daiyu.android.browser.bridge.BrowserExecutionResult
import app.daiyu.android.browser.host.BrowserToolTakeoverToken
import app.daiyu.android.browser.host.BrowserTabLifecycle
import app.daiyu.android.browser.runtime.AsyncBrowserBackend
import app.daiyu.android.browser.runtime.UiDispatcher
import app.daiyu.android.browser.session.BrowserSessionRegistry
import app.daiyu.android.browser.session.BrowserTabLease
import com.openminis.app.browser.BrowserAction
import com.openminis.app.browser.BrowserActionInput
import com.openminis.app.browser.BrowserActionResult
import com.openminis.app.browser.BrowserUseManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.yield
import java.io.ByteArrayInputStream
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.resume

/** One exact UI attachment, never a tab-number or model capability. */
class BrowserVisibleAttachment internal constructor(
    private val ui: UiDispatcher,
    private val navigateAction: (String) -> Unit,
    private val loadingAction: () -> Boolean,
    private val detachAction: () -> Unit,
) : AutoCloseable {
    private var closed = false
    fun navigate(url: String) {
        check(ui.isUiThread()) { "BROWSER_DISPATCH_NOT_UI" }
        check(!closed) { "BROWSER_VISIBLE_CLOSED" }
        navigateAction(url)
    }
    val isLoading: Boolean get() {
        check(ui.isUiThread()) { "BROWSER_DISPATCH_NOT_UI" }
        check(!closed) { "BROWSER_VISIBLE_CLOSED" }
        return loadingAction()
    }
    override fun close() {
        check(ui.isUiThread()) { "BROWSER_DISPATCH_NOT_UI" }
        if (closed) return
        closed = true
        detachAction()
    }
}

/**
 * Android-only composition boundary, not a tool registration or grant factory. Construct on the
 * trusted host side and pass the SAME registry to this adapter, queue port, and BrowserToolSession.
 * Lifecycle creates no manager, performs no navigation and does not touch CookieManager.
 */
class AndroidOpenMinisBrowserHost(
    context: Context,
    sessions: BrowserSessionRegistry,
    private val ui: UiDispatcher,
    historyApproval: OpenMinisHistoryApproval = OpenMinisHistoryApproval.DENY,
    createTimeoutMillis: Long = 5_000,
    operationTimeoutMillis: Long = 8_000,
    // Trusted composition/test seam only. Policy always runs before this request-thread hook.
    requestOverride: ((WebResourceRequest) -> WebResourceResponse?)? = null,
) : AsyncBrowserBackend, BrowserTabLifecycle {
    private val delegate = LeaseBrowserHost(
        sessions, ui, Dispatchers.Main,
        { lease, reject, live ->
            val application = context.applicationContext
            val cache = application.cacheDir.toPath().toRealPath().toFile()
            // Normalize the trusted app-cache alias before the pinned producer appends its filename.
            val browserContext = object : ContextWrapper(application) {
                override fun getCacheDir() = cache
            }
            AndroidBlankResource(browserContext, sessions, ui, lease, reject, live, historyApproval, requestOverride, operationTimeoutMillis)
        },
        createTimeoutMillis, operationTimeoutMillis,
    )

    override val supportsTakeover: Boolean get() = delegate.supportsTakeover
    override fun create(lease: BrowserTabLease) = delegate.create(lease)
    override fun rebind(allocation: BrowserTabLease, lease: BrowserTabLease) = delegate.rebind(allocation, lease)
    override fun beforeReleaseTakeover(allocation: BrowserTabLease, lease: BrowserTabLease) =
        delegate.beforeReleaseTakeover(allocation, lease)

    private fun <T> withVisibleResource(
        token: BrowserToolTakeoverToken, requireIdle: Boolean,
        block: (AndroidBlankResource, BrowserTabLease) -> T,
    ): T {
        check(ui.isUiThread()) { "BROWSER_DISPATCH_NOT_UI" }
        return token.session.withTakenOverTab(token) { allocation, lease ->
            delegate.withTakenOverResource(allocation, lease, requireIdle) {
                block(it as AndroidBlankResource, lease)
            }
        }
    }

    /** Reuses the original WebView; no navigation or tab creation occurs here. */
    fun attach(token: BrowserToolTakeoverToken, container: ViewGroup): BrowserVisibleAttachment =
        withVisibleResource(token, requireIdle = true) { resource, lease ->
            val identity = Any()
            resource.attach(identity, container, lease)
            BrowserVisibleAttachment(ui,
                navigateAction = { url ->
                    withVisibleResource(token, requireIdle = false) { current, _ ->
                        check(current === resource) { "BROWSER_STALE_TAB" }
                        resource.navigateVisible(identity, url)
                    }
                },
                loadingAction = {
                    withVisibleResource(token, requireIdle = false) { current, _ ->
                        check(current === resource) { "BROWSER_STALE_TAB" }
                        resource.visibleLoading(identity)
                    }
                },
                // Cleanup remains legal after token/owner revocation, for this instance only.
                detachAction = { resource.detach(identity) },
            )
        }

    fun retryCleanup(lease: BrowserTabLease) = delegate.retryCleanup(lease)
    fun cleanupFailure(lease: BrowserTabLease): Exception? = delegate.cleanupFailure(lease)
    fun closeWithResult() = delegate.closeWithResult()
    fun closeStatus() = delegate.closeStatus()
    /** Trusted UI-side cookie composition lookup; delegate fences the complete execution lease. */
    fun currentUrl(lease: BrowserTabLease): String? {
        check(ui.isUiThread()) { "BROWSER_DISPATCH_NOT_UI" }
        return try {
            delegate.withResource(lease) { (it as AndroidBlankResource).currentUrl() }
        } catch (failure: IllegalStateException) {
            if (failure.message in setOf(
                    "BROWSER_SESSION_MISSING", "BROWSER_SESSION_CLOSED", "BROWSER_STALE_SESSION",
                    "BROWSER_STALE_TAB", "BROWSER_TAB_MISSING", "BROWSER_TAKEN_OVER",
                    "BROWSER_OPENMINIS_RESOURCE_MISSING", "BROWSER_OPENMINIS_RESOURCE_RETIRED",
                )) null else throw failure
        }
    }
    override fun close(lease: BrowserTabLease) = delegate.close(lease)
    override fun cancel(lease: BrowserTabLease) = delegate.cancel(lease)
    override fun execute(command: BrowserCommand, lease: BrowserTabLease): CompletableFuture<BrowserExecutionResult> =
        delegate.execute(command, lease)
}

private class AndroidBlankResource(
    private val context: Context,
    private val sessions: BrowserSessionRegistry,
    private val ui: UiDispatcher,
    private var lease: BrowserTabLease,
    private val reject: (String) -> Unit,
    private val live: () -> Boolean,
    private val history: OpenMinisHistoryApproval,
    private val requestOverride: ((WebResourceRequest) -> WebResourceResponse?)?,
    private val operationTimeoutMillis: Long,
) : BlankBrowserResource {
    @Volatile private var destroyed = false
    @Volatile private var admissionRevoked = false
    @Volatile private var visibleIdentity: Any? = null
    @Volatile private var visibleLease: BrowserTabLease? = null
    private var visibleParent: ViewGroup? = null
    private fun resourceLive(): Boolean {
        if (destroyed || admissionRevoked) return false
        if (live()) return true
        val original = visibleLease ?: return false
        return try {
            sessions.publishTakenOver(original) { !destroyed && !admissionRevoked && visibleIdentity != null && visibleLease == original }
        } catch (_: IllegalStateException) { false }
    }
    private val navigation = NavigationCallbacks(::resourceLive, { url, title ->
        // Read the rebound lease at each hook, not the allocation/constructor snapshot.
        history.allowUpstreamRecord(lease, url, title)
    }, reject)
    private val view = GuardedWebView(context, ::resourceLive, reject, navigation, ::callbackFence)
    private var manager: BrowserUseManager? = null
    private var visualRequest = 0L

    // All post-suspension callbacks acquire registry before navigation. No disk I/O in this fence:
    // the selected history compatibility store only publishes state and enqueues its private writer.
    private fun callbackFence(action: () -> Unit) {
        check(ui.isUiThread()) { "BROWSER_DISPATCH_NOT_UI" }
        check(resourceLive()) { "BROWSER_OPENMINIS_RESOURCE_RETIRED" }
        val human = visibleLease
        if (human != null && visibleIdentity != null) sessions.publishTakenOver(human) { action() }
        else sessions.publish(lease) { action() }
    }

    override fun initialize() {
        // This phase runs only after LeaseBrowserHost owns the destroyable allocation.
        // Offscreen resource has no Activity/window layout pass. Give the real WebView a bounded
        // mobile viewport so JS metrics and upstream bitmap capture have non-zero dimensions.
        val density = context.resources.displayMetrics.density
        val width = (360 * density).toInt().coerceAtLeast(1)
        val height = (800 * density).toInt().coerceAtLeast(1)
        view.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(height, android.view.View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)
    }

    override val isIdle: Boolean
        get() = !destroyed && !admissionRevoked && navigation.phase == NavigationCallbacks.Phase.IDLE

    fun currentUrl(): String? {
        check(!destroyed && live()) { "BROWSER_OPENMINIS_RESOURCE_RETIRED" }
        return view.url
    }

    fun attach(identity: Any, parent: ViewGroup, takenOver: BrowserTabLease) {
        check(!destroyed && visibleIdentity == null && view.parent == null && parent.childCount == 0) {
            "BROWSER_VISIBLE_ALREADY_ATTACHED"
        }
        check(navigation.phase == NavigationCallbacks.Phase.IDLE) { "BROWSER_OPENMINIS_NATIVE_ACTIVE" }
        visibleLease = takenOver
        visibleIdentity = identity
        visibleParent = parent
        try {
            navigation.enterManual()
            parent.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        } catch (failure: Exception) {
            // addView may have partially succeeded. The host owns teardown of the original view.
            visibleIdentity = null
            visibleLease = null
            visibleParent = null
            navigation.retire()
            reject("BROWSER_VISIBLE_ATTACH_FAILED")
            throw failure
        }
    }

    fun navigateVisible(identity: Any, url: String) {
        check(visibleIdentity === identity && resourceLive()) { "BROWSER_VISIBLE_EXPIRED" }
        check(navigation.phase == NavigationCallbacks.Phase.IDLE) { "BROWSER_OPENMINIS_NATIVE_ACTIVE" }
        ensureManager()
        view.loadUrl(url)
    }

    fun visibleLoading(identity: Any): Boolean {
        check(visibleIdentity === identity && resourceLive()) { "BROWSER_VISIBLE_EXPIRED" }
        return navigation.phase != NavigationCallbacks.Phase.IDLE
    }

    fun detach(identity: Any) {
        check(ui.isUiThread()) { "BROWSER_DISPATCH_NOT_UI" }
        if (visibleIdentity !== identity) return
        val parent = visibleParent
        visibleIdentity = null
        visibleLease = null
        visibleParent = null
        try {
            check(view.parent == null || view.parent === parent) { "BROWSER_VISIBLE_PARENT_CHANGED" }
            parent?.removeView(view)
            if (admissionRevoked || destroyed) { navigation.retire(); return }
            check(!destroyed && navigation.phase == NavigationCallbacks.Phase.IDLE) { "BROWSER_OPENMINIS_NATIVE_ACTIVE" }
            navigation.leaveManual()
        } catch (failure: Exception) {
            navigation.retire()
            reject("BROWSER_VISIBLE_DETACH_UNKNOWN")
            throw failure
        }
    }

    override fun beforeReleaseTakeover() {
        check(!destroyed && visibleIdentity == null && !navigation.isManual) { "BROWSER_VISIBLE_ATTACHED" }
        check(navigation.phase == NavigationCallbacks.Phase.IDLE) { "BROWSER_OPENMINIS_NATIVE_ACTIVE" }
    }

    override fun rebind(lease: BrowserTabLease) {
        check(!destroyed && visibleIdentity == null && !navigation.isManual && navigation.phase == NavigationCallbacks.Phase.IDLE) {
            "BROWSER_OPENMINIS_NAVIGATION_UNKNOWN"
        }
        this.lease = lease
    }

    private fun ensureManager(): BrowserUseManager = manager ?: BrowserUseManager(view).also { created ->
        manager = created
        // The fixed constructor configures cookies/JS. This happens only AFTER posted fence.
        // Enforce file/content restrictions after configureWebView, before any navigation.
        view.settings.apply {
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        view.webViewClient = GuardedClient(view.webViewClient, ui, ::resourceLive, reject, navigation, requestOverride, ::callbackFence)
        // No downloads/blob JS, new windows, external activities, or implicit tab mutation.
        view.setDownloadListener { _, _, _, _, _ ->
            navigation.failed("BROWSER_OPENMINIS_DOWNLOAD_UNSUPPORTED")
        }
        created.onNewWindow = null
        created.onCloseWindow = { navigation.failed("BROWSER_OPENMINIS_PAGE_CLOSE_UNSUPPORTED") }
    }

    override suspend fun execute(input: BrowserActionInput): BrowserActionResult {
        check(!destroyed && live()) { "BROWSER_OPENMINIS_RESOURCE_RETIRED" }
        val navigating = input.action == BrowserAction.NAVIGATE
        try {
            if (navigating) {
                check(navigation.begin(checkNotNull(input.url), operationTimeoutMillis)) { "BROWSER_OPENMINIS_NAVIGATION_NOT_ADMITTED" }
            }
            check(live()) { "BROWSER_OPENMINIS_RESOURCE_RETIRED" }
            val active = ensureManager()
            check(live()) { "BROWSER_OPENMINIS_RESOURCE_RETIRED" }
            if (input.action == BrowserAction.SCREENSHOT) awaitVisualState()
            // Actual pinned upstream implementation, not an emulated action engine.
            val result = active.execute(input)
            if (navigating) {
                // Upstream completes its deferred BEFORE recording history. Let the callback
                // unwind (and the gate commit FINISHED) before publishing the operation result.
                yield()
                check(navigation.returned(result.success)) { "BROWSER_OPENMINIS_NAVIGATION_UNKNOWN" }
            }
            check(live()) { "BROWSER_OPENMINIS_RESOURCE_RETIRED" }
            // The mapper decodes a strict private body frame. Do not corrupt its length or
            // consume queued dialog diagnostics; the next ordinary result may report them.
            val privateBodyFrame = input.action == BrowserAction.EXECUTE_JS &&
                input.script?.contains("__DAIYU_BROWSER_BODY_V1__") == true
            if (privateBodyFrame) return result
            val dialogs = active.drainInterceptedDialogReport()
            return if (dialogs == null) result else result.copy(text = result.text + "\n" + dialogs)
        } catch (failure: Exception) {
            // Includes coroutine cancellation and an upstream apparent success without finish.
            // Fixed manager deferreds have no token; never re-use after any unknown execution.
            navigation.retire()
            throw failure
        }
    }

    /** finish is not a visual readiness guarantee. Explicit screenshots fence the current state. */
    private suspend fun awaitVisualState() = suspendCancellableCoroutine<Unit> { continuation ->
        val request = ++visualRequest
        view.postVisualStateCallback(request, object : WebView.VisualStateCallback() {
            override fun onComplete(requestId: Long) {
                if (requestId == request && continuation.isActive && !destroyed && live()) {
                    continuation.resume(Unit)
                }
            }
        })
    }

    override fun revokeAdmission() { admissionRevoked = true }

    override fun destroy() {
        if (destroyed) return
        destroyed = true
        visibleIdentity = null
        visibleLease = null
        visibleParent = null
        navigation.retire()
        view.disposed = true
        var failure: Exception? = null
        fun attempt(action: () -> Unit) {
            try { action() } catch (e: Exception) {
                if (failure == null) failure = e else failure?.addSuppressed(e)
            }
        }
        manager?.apply {
            onNewWindow = null
            onCloseWindow = null
            onDownloadStart = null
            onBlobDownloadData = null
        }
        manager = null
        attempt { view.removeJavascriptInterface("__minis__") }
        attempt { view.setDownloadListener(null) }
        attempt { view.stopLoading() }
        attempt { view.webViewClient = WebViewClient() }
        attempt { view.webChromeClient = null }
        attempt { (view.parent as? ViewGroup)?.removeView(view) }
        attempt { view.removeAllViews() }
        attempt { view.destroy() }
        failure?.let { throw it }
    }
}

/** Defence in depth for direct manager loads as well as callback-mediated navigations. */
private class GuardedWebView(
    context: Context,
    private val live: () -> Boolean,
    private val reject: (String) -> Unit,
    private val navigation: NavigationCallbacks,
    private val callbackFence: (() -> Unit) -> Unit,
) : WebView(context) {
    var disposed = false

    override fun loadUrl(url: String) {
        check(!disposed && live()) { "BROWSER_OPENMINIS_RESOURCE_RETIRED" }
        if (!OpenMinisNavigationPolicy.permits(url)) {
            navigation.failed("BROWSER_OPENMINIS_URL_BLOCKED")
            error("BROWSER_OPENMINIS_URL_BLOCKED")
        }
        val admitted = if (navigation.isManual) navigation.manualOverride(url, true) else navigation.directLoad(url)
        check(admitted) { "BROWSER_OPENMINIS_NAVIGATION_UNKNOWN" }
        super.loadUrl(url)
    }

    override fun evaluateJavascript(script: String, resultCallback: ValueCallback<String>?) {
        // Fixed upstream posts delayed resize JS; do not let it touch a destroyed WebView.
        if (!disposed && live() && !navigation.isManual) {
            try { callbackFence { if (!disposed && live()) super.evaluateJavascript(script, resultCallback) } }
            catch (failure: IllegalStateException) {
                if (live()) throw failure
            }
        }
    }
}

private class GuardedClient(
    private val upstream: WebViewClient,
    private val ui: UiDispatcher,
    private val live: () -> Boolean,
    private val reject: (String) -> Unit,
    private val navigation: NavigationCallbacks,
    private val requestOverride: ((WebResourceRequest) -> WebResourceResponse?)?,
    private val callbackFence: (() -> Unit) -> Unit,
) : WebViewClient() {
    private fun blocked(url: String?): Boolean {
        if (!live()) { navigation.retire(); return true }
        if (OpenMinisNavigationPolicy.permits(url)) return false
        navigation.failed("BROWSER_OPENMINIS_PAGE_URL_BLOCKED")
        return true
    }

    private fun fenced(action: () -> Unit) {
        try { callbackFence(action) } catch (_: IllegalStateException) { navigation.retire() }
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        var denied = true
        fenced {
            val url = request.url.toString()
            if (!blocked(url)) {
                if (navigation.isManual) denied = !navigation.manualOverride(url, request.isForMainFrame)
                else if (navigation.allowOverride(url, request.isForMainFrame) && live())
                    denied = upstream.shouldOverrideUrlLoading(view, request)
            }
        }
        return denied
    }

    @Suppress("DEPRECATION")
    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
        var denied = true
        fenced {
            if (!blocked(url)) {
                if (navigation.isManual) denied = !navigation.manualOverride(url, true)
                else if (navigation.allowOverride(url, true) && live())
                    denied = upstream.shouldOverrideUrlLoading(view, url)
            }
        }
        return denied
    }

    // Request callbacks are not UI callbacks. Never take navigation's monitor on that thread:
    // UI owns registry -> navigation ordering, and a request must not invert it.
    private fun requestBlocked(url: String?): Boolean {
        if (live() && OpenMinisNavigationPolicy.permits(url)) return false
        postRequestFailure("BROWSER_OPENMINIS_PAGE_URL_BLOCKED")
        return true
    }
    private fun postRequestFailure(reason: String) {
        try { ui.post { navigation.failed(reason) } } catch (_: Exception) { reject(reason) }
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        // Runs on WebView's request thread. Never approve history or touch native view here.
        if (requestBlocked(request.url.toString())) return denied()
        val replacement = try { requestOverride?.invoke(request) } catch (_: Exception) {
            postRequestFailure("BROWSER_OPENMINIS_REQUEST_OVERRIDE_FAILED")
            return denied()
        }
        if (!live()) return denied()
        return replacement ?: upstream.shouldInterceptRequest(view, request)
    }

    @Suppress("DEPRECATION")
    override fun shouldInterceptRequest(view: WebView, url: String): WebResourceResponse? =
        if (requestBlocked(url)) denied() else upstream.shouldInterceptRequest(view, url)

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        fenced {
            if (blocked(url) || !navigation.onStarted(url)) { view.stopLoading(); return@fenced }
            if (!navigation.isManual && live()) upstream.onPageStarted(view, url, favicon)
        }
    }

    override fun onPageFinished(view: WebView, url: String?) {
        fenced {
            if (blocked(url)) return@fenced
            navigation.onFinished(url, view.title.orEmpty(), view.url) {
                upstream.onPageFinished(view, url)
            }
        }
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (request.isForMainFrame) {
            // Upstream completes navigationDeferred on errors and then reports success. Do not.
            navigation.failed("BROWSER_OPENMINIS_NAVIGATION_FAILED")
        } else if (live()) upstream.onReceivedError(view, request, error)
    }

    @Suppress("DEPRECATION")
    override fun onReceivedError(view: WebView, errorCode: Int, description: String?, failingUrl: String?) {
        navigation.failed("BROWSER_OPENMINIS_NAVIGATION_FAILED")
    }

    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
        if (request.isForMainFrame) navigation.failed("BROWSER_OPENMINIS_HTTP_FAILED")
        else if (live()) upstream.onReceivedHttpError(view, request, response)
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        handler.cancel()
        navigation.failed("BROWSER_OPENMINIS_SSL_FAILED")
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        navigation.failed("BROWSER_OPENMINIS_RENDERER_FAILED")
        return true // The lease host schedules terminal native teardown.
    }

    private fun denied() = WebResourceResponse(
        "text/plain", "UTF-8", 403, "Forbidden", emptyMap(),
        ByteArrayInputStream("BROWSER_OPENMINIS_URL_BLOCKED".toByteArray(Charsets.UTF_8)),
    )
}
