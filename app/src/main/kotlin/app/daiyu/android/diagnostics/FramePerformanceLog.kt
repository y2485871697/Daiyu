package app.daiyu.android.diagnostics

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil

/** Fixed product routes only: never serialize provider IDs, conversation IDs or titles. */
internal object PerformanceRoute {
    private val names = setOf("chat", "chat-sidebar", "settings", "manage", "backup", "subagents",
        "appearance", "haptics", "voice-settings", "browser-settings", "mcp-settings", "gui-settings",
        "compression-settings", "reconnect-settings", "pet-settings", "pet-model-settings", "vision-settings",
        "session-gateway", "balance-settings", "title-model-settings", "platform-settings", "linux-settings",
        "chat-workspace", "terminal", "generation-settings", "skills", "offline-speech", "assistant-edit",
        "conversation-search", "message-search", "history-search", "browser", "child-tasks", "providers",
        "provider-settings", "models", "local-models", "create-provider", "provider-authentication",
        "APP_INFO", "TASK_SURFACE", "ENHANCEMENTS", "SHIZUKU_ACCESS", "OVERLAY", "about", "licenses",
        "privacy", "notifications", "all", "general", "model", "tools", "permissions", "data",
        "oauth-login", "attachment-receive", "usage", "model-settings", "permission-check", "shizuku-settings", "legacy-library", "context-management",
        "extensions", "assistants", "OPENAI", "ANTHROPIC", "GEMINI", "BASIC", "FILES", "WORKSPACE", "ROOTFS", "SHARED")
    fun part(value: Any?): String = when (value) {
        is Enum<*> -> value.name.takeIf { it in names } ?: "detail"
        is String -> when {
            value in names -> value
            value in setOf("LIST", "SYNC", "ACCOUNT", "IMPORT", "CREATE:0", "CREATE:1", "CREATE:2") -> "voice-$value"
            value in setOf("child:ASR", "child:MIMO", "child:DOUBAO_ACCOUNT", "child:DOUBAO_VOICES") -> "voice-$value"
            value in setOf("account:DOUBAO", "account:MIMO", "account:OPENAI", "account:AZURE", "account:ELEVENLABS") -> "voice-$value"
            value.startsWith("provider:") -> "provider-detail"
            value.startsWith("detail:") -> "detail"
            else -> "detail"
        }
        else -> "detail"
    }
}

internal data class PerformanceRouteMark(val timeNs: Long, val page: String)
internal class PerformanceRouteTimeline {
    private val marks = ArrayDeque<PerformanceRouteMark>()
    fun change(timeNs: Long, page: String) {
        if (marks.lastOrNull()?.page == page) return
        marks.addLast(PerformanceRouteMark(timeNs, page))
        while (marks.size > 64) marks.removeFirst()
    }
    fun pageAt(timeNs: Long): String = marks.lastOrNull { it.timeNs <= timeNs }?.page ?: "transition"
}

internal data class PerformanceFrame(
    val vsyncNs: Long, val totalNs: Long, val budgetNs: Long, val refreshHz: Double,
    val stagesNs: LongArray, val callbackDrops: Int, val firstDraw: Boolean,
)

