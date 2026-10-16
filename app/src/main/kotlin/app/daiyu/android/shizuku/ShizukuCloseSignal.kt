package app.daiyu.android.shizuku

/** Keeps one terminal cleanup observation; callback failure cannot prevent other observers. */
internal class ShizukuCloseSignal {
    private val lock = Any()
    private var result: ShizukuCloseResult? = null
    private val listeners = mutableListOf<(ShizukuCloseResult) -> Unit>()

    fun observe(listener: (ShizukuCloseResult) -> Unit) {
        val completed = synchronized(lock) {
            result.also { if (it == null) listeners.add(listener) }
        }
        completed?.let { listener(it) }
    }

    fun complete(value: ShizukuCloseResult) {
        val notify = synchronized(lock) {
            if (result != null) return
            result = value
            listeners.toList().also { listeners.clear() }
        }
        notify.forEach { runCatching { it(value) } }
    }
}
