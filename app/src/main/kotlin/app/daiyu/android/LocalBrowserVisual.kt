package app.daiyu.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import app.daiyu.android.browser.bridge.BrowserOutcome
import app.daiyu.android.browser.host.BrowserToolTakeoverToken
import app.daiyu.android.browser.openminis.*
import app.daiyu.android.browser.session.*
import app.daiyu.android.domain.ConversationId
import java.nio.file.Files
import java.util.UUID

/** Explicit user browsing has its own resource owner, never a fabricated model run or approval. */
internal class LocalBrowserVisual(context: Context, private val conversation: ConversationId?) : AutoCloseable {
    private val application = context.applicationContext
    private val host = AndroidBrowserRunHost(application,
        BrowserGrant(BrowserOwner("human-browser:${UUID.randomUUID()}", BrowserOwnerKind.MAIN), BrowserAccess.FULL),
        Files.createDirectories(application.cacheDir.toPath().resolve("human-browser").resolve(UUID.randomUUID().toString())),
        cookieEnvPath = { _, _ -> error("HUMAN_BROWSER_NO_COOKIE_EXPORT") },
        cookieAuthorizeName = { _, _, _ -> false })
    private val uiMemory = app.daiyu.android.browser.ui.BrowserUiMemory("human:${conversation?.value ?: "draft"}")
    private val prefs = application.getSharedPreferences("browser_prefs", Context.MODE_PRIVATE)
    private val stateKey = "tabs:" + uiMemory.key
    private val restoreUrls = mutableMapOf<Int, String>()
    private var token: BrowserToolTakeoverToken? = null
    private var attachment: BrowserVisibleAttachment? = null
    private var parent: ViewGroup? = null
    private var active: Visit? = null
    private var closed = false
    private var unknown = false
    private var configuration: BrowserVisibleConfiguration? = null
    private var downloadListener: BrowserVisibleDownload? = null
    private val handler = Handler(Looper.getMainLooper())
    private val idleClose = Runnable { if (active == null) close() }
    val needsReplacement: Boolean get() = closed || unknown
    private fun requireMain() = check(Looper.myLooper() == Looper.getMainLooper())
    private fun saveTabs() {
        if (unknown) return
        val urls = org.json.JSONArray()
        host.session.visibleTabs.forEach { lease ->
            val url = restoreUrls[lease.tabId] ?: uiMemory.pages[lease.tabId]?.url.orEmpty()
            if (OpenMinisNavigationPolicy.permits(url)) urls.put(url)
        }
        prefs.edit().putString(stateKey, urls.toString()).apply()
    }
    fun openVisit(): BrowserVisualVisit? {
        requireMain()
        if (closed) return null
        active?.close()
        handler.removeCallbacks(idleClose)
        return Visit().also { visit ->
            active = visit
            if (host.session.visibleTabs.isEmpty()) {
                val saved = runCatching { org.json.JSONArray(prefs.getString(stateKey, "[]")) }.getOrNull()
                if (saved != null) for (index in 0 until minOf(saved.length(), 3)) {
                    val url = saved.optString(index)
                    if (OpenMinisNavigationPolicy.permits(url)) {
                        visit.newTab()
                        token?.lease?.let { restoreUrls[it.tabId] = url }
                    }
                }
                host.session.visibleTabs.firstOrNull()?.let(visit::selectTab)
            }
        }
    }
    private fun detach() {
        attachment?.close()
        attachment = null; parent = null
    }
    private fun release() {
        detach()
        token?.let { host.session.releaseTakeover(it) }
        token = null
    }
    private fun acquire(lease: BrowserTabLease) {
        host.session.selectVisibleTab(lease)
        token = host.session.takeover(lease)
        check(token?.cleanupFailure == null)
    }
    private inner class Visit : BrowserVisualVisit {
        private var live = true
        override val conversationId get() = conversation
        override val memory get() = uiMemory
        override val taskOwned: Boolean get() = false
        private fun current() = live && active === this && !closed
        private inline fun change(action: () -> Unit) {
            requireMain()
            if (!current() || unknown) return
            try { action() } catch (_: Exception) { unknown = true }
        }
        override fun snapshot(): ControllerBrowserVisual.State {
            requireMain()
            val page = if (current() && !unknown) try { attachment?.page() } catch (_: Exception) { unknown = true; null } else null
            if (page != null && token != null) uiMemory.pages[checkNotNull(token).lease.tabId] = page
            return ControllerBrowserVisual.State(live = current(), lease = token?.lease ?: host.session.currentTab,
                takenOver = token != null, mounted = attachment != null, unknown = unknown,
                notice = if (unknown) "浏览器操作结果未确认，请关闭后重新打开。" else "手动浏览器；没有运行中的任务。网站登录状态可能共享。",
                tabs = if (closed) emptyList() else host.session.visibleTabs, page = page)
        }
        override fun takeover(lease: BrowserTabLease) = selectTab(lease)
        override fun newTab() = change {
            if (host.session.visibleTabs.size >= 3) return@change
            release()
            when (host.session.execute(mapOf("action" to "new_tab"))) {
                is BrowserOutcome.Confirmed -> acquire(checkNotNull(host.session.currentTab))
                BrowserOutcome.Unknown -> unknown = true
                is BrowserOutcome.NotExecuted -> Unit
            }
        }
        override fun selectTab(lease: BrowserTabLease) = change {
            if (token?.lease == lease) return@change
            if (lease !in host.session.visibleTabs) return@change
            release()
            val target = host.session.visibleTabs.first { it.tabId == lease.tabId }
            acquire(target)
        }
        override fun mount(parent: ViewGroup) = change {
            if (this@LocalBrowserVisual.parent === parent && attachment != null) return@change
            detach()
            val held = token ?: return@change
            attachment = host.webHost.attach(held, parent)
            this@LocalBrowserVisual.parent = parent
            configuration?.let { attachment?.configure(it) }
            attachment?.downloads(downloadListener)
            restoreUrls.remove(held.lease.tabId)?.let { attachment?.navigate(it) }
        }
        override fun unmount(parent: ViewGroup) = change {
            if (this@LocalBrowserVisual.parent === parent) detach()
        }
        override fun navigate(url: String) = change { attachment?.navigate(url) }
        override fun control(action: BrowserVisibleAction) = change { attachment?.control(action) }
        override fun configure(configuration: BrowserVisibleConfiguration) = change {
            this@LocalBrowserVisual.configuration = configuration
            attachment?.configure(configuration)
        }
        override fun downloads(listener: BrowserVisibleDownload?) = change {
            downloadListener = listener; attachment?.downloads(listener)
        }
        override fun closeTab() = change {
            val held = token ?: return@change
            host.session.closeTab(held); token = null; detach()
            uiMemory.pages.remove(held.lease.tabId); restoreUrls.remove(held.lease.tabId)
            host.session.currentTab?.let(::acquire)
        }
        override fun restore() { /* Human sessions have no model execution to resume. */ }
        override fun stop() = control(BrowserVisibleAction.STOP)
        override fun close() {
            requireMain()
            if (!live) return
            live = false
            if (active === this) {
                try { attachment?.page()?.let { page -> token?.lease?.let { uiMemory.pages[it.tabId] = page } } } catch (_: Exception) { }
                saveTabs()
                active = null
                try { detach() } catch (_: Exception) { unknown = true }
                val minutes = application.getSharedPreferences("browser_prefs", Context.MODE_PRIVATE)
                    .getInt("idle_timeout_minutes", 15).coerceIn(1, 240)
                if (!closed) handler.postDelayed(idleClose, minutes * 60_000L)
            }
        }
    }
    override fun close() {
        requireMain()
        if (closed) return
        saveTabs()
        closed = true; handler.removeCallbacks(idleClose)
        active?.close(); active = null
        try { detach() } finally { host.close() }
    }
}
