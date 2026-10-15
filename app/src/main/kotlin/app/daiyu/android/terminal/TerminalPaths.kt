package app.daiyu.android.terminal

import java.io.File
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes

/**
 * The host directory and the guest directory which are allowed to be shared.
 *
 * This class deliberately keeps the host side as a canonical, absolute path.  A
 * [ResolvedAttachment] is not a permission to look up the name again: it is a
 * frozen name whose root and descendant objects stay pinned by descriptors.
 * Close each attachment after use and this run-owned boundary at run teardown.
 * Authorized reads must use [ResolvedAttachment.openRead], not its display path.
 */
class TerminalPaths(
    hostRoot: File,
    guestRoot: String
) : AutoCloseable {
    constructor(hostRoot: Path, guestRoot: String) : this(hostRoot.toFile(), guestRoot)
    constructor(hostRoot: String, guestRoot: String) : this(File(hostRoot), guestRoot)

    private val hostAnchor: Path
    private val canonicalHostRoot: Path
    private val hostRootIdentity: NodeIdentity
    private val guestRootPath: Path
    private val rootDescriptor: android.os.ParcelFileDescriptor
    private val lifecycle = Any()
    private val attachments = mutableSetOf<ResolvedAttachment>()
    private var closed = false
    internal var afterDescriptorOpened: ((Int) -> Unit)? = null

    /** The normalized, absolute host root. */
    val hostRoot: File
        get() = canonicalHostRoot.toFile()

    /** The normalized, absolute guest root. */
    val guestRoot: String
        get() = guestRootPath.toString()

    init {
        hostAnchor = absoluteNormalizedPath(hostRoot.toPath(), "host root")
        requireNoNul(hostAnchor.toString(), "host root")

        val realRoot = try {
            hostAnchor.toRealPath()
        } catch (error: Exception) {
            throw IllegalArgumentException("host root must be an existing directory", error)
        }
        if (!Files.isDirectory(realRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw IllegalArgumentException("host root must be a directory")
        }
        canonicalHostRoot = realRoot.normalize()
        guestRootPath = parseAbsoluteGuestPath(guestRoot, "guest root")
        val descriptor = openDescriptor(canonicalHostRoot.toString(), true)
        try {
            hostRootIdentity = descriptorIdentity(descriptor)
            if (hostRootIdentity.fileKey == null ||
                !readIdentity(canonicalHostRoot).sameIdentity(hostRootIdentity) ||
                hostAnchor.toRealPath().normalize() != canonicalHostRoot) {
                throw SecurityException(PATH_CHANGED_NOT_AUTHORIZED)
            }
        } catch (failure: Throwable) {
            try { descriptor.close() } catch (close: Exception) { failure.addSuppressed(close) }
            throw failure
        }
        rootDescriptor = descriptor
    }

    /** Resolve and pin every object; the caller owns the returned closeable attachment. */
    fun resolveAttachment(guestPath: String): ResolvedAttachment = synchronized(lifecycle) {
        resolvePinned(guestPath)
    }

    private fun resolvePinned(guestPath: String): ResolvedAttachment {
        if (closed) throw SecurityException(PATH_CHANGED_NOT_AUTHORIZED)
        val requested = parseAbsoluteGuestPath(guestPath, "guest path")
        if (!requested.startsWith(guestRootPath) || !rootIsUnchanged()) {
            throw SecurityException(PATH_CHANGED_NOT_AUTHORIZED)
        }
        val relative = guestRootPath.relativize(requested)
        val parts = relative.map { it.toString() }
        if (parts.isEmpty() || parts.singleOrNull() == "") {
            throw IllegalArgumentException("attachment must be an existing regular file")
        }
        val hostPath = canonicalHostRoot.resolve(relative).normalize()
        val pins = mutableListOf<android.os.ParcelFileDescriptor>()
        try {
            val nodes = mutableListOf<NodeIdentity>()
            var parent = rootDescriptor
            for ((index, part) in parts.withIndex()) {
                val next = openDescriptor("/proc/self/fd/${parent.fd}/$part", index < parts.lastIndex)
                pins += next
                afterDescriptorOpened?.invoke(index)
                val identity = descriptorIdentity(next)
                if (identity.fileKey == null) throw SecurityException(PATH_CHANGED_NOT_AUTHORIZED)
                nodes += identity
                parent = next
            }
            val snapshot = AttachmentSnapshot(hostPath.toString(), requested.toString(), hostRootIdentity, nodes)
            val attachment = ResolvedAttachment(this, hostPath.toFile(), snapshot, pins.toList())
            if (!validatePinned(attachment).authorized) throw SecurityException(PATH_CHANGED_NOT_AUTHORIZED)
            attachments += attachment
            return attachment
        } catch (failure: Throwable) {
            try { closeDescriptors(pins) } catch (close: Exception) { failure.addSuppressed(close) }
            if (failure is Exception && failure !is SecurityException) {
                throw SecurityException(PATH_CHANGED_NOT_AUTHORIZED, failure)
            }
            throw failure
        }
    }

    /** Check that a previously resolved attachment still refers to the same path. */
    fun validate(attachment: ResolvedAttachment): PathValidation = synchronized(lifecycle) {
        validatePinned(attachment)
    }

    private fun validatePinned(attachment: ResolvedAttachment): PathValidation {
        if (closed || attachment.closed || attachment.owner !== this) return PathValidation.pathChangedNotAuthorized()
        if (!rootIsUnchanged()) return PathValidation.pathChangedNotAuthorized()

        val path = Paths.get(attachment.snapshot.hostPath)
        val currentNodes = try {
            captureDescendants(path)
        } catch (_: Exception) {
            return PathValidation.pathChangedNotAuthorized()
        }
        if (currentNodes.size != attachment.snapshot.nodes.size) {
            return PathValidation.pathChangedNotAuthorized()
        }
        if (currentNodes.indices.any { !currentNodes[it].sameIdentity(attachment.snapshot.nodes[it]) }) {
            return PathValidation.pathChangedNotAuthorized()
        }
        if (currentNodes.lastOrNull()?.regularFile != true) {
            return PathValidation.pathChangedNotAuthorized()
        }
        return PathValidation.authorized()
    }

    /** Opens and verifies each descriptor, then reads the very same leaf object. */
    fun openRead(attachment: ResolvedAttachment): java.io.InputStream = synchronized(lifecycle) {
        openPinned(attachment)
    }

    private fun openPinned(attachment: ResolvedAttachment): java.io.InputStream {
        if (closed || attachment.closed || attachment.owner !== this || !rootIsUnchanged()) {
            throw SecurityException(PATH_CHANGED_NOT_AUTHORIZED)
        }
        val parts = canonicalHostRoot.relativize(Paths.get(attachment.snapshot.hostPath)).map { it.toString() }
        if (parts.size != attachment.snapshot.nodes.size || parts.isEmpty() ||
            parts.any { it.isEmpty() || it == "." || it == ".." || '/' in it || '\u0000' in it }) {
            throw SecurityException(PATH_CHANGED_NOT_AUTHORIZED)
        }
        var held: android.os.ParcelFileDescriptor? = null
        try {
            held = openDescriptor(canonicalHostRoot.toString(), true)
            afterDescriptorOpened?.invoke(-1)
            requireDescriptor(held, hostRootIdentity)
            for ((index, part) in parts.withIndex()) {
                val parent = held!!
                val next = openDescriptor("/proc/self/fd/${parent.fd}/$part", index < parts.lastIndex)
                try {
                    afterDescriptorOpened?.invoke(index)
                    requireDescriptor(next, attachment.snapshot.nodes[index])
                } catch (error: Throwable) { next.close(); throw error }
                held = next
                parent.close()
            }
            val leaf = held!!
            val input = android.os.ParcelFileDescriptor.AutoCloseInputStream(leaf)
            held = null // Ownership passes only after stream construction succeeds.
            return input
        } catch (error: Exception) {
            throw SecurityException(PATH_CHANGED_NOT_AUTHORIZED, error)
        } finally { held?.close() }
    }

    private fun openDescriptor(path: String, directory: Boolean): android.os.ParcelFileDescriptor {
        val flags = android.system.OsConstants.O_RDONLY or LINUX_O_CLOEXEC or
            android.system.OsConstants.O_NOFOLLOW or android.system.OsConstants.O_NONBLOCK
        val raw = android.system.Os.open(path, flags, 0)
        var adopted: android.os.ParcelFileDescriptor? = null
        try {
            val stat = android.system.Os.fstat(raw)
            if (if (directory) !android.system.OsConstants.S_ISDIR(stat.st_mode) else !android.system.OsConstants.S_ISREG(stat.st_mode)) {
                throw SecurityException(PATH_CHANGED_NOT_AUTHORIZED)
            }
            val duplicate = if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.system.Os.fcntlInt(raw, LINUX_F_DUPFD_CLOEXEC, 0)
            } else {
                android.system.Os::class.java.getMethod("fcntlInt", java.io.FileDescriptor::class.java,
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    .invoke(null, raw, LINUX_F_DUPFD_CLOEXEC, 0) as Int
            }
            return android.os.ParcelFileDescriptor.adoptFd(duplicate).also { adopted = it }
        } finally {
            try { android.system.Os.close(raw) } catch (failure: Exception) {
                try { adopted?.close() } catch (close: Exception) { failure.addSuppressed(close) }
                throw failure
            }
        }
    }

    private fun descriptorIdentity(fd: android.os.ParcelFileDescriptor): NodeIdentity = NodeIdentity.from(
        Files.readAttributes(Paths.get("/proc/self/fd/${fd.fd}"), BasicFileAttributes::class.java)
    )

    private fun requireDescriptor(fd: android.os.ParcelFileDescriptor, expected: NodeIdentity) {
        val current = descriptorIdentity(fd)
        if (expected.fileKey == null || current.fileKey == null || !current.sameIdentity(expected)) {
            throw SecurityException(PATH_CHANGED_NOT_AUTHORIZED)
        }
    }

    /** Alias useful at call sites which express the check as a revalidation. */
    fun revalidate(attachment: ResolvedAttachment): PathValidation = validate(attachment)

    fun isAuthorized(attachment: ResolvedAttachment): Boolean = validate(attachment).authorized

    /** Throw a stable, machine-readable reason when the snapshot is stale. */
    fun requireAuthorized(attachment: ResolvedAttachment) {
        val result = validate(attachment)
        if (!result.authorized) throw SecurityException(result.reason ?: PATH_CHANGED_NOT_AUTHORIZED)
    }

    private fun release(attachment: ResolvedAttachment): Unit = synchronized(lifecycle) {
        if (attachment.closed) return@synchronized
        attachment.closed = true
        attachments.remove(attachment)
        closeDescriptors(attachment.pins)
    }

    override fun close(): Unit = synchronized(lifecycle) {
        if (closed) return@synchronized
        closed = true
        val descriptors = mutableListOf(rootDescriptor)
        attachments.forEach { attachment ->
            attachment.closed = true
            descriptors += attachment.pins
        }
        attachments.clear()
        closeDescriptors(descriptors)
    }

    private fun closeDescriptors(descriptors: List<android.os.ParcelFileDescriptor>) {
        var failure: Exception? = null
        for (descriptor in descriptors.asReversed()) {
            try { descriptor.close() } catch (close: Exception) {
                if (failure == null) failure = close else failure.addSuppressed(close)
            }
        }
        failure?.let { throw it }
    }

    class ResolvedAttachment internal constructor(
        internal val owner: TerminalPaths,
        file: File,
        val snapshot: AttachmentSnapshot,
        internal val pins: List<android.os.ParcelFileDescriptor>
    ) : AutoCloseable {
        internal var closed = false
        /** Display/legacy path only. Use openRead() for authorized reads, never reopen this File. */
        val file: File = File(file.path)
        val frozenFile: File
            get() = file
        val path: File
            get() = file

        fun revalidate(): PathValidation = owner.validate(this)
        fun openRead(): java.io.InputStream = owner.openRead(this)
        /** Releases identity pins. Streams already admitted retain their own descriptors. */
        override fun close() = owner.release(this)
    }

    class AttachmentSnapshot internal constructor(
        val hostPath: String,
        val guestPath: String,
        internal val rootIdentity: NodeIdentity,
        internal val nodes: List<NodeIdentity>
    )

    data class PathValidation(
        val authorized: Boolean,
        val reason: String? = null
    ) {
        val isAuthorized: Boolean
            get() = authorized

        companion object {
            internal fun authorized() = PathValidation(true)
            internal fun pathChangedNotAuthorized() = PathValidation(false, PATH_CHANGED_NOT_AUTHORIZED)
        }
    }

    private fun readIdentity(path: Path): NodeIdentity {
        val attributes = Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS
        )
        if (attributes.isSymbolicLink) throw SecurityException(PATH_CHANGED_NOT_AUTHORIZED)
        return NodeIdentity.from(attributes)
    }

    private fun rootIsUnchanged(): Boolean {
        return try {
            if (!Files.isDirectory(canonicalHostRoot, LinkOption.NOFOLLOW_LINKS)) return false
            if (hostAnchor.toRealPath().normalize() != canonicalHostRoot) return false
            readIdentity(canonicalHostRoot).sameIdentity(hostRootIdentity)
        } catch (_: Exception) {
            false
        }
    }

    /** Capture every path component below the canonical root, refusing links. */
    private fun captureDescendants(path: Path): List<NodeIdentity> {
        val normalized = path.toAbsolutePath().normalize()
        if (!normalized.startsWith(canonicalHostRoot)) throw SecurityException(PATH_CHANGED_NOT_AUTHORIZED)
        val relative = canonicalHostRoot.relativize(normalized)
        val result = ArrayList<NodeIdentity>(relative.nameCount)
        var current = canonicalHostRoot
        for (part in relative) {
            current = current.resolve(part)
            val attributes = Files.readAttributes(
                current,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS
            )
            if (attributes.isSymbolicLink) throw SecurityException(PATH_CHANGED_NOT_AUTHORIZED)
            result += NodeIdentity.from(attributes)
        }
        return result
    }

    internal data class NodeIdentity(
        val fileKey: String?,
        val directory: Boolean,
        val regularFile: Boolean
    ) {
        fun sameIdentity(other: NodeIdentity): Boolean {
            if (directory != other.directory || regularFile != other.regularFile) return false
            return fileKey != null && other.fileKey != null && fileKey == other.fileKey
        }

        companion object {
            fun from(attributes: BasicFileAttributes) = NodeIdentity(
                fileKey = attributes.fileKey()?.toString(),
                directory = attributes.isDirectory,
                regularFile = attributes.isRegularFile
            )
        }
    }

    companion object {
        // Android bionic UAPI values, including API26 where the SDK omits these fields.
        private const val LINUX_O_CLOEXEC = 0x80000
        private const val LINUX_F_DUPFD_CLOEXEC = 1030
        const val PATH_CHANGED_NOT_AUTHORIZED = "path_changed_not_authorized"

        @JvmStatic
        fun create(hostRoot: File, guestRoot: String): TerminalPaths = TerminalPaths(hostRoot, guestRoot)

        @JvmStatic
        fun of(hostRoot: File, guestRoot: String): TerminalPaths = TerminalPaths(hostRoot, guestRoot)

        private fun absoluteNormalizedPath(path: Path, name: String): Path {
            requireNoNul(path.toString(), name)
            return try {
                path.toAbsolutePath().normalize()
            } catch (error: InvalidPathException) {
                throw IllegalArgumentException("$name is not a valid path", error)
            }
        }

        private fun parseAbsoluteGuestPath(value: String, name: String): Path {
            requireNoNul(value, name)
            val parsed = try {
                Paths.get(value)
            } catch (error: InvalidPathException) {
                throw IllegalArgumentException("$name is not a valid path", error)
            }
            if (!parsed.isAbsolute) throw IllegalArgumentException("$name must be absolute")
            for (part in parsed) {
                if (part.toString() == "..") {
                    throw IllegalArgumentException("$name must not contain ..")
                }
            }
            return parsed.normalize()
        }

        private fun requireNoNul(value: String, name: String) {
            if (value.indexOf('\u0000') >= 0) throw IllegalArgumentException("$name must not contain NUL")
        }
    }
}
