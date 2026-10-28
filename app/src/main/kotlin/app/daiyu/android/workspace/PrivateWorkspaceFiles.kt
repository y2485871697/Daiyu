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
    private val availableBytes: (File) -> Long = { it.usableSpace },
    private val allowDirectoryCreation: Boolean = false) {
    private val readOnly: Boolean get() = paths.readOnly
    constructor(context: Context) : this(PrivateWorkspacePaths(context.filesDir))

    internal data class Identity(val device: Long, val inode: Long, val mode: Int, val size: Long, val modified: Long)
    class Entry internal constructor(val relative: String, val name: String, val directory: Boolean,
        val size: Long, internal val identities: List<Identity>, val symbolicLink: Boolean = false)
    data class Listing(val directory: Entry, val entries: List<Entry>, val truncated: Boolean)

    fun list(directory: Entry? = null, cancelled: () -> Boolean = { false }): Listing = listEntries(directory, MAX_ENTRIES, cancelled)

    fun listAll(directory: Entry, cancelled: () -> Boolean = { false }): Listing = listEntries(directory, Int.MAX_VALUE, cancelled)

    private fun listEntries(directory: Entry?, maximumEntries: Int, cancelled: () -> Boolean): Listing = synchronized(publicationLock) {
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
                    if (scanned++ == maximumEntries) { truncated = true; break }
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

    fun createDirectory(directory: Entry?, name: String, cancelled: () -> Boolean = { false }) {
        check(!readOnly || allowDirectoryCreation) { "workspace_read_only" }
        val safeName = workspaceDirectoryName(name)
        check(directory == null || directory.directory) { "workspace_not_directory" }
        pinned(directory, cancelled) { descriptor, _ ->
            active(cancelled)
            try { Os.mkdir("${fdPath(descriptor)}/$safeName", 448) }
            catch (failure: android.system.ErrnoException) {
                if (failure.errno == OsConstants.EEXIST) error("workspace_directory_exists")
                throw failure
            }
        }
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

    fun canModify(entry: Entry): Boolean = (!readOnly || paths.rootfsMutations) &&
        WorkspaceEntryPolicy.mutable(entry.relative, paths.rootfsMutations, entry.symbolicLink)

    fun rename(entry: Entry, name: String, cancelled: () -> Boolean = { false }) = synchronized(publicationLock) {
        check(canModify(entry)) { "workspace_entry_protected" }
        WorkspaceEntryPolicy.requireName(name)
        val parentRelative = entry.relative.substringBeforeLast('/', "")
        val targetRelative = listOf(parentRelative, name).filter { it.isNotEmpty() }.joinToString("/")
        check(WorkspaceEntryPolicy.mutable(targetRelative, paths.rootfsMutations)) { "workspace_entry_protected" }
        val parent = Entry(parentRelative, "", true, 0, entry.identities.dropLast(1))
        pinned(parent, cancelled) { descriptor, _ ->
            val source = File(fdPath(descriptor), entry.name).toPath()
            val target = File(fdPath(descriptor), name).toPath()
            active(cancelled)
            check(identity(Os.lstat(source.toString())) == entry.identities.last()) { "workspace_entry_changed" }
            if (entry.name != name) java.nio.file.Files.move(source, target)
        }
        Unit
    }

    /** No-follow deletion; a cancelled nonempty-directory operation may have removed some children. */
    fun deleteTree(entry: Entry, cancelled: () -> Boolean = { false }) = synchronized(publicationLock) {
        check(canModify(entry)) { "workspace_entry_protected" }
        val parentRelative = entry.relative.substringBeforeLast('/', "")
        val parent = Entry(parentRelative, "", true, 0, entry.identities.dropLast(1))
        pinned(parent, cancelled) { descriptor, _ ->
            fun remove(parentFd: ParcelFileDescriptor, name: String, expected: Identity, depth: Int) {
                active(cancelled)
                check(depth <= 128) { "workspace_directory_too_deep" }
                val target = "${fdPath(parentFd)}/$name"
                check(identity(Os.lstat(target)) == expected && expected.device == entry.identities.first().device) { "workspace_entry_changed" }
                if (OsConstants.S_ISDIR(expected.mode)) {
                    open(target, true).use { held ->
                        val actual = identity(Os.fstat(held.fileDescriptor))
                        check(sameNode(actual, expected) && actual.device == entry.identities.first().device) { "workspace_entry_changed" }
                        java.nio.file.Files.newDirectoryStream(File(fdPath(held)).toPath()).use { children ->
                            for (child in children) {
                                active(cancelled)
                                remove(held, child.fileName.toString(), identity(Os.lstat(child.toString())), depth + 1)
                            }
                        }
                    }
                    active(cancelled)
                    check(sameNode(identity(Os.lstat(target)), expected)) { "workspace_entry_changed" }
                }
                active(cancelled)
                Os.remove(target)
            }
            remove(descriptor, entry.name, entry.identities.last(), 0)
        }
    }

    /** Resolve from the fixed private root, retaining the identity of every ancestor. */
    fun directoryAt(relative: String, cancelled: () -> Boolean = { false }): Entry =
        pinned(null, cancelled, relative) { _, chain -> Entry(relative, relative.substringAfterLast('/'), true, 0, chain) }

    private fun <T> pinned(entry: Entry?, cancelled: () -> Boolean, relative: String = entry?.relative.orEmpty(),
        block: (ParcelFileDescriptor, List<Identity>) -> T): T {
        active(cancelled)
        paths.ensureRoot()
        val descriptors = mutableListOf<ParcelFileDescriptor>()
        try {
            val storage = open(paths.storage.path, true).also { descriptors += it }
            var held = storage
            for (part in paths.rootParts) held = open("${fdPath(held)}/$part", true).also { descriptors += it }
            val chain = mutableListOf(identity(Os.fstat(held.fileDescriptor)))
            val parts = relative.split('/').filter { it.isNotEmpty() }
            check(relative.isEmpty() ||
                (!relative.startsWith('/') && parts.joinToString("/") == relative &&
                    parts.none { it == "." || it == ".." || '\\' in it || '\u0000' in it })) { "workspace_invalid_path" }
            if (entry != null) check(entry.identities.size == parts.size + 1 && sameNode(chain[0], entry.identities[0])) { "workspace_entry_changed" }
            for ((index, part) in parts.withIndex()) {
                active(cancelled)
                val isDirectory = index < parts.lastIndex || entry?.directory != false
                held = open("${fdPath(held)}/$part", isDirectory).also { descriptors += it }
                val id = identity(Os.fstat(held.fileDescriptor)); chain += id
                if (entry != null) {
                    val expected = entry.identities[index + 1]
                    check(if (isDirectory) sameNode(id, expected) else id == expected) { "workspace_entry_changed" }
                }
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
