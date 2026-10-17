package app.daiyu.android.terminal.install

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.tukaani.xz.XZInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.zip.GZIPInputStream

/**
 * Streaming, files-only extractor for a verified archive in an empty app-private staging root.
 * This independently implemented adapter follows the authorized backend's extraction contract;
 * it does not download, run a shell, publish files, or trust archive-supplied ownership.
 * On failure the caller owns removal of staging, without following links.
 */
class TarLinuxRootfsExtraction internal constructor(
    private val maxExpandedBytes: Long = MAX_EXPANDED_BYTES,
    private val maxEntries: Int = MAX_ENTRIES,
    private val ops: TarRootfsFileOps = NioTarRootfsFileOps
) : LinuxRootfsExtraction {
    init {
        require(maxExpandedBytes in 1..MAX_EXPANDED_BYTES)
        require(maxEntries in 1..MAX_ENTRIES)
    }

    override fun extract(artifact: LinuxRootfsArtifact, verifiedArchive: File, stagingRoot: File,
                         cancellation: LinuxInstallCancellation) {
        cancellation.check()
        val root = stagingRoot.toPath()
        require(root.isAbsolute && root == root.normalize()) { "linux_extract_staging_path" }
        var ancestor: Path? = root
        while (ancestor != null) {
            check(!ops.isSymbolicLink(ancestor)) { "linux_extract_staging_symlink" }
            ancestor = ancestor.parent
        }
        check(Files.isDirectory(root, NOFOLLOW_LINKS)) { "linux_extract_staging_directory" }
        Files.newDirectoryStream(root).use { check(!it.iterator().hasNext()) { "linux_extract_staging_not_empty" } }
        check(ops.isRegularFile(verifiedArchive.toPath())) { "linux_extract_archive_file" }
        ops.setPermissions(root, PosixFilePermissions.fromString("rwx------"))
        val names = HashSet<String>()
        val declared = HashMap<Path, Boolean>() // true = deferred link; ancestors may never be links
        val links = ArrayList<DeferredLink>()
        val directoryModes = LinkedHashMap<Path, Int>()
        var entries = 0
        var declaredBytes = 0L
        verifiedArchive.inputStream().use { raw ->
            cancellation.register { raw.close() }.use {
                cancellation.check()
                val decoded: InputStream = when (artifact.archive) {
                    RootfsArchive.TAR_GZIP -> GZIPInputStream(raw, BUFFER_SIZE)
                    RootfsArchive.TAR_XZ -> XZInputStream(raw, XZ_MEMORY_LIMIT_KIB)
                }
                BoundedInput(decoded, maxExpandedBytes, cancellation).use { expanded ->
                    val guarded = TarRecordGuard(expanded, maxEntries, cancellation)
                    TarArchiveInputStream(guarded).use { tar ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            cancellation.check()
                            val entry = tar.nextTarEntry ?: break
                            check(++entries <= maxEntries) { "linux_extract_entry_limit" }
                            check(entry.isCheckSumOK && tar.canReadEntryData(entry) && !entry.isSparse) {
                                "linux_extract_unsupported_tar"
                            }
                            check(entry.size >= 0 && entry.size <= maxExpandedBytes - declaredBytes) {
                                "linux_extract_size_limit"
                            }
                            declaredBytes += entry.size
                            // Reject, rather than create or silently reinterpret, devices, FIFO, etc.
                            // isFile() may classify unknown legacy flags by filename. Only
                            // admit the five actual tar types we implement, never devices/FIFO.
                            check(entry.linkFlag.toInt() in ALLOWED_TYPES) { "linux_extract_special_entry" }
                            if (!entry.isFile || entry.isSymbolicLink || entry.isLink) {
                                check(entry.size == 0L) { "linux_extract_nonfile_payload" }
                            }
                            val parts = archiveParts(entry.name)
                            check(names.add(parts.joinToString("/"))) { "linux_extract_duplicate_path" }
                            if (parts.size <= artifact.stripComponents) {
                                check(entry.isDirectory) { "linux_extract_stripped_file" }
                                continue
                            }
                            val relative = parts.drop(artifact.stripComponents).joinToString("/")
                            check(relative.substringBefore('/') != RESERVED_MARKER) { "linux_extract_reserved_marker" }
                            val destination = root.resolve(relative)
                            check(destination.startsWith(root) && destination != root)
                            check(declared.put(destination, entry.isSymbolicLink || entry.isLink) == null) {
                                "linux_extract_duplicate_path"
                            }
                            var parent = destination.parent
                            while (parent != root) {
                                cancellation.check()
                                check(declared[parent] != true) { "linux_extract_link_ancestor" }
                                parent = parent.parent
                            }
                            ensureParents(root, destination.parent, cancellation)
                            when {
                                entry.isSymbolicLink -> {
                                    check(!Files.exists(destination, NOFOLLOW_LINKS)) { "linux_extract_path_exists" }
                                    val target = symbolicTarget(root, destination, entry.linkName)
                                    links.add(DeferredLink(destination, target, symbolic = true))
                                }
                                entry.isLink -> {
                                    check(!Files.exists(destination, NOFOLLOW_LINKS)) { "linux_extract_path_exists" }
                                    val targetParts = archiveParts(entry.linkName)
                                    check(targetParts.size > artifact.stripComponents) { "linux_extract_hardlink_target" }
                                    val target = root.resolve(targetParts.drop(artifact.stripComponents).joinToString("/"))
                                    links.add(DeferredLink(destination, target, symbolic = false))
                                }
                                entry.isDirectory -> {
                                    if (!Files.exists(destination, NOFOLLOW_LINKS)) {
                                        ops.createPrivateDirectory(destination)
                                    }
                                    check(Files.isDirectory(destination, NOFOLLOW_LINKS)) { "linux_extract_directory_collision" }
                                    directoryModes[destination] = entry.mode
                                }
                                else -> {
                                    Files.newOutputStream(destination, CREATE_NEW, WRITE).use { output ->
                                        var remaining = entry.size
                                        while (remaining > 0) {
                                            cancellation.check()
                                            val n = tar.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                            check(n > 0) { "linux_extract_truncated_file" }
                                            output.write(buffer, 0, n)
                                            remaining -= n
                                        }
                                    }
                                    setMode(destination, entry.mode)
                                }
                            }
                        }
                        // Tar EOF does not imply compressed EOF: consume footer/trailer to validate
                        // gzip CRC / XZ checksum and apply quotas/cancellation to trailing data too.
                        while (true) {
                            cancellation.check()
                            if (guarded.read(buffer) < 0) break
                        }
                        guarded.finish()
                    }
                }
            }
        }
        // All ordinary paths exist before any link. Resolve forward hardlink chains by progress;
        // symlink targets and symlink ancestors are never accepted as hardlink sources.
        val hardLinks = links.filterNot { it.symbolic }.associateBy { it.path }
        for (first in hardLinks.values) {
            cancellation.check()
            if (Files.exists(first.path, NOFOLLOW_LINKS)) continue
            val chain = ArrayList<DeferredLink>()
            val visiting = HashSet<Path>()
            var link = first
            while (true) {
                cancellation.check()
                check(visiting.add(link.path)) { "linux_extract_hardlink_cycle_or_missing" }
                chain.add(link)
                checkAncestors(root, link.target, cancellation)
                if (Files.exists(link.target, NOFOLLOW_LINKS)) {
                    check(ops.isRegularFile(link.target)) { "linux_extract_hardlink_not_regular" }
                    break
                }
                link = hardLinks[link.target] ?: error("linux_extract_hardlink_cycle_or_missing")
            }
            // Iterative resolution avoids recursion and quadratic rescanning of long chains.
            for (resolved in chain.asReversed()) {
                cancellation.check()
                ensureParents(root, resolved.path.parent, cancellation)
                Files.createLink(resolved.path, resolved.target)
            }
        }
        for (link in links) {
            cancellation.check()
            if (!link.symbolic) continue
            ensureParents(root, link.path.parent, cancellation)
            // Store relative guest links, including guest-absolute links. Never store /host paths.
            val relative = link.path.parent.relativize(link.target)
            ops.createSymbolicLink(link.path,
                if (relative.toString().isEmpty()) root.fileSystem.getPath(".") else relative)
        }
        for ((directory, mode) in directoryModes.entries.sortedByDescending { it.key.nameCount }) {
            cancellation.check()
            setMode(directory, mode)
        }
        cancellation.check()
    }

    private fun archiveParts(name: String): List<String> {
        require(name.isNotEmpty() && name.length <= MAX_PATH_CHARS && '\u0000' !in name && '\\' !in name) {
            "linux_extract_invalid_name"
        }
        require(!name.startsWith('/') && !Regex("^[A-Za-z]:").containsMatchIn(name)) {
            "linux_extract_absolute_name"
        }
        val parts = name.split('/')
        require(parts.none { it == ".." }) { "linux_extract_parent_segment" }
        return parts.filter { it.isNotEmpty() && it != "." }.also {
            require(it.size <= MAX_DEPTH) { "linux_extract_depth_limit" }
        }
    }

    private fun symbolicTarget(root: Path, link: Path, name: String): Path {
        require(name.isNotEmpty() && name.length <= MAX_PATH_CHARS && '\u0000' !in name && '\\' !in name)
        require(!Regex("^[A-Za-z]:").containsMatchIn(name))
        val target = if (name.startsWith('/')) root.resolve(name.trimStart('/')) else link.parent.resolve(name)
        return target.normalize().also {
            require(it.startsWith(root)) { "linux_extract_symlink_escape" }
            require(root.relativize(it).nameCount <= MAX_DEPTH) { "linux_extract_depth_limit" }
        }
    }

    private fun ensureParents(root: Path, directory: Path, cancellation: LinuxInstallCancellation) {
        check(directory.startsWith(root))
        var current = root
        for (part in root.relativize(directory)) {
            cancellation.check()
            current = current.resolve(part)
            if (!Files.exists(current, NOFOLLOW_LINKS)) ops.createPrivateDirectory(current)
            check(Files.isDirectory(current, NOFOLLOW_LINKS) && !ops.isSymbolicLink(current)) {
                "linux_extract_link_ancestor"
            }
        }
    }

    private fun checkAncestors(root: Path, target: Path, cancellation: LinuxInstallCancellation) {
        check(target.startsWith(root))
        var current = root
        for (part in root.relativize(target)) {
            cancellation.check()
            current = current.resolve(part)
            check(!ops.isSymbolicLink(current)) { "linux_extract_hardlink_symlink" }
        }
    }

    private fun setMode(path: Path, mode: Int) {
        // Deliberately discard uid/gid, setuid, setgid, sticky bits and archive metadata/xattrs.
        val bits = arrayOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE,
            PosixFilePermission.OTHERS_EXECUTE)
        ops.setPermissions(path, bits.filterIndexed { index, _ -> mode and (1 shl (8 - index)) != 0 }.toSet())
    }

    private data class DeferredLink(val path: Path, val target: Path, val symbolic: Boolean)

    /**
     * Inspect physical records as well as Commons' logical entries: PAX/GNU metadata must not
     * bypass the entry quota or allocate unbounded strings. Require a complete tar terminator,
     * reject hidden NUL suffixes in fixed path fields, and never silently accept a second tar.
     */
    private class TarRecordGuard(input: InputStream, private val limit: Int,
                                 private val cancellation: LinuxInstallCancellation) : FilterInputStream(input) {
        private val header = ByteArray(512)
        private var filled = 0
        private var payloadRemaining = 0L
        private var records = 0
        private var zeroRecords = 0

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255
        }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            cancellation.check()
            val n = `in`.read(bytes, offset, length)
            var cursor = offset
            val end = offset + maxOf(n, 0)
            while (cursor < end) {
                cancellation.check()
                if (payloadRemaining > 0) {
                    val taken = minOf(payloadRemaining, (end - cursor).toLong()).toInt()
                    payloadRemaining -= taken
                    cursor += taken
                } else {
                    val taken = minOf(512 - filled, end - cursor)
                    System.arraycopy(bytes, cursor, header, filled, taken)
                    filled += taken
                    cursor += taken
                    if (filled == 512) {
                        inspectHeader()
                        filled = 0
                    }
                }
            }
            return n
        }

        private fun inspectHeader() {
            if (header.all { it == 0.toByte() }) {
                zeroRecords++
                return
            }
            check(zeroRecords == 0) { "linux_extract_trailing_tar_data" }
            check(++records <= limit) { "linux_extract_entry_limit" }
            checkNullPadding(0, 100)
            checkNullPadding(157, 100)
            // POSIX ustar prefix; GNU's old header uses this area for other fields.
            if (header[257] == 'u'.code.toByte() && header[262] == 0.toByte()) checkNullPadding(345, 155)
            val entry = TarArchiveEntry(header)
            check(entry.isCheckSumOK && entry.size >= 0 && entry.size <= MAX_EXPANDED_BYTES) {
                "linux_extract_invalid_header"
            }
            val type = header[156].toInt().toChar()
            if (type in charArrayOf('x', 'g', 'L', 'K')) {
                check(entry.size <= 1024 * 1024) { "linux_extract_metadata_limit" }
            }
            payloadRemaining = ((entry.size + 511) / 512) * 512
        }

        private fun checkNullPadding(start: Int, length: Int) {
            var nul = false
            for (index in start until start + length) {
                if (header[index] == 0.toByte()) nul = true
                else check(!nul) { "linux_extract_embedded_nul" }
            }
        }

        fun finish() {
            check(filled == 0 && payloadRemaining == 0L && zeroRecords >= 2) { "linux_extract_truncated_tar" }
        }

        // Do not delegate skip: that would bypass physical-record validation.
        override fun skip(n: Long): Long {
            if (n <= 0) return 0
            val buffer = ByteArray(minOf(n, BUFFER_SIZE.toLong()).toInt())
            var skipped = 0L
            while (skipped < n) {
                cancellation.check()
                val read = read(buffer, 0, minOf(buffer.size.toLong(), n - skipped).toInt())
                if (read < 0) break
                skipped += read
            }
            return skipped
        }
        override fun markSupported() = false
    }

    private class BoundedInput(input: InputStream, private val limit: Long,
                               private val cancellation: LinuxInstallCancellation) : FilterInputStream(input) {
        private var count = 0L
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255
        }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            cancellation.check()
            // A one-byte lookahead detects overflow without allocating/decoding a large surplus.
            val allowed = minOf(length.toLong(), limit - count + 1).toInt()
            val n = try { `in`.read(bytes, offset, allowed) } catch (failure: IOException) {
                cancellation.check()
                throw failure
            }
            if (n > 0) {
                check(n.toLong() <= limit - count) { "linux_extract_size_limit" }
                count += n
            }
            cancellation.check()
            return n
        }
        override fun skip(n: Long): Long {
            if (n <= 0) return 0
            val buffer = ByteArray(minOf(n, BUFFER_SIZE.toLong()).toInt())
            var skipped = 0L
            while (skipped < n) {
                cancellation.check()
                val read = read(buffer, 0, minOf(buffer.size.toLong(), n - skipped).toInt())
                if (read < 0) break
                skipped += read
            }
            return skipped
        }
        override fun markSupported() = false
    }

    companion object {
        const val MAX_EXPANDED_BYTES: Long = 3L * 1024 * 1024 * 1024
        const val MAX_ENTRIES: Int = 200_000
        const val XZ_MEMORY_LIMIT_KIB: Int = 128 * 1024
        private const val BUFFER_SIZE = 64 * 1024
        private const val MAX_PATH_CHARS = 4096
        private const val MAX_DEPTH = 128
        private const val RESERVED_MARKER = ".daiyu-rootfs-install"
        private val ALLOWED_TYPES = setOf(0, '0'.code, '1'.code, '2'.code, '5'.code)
    }
}
