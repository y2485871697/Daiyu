package app.daiyu.android.browser.runtime

import android.os.Handler
import android.os.Looper

/** Production main-looper adapter; the queue bridge itself remains Android-free. */
class AndroidBrowserUiDispatcher : UiDispatcher {
    private val handler = Handler(Looper.getMainLooper())

    override fun isUiThread(): Boolean = Looper.myLooper() === handler.looper

    override fun post(task: () -> Unit) {
        check(handler.post { task() }) { "BROWSER_UI_QUEUE_REJECTED" }
    }
}
