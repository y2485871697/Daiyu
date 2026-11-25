package app.daiyu.android.diagnostics

import org.json.JSONObject

/** Chat geometry rows while page-frame capture is on: main thread only, changed values only, bounded per second. */
internal object ChatGeometryTrace {
    @Volatile var sink: ((JSONObject) -> Unit)? = null
    private const val MAX_ROWS_PER_SECOND = 240
    private var windowStartNs = 0L
    private var rows = 0
    private val last = HashMap<String, String>()

    val active: Boolean get() = sink != null

    fun record(event: String, vararg values: Pair<String, Any?>) {
        val out = sink ?: return
        val signature = values.joinToString(",") { "${it.first}=${it.second}" }
        if (last[event] == signature) return
        last[event] = signature
        val now = System.nanoTime()
        if (now - windowStartNs > 1_000_000_000L) { windowStartNs = now; rows = 0 }
        if (++rows > MAX_ROWS_PER_SECOND) return
        try {
            val row = JSONObject().put("kind", "chat_geometry").put("event", event).put("at_ns", now)
            values.forEach { (name, value) ->
                row.put(name, when {
                    value == null -> JSONObject.NULL
                    value is Float && !value.isFinite() -> value.toString()
                    else -> value
                })
            }
            out(row)
        } catch (_: Exception) { }
    }
}
