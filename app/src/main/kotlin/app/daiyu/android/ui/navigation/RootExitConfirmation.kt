package app.daiyu.android.ui.navigation

internal class RootExitConfirmation {
    private var firstBackMillis: Long? = null

    fun shouldExit(nowMillis: Long): Boolean {
        val first = firstBackMillis
        if (first != null && nowMillis - first in 0L..2_000L) {
            reset()
            return true
        }
        firstBackMillis = nowMillis
        return false
    }

    fun reset() {
        firstBackMillis = null
    }
}
