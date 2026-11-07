package app.daiyu.android.diagnostics

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Fixed categories and numeric metadata only; never accepts a payload, URL or exception message. */
internal object RuntimeDiagnostics {
    enum class Stage { RUN, MODEL_OPEN, MODEL_REQUEST, MODEL_EVENT, MODEL_ACK, TOOL_EXECUTION,
        CHECKPOINT_FREEZE, CHECKPOINT_ENCODE, CHECKPOINT_TRANSACTION, MESSAGE_WRITE, ARCHIVE_ENCODE,
        GUI_CAPTURE, GUI_ENCODE, GUI_VALIDATE, GUI_ACTION, UI_PREPARE, UI_DISPATCH, UI_PUBLISH }
    enum class Code {
        BEGIN,
        END,
        FAILURE,
        OOM,
        STALL,
        DROPPED,
        REJECTED,
        UNKNOWN,
        FRAME_EQUAL,
        PIXELS_MISSING,
        DIMENSIONS_CHANGED,
        NOT_A_TAP,
        DISPLAY_CHANGED,
        TARGET_OUTSIDE,
        REGION_TOO_SMALL,
        LOCAL_PIXELS_CHANGED,
        BLANK_TARGET,
        LOCAL_EQUAL,
        FRAME_CHANGED,
        ACTION_DELIVERY_UNCONFIRMED,
        ACTION_RESULT_REVOKED_UNCONFIRMED,
        ACTIVE_WINDOW_UNAVAILABLE_NOT_EXECUTED,
        APPLICATION_LAUNCH_PERMISSION_DENIED_NOT_EXECUTED,
        APPLICATION_LAUNCH_UNCONFIRMED,
        APPLICATION_NOT_LAUNCHABLE_NOT_EXECUTED,
        APPLICATION_PACKAGE_UNAVAILABLE_NOT_EXECUTED,
        APPLICATION_RESOLUTION_FAILED_NOT_EXECUTED,
        APPLICATION_TARGET_MISMATCH_NOT_EXECUTED,
        APPLICATION_WINDOW_AMBIGUOUS_NOT_EXECUTED,
        APPLICATION_WINDOW_DISPLAY_MISMATCH_NOT_EXECUTED,
        APPLICATION_WINDOW_ID_MISMATCH_NOT_EXECUTED,
        APPLICATION_WINDOW_LIMIT_EXCEEDED_NOT_EXECUTED,
        APPLICATION_WINDOW_METADATA_UNAVAILABLE_NOT_EXECUTED,
        APPLICATION_WINDOW_NOT_ACTIVE_NOT_EXECUTED,
        APPLICATION_WINDOW_REQUIRED_NOT_EXECUTED,
        APPLICATION_WINDOW_ROOT_UNAVAILABLE_NOT_EXECUTED,
        CANCELLED_NOT_EXECUTED,
        CAPTURE_CONTENT_CHANGED_NOT_EXECUTED,
        CAPTURE_GEOMETRY_CHANGED_NOT_EXECUTED,
        CAPTURE_ID_MISMATCH_NOT_EXECUTED,
        CAPTURE_STALE_NOT_EXECUTED,
        CAPTURE_WINDOW_CHANGED_NOT_EXECUTED,
        CONFIRMATION_ABORTED_BEFORE_TRANSFER_NOT_EXECUTED,
        CONFIRMATION_CANCELLED_OR_SERVICE_LOST_NOT_EXECUTED,
        CONFIRMATION_CLOSED_NOT_EXECUTED,
        CONFIRMATION_EXPIRED_NOT_EXECUTED,
        CONFIRMATION_TRANSFER_REJECTED_NOT_EXECUTED,
        CONFIRMATION_TRANSFER_UNCONFIRMED,
        FINAL_OBSERVATION_UNAVAILABLE_NOT_EXECUTED,
        FOREGROUND_DRAIN_PENDING_NOT_EXECUTED,
        GESTURE_CANCELLED_UNCONFIRMED,
        GESTURE_DELIVERY_UNCONFIRMED,
        GESTURE_IN_PROGRESS_NOT_EXECUTED,
        GESTURE_OUTSIDE_OBSERVED_WINDOW_NOT_EXECUTED,
        GESTURE_RESULT_UNCONFIRMED,
        GESTURE_TREE_CHANGED_OR_INCOMPLETE_NOT_EXECUTED,
        GESTURE_TREE_UNVERIFIABLE_NOT_EXECUTED,
        GESTURE_WINDOWS_UNVERIFIABLE_NOT_EXECUTED,
        GESTURE_WINDOW_OCCLUDED_NOT_EXECUTED,
        GESTURE_WINDOW_OCCLUDED_OR_OUTSIDE_NOT_EXECUTED,
        GUI_OPERATION_UNCONFIRMED,
        GUI_PREVIOUS_OPERATION_UNCONFIRMED,
        IME_SUBMIT_NOT_SUPPORTED_BY_FOCUSED_NODE_NOT_EXECUTED,
        IME_SUBMIT_UNSUPPORTED_API_NOT_EXECUTED,
        INVALID_TARGET_PACKAGE_NOT_EXECUTED,
        NODE_UNAVAILABLE_NOT_EXECUTED,
        OVERLAY_UNAVAILABLE_NOT_EXECUTED,
        PLATFORM_ACTION_ACCEPTED_NOT_VERIFIED,
        PLATFORM_GESTURE_ACCEPTED_NOT_VERIFIED,
        PLATFORM_GESTURE_COMPLETED_NOT_VERIFIED,
        PLATFORM_LAUNCH_ACCEPTED_NOT_VERIFIED,
        SCREENSHOT_DELIVERY_UNCONFIRMED,
        SCREEN_LOCKED_NOT_EXECUTED,
        SELF_APP_GLOBAL_ACTION_NOT_ALLOWED_NOT_EXECUTED,
        SELF_APP_TARGET_REQUIRED_NOT_EXECUTED,
        SELF_APP_VISUAL_FALLBACK_NOT_EXECUTED,
        SERVICE_DISCONNECTED_NOT_EXECUTED,
        SERVICE_PERMISSION_REVOKED_NOT_EXECUTED,
        SYSTEM_WINDOW_NODE_ACTIONS_ONLY_NOT_EXECUTED,
        TARGET_CHANGED_NOT_EXECUTED,
        TARGET_NOT_UNIQUE_RECONFIRM_NOT_EXECUTED,
        TARGET_PACKAGE_MISMATCH_NOT_EXECUTED,
        TARGET_TREE_TRUNCATED_RECONFIRM_NOT_EXECUTED,
        TARGET_UNAVAILABLE_RECONFIRM_NOT_EXECUTED,
        TARGET_UNSAFE_NOT_EXECUTED,
        TARGET_WINDOW_CHANGED_RECONFIRM_NOT_EXECUTED,
        VISUAL_CAPTURE_COORDINATES_OR_BACK_ONLY_NOT_EXECUTED,
        VISUAL_CAPTURE_UNAVAILABLE_NOT_EXECUTED,
        VISUAL_WINDOW_UNVERIFIABLE_NOT_EXECUTED,
        CANCELLED_BEFORE_GUI_EXECUTION,
        CONFIRMATION_ALREADY_OPEN,
        EXACT_ACTION_NOT_AUTHORIZED,
        FOREGROUND_BUSY,
        GUI_ACTION_PARAMETERS_REQUIRED,
        GUI_AUTHORIZATION_REVOKED,
        GUI_GESTURE_REQUIRES_OBSERVED_COORDINATES,
        GUI_HOST_UNAVAILABLE,
        GUI_LEASE_REVOKED_SERVICE_OR_OWNER_LOST,
        GUI_RUN_OWNER_MISMATCH,
        GUI_TARGET_CLARIFICATION_REQUIRED,
        GUI_WORKER_REQUIRED,
        NODE_NOT_IN_OBSERVATION,
        NODE_STALE,
        NO_ACTIVE_EXTERNAL_WINDOW,
        OBSERVATION_EXPIRED,
        OBSERVATION_ID_MISMATCH,
        OBSERVATION_NOT_AUTHORIZED,
        OBSERVATION_REQUIRED,
        OBSERVATION_STALE,
        OBSERVATION_UNAVAILABLE,
        PASSWORD_NODE_REFUSED,
        PLATFORM_ACTION_REJECTED,
        PLATFORM_GESTURE_REJECTED,
        SCREENSHOT_CANCELLED,
        SCREENSHOT_DIMENSIONS_EXCEED_LIMIT,
        SCREENSHOT_ENCODING_UNAVAILABLE,
        SCREENSHOT_NOT_EXPLICITLY_AUTHORIZED,
        SCREENSHOT_RESULT_REVOKED,
        SCREENSHOT_RESULT_UNAVAILABLE,
        SCREENSHOT_TREE_CHANGED,
        SCREENSHOT_TREE_UNVERIFIABLE,
        SCREENSHOT_UNAVAILABLE,
        SCREENSHOT_UNSUPPORTED_API,
        SCREENSHOT_WINDOW_CHANGED,
        SELF_APP_TARGET_LOST,
        SYSTEM_SETTINGS_UNAVAILABLE,
        TARGET_IDENTITY_EXCEEDS_BOUND,
        USER_MUST_ENABLE_SERVICE_IN_SYSTEM_SETTINGS,
    }
    private val sequence = AtomicLong()
    private val dropped = AtomicLong()
    private val generation = AtomicLong()
    private val active = ConcurrentHashMap<Long, Span>()
    private val lock = ReentrantLock()
    @Volatile private var directory: File? = null
    @Volatile var enabled = false
        set(value) = lock.withLock {
            if (field != value) {
                generation.incrementAndGet()
                active.clear()
                field = value
            }
        }
    private var reserve: ByteArray? = null
    private val executor by lazy { ThreadPoolExecutor(1, 1, 10, TimeUnit.SECONDS, ArrayBlockingQueue(128),
        { work -> Thread(work, "daiyu-runtime-diagnostics").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()).apply { allowCoreThreadTimeOut(true) } }

    fun install(root: File) = lock.withLock {
        if (directory != null) return@withLock
        directory = root
        reserve = ByteArray(64 * 1024)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous != null) Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
            try { fatal(failure, thread) } finally { previous.uncaughtException(thread, failure) }
        }
        Executors.newSingleThreadScheduledExecutor { work -> Thread(work, "daiyu-runtime-watchdog").apply { isDaemon = true } }
            .scheduleWithFixedDelay({ try { checkStalls() } catch (_: Throwable) { } }, 5, 5, TimeUnit.SECONDS)
    }

    class Span internal constructor(val stage: Stage, val id: Long, val revision: Long, val size: Long) {
        internal val epoch = generation.get()
        internal val started = System.nanoTime()
        internal val thread = Thread.currentThread()
        internal var lastStall = started
        private val ended = java.util.concurrent.atomic.AtomicBoolean()
        fun end(failure: Throwable? = null) {
            try {
            if (id == 0L || epoch != generation.get()) return
            if (!ended.compareAndSet(false, true)) return
            active.remove(id)
            if (failure is OutOfMemoryError) reserve = null
            emit(stage, if (failure is OutOfMemoryError) Code.OOM else if (failure != null) Code.FAILURE else Code.END,
                id, elapsed(), revision, size, failure, thread, critical = failure != null, sourceEpoch = epoch)
            } catch (_: Throwable) { }
        }
        internal fun elapsed() = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
    }

    private val noop = Span(Stage.RUN, 0, -1, -1)

    fun begin(stage: Stage, revision: Long = -1, size: Long = -1): Span {
        try {
            if (directory == null || !enabled) return noop
            if (!lock.tryLock()) { dropped.incrementAndGet(); return noop }
            val span = try {
                if (!enabled || active.size >= 128) return noop
                Span(stage, sequence.incrementAndGet(), revision, size).also { active[it.id] = it }
            } finally { lock.unlock() }
            emit(stage, Code.BEGIN, span.id, 0, revision, size, sourceEpoch = span.epoch)
            return span
        } catch (_: Throwable) { return noop }
    }

    inline fun <T> measure(stage: Stage, revision: Long = -1, size: Long = -1, work: () -> T): T {
        val span = begin(stage, revision, size)
        var failure: Throwable? = null
        try { return work() }
        catch (caught: Throwable) { failure = caught; throw caught }
        finally { try { span.end(failure) } catch (_: Throwable) { } }
    }

    fun event(stage: Stage, code: Code, size: Long = -1, count: Long = -1) =
        emit(stage, code, 0, 0, count, size, critical = code == Code.REJECTED || code == Code.UNKNOWN)

    fun guiResult(code: String, stage: Stage = Stage.GUI_ACTION) {
        try {
        val safe = Code.entries.firstOrNull { it.name == code } ?: Code.REJECTED
        event(stage, safe)
        } catch (_: Throwable) { }
    }

    fun pixels(check: app.daiyu.android.gui.GuiVisualPixels.Check) {
        try {
        emit(Stage.GUI_VALIDATE, check.reason, 0, 0, check.changedTiles?.toLong() ?: -1, check.comparedTiles?.toLong() ?: -1,
            numbers = intArrayOf(check.beforeWidth, check.beforeHeight, check.afterWidth, check.afterHeight))
        } catch (_: Throwable) { }
    }

    internal fun awaitWrites() {
        val done = java.util.concurrent.CountDownLatch(1)
        executor.prestartCoreThread()
        check(executor.queue.offer(Runnable { done.countDown() }, 10, TimeUnit.SECONDS))
        check(done.await(10, TimeUnit.SECONDS))
    }

    private fun checkStalls() {
        val now = System.nanoTime()
        active.values.forEach { span ->
            if (span.epoch != generation.get()) { active.remove(span.id); return@forEach }
            if (now - span.started >= TimeUnit.SECONDS.toNanos(10) &&
                now - span.lastStall >= TimeUnit.SECONDS.toNanos(if (span.lastStall == span.started) 10 else 60)) {
                span.lastStall = now
                emit(span.stage, Code.STALL, span.id, span.elapsed(), span.revision, span.size,
                    owner = span.thread, critical = true, stack = true, sourceEpoch = span.epoch)
            }
        }
    }

    private fun emit(stage: Stage, code: Code, id: Long, elapsed: Long, revision: Long, size: Long,
        failure: Throwable? = null, owner: Thread = Thread.currentThread(), critical: Boolean = false, stack: Boolean = false, numbers: IntArray? = null, sourceEpoch: Long = generation.get()) {
        val epoch = sourceEpoch
        if (directory == null || !enabled || epoch != generation.get()) return
        try {
            val row = row(stage, code, id, elapsed, revision, size, failure, owner, stack, numbers)
            executor.execute {
                try { lock.withLock {
                    if (generation.get() != epoch) return@withLock
                    val missing = dropped.getAndSet(0)
                    if (missing > 0) append(JSONObject().put("code", Code.DROPPED.name).put("count", missing).toString())
                    append(row)
                } } catch (_: Throwable) { dropped.incrementAndGet() }
            }
        } catch (_: Throwable) { dropped.incrementAndGet() }
    }

    private fun row(stage: Stage, code: Code, id: Long, elapsed: Long, revision: Long, size: Long,
        failure: Throwable?, owner: Thread, stack: Boolean, numbers: IntArray? = null): String {
        val runtime = Runtime.getRuntime()
        val value = JSONObject().put("time_ms", System.currentTimeMillis()).put("stage", stage.name).put("code", code.name)
            .put("span", id).put("elapsed_ms", elapsed).put("revision_or_count", revision).put("bytes_or_size", size)
            .put("thread_id", owner.id).put("heap_used", runtime.totalMemory() - runtime.freeMemory())
            .put("heap_max", runtime.maxMemory()).put("active_spans", active.size)
        if (numbers != null) value.put("dimensions", JSONArray(numbers.toList()))
        val request = failure as? app.daiyu.android.domain.ModelRequestException
        request?.let { problem ->
            value.put("model_failure", problem.failure.name)
            problem.diagnostic?.let { diagnostic ->
                value.put("http_status", diagnostic.httpStatus ?: -1)
                    .put("request_rejection", diagnostic.rejection.name)
                    .put("request_field", diagnostic.field?.name ?: "NONE")
                    .put("provider_code", diagnostic.providerCode?.name ?: "NONE")
            }
        }
        if (failure != null || stack) {
            value.put("failure_kind", when (failure) {
                is OutOfMemoryError -> "OOM"
                is InterruptedException, is java.util.concurrent.CancellationException -> "CANCELLED"
                null -> "NONE"
                else -> "EXCEPTION"
            })
            val frames = failure?.stackTrace ?: owner.stackTrace
            value.put("frames", JSONArray().apply { frames.filter { frame ->
                frame.className.matches(Regex("[A-Za-z0-9_.$]{1,200}")) &&
                    frame.methodName.matches(Regex("[A-Za-z0-9_$<>-]{1,120}")) &&
                    listOf("app.daiyu.android.", "java.", "kotlin.", "android.", "kotlinx.").any { frame.className.startsWith(it) }
            }.take(16).forEach { frame ->
                put(JSONObject().put("class", frame.className.take(200)).put("method", frame.methodName.take(120))
                    .put("line", frame.lineNumber))
            } })
        }
        return value.toString()
    }

    private fun fatal(failure: Throwable, thread: Thread) {
        if (failure is OutOfMemoryError) reserve = null
        val epoch = generation.get()
        if (!enabled) return
        try {
            val emergency = Thread({ writeFatalAtEpoch(failure, thread, epoch) },
                "daiyu-fatal-diagnostic").apply { isDaemon = true }
            emergency.start()
            emergency.join(150) // Never hold up Android's original fatal handler on a stalled writer.
        } catch (_: Throwable) { }
    }

    private fun writeFatalAtEpoch(failure: Throwable, thread: Thread, epoch: Long) {
        if (!lock.tryLock()) return
        try {
            if (!enabled || generation.get() != epoch) return
            active.values.take(32).forEach { span -> append(row(span.stage, Code.STALL, span.id,
                span.elapsed(), span.revision, span.size, null, span.thread, false)) }
            append(row(Stage.RUN, if (failure is OutOfMemoryError) Code.OOM else Code.FAILURE,
                0, 0, -1, -1, failure, thread, true))
        } catch (_: Throwable) { } finally { lock.unlock() }
    }

    private fun append(text: String) {
        val root = directory ?: return
        check(root.isDirectory || root.mkdirs())
        val current = File(root, "trace-0.jsonl")
        val bytes = (text + "\n").toByteArray(Charsets.UTF_8)
        require(bytes.size <= 16 * 1024)
        if (current.length() + bytes.size > FILE_BYTES) {
            File(root, "trace-2.jsonl").delete()
            File(root, "trace-1.jsonl").takeIf { it.exists() }?.let { check(it.renameTo(File(root, "trace-2.jsonl"))) }
            if (current.exists()) check(current.renameTo(File(root, "trace-1.jsonl")))
        }
        current.appendBytes(bytes)
    }

    fun export(): JSONArray = lock.withLock {
        val root = directory ?: return@withLock JSONArray()
        val rows = java.util.ArrayDeque<JSONObject>()
        for (i in 2 downTo 0) {
            val file = File(root, "trace-$i.jsonl")
            if (!file.exists() || file.length() > FILE_BYTES) continue
            file.useLines { lines -> lines.forEach { line ->
                if (line.length <= 16 * 1024) try {
                    rows.addLast(JSONObject(line)); if (rows.size > 256) rows.removeFirst()
                } catch (_: Exception) { }
            } }
        }
        JSONArray(rows.toList())
    }

    fun clear() = lock.withLock {
        generation.incrementAndGet()
        active.clear()
        directory?.let { root -> for (i in 0..2) File(root, "trace-$i.jsonl").let { check(!it.exists() || it.delete()) } }
        dropped.set(0)
    }

    internal const val FILE_BYTES = 512 * 1024
}
