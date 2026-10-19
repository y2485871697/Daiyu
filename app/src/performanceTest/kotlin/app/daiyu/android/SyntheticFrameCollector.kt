package app.daiyu.android

import android.app.Instrumentation
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Test-APK-only raw collection; callback labels are hints, not presentation boundaries. */
internal class SyntheticFrameCollector(instrumentation: Instrumentation, private val window: Window) : AutoCloseable {
    private val instrumentation = instrumentation
    private val metrics = linkedMapOf(
        "unknown_delay_ns" to FrameMetrics.UNKNOWN_DELAY_DURATION,
        "input_handling_ns" to FrameMetrics.INPUT_HANDLING_DURATION,
        "animation_ns" to FrameMetrics.ANIMATION_DURATION,
        "layout_measure_ns" to FrameMetrics.LAYOUT_MEASURE_DURATION,
        "draw_ns" to FrameMetrics.DRAW_DURATION,
        "sync_ns" to FrameMetrics.SYNC_DURATION,
        "command_issue_ns" to FrameMetrics.COMMAND_ISSUE_DURATION,
        "swap_buffers_ns" to FrameMetrics.SWAP_BUFFERS_DURATION,
        "total_duration_ns" to FrameMetrics.TOTAL_DURATION,
        "first_draw_frame_flag" to FrameMetrics.FIRST_DRAW_FRAME,
        "intended_vsync_timestamp_ns" to FrameMetrics.INTENDED_VSYNC_TIMESTAMP,
        "vsync_timestamp_ns" to FrameMetrics.VSYNC_TIMESTAMP)
    private data class Frame(val callbackNs: Long, val label: String, val dropped: Int, val values: LongArray)
    private val ids = metrics.values.toIntArray()
    private val thread = HandlerThread("synthetic-frame-metrics").apply { start() }
    private val lock = Any()
    private val frames = ArrayList<Frame>()
    private val label = AtomicReference("setup")
    private var closed = false
    private var listenerDetached = false
    private val startedNs = System.nanoTime()
    private var endedNs = 0L
    var overflow = 0L; private set
    var droppedReports = 0L; private set
    val frameCount get() = synchronized(lock) { frames.size }
    private val listener = Window.OnFrameMetricsAvailableListener { _, frame, dropped ->
        // Android reuses frame: copy every metric before returning from this callback.
        val sample = Frame(System.nanoTime(), label.get(), dropped, LongArray(ids.size) { frame.getMetric(ids[it]) })
        synchronized(lock) {
            droppedReports += dropped
            if (frames.size < 50_000) frames.add(sample) else overflow++
        }
    }
    init {
        try { syntheticMain(instrumentation) { window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper)) } }
        catch (failure: Throwable) { thread.quitSafely(); thread.join(5_000); throw failure }
    }
    fun framesBetween(startNs: Long, endNs: Long): Int {
        check(closed)
        val index = metrics.keys.indexOf("intended_vsync_timestamp_ns")
        return synchronized(lock) { frames.count { it.values[index] >= startNs && it.values[index] < endNs } }
    }
    fun scenario(value: String) { check(!closed); label.set(value) }
    override fun close() {
        if (closed) return
        try {
            if (!listenerDetached) {
                syntheticMain(instrumentation) { window.removeOnFrameMetricsAvailableListener(listener) }
                listenerDetached = true
            }
        } finally {
            thread.quitSafely()
            thread.join(5_000)
        }
        check(!thread.isAlive) { "frame_callback_drain_unconfirmed" }
        endedNs = System.nanoTime()
        closed = true
    }
    fun write(file: File, environment: JSONObject, inputs: List<JSONObject>) {
        check(closed && !thread.isAlive)
        val copy = synchronized(lock) { frames.toList() }
        check(file.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
        file.bufferedWriter(Charsets.UTF_8).use { output ->
            fun line(value: JSONObject) { output.write(value.toString()); output.newLine() }
            line(JSONObject().put("type", "metadata").put("schema", "daiyu.synthetic.performance.raw.v1")
                .put("synthetic_data", true).put("production_delivery", false).put("business_acceptance", false)
                .put("collection_authenticated", false).put("measurement_kind", "release_instrumented_synthetic")
                .put("clock", "System.nanoTime").put("started_ns", startedNs).put("ended_ns", endedNs)
                .put("frame_count", copy.size).put("framework_dropped_reports", droppedReports)
                .put("collector_overflow_count", overflow).put("metric_unavailable_value", -1)
                .put("scenario_label_kind", "hint_at_callback_delivery").put("environment", environment)
                .put("metric_names", JSONArray(metrics.keys.toList()))
                .put("measurement_scope", "raw_frames_and_instrumented_synthetic_workloads")
                .put("not_measured_by_this_report", JSONArray(listOf("cold_start", "migration_ui_progress", "backend_migration", "stop_first_visible_feedback", "real_provider_streaming", "system_ime_round_trip", "anr_count", "memory_growth"))))
            copy.forEachIndexed { index, raw ->
                line(JSONObject().put("type", "frame").put("index", index).put("callback_ns", raw.callbackNs)
                    .put("scenario_hint", raw.label).put("framework_drop_count", raw.dropped)
                    .put("metrics", JSONArray(raw.values.toList())))
            }
            inputs.forEach(::line)
        }
    }
}

internal fun <T> syntheticMain(instrumentation: Instrumentation, action: () -> T): T {
    val result = AtomicReference<T>()
    val failure = AtomicReference<Throwable?>()
    instrumentation.runOnMainSync { try { result.set(action()) } catch (caught: Throwable) { failure.set(caught) } }
    failure.get()?.let { throw it }
    return result.get()
}
