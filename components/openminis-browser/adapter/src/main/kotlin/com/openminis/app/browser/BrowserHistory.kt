package com.openminis.app.browser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Calendar
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Independent adapter implementation of the fixed OpenMinis BrowserHistoryStore API, NOT an
 * upstream source copy. Select this file instead of upstream BrowserHistory.kt when compiling.
 *
 * getInstance/record only register bounded in-memory state and enqueue work; even filesDir lookup
 * and the initial load run on our own serial worker. No worker calls host/registry/UI code.
 * Reads are snapshots of admitted events, PARTIAL until loadState leaves LOADING. FAILED means
 * old disk history could not be read, not that it was synchronously loaded. Accepted mutations
 * are merged over startup data in order, so loading cannot resurrect cleared entries.
 *
 * Mutations throw RejectedExecutionException BEFORE changing memory if closed or queue-full.
 * Admission is not a durability acknowledgement: persistenceFailed reports the last write result.
 * No automatic retry of failed/unknown writes occurs; later events write the current snapshot.
 * close() does not wait; previously admitted work drains
 * and may reach disk after close returns. There is deliberately no model-facing history export.
 */
class BrowserHistoryStore internal constructor(
    private val backend: Backend,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxEntries: Int = MAX_ENTRIES,
    queueCapacity: Int = QUEUE_CAPACITY,
) : AutoCloseable {
    companion object {
        private const val MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000L
        private const val MAX_ENTRIES = 256
        private const val QUEUE_CAPACITY = 64
        private const val MAX_URL = 4096
        private const val MAX_TITLE = 1024
        internal const val MAX_FILE_BYTES = 12 * 1024 * 1024
        internal const val FILENAME = "browser_history.json"
        @Volatile private var instance: BrowserHistoryStore? = null

        fun getInstance(context: Context): BrowserHistoryStore = instance ?: synchronized(this) {
            instance ?: run {
                val app = context.applicationContext
                // Do not evaluate filesDir on the caller (often holding host navigation locks).
                BrowserHistoryStore(FileBackend { app.filesDir.toPath() }).also { instance = it }
            }
        }
    }

    data class Entry(
        val id: String = UUID.randomUUID().toString(),
        val url: String,
        val title: String,
        val timestamp: Long = System.currentTimeMillis(),
        val domain: String = extractDomain(url),
    )

    enum class LoadState { LOADING, READY, FAILED }
    @Volatile var loadState: LoadState = LoadState.LOADING
        private set
    @Volatile var persistenceFailed: Boolean = false
        private set

    /** Test seam: invoked exclusively on the worker; no Android construction is required. */
    internal interface Backend {
        fun read(): String?
        fun write(json: String)
    }

    /** directory must be an app-private host directory, never a model-supplied path. */
    internal class FileBackend(private val directory: () -> Path) : Backend {
        override fun read(): String? {
            val path = directory().resolve(FILENAME)
            if (!Files.exists(path)) return null
            require(Files.size(path) <= MAX_FILE_BYTES) { "History file too large" }
            return Files.newInputStream(path).use { input ->
                val bytes = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer, 0, minOf(buffer.size, MAX_FILE_BYTES + 1 - bytes.size()))
                    if (count < 0) break
                    bytes.write(buffer, 0, count)
                    // Also enforce the cap if the file grew after the size check.
                    require(bytes.size() <= MAX_FILE_BYTES) { "History file too large" }
                }
                String(bytes.toByteArray(), Charsets.UTF_8)
            }
        }

        override fun write(json: String) {
            val bytes = json.toByteArray(Charsets.UTF_8)
            require(bytes.size <= MAX_FILE_BYTES) { "History file too large" }
            val dir = directory()
            Files.createDirectories(dir)
            val temp = Files.createTempFile(dir, ".browser-history-", ".tmp")
            try {
                FileChannel.open(temp, StandardOpenOption.WRITE).use { channel ->
                    val buffer = ByteBuffer.wrap(bytes)
                    while (buffer.hasRemaining()) channel.write(buffer)
                    channel.force(true)
                }
                // Fail closed if the filesystem cannot atomically replace; no partial-file fallback.
                Files.move(temp, dir.resolve(FILENAME),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(temp)
            }
        }
    }

    private sealed class Mutation {
        data class Record(val entry: Entry) : Mutation()
        object Clear : Mutation()
    }

    private val lock = Any()
    private val entries = ArrayList<Entry>()
    // Exists only during startup; bounded by the same queue admission limit.
    private val startup = ArrayList<Mutation>()
    private var closed = false
    private var nextId = 0L
    private val idPrefix = java.lang.Long.toHexString(System.nanoTime())
    private val worker: ThreadPoolExecutor

    init {
        require(maxEntries in 1..MAX_ENTRIES && queueCapacity in 1..QUEUE_CAPACITY)
        worker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            ArrayBlockingQueue<Runnable>(queueCapacity), { runnable ->
                Thread(runnable, "browser-history-io").apply { isDaemon = true }
            }, ThreadPoolExecutor.AbortPolicy())
        worker.execute { load() }
    }

    fun record(url: String, title: String) {
        if (url.isEmpty()) return
        require(url.length <= MAX_URL && title.length <= MAX_TITLE) { "History entry too large" }
        synchronized(lock) {
            if (closed) throw RejectedExecutionException("History closed")
            if (entries.lastOrNull()?.url == url) return
            // Explicit ID avoids SecureRandom/UUID initialization on the navigation callback.
            val now = clock()
            val entry = Entry(id = "$idPrefix-${now.toString(16)}-${nextId++}",
                url = url, title = title, timestamp = now)
            admit(Mutation.Record(entry))
        }
    }

    fun getEntries(): List<Entry> = synchronized(lock) {
        entries.filter { it.timestamp >= clock() - MAX_AGE_MS }.sortedByDescending { it.timestamp }
    }

    fun search(query: String): List<Entry> {
        val snapshot = getEntries()
        if (query.isBlank()) return snapshot
        val q = query.lowercase()
        return snapshot.filter { it.title.lowercase().contains(q) || it.url.lowercase().contains(q) }
    }

    fun groupedByDay(): Map<String, List<Entry>> {
        val today = Calendar.getInstance().apply { timeInMillis = clock() }
        val yesterday = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
        val cal = Calendar.getInstance()
        fun sameDay(other: Calendar) = cal.get(Calendar.YEAR) == other.get(Calendar.YEAR) &&
            cal.get(Calendar.DAY_OF_YEAR) == other.get(Calendar.DAY_OF_YEAR)
        return getEntries().groupBy { entry ->
            cal.timeInMillis = entry.timestamp
            when {
                sameDay(today) -> "Today"
                sameDay(yesterday) -> "Yesterday"
                else -> "${cal.get(Calendar.MONTH) + 1}/${cal.get(Calendar.DAY_OF_MONTH)}"
            }
        }
    }

    fun uniqueDomains(): List<String> = getEntries().map { it.domain }
        .filter { it.isNotEmpty() }.distinct().sorted()

    fun clear() = synchronized(lock) { admit(Mutation.Clear) }

    // Caller holds lock. Enqueue BEFORE changing memory: a rejected event is never replayed.
    private fun admit(mutation: Mutation) {
        if (closed) throw RejectedExecutionException("History closed")
        worker.execute { persist() }
        if (loadState == LoadState.LOADING) startup.add(mutation)
        apply(entries, mutation)
        trim(entries)
    }

    private fun apply(target: MutableList<Entry>, mutation: Mutation) {
        when (mutation) {
            is Mutation.Record -> if (target.lastOrNull()?.url != mutation.entry.url) target.add(mutation.entry)
            Mutation.Clear -> target.clear()
        }
    }

    private fun trim(target: MutableList<Entry>) {
        val cutoff = clock() - MAX_AGE_MS
        target.removeAll { it.timestamp < cutoff }
        if (target.size > maxEntries) target.subList(0, target.size - maxEntries).clear()
    }

    private fun load() {
        var failed = false
        val loaded = try {
            decode(backend.read())
        } catch (_: Exception) {
            failed = true
            arrayListOf<Entry>()
        }
        synchronized(lock) {
            trim(loaded)
            for (mutation in startup) {
                apply(loaded, mutation)
                trim(loaded)
            }
            entries.clear()
            entries.addAll(loaded)
            startup.clear()
            loadState = if (failed) LoadState.FAILED else LoadState.READY
        }
    }

    private fun persist() {
        val snapshot = synchronized(lock) {
            trim(entries)
            entries.toList()
        }
        try {
            val array = JSONArray()
            for (entry in snapshot) array.put(JSONObject().apply {
                put("id", entry.id)
                put("url", entry.url)
                put("title", entry.title)
                put("timestamp", entry.timestamp)
                put("domain", entry.domain)
            })
            backend.write(array.toString())
            persistenceFailed = false
        } catch (_: Exception) {
            // No URLs, titles, paths or exception messages are logged or sent to a model.
            persistenceFailed = true
        }
    }

    private fun decode(json: String?): ArrayList<Entry> {
        val result = ArrayList<Entry>()
        if (json == null) return result
        require(json.length <= MAX_FILE_BYTES) { "History file too large" }
        val array = JSONArray(json)
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val entry = Entry(
                id = obj.optString("id", "loaded-$i"),
                url = obj.optString("url", ""),
                title = obj.optString("title", ""),
                timestamp = obj.optLong("timestamp", 0),
                domain = obj.optString("domain", ""),
            )
            if (entry.id.length <= 128 && entry.url.isNotEmpty() && entry.url.length <= MAX_URL &&
                entry.title.length <= MAX_TITLE && entry.domain.length <= MAX_URL) {
                result.add(entry)
                if (result.size > maxEntries) result.removeAt(0)
            }
        }
        return result
    }

    override fun close() = synchronized(lock) {
        closed = true
        worker.shutdown() // no join, await, callback, disk access, or cancellation of accepted work
    }

    /** Test-only waiting seam, never used by production navigation or close. */
    internal fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = worker.awaitTermination(timeout, unit)
}

private fun extractDomain(url: String): String = try { URI(url).host ?: "" } catch (_: Exception) { "" }
