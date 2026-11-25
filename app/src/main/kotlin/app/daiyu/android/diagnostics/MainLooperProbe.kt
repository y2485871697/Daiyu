package app.daiyu.android.diagnostics

import android.os.Handler
import android.os.Looper
import android.util.Printer
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * While page-frame capture is on, names every main-looper dispatch of at least 8ms and samples
 * its stack from the metrics worker. Frame metrics only say a frame started late; this says which
 * message held the main thread (Choreographer frame, chat publish, coroutine resume, input, ...).
 */
internal class MainLooperProbe(private val append: (JSONObject) -> Unit) {
    @Volatile private var sequence = 0L
    @Volatile private var startedNs = 0L
    @Volatile private var worker: Handler? = null
    private var label: String? = null
    private var installed: Printer? = null
    private val armed = AtomicBoolean()
    // Worker-thread budget state.
    private var budgetSecond = 0L
    private var budgetRows = 0
    private var sampledSequence = -1L
    private var samples = 0

    /** Main thread. A null worker uninstalls the printer. */
    fun update(target: Handler?) {
        val looper = Looper.getMainLooper()
        if (target == null) {
            if (installed != null) looper.setMessageLogging(null)
            installed = null; worker = null; startedNs = 0L; label = null
            return
        }
        worker = target
        if (installed != null) return
        val printer = Printer { line ->
            if (line.startsWith(">>>>>")) begin(line) else if (line.startsWith("<<<<<")) finish()
        }
        installed = printer
        looper.setMessageLogging(printer)
    }

    private fun begin(line: String) {
        label = line
        sequence++
        startedNs = System.nanoTime()
        val target = worker ?: return
        if (armed.compareAndSet(false, true)) target.postDelayed(tick, SLOW_MS)
    }

    private fun finish() {
        val start = startedNs
        startedNs = 0L
        val text = label
        label = null
        if (start == 0L) return
        val end = System.nanoTime()
        if (end - start < SLOW_MS * 1_000_000L) return
        val id = sequence
        worker?.post {
            if (!admit(end)) return@post
            append(JSONObject().put("kind", "main_slow_message").put("seq", id).put("start_ns", start)
                .put("end_ns", end).put("dispatch", text.orEmpty().replace(HASH, "").take(240)))
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            armed.set(false)
            val target = worker ?: return
            val id = sequence
            val start = startedNs
            if (start == 0L) return
            val running = System.nanoTime() - start
            if (running < SLOW_MS * 1_000_000L) {
                if (armed.compareAndSet(false, true))
                    target.postDelayed(this, ((SLOW_MS * 1_000_000L - running) / 1_000_000L).coerceAtLeast(1L))
                return
            }
            if (sampledSequence != id) { sampledSequence = id; samples = 0 }
            if (samples >= MAX_SAMPLES) return
            val at = System.nanoTime()
            val stack = Looper.getMainLooper().thread.stackTrace
            if (sequence != id || startedNs != start) return
            if (!admit(at)) return
            samples++
            val symbols = JSONArray()
            stack.asSequence().take(40).forEach { symbols.put(it.className.take(180) + "#" + it.methodName.take(100)) }
            append(JSONObject().put("kind", "main_slow_stack").put("seq", id).put("start_ns", start)
                .put("at_ns", at).put("symbols", symbols))
            if (samples < MAX_SAMPLES && armed.compareAndSet(false, true)) target.postDelayed(this, RESAMPLE_MS)
        }
    }

    private fun admit(now: Long): Boolean {
        val second = now / 1_000_000_000L
        if (second != budgetSecond) { budgetSecond = second; budgetRows = 0 }
        return ++budgetRows <= MAX_ROWS_PER_SECOND
    }

    private companion object {
        const val SLOW_MS = 8L
        const val RESAMPLE_MS = 4L
        const val MAX_SAMPLES = 8
        const val MAX_ROWS_PER_SECOND = 80
        val HASH = Regex("[@{][0-9a-f]{5,}}?")
    }
}
