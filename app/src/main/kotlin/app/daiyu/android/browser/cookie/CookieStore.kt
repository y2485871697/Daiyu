package app.daiyu.android.browser.cookie

/** All methods and callbacks run on the same UI dispatcher/Looper as the owning WebView. */
interface CookieStore {
    /** Secret transport only. Never send this header to a tool, logger or upstream browser adapter. */
    fun getCookie(url: String): String?
    fun setCookie(url: String, header: String, callback: (Boolean) -> Unit)
}
