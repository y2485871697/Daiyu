package app.daiyu.android.browser.openminis

import app.daiyu.android.browser.session.BrowserTabLease
import java.net.URI
import java.util.Locale

/** Independent policy used both for entry URLs and page/subresource navigations. No local fallback. */
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
 * Trusted host hook, NOT a model parameter. Returning true explicitly authorizes the FIXED
 * manager callback to accept this URL/title into the app-global compatibility history store.
 * The selected adapter history implementation queues disk I/O; events accepted before close may
 * persist afterward. This is not a promise that close waits for disk or that accounts are isolated.
 * Called on UI; must be bounded, must not reenter ownership mutation, and must not do I/O.
 * Default denies, including redirects. Consent is frozen trusted-host policy, not model input.
 */
fun interface OpenMinisHistoryApproval {
    fun allowUpstreamRecord(lease: BrowserTabLease, url: String, title: String): Boolean

    companion object {
        val DENY = OpenMinisHistoryApproval { _, _, _ -> false }
    }
}