/** Worker-confined one-second windows, split on page, refresh-rate and capture epoch changes. */
internal class PerformanceFrameBatch(
    private val emit: (Long, JSONObject) -> Unit,
    private val wallClock: () -> Long = System::currentTimeMillis,
) {
    private val frames = ArrayList<PerformanceFrame>(144)
    private var page = "startup"
    private var epoch = -1L
    private var startedNs = 0L
    private var startedWallMs = 0L
    fun add(captureEpoch: Long, route: String, frame: PerformanceFrame) {
        if (frames.isNotEmpty() && (captureEpoch != epoch || page != route ||
                frame.refreshHz != frames.first().refreshHz || frame.vsyncNs - startedNs >= 1_000_000_000L ||
                frames.size >= 240)) flush("boundary")
        if (frames.isEmpty()) {
            epoch = captureEpoch; page = route; startedNs = frame.vsyncNs; startedWallMs = wallClock()
        }
        frames.add(frame)
    }
    fun flush(reason: String) {
        if (frames.isEmpty()) return
        val durations = frames.map { it.totalNs }.sorted()
        fun percentile(p: Double) = durations[(ceil(p * durations.size).toInt() - 1).coerceAtLeast(0)] / 1_000.0
        val raw = JSONArray()
        frames.forEach { f ->
            raw.put(JSONArray().put((f.vsyncNs - startedNs) / 1_000L).put(f.totalNs / 1_000L)
                .put(if (f.budgetNs > 0) f.budgetNs / 1_000L else -1L).also { row ->
                    f.stagesNs.forEach { row.put(if (it >= 0) it / 1_000L else -1L) }
                }.put(f.callbackDrops).put(if (f.firstDraw) 1 else 0))
        }
        val paced = frames.filterNot { it.firstDraw }
        val judged = paced.filter { it.budgetNs > 0 }
        val overBudget = judged.count { it.totalNs > it.budgetNs }
        val spanNs = frames.last().vsyncNs - startedNs
        val summary = JSONObject().put("page", page).put("time_ms", startedWallMs)
            .put("start_vsync_ns", startedNs).put("span_ms", spanNs / 1_000_000.0)
            .put("refresh_hz", frames.first().refreshHz).put("frame_count", frames.size)
            .put("callback_fps", if (spanNs > 0 && frames.size > 1) (frames.size - 1) * 1e9 / spanNs else JSONObject.NULL)
            .put("avg_total_us", durations.average() / 1_000.0).put("p50_us", percentile(.5))
            .put("p90_us", percentile(.9)).put("p95_us", percentile(.95)).put("p99_us", percentile(.99))
            .put("max_us", durations.last() / 1_000.0).put("judged_frames", judged.size)
            .put("over_budget_frames", overBudget).put("over_budget_ratio", if (judged.isNotEmpty()) overBudget.toDouble() / judged.size else JSONObject.NULL)
            .put("over_50ms", frames.count { it.totalNs > 50_000_000L })
            .put("over_100ms", frames.count { it.totalNs > 100_000_000L })
            .put("over_700ms", frames.count { it.totalNs > 700_000_000L })
            .put("callback_drops", frames.sumOf { it.callbackDrops.toLong() })
            .put("first_draw_frames", frames.count { it.firstDraw }).put("end_reason", reason)
            .put("frames", raw)
        emit(epoch, summary)
        frames.clear()
    }
}

