package app.daiyu.android.terminal

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.READ
import java.util.UUID

/** Worker-only, read-only evidence, NOT a Registry/session/authorization factory.
 * The host supplies an already trusted conversation owner; disk metadata cannot select an owner,
 * executable, PID, storage root or a control operation. No directory creation, probes or journal I/O.
 */
internal class TerminalJobHistoryReader(filesDir: File) {
    constructor(context: Context) : this(context.applicationContext.filesDir)
    private val files = filesDir.canonicalFile
    private val root = File(files, "terminal-jobs").toPath()

    data class Record(val jobId: String, val owner: String, val scope: String,
        val sessionId: String, val mode: String, val backend: String) {
        val state: String get() = "unknown_after_process_restart"
    }
    data class Scan(val records: List<Record>, val skipped: Int = 0, val error: String? = null)

    fun list(owner: String): Scan {
        requireOwner(owner)
        var skipped = 0
        return try {
            if (!rootAvailable()) return Scan(emptyList())
            val records = mutableListOf<Record>()
            var scanned = 0
            Files.newDirectoryStream(root).use { entries ->
                for (entry in entries) {
                    if (++scanned > MAX_FILES) return Scan(emptyList(), skipped, "job_directory_scan_limit")
                    val name = entry.fileName.toString()
                    if (!name.endsWith(".json")) continue
                    try {
                        val id = name.removeSuffix(".json")
                        requireUuid(id)
                        val record = readRecord(id, owner)
                        if (record != null) {
                            if (records.size >= MAX_JOBS) return Scan(emptyList(), skipped, "job_history_limit")
                            records.add(record)
                        }
                    } catch (_: Exception) { skipped++ }
                }
            }
            check(rootAvailable())
            Scan(records.sortedBy { it.jobId }, skipped)
        } catch (_: Exception) { Scan(emptyList(), skipped, "job_history_unavailable") }
    }

    /** Revalidate the exact original locator and owner on EVERY page. Bytes, not characters,
     * define offsets; stdout_base64 preserves UTF-8 split across page boundaries losslessly. */
    fun page(owner: String, original: Record, offset: Long = 0, limit: Int = 16384): JSONObject {
        requireOwner(owner)
        require(original.owner == owner)
        requireUuid(original.jobId)
        require(offset >= 0 && limit in 1..MAX_PAGE_BYTES)
        return try {
            check(rootAvailable())
            check(readRecord(original.jobId, owner) == original)
            val log = root.resolve("${original.jobId}.log")
            val bytes: ByteArray
            val size: Long
            val available = Files.exists(log, NOFOLLOW_LINKS)
            if (!available) {
                require(offset == 0L)
                bytes = ByteArray(0); size = 0
            } else {
                checkRegular(log)
                Files.newByteChannel(log, setOf<java.nio.file.OpenOption>(READ, NOFOLLOW_LINKS)).use { input ->
                    size = input.size()
                    require(size in 0..MAX_LOG_BYTES.toLong() && offset <= size)
                    input.position(offset)
                    bytes = ByteArray(minOf(limit.toLong(), size - offset).toInt())
                    val target = ByteBuffer.wrap(bytes)
                    while (target.hasRemaining()) check(input.read(target) > 0)
                }
                checkRegular(log)
            }
            check(rootAvailable())
            JSONObject().put("ok", true).put("job_id", original.jobId).put("backend", original.backend)
                .put("scope", original.scope).put("mode", original.mode).put("history_read_only", true)
                .put("state", original.state).put("execution", "unknown").put("exit_code", JSONObject.NULL)
                .put("stdout", String(bytes, Charsets.UTF_8))
                .put("stdout_base64", java.util.Base64.getEncoder().encodeToString(bytes))
                .put("offset", offset).put("next_offset", offset + bytes.size).put("has_more", offset + bytes.size < size)
                .put("total_bytes", size).put("log_available", available)
        } catch (_: Exception) {
            JSONObject().put("ok", false).put("error", "job_history_unavailable")
                .put("state", original.state).put("execution", "unknown").put("exit_code", JSONObject.NULL)
        }
    }

    private fun rootAvailable(): Boolean {
        if (!Files.exists(root, NOFOLLOW_LINKS)) return false
        check(!Files.isSymbolicLink(root) && Files.isDirectory(root, NOFOLLOW_LINKS))
        check(root.toRealPath() == root && root.parent.toFile() == files)
        return true
    }
    private fun checkRegular(path: java.nio.file.Path) {
        check(rootAvailable() && path.parent == root)
        check(!Files.isSymbolicLink(path) && Files.isRegularFile(path, NOFOLLOW_LINKS))
        check(path.toRealPath() == path)
    }
    private fun readRecord(id: String, owner: String): Record? {
        requireUuid(id)
        val path = root.resolve("$id.json")
        checkRegular(path)
        val text = Files.newByteChannel(path, setOf<java.nio.file.OpenOption>(READ, NOFOLLOW_LINKS)).use { input ->
            val length = input.size()
            require(length in 1..MAX_METADATA_BYTES.toLong())
            val bytes = ByteArray(length.toInt())
            val target = ByteBuffer.wrap(bytes)
            while (target.hasRemaining()) check(input.read(target) > 0)
            require(input.size() == length)
            String(bytes, Charsets.UTF_8)
        }
        checkRegular(path)
        val json = JSONObject(text)
        fun field(key: String): String = (json.get(key) as? String) ?: error("invalid_history_field")
        require(field("job_id") == id)
        val recordedOwner = field("owner")
        requireOwner(recordedOwner)
        if (recordedOwner != owner) return null
        val scope = field("scope").also(::requireOwner)
        val session = field("session_id").also(::requireUuid)
        val mode = field("mode").also { require(it in setOf("sync", "async", "daemon")) }
        val backend = field("backend").also { require(it.isNotBlank() && it.length <= 256 && it.none { c -> c.isISOControl() }) }
        // Never propagate persisted state, exit_code, PID or process_start as live evidence.
        return Record(id, recordedOwner, scope, session, mode, backend)
    }
    private fun requireOwner(owner: String) { require(owner.matches(Regex("[A-Za-z0-9_-]{1,128}"))) }
    private fun requireUuid(id: String) { require(UUID.fromString(id).toString() == id) }

    companion object {
        const val MAX_FILES = 256
        const val MAX_JOBS = 64
        const val MAX_METADATA_BYTES = 16384
        const val MAX_LOG_BYTES = 262144
        const val MAX_PAGE_BYTES = 65536
    }
}
