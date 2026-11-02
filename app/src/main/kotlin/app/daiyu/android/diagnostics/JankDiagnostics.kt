package app.daiyu.android.diagnostics

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.Trace
import android.util.Log
import app.daiyu.android.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Diagnostic-build only. No content, identifiers, touch coordinates or exception messages. */
internal object JankDiagnostics {
    private val serial = AtomicInteger()
    private val writer by lazy {
        ThreadPoolExecutor(1, 1, 15, TimeUnit.SECONDS, ArrayBlockingQueue(4),
            { work -> Thread(work, "daiyu-jank-writer").apply { isDaemon = true } }).apply { allowCoreThreadTimeOut(true) }
    }
    inline fun <T> section(name: String, block: () -> T): T {
        if (!BuildConfig.JANK_DIAGNOSTICS) return block()
        Trace.beginSection(name)
        return try { block() } finally { Trace.endSection() }
    }
    fun ready() {
        if (BuildConfig.JANK_DIAGNOSTICS) Log.i("DaiyuJank", "DAIYU_JANK_READY")
    }
    fun counter(name: String, value: Int) {
        if (BuildConfig.JANK_DIAGNOSTICS && Build.VERSION.SDK_INT >= 29) Trace.setCounter(name, value.toLong())
    }
    fun beginPhase(name: String, cookie: Int) {
        if (BuildConfig.JANK_DIAGNOSTICS && Build.VERSION.SDK_INT >= 29) Trace.beginAsyncSection(name, cookie)
    }
    fun endPhase(name: String, cookie: Int) {
        if (BuildConfig.JANK_DIAGNOSTICS && Build.VERSION.SDK_INT >= 29) Trace.endAsyncSection(name, cookie)
    }
    fun trigger() {
        if (BuildConfig.JANK_DIAGNOSTICS) Log.i("DaiyuJank", "DAIYU_JANK_TRIGGER")
    }
    fun save(context: Context, window: String, rows: List<Pair<String, PerformanceFrame>>, complete: Boolean) {
        if (!BuildConfig.JANK_DIAGNOSTICS) return
        val sequence = serial.incrementAndGet()
        val clockNs = System.nanoTime()
        val bootNs = SystemClock.elapsedRealtimeNanos()
        val clockEndNs = System.nanoTime()
        try {
            writer.execute {
                try {
                    val directory = File(context.noBackupFilesDir, "jank-diagnostics-v1")
                    check(directory.isDirectory || directory.mkdirs())
                    val samples = JSONArray()
                    rows.forEach { (page, f) ->
                        samples.put(JSONObject().put("page", page).put("vsync_ns", f.vsyncNs)
                            .put("total_ns", f.totalNs).put("budget_ns", f.budgetNs)
                            .put("refresh_hz", f.refreshHz).put("stages_ns", JSONArray(f.stagesNs.toList()))
                            .put("callback_drops", f.callbackDrops).put("first_draw", f.firstDraw))
                    }
                    val json = JSONObject().put("format", "daiyu-jank-incident").put("version", 1)
                        .put("app_version", BuildConfig.VERSION_NAME).put("sdk", Build.VERSION.SDK_INT)
                        .put("window", window).put("postroll_complete", complete)
                        .put("saved_monotonic_ns", clockNs).put("saved_monotonic_end_ns", clockEndNs).put("saved_boottime_ns", bootNs)
                        .put("pid", android.os.Process.myPid()).put("frames", samples)
                        .put("notes", "Window callbacks, not display-present FPS; trace required for call attribution.")
                    val file = File(directory, "incident-${sequence % 6}.json")
                    val temp = File(directory, "incident.tmp")
                    temp.writeText(json.toString())
                    check(temp.renameTo(file))
                } catch (_: Exception) { Log.w("DaiyuJank", "DAIYU_JANK_WRITE_FAILED") }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { Log.w("DaiyuJank", "DAIYU_JANK_QUEUE_FULL") }
    }
}