/** Independent bounded JSONL; no per-frame disk IO and no writes on the UI/callback thread. */
internal class FramePerformanceLog(private val directory: File, private val maxFileBytes: Long = 2L * 1024 * 1024,
    private val appendBytes: (File, ByteArray) -> Unit = { target, bytes -> target.appendBytes(bytes) },
) {
    private val lock = Any()
    private val fileLock = Any()
    @Volatile private var enabled = false
    @Volatile private var epoch = 0L
    private var executor: ThreadPoolExecutor? = null
    private val queueDrops = AtomicLong()
    private val writeFailures = AtomicLong()
    private val captureFailures = AtomicLong()
    fun reportCaptureFailure() { if (enabled) captureFailures.incrementAndGet() }
    @Volatile var flushCapture: (() -> Unit)? = null
    private val captureHooks = LinkedHashMap<Any, () -> Unit>()
    fun registerCapture(owner: Any, flush: () -> Unit) = synchronized(lock) { captureHooks[owner] = flush }
    fun unregisterCapture(owner: Any) = synchronized(lock) { captureHooks.remove(owner); Unit }
    private fun captures(): List<() -> Unit> = synchronized(lock) { listOfNotNull(flushCapture) + captureHooks.values.toList() }
    fun setEnabled(value: Boolean) {
        if (!value && enabled) {
            captures().forEach { flush -> try { flush() } catch (_: Exception) { writeFailures.incrementAndGet() } }
        }
        synchronized(lock) {
            if (!enabled && value) epoch++
            enabled = value
        }
    }
    fun captureEpoch(): Long? = epoch.takeIf { enabled }
    fun append(expectedEpoch: Long, row: JSONObject) {
        synchronized(lock) {
            if (!enabled || expectedEpoch != epoch) return
            val worker = executor ?: ThreadPoolExecutor(1, 1, 15, TimeUnit.SECONDS, ArrayBlockingQueue(8),
                { work -> Thread(work, "daiyu-frame-log").apply { isDaemon = true } }).also {
                it.allowCoreThreadTimeOut(true); executor = it
            }
            try {
                worker.execute {
                    synchronized(fileLock) write@{
                        if (expectedEpoch != epoch) return@write
                        try { writeRow(row) } catch (_: Exception) { writeFailures.incrementAndGet() }
                    }
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) { queueDrops.incrementAndGet() }
        }
    }
    private fun writeRow(row: JSONObject) {
        val bytes = (row.toString() + "\n").toByteArray(Charsets.UTF_8)
        require(bytes.size <= maxFileBytes)
        check(directory.isDirectory || directory.mkdirs())
        val current = File(directory, "frames-0.jsonl")
        val incompleteTail = if (current.length() > 0) java.io.RandomAccessFile(current, "r").use {
            it.seek(it.length() - 1); it.read() != 10
        } else false
        if (current.length() + bytes.size + (if (incompleteTail) 1 else 0) > maxFileBytes) {
            val oldest = File(directory, "frames-2.jsonl")
            check(!oldest.exists() || oldest.delete())
            for (index in 1 downTo 0) {
                val source = File(directory, "frames-$index.jsonl")
                check(!source.exists() || source.renameTo(File(directory, "frames-${index + 1}.jsonl")))
            }
        }
        val originalLength = current.length()
        try { appendBytes(current, if (incompleteTail && current.length() > 0) byteArrayOf(10) + bytes else bytes) }
        catch (failure: Exception) {
            try { java.io.RandomAccessFile(current, "rw").use { it.setLength(originalLength) } }
            catch (_: Exception) { /* Export also tolerates a process-interrupted final row. */ }
            throw failure
        }
    }
    fun clear() = synchronized(fileLock) {
        synchronized(lock) { epoch++ } // Queued writes and in-progress callback windows belong to the old capture epoch.
        for (index in 0..2) File(directory, "frames-$index.jsonl").let { check(!it.exists() || it.delete()) }
        queueDrops.set(0); writeFailures.set(0); captureFailures.set(0)
    }
    fun export(): JSONObject {
        captures().forEach { it() }
        val worker = synchronized(lock) { executor }
        if (worker != null) {
            // Do not hold the file lock while waiting for the queued writes to drain.
            val barrier = java.util.concurrent.FutureTask { Unit }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (true) {
                try { worker.execute(barrier); break }
                catch (_: java.util.concurrent.RejectedExecutionException) {
                    check(System.nanoTime() < deadline) { "frame_log_busy" }; Thread.sleep(5)
                }
            }
            barrier.get(5, TimeUnit.SECONDS)
        }
        return synchronized(fileLock) {
            val windows = JSONArray()
            var corruptRows = 0L
            for (index in 2 downTo 0) {
                val source = File(directory, "frames-$index.jsonl")
                if (source.exists()) {
                    require(source.length() <= maxFileBytes)
                    source.useLines { lines -> lines.forEach { line ->
                        if (line.isNotBlank()) try { windows.put(JSONObject(line)) }
                        catch (_: Exception) { corruptRows++ }
                    } }
                }
            }
            JSONObject().put("format", "daiyu-page-frames").put("version", 1)
                .put("units", "microseconds unless named otherwise").put("max_retained_bytes", maxFileBytes * 3)
                .put("queue_dropped_windows", queueDrops.get()).put("write_failures", writeFailures.get())
                .put("corrupt_rows_skipped", corruptRows).put("capture_failures", captureFailures.get())
                .put("columns", JSONArray(listOf("vsync_offset_us", "total_us", "budget_us", "unknown_delay_us",
                    "input_us", "animation_us", "layout_measure_us", "draw_us", "sync_us", "command_issue_us",
                    "swap_buffers_us", "gpu_us", "callback_drops", "first_draw")))
                .put("notes", "Window render callbacks, not display-present FPS. Idle windows do not render continuously. " +
                    "Budget uses API31 deadline, otherwise observed display refresh period; first-draw excluded from budget ratios. " +
                    "Unavailable metrics are -1. Callback drops and writer losses are not display dropped-frame counts. " +
                    "Oldest windows rotate out; recording itself adds overhead.")
                .put("windows", windows)
        }
    }
}
