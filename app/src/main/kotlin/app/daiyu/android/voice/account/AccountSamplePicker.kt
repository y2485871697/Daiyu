package app.daiyu.android.voice.account

/** Local picker identity, not audio/network admission. STARTED results wait until RESUMED. */
internal class AccountSamplePicker<T> {
    private class Pending<T>(val revision: String, var valid: Boolean = true, var delivered: Boolean = false, var value: T? = null)
    private var request: Pending<T>? = null
    val pending get() = request != null
    fun begin(revision: String): Boolean {
        if (pending) return false
        request = Pending(revision); return true
    }
    fun deliver(value: T?) { request?.let { it.delivered = true; it.value = value } }
    fun invalidate() { request?.valid = false }
    fun take(revision: String, resumed: Boolean, pageCurrent: Boolean): T? {
        val owned = request ?: return null
        if (!owned.delivered) { if (owned.revision != revision) owned.valid = false; return null }
        if (!owned.valid || owned.revision != revision || owned.value == null) { request = null; return null }
        if (!resumed || !pageCurrent) return null
        request = null
        return owned.value
    }
}
