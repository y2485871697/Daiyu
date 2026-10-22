package app.daiyu.android.browser.ui

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.URLUtil
import app.daiyu.android.BrowserVisualVisit
import app.daiyu.android.browser.openminis.*
import com.openminis.app.browser.UserAgentProfile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.net.URLEncoder

internal class BrowserUiMemory(val key: String) {
    val downloads = MutableStateFlow<List<BrowserUiPool.DownloadEntry>>(emptyList())
    val pages = mutableMapOf<Int, BrowserVisiblePage>()
}

/** UI projection only: every tab operation goes back through the exact visit/lease. */
internal class BrowserUiPool(private val context: Context, val visit: BrowserVisualVisit?) {
    companion object {
        const val PREF_IDLE_TIMEOUT_MINUTES = "idle_timeout_minutes"
        const val DEFAULT_IDLE_TIMEOUT_MINUTES = 15
        const val MIN_IDLE_TIMEOUT_MINUTES = 1
        const val MAX_IDLE_TIMEOUT_MINUTES = 240
        fun middleTruncated(name: String, head: Int = 22, tail: Int = 10) =
            if (name.length <= head + tail + 1) name else name.take(head) + "…" + name.takeLast(tail)
        fun normalize(input: String): String {
            val value = input.trim()
            require(value.isNotEmpty() && value.length <= 4096 && value.none { it.code < 32 || it.code == 127 })
            if ("://" in value) return value
            val host = value.substringBefore('/').substringBefore('?').substringBefore('#')
            return if (!value.any(Char::isWhitespace) && ('.' in host || host.startsWith("localhost"))) "https://$value"
                else "https://www.bing.com/search?q=" + URLEncoder.encode(value, "UTF-8")
        }
    }
    private val prefs = context.getSharedPreferences("browser_prefs", Context.MODE_PRIVATE)
    private val models = mutableMapOf<Int, Tab>()
    val tabs = MutableStateFlow<List<Tab>>(emptyList())
    val selectedTabId = MutableStateFlow<Int?>(null)
    val busy = MutableStateFlow(visit == null)
    val currentUserAgentProfile = MutableStateFlow(runCatching {
        UserAgentProfile.valueOf(prefs.getString("user_agent_profile", UserAgentProfile.MOBILE_CHROME.name)!!)
    }.getOrDefault(UserAgentProfile.MOBILE_CHROME))
    val customViewportWidth = MutableStateFlow(prefs.getInt("browser_custom_viewport_width", 0))
    val customViewportHeight = MutableStateFlow(prefs.getInt("browser_custom_viewport_height", 0))
    private var customUserAgent = prefs.getString("custom_user_agent", null)
    val taskOwned get() = visit?.taskOwned == true
    private var deferredNavigation: String? = null
    private val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    enum class DownloadState { DOWNLOADING, COMPLETED, FAILED }
    data class DownloadEntry(val id: Long, val filename: String, val bytesDone: Long = 0,
        val totalBytes: Long = -1, val state: DownloadState = DownloadState.DOWNLOADING,
        val failureReason: String? = null, val seen: Boolean = false)
    data class DownloadProgress(val filename: String, val progress: Float)
    val downloads = visit?.memory?.downloads ?: MutableStateFlow<List<DownloadEntry>>(emptyList())
    private val downloadKey = "downloads:" + (visit?.memory?.key ?: "empty")
    val activeDownload = MutableStateFlow<DownloadProgress?>(null)
    val notice = MutableStateFlow("")
    init {
        if (downloads.value.isEmpty()) runCatching {
            val saved = org.json.JSONArray(prefs.getString(downloadKey, "[]"))
            downloads.value = (0 until minOf(saved.length(), 128)).mapNotNull { index ->
                val row = saved.getJSONObject(index)
                val id = row.getLong("id")
                if (id <= 0) null else DownloadEntry(id, row.getString("filename"),
                    state = DownloadState.valueOf(row.getString("state")), seen = row.optBoolean("seen"))
            }
        }
        visit?.configure(configuration())
        visit?.downloads(BrowserVisibleDownload(::download))
        refresh()
    }
    inner class Tab(val id: Int) {
        val manager = Page()
        inner class Page {
            val currentURL = MutableStateFlow("")
            val pageTitle = MutableStateFlow("")
            val isLoading = MutableStateFlow(false)
            val canGoBack = MutableStateFlow(false)
            val canGoForward = MutableStateFlow(false)
            fun loadURL(input: String) {
                val url = normalize(input)
                if (!OpenMinisNavigationPolicy.permits(url)) { notice.value = "此地址不能在网页中打开"; return }
                if (visit?.snapshot()?.mounted == true) visit.navigate(url) else deferredNavigation = url
                refresh()
            }
            fun goBack() = visit?.control(BrowserVisibleAction.BACK)
            fun goForward() = visit?.control(BrowserVisibleAction.FORWARD)
            fun reload() = visit?.control(BrowserVisibleAction.RELOAD)
            fun stopLoading() = visit?.control(BrowserVisibleAction.STOP)
        }
    }
    fun refresh() {
        val state = visit?.snapshot() ?: return
        notice.value = state.notice
        busy.value = !state.live || state.waiting || state.unknown || (state.tabs.isNotEmpty() && !state.takenOver)
        val liveIds = state.tabs.map { it.tabId }.toSet()
        models.keys.retainAll(liveIds)
        tabs.value = state.tabs.map { models.getOrPut(it.tabId) { Tab(it.tabId) } }
        selectedTabId.value = state.lease?.tabId
        val page = state.page ?: visit.memory.pages[state.lease?.tabId]
        models[selectedTabId.value]?.manager?.let { target ->
            if (page != null) {
                visit.memory.pages[checkNotNull(state.lease).tabId] = page
                target.currentURL.value = page.url; target.pageTitle.value = page.title
                target.isLoading.value = page.loading; target.canGoBack.value = page.canGoBack
                target.canGoForward.value = page.canGoForward
            }
        }
        if (state.mounted) deferredNavigation?.let { deferredNavigation = null; visit.navigate(it) }
    }
    fun ensureTabForUI() {
        val state = visit?.snapshot() ?: return
        if (state.tabs.isEmpty()) visit.newTab()
        else if (!state.takenOver && !state.waiting) state.lease?.let(visit::takeover)
        refresh()
    }
    fun newTabFromUI(): Tab? { visit?.newTab(); refresh(); return models[selectedTabId.value] }
    fun selectTab(id: Int) { visit?.snapshot()?.tabs?.firstOrNull { it.tabId == id }?.let { visit.selectTab(it) }; refresh() }
    fun closeTabFromUI(id: Int) {
        selectTab(id)
        if (visit?.snapshot()?.lease?.tabId == id && visit.snapshot().takenOver) visit.closeTab()
        refresh()
    }
    private fun configuration() = BrowserVisibleConfiguration(currentUserAgentProfile.value, customUserAgent,
        customViewportWidth.value, customViewportHeight.value)
    fun setUserAgentFromUI(profile: UserAgentProfile, customUA: String? = null) {
        if (profile == UserAgentProfile.CUSTOM && customUA.isNullOrBlank()) return
        currentUserAgentProfile.value = profile
        if (profile == UserAgentProfile.CUSTOM) customUserAgent = customUA
        prefs.edit().putString("user_agent_profile", profile.name).putString("custom_user_agent", customUserAgent).apply()
        visit?.configure(configuration())
    }
    fun setGlobalViewport(width: Int, height: Int) {
        require(width == 0 && height == 0 || width in 320..2560 && height in 320..2560)
        customViewportWidth.value = width; customViewportHeight.value = height
        prefs.edit().putInt("browser_custom_viewport_width", width).putInt("browser_custom_viewport_height", height).apply()
        visit?.configure(configuration())
    }
    fun resolvedViewportSize(): Pair<Int, Int> = if (customViewportWidth.value > 0 && customViewportHeight.value > 0)
        customViewportWidth.value to customViewportHeight.value else currentUserAgentProfile.value.viewportSize
    val idleTimeoutMinutes get() = prefs.getInt(PREF_IDLE_TIMEOUT_MINUTES, DEFAULT_IDLE_TIMEOUT_MINUTES).coerceIn(1, 240)
    fun setIdleTimeoutMinutes(minutes: Int) { prefs.edit().putInt(PREF_IDLE_TIMEOUT_MINUTES, minutes.coerceIn(1, 240)).apply() }
    fun takeControl() = ensureTabForUI()
    fun restore() { visit?.restore(); refresh() }
    fun stopTask() { visit?.stop(); refresh() }
    private fun download(url: String, userAgent: String, disposition: String?, mime: String?) {
        if (!OpenMinisNavigationPolicy.permits(url)) return
        val filename = URLUtil.guessFileName(url, disposition, mime).replace('/', '_').replace('\\', '_')
        try {
            val request = DownloadManager.Request(Uri.parse(url)).setTitle(filename)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(context, android.os.Environment.DIRECTORY_DOWNLOADS, "${System.nanoTime()}-$filename")
            request.addRequestHeader("User-Agent", userAgent)
            CookieManager.getInstance().getCookie(url)?.let { request.addRequestHeader("Cookie", it) }
            if (!mime.isNullOrBlank()) request.setMimeType(mime)
            val id = downloadManager.enqueue(request)
            downloads.value = (listOf(DownloadEntry(id, filename)) + downloads.value).take(128)
            saveDownloads()
        } catch (_: Exception) { notice.value = "下载未能启动" }
    }
    suspend fun refreshDownloads() {
        val current = downloads.value
        if (current.none { it.state == DownloadState.DOWNLOADING }) { updateDownloadProgress(); return }
        val updated = withContext(Dispatchers.IO) {
            current.map { entry ->
                if (entry.state != DownloadState.DOWNLOADING) entry else {
                    downloadManager.query(DownloadManager.Query().setFilterById(entry.id)).use { cursor ->
                        if (!cursor.moveToFirst()) entry.copy(state = DownloadState.FAILED, failureReason = "下载已取消")
                        else {
                            fun number(name: String) = cursor.getLong(cursor.getColumnIndexOrThrow(name))
                            val state = when (number(DownloadManager.COLUMN_STATUS).toInt()) {
                                DownloadManager.STATUS_SUCCESSFUL -> DownloadState.COMPLETED
                                DownloadManager.STATUS_FAILED -> DownloadState.FAILED
                                else -> DownloadState.DOWNLOADING
                            }
                            entry.copy(bytesDone = number(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
                                totalBytes = number(DownloadManager.COLUMN_TOTAL_SIZE_BYTES), state = state,
                                failureReason = if (state == DownloadState.FAILED) "下载失败（${number(DownloadManager.COLUMN_REASON)}）" else null)
                        }
                    }
                }
            }
        }
        // Retain explicit cancel/clear/seen changes made while the system query was running.
        downloads.value = downloads.value.map { latest ->
            if (latest.state != DownloadState.DOWNLOADING) latest
            else updated.firstOrNull { it.id == latest.id }?.copy(seen = latest.seen) ?: latest
        }
        saveDownloads()
    }
    private fun updateDownloadProgress() {
        activeDownload.value = downloads.value.firstOrNull { it.state == DownloadState.DOWNLOADING }?.let {
            DownloadProgress(it.filename, if (it.totalBytes > 0) it.bytesDone.toFloat() / it.totalBytes else -1f)
        }
    }
    private fun saveDownloads() {
        val saved = org.json.JSONArray()
        downloads.value.forEach { saved.put(org.json.JSONObject().put("id", it.id).put("filename", it.filename)
            .put("state", it.state.name).put("seen", it.seen)) }
        prefs.edit().putString(downloadKey, saved.toString()).apply()
        updateDownloadProgress()
    }
    fun downloadBadgeCount(entries: List<DownloadEntry>) = entries.count { it.state == DownloadState.DOWNLOADING || !it.seen }
    fun markDownloadsSeen() { downloads.value = downloads.value.map { if (it.state != DownloadState.DOWNLOADING) it.copy(seen = true) else it }; saveDownloads() }
    fun cancelDownload(id: Long) { downloadManager.remove(id); downloads.value = downloads.value.map {
        if (it.id == id) it.copy(state = DownloadState.FAILED, failureReason = "下载已取消") else it
    }; saveDownloads() }
    fun clearFinishedDownloads() { downloads.value = downloads.value.filter { it.state == DownloadState.DOWNLOADING }; saveDownloads() }
    fun openDownload(entry: DownloadEntry) {
        val uri = downloadManager.getUriForDownloadedFile(entry.id) ?: return
        val mime = downloadManager.getMimeTypeForDownloadedFile(entry.id) ?: "*/*"
        try { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: android.content.ActivityNotFoundException) { notice.value = "没有可打开此文件的应用" }
    }
}
