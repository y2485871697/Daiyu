package app.daiyu.android

import java.util.concurrent.atomic.AtomicBoolean

/** Worker-safe single-visit admission fence. No Activity/Compose state is read on the worker.
 * The atomic current check is the save admission point. Departure is nonblocking: it rejects
 * queued work but never cancels/retries a persistence operation already admitted here. Such a
 * worker may complete offscreen; the host independently fences its result by visit identity.
 */
internal class ModelSettingsSaveOwner {
    private val current = AtomicBoolean(true)
    fun revoke() { current.set(false) }
    fun <T> commit(work: () -> T): T {
        check(current.get()) { "设置页面已关闭；请重新打开" }
        return work()
    }
}
