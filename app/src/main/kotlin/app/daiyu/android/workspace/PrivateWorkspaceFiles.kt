package app.daiyu.android.workspace

import android.content.Context
import android.system.Os
import android.system.OsConstants
import java.io.File
import android.os.ParcelFileDescriptor
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** App-owned files only. Each operation walks pinned directory descriptors, never a SAF host path. */
internal class PrivateWorkspaceFiles(private val paths: PrivateWorkspacePaths,
    private val availableBytes: (File) -> Long = { it.usableSpace }) {
    private val readOnly: Boolean get() = paths.readOnly
    constructor(context: Context) : this(PrivateWorkspacePaths(context.filesDir))

    internal data class Identity(val device: Long, val inode: Long, val mode: Int, val size: Long, val modified: Long)
    class Entry internal constructor(val relative: String, val name: String, val directory: Boolean,
        val size: Long, internal val identities: List<Identity>, val symbolicLink: Boolean = false)
    data class Listing(val directory: Entry, val entries: List<Entry>, val truncated: Boolean)

    fun list(directory: Entry? = null, cancelled: () -> Boolean = { false }): Listing = synchronized(publicationLock) {
        check(directory == null || directory.directory) { "workspace_not_directory" }
        pinned(directory, cancelled) { descriptor, chain ->
            val relative = directory?.relative.orEmpty()
            val current = Entry(relative, relative.substringAfterLast('/').ifEmpty { "工作区" }, true, 0, chain)
            val entries = mutableListOf<Entry>()
            var truncated = false
            var scanned = 0
            java.nio.file.Files.newDirectoryStream(File(fdPath(descriptor)).toPath()).use { children ->
                for (path in children) {
                    val child = path.toFile()
                    active(cancelled)
                    if (scanned++ == MAX_ENTRIES) { truncated = true; break }
                    if (child.name.startsWith(STAGING)) continue
                    val stat = Os.lstat(child.path)
                    val symbolicLink = OsConstants.S_ISLNK(stat.st_mode)
                    if (!OsConstants.S_ISDIR(stat.st_mode) && !OsConstants.S_ISREG(stat.st_mode) && !(readOnly && symbolicLink)) continue
                    val id = identity(stat)
                    entries += Entry(listOf(relative, child.name).filter { it.isNotEmpty() }.joinToString("/"),
                        child.name, OsConstants.S_ISDIR(stat.st_mode), stat.st_size, chain + id, symbolicLink)
                }
            }
            active(cancelled)
            Listing(current, entries.sortedWith(compareByDescending<Entry> { it.directory }.thenBy { it.name.lowercase() }), truncated)
        }
    }

    /** Folder picker reads names only through the same pinned, no-follow directory walk. */
    fun directoryNames(cancelled: () -> Boolean = { false }): List<String> = pinned(null, cancelled) { descriptor, _ ->
        val names = mutableListOf<String>()
        java.nio.file.Files.newDirectoryStream(File(fdPath(descriptor)).toPath()).use { children ->
            for (child in children) {
                active(cancelled)
                val name = child.fileName.toString()
                if (name.startsWith(STAGING)) continue
                val stat = try { Os.lstat(child.toString()) } catch (failure: android.system.ErrnoException) {
                    if (failure.errno == OsConstants.ENOENT) continue else throw failure
                }
                if (OsConstants.S_ISDIR(stat.st_mode)) names += name
            }
        }
        active(cancelled)
        names.sortedWith(compareBy<String> { it.lowercase(java.util.Locale.ROOT) }.thenBy { it })
    }

    fun parent(directory: Entry, cancelled: () -> Boolean = { false }): Listing {
        check(directory.directory && directory.relative.isNotEmpty()) { "workspace_no_parent" }
        val relative = directory.relative.substringBeforeLast('/', "")
        return list(Entry(relative, relative.substringAfterLast('/'), true, 0, directory.identities.dropLast(1)), cancelled)
    }

    fun importFile(directory: Entry, name: String, openInput: () -> InputStream,
        cancelled: () -> Boolean = { false }): String = importFile(directory, name, openInput, cancelled, {})

    fun importFile(directory: Entry, name: String, openInput: () -> InputStream,
        cancelled: () -> Boolean, progress: (Long) -> Unit): String {
        check(!readOnly) { "workspace_read_only" }
        check(directory.directory) { "workspace_not_directory" }
        val cleaned = name.substringAfterLast('/').substringAfterLast('\\')
            .map { if (it.isISOControl()) '_' else it }.joinToString("").ifBlank { "文件" }
        val safeName = buildString {
            var bytes = 0
            val points = cleaned.codePoints().iterator()
            while (points.hasNext()) {
                val next = String(Character.toChars(points.nextInt()))
                val length = next.toByteArray(Charsets.UTF_8).size
                if (bytes + length > 200) break
                append(next); bytes += length
            }
        }
        return pinned(directory, cancelled) { parent, _ ->
            val prefix = fdPath(parent)
            val token = UUID.randomUUID().toString()
            val temporary = "$prefix/$STAGING$token"
            val finalName = "$token-$safeName"
            val target = "$prefix/$finalName"
            var staged: Identity? = null
            var reserved: Identity? = null
            fun removeOwned(path: String, owned: Identity?) {
                if (owned != null) runCatching {
                    if (sameNode(identity(Os.lstat(path)), owned)) Os.remove(path)
                }
            }
            try {
                val raw = Os.open(temporary,
                    OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW, 384)
                val held = try {
                    staged = identity(Os.fstat(raw))
                    ParcelFileDescriptor.dup(raw)
                } finally { Os.close(raw) }
                val completed = ParcelFileDescriptor.AutoCloseOutputStream(held).use { destination ->
                    active(cancelled)
                    openInput().use { source -> copy(source, destination, cancelled, progress, File(prefix)) }
                    Os.fsync(destination.fd)
                    active(cancelled)
                    identity(Os.fstat(held.fileDescriptor)).also {
                        check(identity(Os.lstat(temporary)) == it) { "workspace_entry_changed" }
                    }
                }
                synchronized(publicationLock) {
                    active(cancelled)
                    check(identity(Os.lstat(temporary)) == completed) { "workspace_entry_changed" }
                    // SELinux denies hard links. Lists share this short commit lock so they
                    // cannot observe the empty name reserved exclusively for this import.
                    try {
                        val reservation = Os.open(target,
                            OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW, 384)
                        try { reserved = identity(Os.fstat(reservation)) } finally { Os.close(reservation) }
                        check(identity(Os.lstat(target)) == reserved) { "workspace_entry_changed" }
                        Os.rename(temporary, target)
                        reserved = null
                    } finally { removeOwned(target, reserved) }
                }
                finalName
            } finally {
                removeOwned(temporary, staged)
                removeOwned(target, reserved)
            }
        }
    }

    fun exportFile(entry: Entry, openOutput: () -> OutputStream, cancelled: () -> Boolean = { false }) =
        exportFile(entry, openOutput, cancelled, {})

    fun exportFile(entry: Entry, openOutput: () -> OutputStream, cancelled: () -> Boolean,
        progress: (Long) -> Unit) {
        check(!readOnly) { "workspace_read_only" }
        check(!entry.directory && !entry.symbolicLink) { "workspace_not_file" }
        pinned(entry, cancelled) { descriptor, _ ->
            // Duplicate the pinned file: closing the stream must not close the walk's descriptor twice.
            ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(descriptor.fileDescriptor)).use { input ->
                active(cancelled)
                openOutput().use { output -> copy(input, output, cancelled, progress) }
            }
        }
    }

    /** Deletes files and empty directories only; a populated directory is never recursively removed. */
    fun delete(entry: Entry, cancelled: () -> Boolean = { false }) {
        check(!readOnly) { "workspace_read_only" }
        check(entry.relative.isNotEmpty()) { "workspace_root_protected" }
        val parentRelative = entry.relative.substringBeforeLast('/', "")
        val parent = Entry(parentRelative, "", true, 0, entry.identities.dropLast(1))
        pinned(parent, cancelled) { descriptor, _ ->
            val target = "${fdPath(descriptor)}/${entry.name}"
            active(cancelled)
            check(identity(Os.lstat(target)) == entry.identities.last()) { "workspace_entry_changed" }
            // unlink/rmdir never follow a replaced final symlink; the parent remains pinned.
            Os.remove(target)
        }
    }

    private fun <T> pinned(entry: Entry?, cancelled: () -> Boolean,
        block: (ParcelFileDescriptor, List<Identity>) -> T): T {
        active(cancelled)
        paths.ensureRoot()
        val descriptors = mutableListOf<ParcelFileDescriptor>()
        try {
            val storage = open(paths.storage.path, true).also { descriptors += it }
            var held = storage
            for (part in paths.rootParts) held = open("${fdPath(held)}/$part", true).also { descriptors += it }
            val chain = mutableListOf(identity(Os.fstat(held.fileDescriptor)))
            val parts = entry?.relative.orEmpty().split('/').filter { it.isNotEmpty() }
            check(entry == null || entry.relative.isEmpty() ||
                (!entry.relative.startsWith('/') && parts.joinToString("/") == entry.relative &&
                    parts.none { it == "." || it == ".." || '\\' in it || '\u0000' in it })) { "workspace_invalid_path" }
            if (entry != null) check(entry.identities.size == parts.size + 1 && sameNode(chain[0], entry.identities[0])) { "workspace_entry_changed" }
            for ((index, part) in parts.withIndex()) {
                active(cancelled)
                val isDirectory = index < parts.lastIndex || checkNotNull(entry).directory
                held = open("${fdPath(held)}/$part", isDirectory).also { descriptors += it }
                val id = identity(Os.fstat(held.fileDescriptor)); chain += id
                val expected = checkNotNull(entry).identities[index + 1]
                check(if (isDirectory) sameNode(id, expected) else id == expected) { "workspace_entry_changed" }
            }
            active(cancelled)
            return block(held, chain.toList())
        } finally { descriptors.asReversed().forEach { runCatching { it.close() } } }
    }

    private fun open(path: String, directory: Boolean): ParcelFileDescriptor {
        val fd = Os.open(path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK, 0)
        try {
            val mode = Os.fstat(fd).st_mode
            check(if (directory) OsConstants.S_ISDIR(mode) else OsConstants.S_ISREG(mode)) { "workspace_entry_changed" }
            return ParcelFileDescriptor.dup(fd)
        } finally { Os.close(fd) }
    }

    private fun fdPath(fd: ParcelFileDescriptor) = "/proc/self/fd/${fd.fd}"

    private fun copy(input: InputStream, output: OutputStream, cancelled: () -> Boolean,
        progress: (Long) -> Unit, destination: File? = null) {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        progress(total)
        while (true) {
            active(cancelled)
            var count = input.read(buffer)
            if (count < 0) { active(cancelled); return }
            if (count == 0) {
                val next = input.read()
                if (next < 0) { active(cancelled); return }
                buffer[0] = next.toByte(); count = 1
            }
            if (destination != null) {
                val available = availableBytes(destination)
                if (available > 0 && available < count) throw java.io.IOException("workspace_storage_full")
            }
            active(cancelled)
            output.write(buffer, 0, count)
            total = Math.addExact(total, count.toLong())
            progress(total)
        }
    }

    private fun identity(stat: android.system.StructStat) = Identity(stat.st_dev, stat.st_ino, stat.st_mode, stat.st_size, stat.st_mtime)
    private fun sameNode(left: Identity, right: Identity) = left.device == right.device && left.inode == right.inode && left.mode == right.mode
    private fun active(cancelled: () -> Boolean) { if (Thread.currentThread().isInterrupted || cancelled()) throw InterruptedException() }

    companion object {
        const val MAX_ENTRIES = 100
        private const val STAGING = ".workspace-import-"
        private val publicationLock = Any()
    }
}
