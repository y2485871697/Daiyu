package app.daiyu.android.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal enum class DiagnosticEvent {
    DATA_STARTED, DATA_COMPLETED, DATA_FAILED,
    RUN_SLICE_STARTED, RUN_WAITING_USER, RUN_COMPLETED, RUN_INTERRUPTED, RUN_FAILED,
    SPEECH_REQUEST_ACCEPTED, SPEECH_REQUEST_STALE, SPEECH_CONFIGURATION_READY, SPEECH_CONFIGURATION_FAILED,
    SPEECH_ENGINE_SYSTEM, SPEECH_ENGINE_DOUBAO, SPEECH_ENGINE_OTHER,
    SPEECH_HOST_REQUESTED, SPEECH_HOST_QUEUED, SPEECH_HOST_SPEAKING, SPEECH_HOST_TERMINAL,
    SPEECH_HOST_RELEASED, SPEECH_HOST_RELEASE_FAILED,
    SPEECH_OBSERVER_CANCELLED, SPEECH_OBSERVER_FAILED, SPEECH_OBSERVER_ENDED,
    SPEECH_PLAYER_QUEUED, SPEECH_HTTP_STARTED, SPEECH_HTTP_COMPLETED, SPEECH_HTTP_FAILED, SPEECH_HTTP_CANCELLED,
    SPEECH_CALL_SUBMITTED, SPEECH_RESPONSE_HEADERS, SPEECH_RESPONSE_FIRST_BYTES, SPEECH_RESPONSE_BODY_COMPLETED,
    SPEECH_CALL_FAILED, SPEECH_CALL_CANCELLED,
    SPEECH_AUDIO_DECODED, SPEECH_AUDIO_STORED, SPEECH_NATIVE_PREPARING, SPEECH_NATIVE_PREPARED,
    SPEECH_FOCUS_GRANTED, SPEECH_FOCUS_DENIED, SPEECH_NATIVE_STARTED,
    SPEECH_STREAM_AUDIO_RECEIVED, SPEECH_STREAM_PLAYBACK_STARTED, SPEECH_STREAM_DRAINED,
    SPEECH_PLAYER_COMPLETED, SPEECH_PLAYER_FAILED, SPEECH_PLAYER_CANCELLED, SPEECH_PLAYER_CLOSED,
    SPEECH_MEDIA_RELEASED, SPEECH_MEDIA_RELEASE_FAILED,
    SPEECH_UI_PREPARING, SPEECH_UI_PLAYING, SPEECH_UI_TERMINAL,
    DIAGNOSTIC_EVENTS_DROPPED,
}
internal data class DiagnosticEntry(val timeMs: Long, val event: DiagnosticEvent)
internal data class DiagnosticLogState(val loaded: Boolean = false, val enabled: Boolean = false,
    val count: Int = 0, val unavailable: Boolean = false)

