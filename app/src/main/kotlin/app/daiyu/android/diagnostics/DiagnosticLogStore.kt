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

internal enum class DiagnosticEvent {
    DATA_STARTED, DATA_COMPLETED, DATA_FAILED,
    RUN_SLICE_STARTED, RUN_WAITING_USER, RUN_COMPLETED, RUN_INTERRUPTED, RUN_FAILED,
}
internal data class DiagnosticEntry(val timeMs: Long, val event: DiagnosticEvent)
internal data class DiagnosticLogState(val loaded: Boolean = false, val enabled: Boolean = false,
    val count: Int = 0, val unavailable: Boolean = false)

/** Dedicated, bounded metadata only. No free-form text, exception, credential or business ledger. */
internal class DiagnosticLogStore(
    private val file: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val write: (File, ByteArray) -> Unit = ::writeDiagnosticFile,
) {
    private val lock = Any()
    private var entries = emptyList<DiagnosticEntry>()
    private var initialized = false
    private var readable = true
    private val mutableState = MutableStateFlow(DiagnosticLogState())
    val state = mutableState.asStateFlow()

    fun load(): DiagnosticLogState = synchronized(lock) {
        try { readOnce(); mutableState.value }
        catch (_: Exception) { markUnavailable(); mutableState.value }
    }

    fun setEnabled(enabled: Boolean) = synchronized(lock) {
        requireReadable()
        persist(enabled, entries)
    }

    /** Worker-only. Diagnostics failure does not determine a request or persistence outcome. */
    fun record(event: DiagnosticEvent) = synchronized(lock) {
        try {
            requireReadable()
            if (mutableState.value.enabled) {
                val entry = DiagnosticEntry(clock().also { require(it >= 0) }, event)
                persist(true, (entries + entry).takeLast(MAX_ENTRIES))
            }
        } catch (_: Exception) { markUnavailable() }
    }

    fun clear() = synchronized(lock) {
        // An explicit clear can recover an unreadable file; unknown enablement resets to off.
        val enabled = try { readOnce(); if (readable) mutableState.value.enabled else false }
            catch (_: Exception) { false }
        persist(enabled, emptyList())
    }

    fun exportSnapshot(): ByteArray = synchronized(lock) {
        requireReadable()
        JSONObject().put("format", "daiyu-diagnostics").put("version", 1)
            .put("enabled", mutableState.value.enabled).put("events", encodeEntries(entries))
            .put("privacy", "Only event categories and timestamps; no chat, keys, paths or device identifiers.")
            .toString(2).toByteArray(Charsets.UTF_8)
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
