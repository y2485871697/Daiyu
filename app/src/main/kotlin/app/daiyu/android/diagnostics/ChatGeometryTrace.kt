package app.daiyu.android.diagnostics

import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/** Main-thread, bounded geometry metadata. Rows never contain conversation or tool data. */
internal object ChatGeometryTrace {
    @Volatile var sink: ((JSONObject) -> Unit)? = null
    private const val MAX_ROWS_PER_SECOND = 240
    private const val MAX_DETAIL_ROWS_PER_SECOND = 1500
    private var windowStartNs = 0L
    private var rows = 0
    private var detailRows = 0
    private val sequence = AtomicLong()
    private val droppedTotal = AtomicLong()
    private val idSource = AtomicLong()
    private val last = HashMap<String, String>()
    private val lastProductionDraw = object : LinkedHashMap<Any, List<Pair<String, Any?>>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Any, List<Pair<String, Any?>>>?) = size > 128
    }
    private val observers = CopyOnWriteArrayList<(JSONObject) -> Unit>()

    val active: Boolean get() = sink != null || observers.isNotEmpty()

    fun newId(): Long = idSource.incrementAndGet()
    fun droppedRows(): Long = droppedTotal.get()

    /** Test-only observer; removing its lease leaves the production sink untouched. */
    fun observe(observer: (JSONObject) -> Unit): AutoCloseable {
        observers += observer
        return AutoCloseable { observers -= observer }
    }

    fun record(event: String, vararg values: Pair<String, Any?>) = publish(event, true, values)
    fun sample(event: String, vararg values: Pair<String, Any?>) = publish(event, false, values)

    private fun publish(event: String, deduplicate: Boolean, values: Array<out Pair<String, Any?>>) {
        var target = sink
        val listeners = observers.iterator()
        if (target != null && event == "disclosure_draw") {
            // A stationary tall card must not evict the gesture that reproduced a jump.
            val owner = values.firstOrNull { it.first == "owner" }?.second
            if (owner != null) {
                val geometry = values.filterNot { it.first == "traversal" || it.first == "gesture" }
                if (lastProductionDraw.put(owner, geometry) == geometry) target = null
            }
        }
        if (target == null && !listeners.hasNext()) return
        if (deduplicate) {
            val signature = values.joinToString(",") { "${it.first}=${it.second}" }
            if (last[event] == signature) return
            last[event] = signature
        }
        val now = System.nanoTime()
        if (now - windowStartNs >= 1_000_000_000L || now < windowStartNs) {
            windowStartNs = now
            rows = 0
            detailRows = 0
        }
        val seq = sequence.incrementAndGet()
        if (if (deduplicate) ++rows > MAX_ROWS_PER_SECOND else ++detailRows > MAX_DETAIL_ROWS_PER_SECOND) {
            droppedTotal.incrementAndGet()
            return
        }
        try {
            val row = JSONObject().put("kind", "chat_geometry").put("event", event)
                .put("seq", seq).put("at_ns", now).put("rate_dropped_total", droppedTotal.get())
            values.forEach { (name, value) ->
                row.put(name, when {
                    value == null -> JSONObject.NULL
                    value is Float && !value.isFinite() -> value.toString()
                    else -> value
                })
            }
            target?.invoke(row)
            listeners.forEach { observer ->
                try { observer(row) } catch (_: Exception) { }
            }
        } catch (_: Exception) { }
    }
}