/** Dedicated, bounded metadata only. No free-form text, exception, credential or business ledger. */
internal class DiagnosticLogStore(
    private val file: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val write: (File, ByteArray) -> Unit = ::writeDiagnosticFile,
    private val executor: Executor = diagnosticEventExecutor,
) {
    val frames = FramePerformanceLog(File(file.parentFile, "page-frames"))
    private val lock = Any()
    private var entries = emptyList<DiagnosticEntry>()
    private var initialized = false
    private var readable = true
    private class AsyncGeneration { val dropped = AtomicBoolean() }
    @Volatile private var asyncGeneration = AsyncGeneration()
    private val submissionLock = Any()
    private val mutableState = MutableStateFlow(DiagnosticLogState())
    val state = mutableState.asStateFlow()

    fun load(): DiagnosticLogState = synchronized(lock) {
        try { readOnce(); mutableState.value }
        catch (_: Exception) { markUnavailable(); mutableState.value }
    }

    fun setEnabled(enabled: Boolean) = synchronized(lock) {
        requireReadable()
        persist(enabled, entries)
        asyncGeneration = AsyncGeneration()
        frames.setEnabled(enabled)
        RuntimeDiagnostics.enabled = enabled
    }

    /** Worker-only. Diagnostics failure does not determine a request or persistence outcome. */
    fun record(event: DiagnosticEvent) {
        try { recordEntry(DiagnosticEntry(clock(), event)) }
        catch (_: Throwable) { try { synchronized(lock) { markUnavailable() } } catch (_: Throwable) { } }
    }

    /** Capture occurrence time before the ordered, bounded worker queue; never perform UI-thread I/O. */
    fun recordAsync(event: DiagnosticEvent) {
        val generation = asyncGeneration
        try {
            synchronized(submissionLock) {
                val entry = DiagnosticEntry(clock(), event)
                executor.execute {
                    synchronized(lock) {
                        if (generation !== asyncGeneration) return@synchronized
                        if (generation.dropped.getAndSet(false)) recordEntry(entry.copy(event = DiagnosticEvent.DIAGNOSTIC_EVENTS_DROPPED), generation)
                        recordEntry(entry, generation)
                    }
                }
            }
        } catch (_: Throwable) { generation.dropped.set(true) }
    }

    private fun recordEntry(entry: DiagnosticEntry, generation: AsyncGeneration? = null) = synchronized(lock) {
        try {
            if (generation != null && generation !== asyncGeneration) return@synchronized
            requireReadable()
            require(entry.timeMs >= 0)
            if (mutableState.value.enabled) persist(true, (entries + entry).takeLast(MAX_ENTRIES))
        } catch (_: Throwable) { try { markUnavailable() } catch (_: Throwable) { } }
    }

    fun clear() = synchronized(lock) {
        // An explicit clear can recover an unreadable file; unknown enablement resets to off.
        val enabled = try { readOnce(); if (readable) mutableState.value.enabled else false }
            catch (_: Exception) { false }
        frames.clear()
        RuntimeDiagnostics.clear()
        persist(enabled, emptyList())
        asyncGeneration = AsyncGeneration()
    }

    fun exportSnapshot(): ByteArray = synchronized(lock) {
        requireReadable()
        JSONObject().put("format", "daiyu-diagnostics").put("version", 2)
            .put("enabled", mutableState.value.enabled).put("events", encodeEntries(entries))
            .put("frame_performance", frames.export())
            .put("runtime_trace", RuntimeDiagnostics.export())
            .put("privacy", "Only fixed stages, outcomes, numeric timings/sizes/memory and code stack locations; no chat, keys, paths or device identifiers.")
            .toString().toByteArray(Charsets.UTF_8)
    }

    private fun requireReadable() {
        try { check(readable) { "diagnostic_store_unavailable" }; readOnce() }
        catch (_: Exception) { markUnavailable(); throw IllegalStateException("diagnostic_store_unavailable") }
    }
    private fun readOnce() {
        if (initialized) return
        if (!file.exists()) {
            initialized = true
            mutableState.value = DiagnosticLogState(loaded = true)
            RuntimeDiagnostics.enabled = false
            return
        }
        val bytes = file.inputStream().use { input ->
            val output = ByteArrayOutputStream()
            val block = ByteArray(8192)
            while (true) {
                val count = input.read(block)
                if (count < 0) break
                require(output.size() + count <= MAX_BYTES)
                output.write(block, 0, count)
            }
            output.toByteArray()
        }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        val tokens = JSONTokener(text)
        val json = tokens.nextValue() as? JSONObject ?: error("invalid_diagnostics")
        require(tokens.nextClean() == '\u0000')
        require(json.keys().asSequence().toSet() == setOf("version", "enabled", "events"))
        require(json.get("version") == 1)
        val enabled = json.get("enabled") as? Boolean ?: error("invalid_diagnostics")
        val rows = json.get("events") as? JSONArray ?: error("invalid_diagnostics")
        require(rows.length() <= MAX_ENTRIES)
        val loaded = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            require(row.keys().asSequence().toSet() == setOf("time_ms", "event"))
            val time = when (val raw = row.get("time_ms")) {
                is Long -> raw
                is Int -> raw.toLong()
                else -> error("invalid_diagnostics")
            }
            require(time >= 0)
            DiagnosticEntry(time, DiagnosticEvent.valueOf(row.get("event") as? String ?: error("invalid_diagnostics")))
        }
        entries = loaded; initialized = true; readable = true
        mutableState.value = DiagnosticLogState(true, enabled, loaded.size)
        frames.setEnabled(enabled)
        RuntimeDiagnostics.enabled = enabled
    }
    private fun persist(enabled: Boolean, next: List<DiagnosticEntry>) {
        val bytes = JSONObject().put("version", 1).put("enabled", enabled)
            .put("events", encodeEntries(next)).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES)
        try { write(file, bytes) }
        catch (_: Exception) { markUnavailable(); throw IllegalStateException("diagnostic_store_unavailable") }
        entries = next; initialized = true; readable = true
        mutableState.value = DiagnosticLogState(true, enabled, next.size)
    }
    private fun markUnavailable() {
        frames.setEnabled(false)
        RuntimeDiagnostics.enabled = false
        readable = false
        mutableState.value = mutableState.value.copy(loaded = true, unavailable = true)
    }
    private fun encodeEntries(values: List<DiagnosticEntry>) = JSONArray().also { array ->
        values.forEach { array.put(JSONObject().put("time_ms", it.timeMs).put("event", it.event.name)) }
    }
    companion object {
        internal const val MAX_ENTRIES = 1024
        internal const val MAX_BYTES = 512 * 1024
    }
}

private val diagnosticEventExecutor: Executor by lazy {
    ThreadPoolExecutor(1, 1, 5, TimeUnit.SECONDS, ArrayBlockingQueue(64),
        { action -> Thread(action, "daiyu-speech-diagnostics").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()).apply { allowCoreThreadTimeOut(true) }
}

@Synchronized
private fun writeDiagnosticFile(file: File, bytes: ByteArray) {
    val parent = checkNotNull(file.parentFile)
    check(parent.isDirectory || parent.mkdirs())
    // A fixed sibling bounds crash leftovers; serialize writers before reusing it.
    val pending = File(parent, "${file.name}.pending")
    try {
        FileOutputStream(pending).use { it.write(bytes); it.fd.sync() }
        Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally { pending.delete() }
}
