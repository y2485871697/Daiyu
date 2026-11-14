package app.daiyu.android.browser.openminis

import app.daiyu.android.browser.session.BrowserTabLease
import java.net.URI
import java.util.Locale

/** 入口 URL、页面导航和子资源共用 HTTP(S) 策略，并排除 Google 登录。 */
object OpenMinisNavigationPolicy {
    private val authHosts = setOf(
        "accounts.google.com", "signin.google.com", "myaccount.google.com",
        "oauth2.googleapis.com", "accounts.youtube.com",
    )

    fun isGoogleAuth(url: String?): Boolean {
        val host = runCatching { URI(url ?: return false).host?.lowercase(Locale.ROOT) }.getOrNull()
            ?: return false
        return authHosts.any { host == it || host.endsWith(".$it") }
    }

    fun permits(url: String?): Boolean = runCatching {
        if (url == null) return false
        val uri = URI(url)
        uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") &&
            !uri.host.isNullOrBlank() && uri.userInfo == null && !isGoogleAuth(url)
    }.getOrDefault(false)
}

/** A temporarily unbound execution lease denies requests without retiring its reusable allocation. */
internal fun pageRequestBlocked(live: Boolean, url: String?, reject: (String) -> Unit): Boolean {
    if (!live) return true
    if (OpenMinisNavigationPolicy.permits(url)) return false
    reject("BROWSER_OPENMINIS_PAGE_URL_BLOCKED")
    return true
}

/**
 * 由宿主决定是否通过固定管理器的回调记录 URL 和标题。
 * 历史记录由整个应用共用，已排队的写入可在 close 返回后落盘。
 * 回调在主线程运行，需限制耗时、避免 I/O 和重入归属变更。
 * 授权采用固定的宿主策略，默认拒绝，包括重定向；模型参数不能改变授权。
 */
fun interface OpenMinisHistoryApproval {
    fun allowUpstreamRecord(lease: BrowserTabLease, url: String, title: String): Boolean

    companion object {
        val DENY = OpenMinisHistoryApproval { _, _, _ -> false }
    }
}
