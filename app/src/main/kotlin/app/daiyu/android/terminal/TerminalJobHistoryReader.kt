package app.daiyu.android.terminal

import android.content.Context
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID

/** Worker-only, read-only evidence, NOT a Registry/session/authorization factory.
 * The host supplies an already trusted conversation owner; disk metadata cannot select an owner,
 * executable, PID, storage root or a control operation. No directory creation, probes or journal I/O.
 */
internal class TerminalJobHistoryReader(
    filesDir: File,
    // Explicit JVM format-fixture seam. Production always uses Android descriptors below.
    private val directoryOpener: ((File) -> Directory?)? = null
) {
    constructor(context: Context) : this(context.applicationContext.filesDir)
    private val files = filesDir.canonicalFile

    /** Deterministic device-test boundaries, not a path/owner/authorization input. */
    internal var afterFilesOpened: (() -> Unit)? = null
    internal var afterRootOpened: (() -> Unit)? = null
    internal var beforeLeafOpened: ((String) -> Unit)? = null
    internal var afterLeafOpened: ((String) -> Unit)? = null

    internal data class Slice(val bytes: ByteArray, val size: Long)
    internal interface Directory : Closeable {
        fun names(): DirectoryStream<Path>
        /** null means ENOENT only; links, special files and all other errors must throw. */
        fun read(name: String, offset: Long, limit: Int, maxSize: Int): Slice?
    }

    data class Record(val jobId: String, val owner: String, val scope: String,
        val sessionId: String, val mode: String, val backend: String) {
        val state: String get() = "unknown_after_process_restart"
    }
    data class Scan(val records: List<Record>, val skipped: Int = 0, val error: String? = null)

    fun list(owner: String): Scan {
        requireOwner(owner)
        var skipped = 0
        return try {
            val directory = openDirectory() ?: return Scan(emptyList())
            directory.use {
                val records = mutableListOf<Record>()
                var scanned = 0
                directory.names().use { entries ->
                    for (entry in entries) {
                        if (++scanned > MAX_FILES) return Scan(emptyList(), skipped, "job_directory_scan_limit")
                        val name = entry.fileName.toString()
                        if (!name.endsWith(".json")) continue
                        try {
                            val id = name.removeSuffix(".json")
                            requireUuid(id)
                            val record = readRecord(directory, id, owner)
                            if (record != null) {
                                if (records.size >= MAX_JOBS) return Scan(emptyList(), skipped, "job_history_limit")
                                records.add(record)
                            }
                        } catch (_: Exception) { skipped++ }
                    }
                }
                Scan(records.sortedBy { it.jobId }, skipped)
            }
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
            val directory = checkNotNull(openDirectory())
            directory.use {
                check(readRecord(directory, original.jobId, owner) == original)
                val log = directory.read("${original.jobId}.log", offset, limit, MAX_LOG_BYTES)
                if (log == null) require(offset == 0L)
                val bytes = log?.bytes ?: ByteArray(0)
                val size = log?.size ?: 0L
                JSONObject().put("ok", true).put("job_id", original.jobId).put("backend", original.backend)
                    .put("scope", original.scope).put("mode", original.mode).put("history_read_only", true)
                    .put("state", original.state).put("execution", "unknown").put("exit_code", JSONObject.NULL)
                    .put("stdout", String(bytes, Charsets.UTF_8))
                    .put("stdout_base64", java.util.Base64.getEncoder().encodeToString(bytes))
                    .put("offset", offset).put("next_offset", offset + bytes.size).put("has_more", offset + bytes.size < size)
                    .put("total_bytes", size).put("log_available", log != null)
            }
        } catch (_: Exception) {
            JSONObject().put("ok", false).put("error", "job_history_unavailable")
                .put("state", original.state).put("execution", "unknown").put("exit_code", JSONObject.NULL)
        }
    }

    private fun openDirectory(): Directory? {
        if (directoryOpener != null) return directoryOpener.invoke(files)
        // filesDir is the host's trusted canonical anchor. Every untrusted child is opened relative
        // to a held descriptor, never by reopening a checked pathname. Keep both directories pinned
        // until the complete scan/page has finished (including metadata and log reads).
        val anchor = openDescriptor(files.path, directory = true)
        var root: ParcelFileDescriptor? = null
        var transferred = false
        try {
            afterFilesOpened?.invoke()
            root = try { openDescriptor("/proc/self/fd/${anchor.fd}/terminal-jobs", directory = true) }
            catch (error: ErrnoException) {
                if (error.errno == OsConstants.ENOENT) return null
                throw error
            }
            afterRootOpened?.invoke()
            val result = PinnedDirectory(anchor, checkNotNull(root))
            transferred = true
            return result
        } finally {
            if (!transferred) { try { root?.close() } finally { anchor.close() } }
        }
    }

    private inner class PinnedDirectory(
        private val anchor: ParcelFileDescriptor,
        private val root: ParcelFileDescriptor
    ) : Directory {
        private val path: Path get() = Paths.get("/proc/self/fd/${root.fd}")
        override fun names(): DirectoryStream<Path> = Files.newDirectoryStream(path)
        override fun read(name: String, offset: Long, limit: Int, maxSize: Int): Slice? {
            require(name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\u0000' !in name)
            beforeLeafOpened?.invoke(name)
            val leaf = try { openDescriptor("$path/$name", directory = false) }
            catch (error: ErrnoException) {
                if (error.errno == OsConstants.ENOENT) return null
                throw error
            }
            leaf.use {
                afterLeafOpened?.invoke(name)
                val fd = leaf.fileDescriptor
                val size = Os.fstat(fd).st_size
                require(size in 0..maxSize.toLong() && offset in 0..size)
                val bytes = ByteArray(minOf(limit.toLong(), size - offset).toInt())
                Os.lseek(fd, offset, OsConstants.SEEK_SET)
                var consumed = 0
                while (consumed < bytes.size) {
                    val count = Os.read(fd, bytes, consumed, bytes.size - consumed)
                    check(count > 0)
                    consumed += count
                }
                check(Os.fstat(fd).st_size == size)
                return Slice(bytes, size)
            }
        }
        override fun close() { try { root.close() } finally { anchor.close() } }
    }

    /** Same descriptor admission pattern as TerminalPaths.openDescriptor. NONBLOCK is essential:
     * a FIFO substituted immediately before open must be rejected by fstat, not block in open.
     * /proc/self/fd is only used to address our held parent; NOFOLLOW applies to each child.
     */
    private fun openDescriptor(path: String, directory: Boolean): ParcelFileDescriptor {
        val flags = OsConstants.O_RDONLY or LINUX_O_CLOEXEC or OsConstants.O_NOFOLLOW or
            OsConstants.O_NONBLOCK
        val raw = Os.open(path, flags, 0)
        var adopted: ParcelFileDescriptor? = null
        try {
            val stat = Os.fstat(raw)
            check(if (directory) OsConstants.S_ISDIR(stat.st_mode) else OsConstants.S_ISREG(stat.st_mode))
            val duplicate = if (android.os.Build.VERSION.SDK_INT >= 30) {
                Os.fcntlInt(raw, LINUX_F_DUPFD_CLOEXEC, 0)
            } else {
                Os::class.java.getMethod("fcntlInt", java.io.FileDescriptor::class.java,
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    .invoke(null, raw, LINUX_F_DUPFD_CLOEXEC, 0) as Int
            }
            return ParcelFileDescriptor.adoptFd(duplicate).also { adopted = it }
        } finally {
            try { Os.close(raw) } catch (failure: Exception) {
                try { adopted?.close() } catch (close: Exception) { failure.addSuppressed(close) }
                throw failure
            }
        }
    }

    private fun readRecord(directory: Directory, id: String, owner: String): Record? {
        requireUuid(id)
        val data = checkNotNull(directory.read("$id.json", 0, MAX_METADATA_BYTES, MAX_METADATA_BYTES))
        require(data.size in 1..MAX_METADATA_BYTES.toLong() && data.bytes.size.toLong() == data.size)
        val json = JSONObject(String(data.bytes, Charsets.UTF_8))
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
        private const val LINUX_O_CLOEXEC = 0x80000
        private const val LINUX_F_DUPFD_CLOEXEC = 1030
        const val MAX_FILES = 256
        const val MAX_JOBS = 64
        const val MAX_METADATA_BYTES = 16384
        const val MAX_LOG_BYTES = 262144
        const val MAX_PAGE_BYTES = 65536
    }
}
