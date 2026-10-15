package app.daiyu.android.browser.cookie

import android.os.Looper
import android.webkit.CookieManager

/** Construct on the WebView's UI Looper. No synchronous setCookie overload or flush/wait. */
class AndroidCookieStore(private val manager: CookieManager = CookieManager.getInstance()) : CookieStore {
    private val looper = checkNotNull(Looper.myLooper()) { "COOKIE_LOOPER_REQUIRED" }
    init { check(looper == Looper.getMainLooper()) { "COOKIE_UI_REQUIRED" } }
    private fun checkLooper() { check(Looper.myLooper() == looper) { "COOKIE_LOOPER_MISMATCH" } }
    override fun getCookie(url: String): String? {
        checkLooper()
        return manager.getCookie(url)
    }
    override fun setCookie(url: String, header: String, callback: (Boolean) -> Unit) {
        checkLooper()
        manager.setCookie(url, header) { accepted ->
            checkLooper()
            callback(accepted)
        }
    }
}
