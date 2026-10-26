package app.daiyu.android.terminal

import java.io.File
import java.nio.file.Files

/** One conservative usage/export domain for all approved roots, including workspace stores. */
internal class LinuxRootfsBackupAccess private constructor(private val files: File) {
    private val directory = File(files, "linux-rootfs-access-v1").also { TerminalDurability.ensureDirectory(it) }
    private val pins = LinuxRunnerGenerationPins(directory, directorySync = TerminalDurability::syncDirectory)
    private var exporting = false

    @Synchronized fun acquire(installation: LinuxInstallation, identities: List<String>, export: Boolean = false): AutoCloseable {
        val pin = pins.claim(LinuxGenerationRef(installation.environmentId, installation.generation), identities,
            requireEmpty = export) {
            check(!exporting) { "linux_rootfs_in_use" }
            if (export) {
                requireNoLegacyPins()
                // A failed durable claim deliberately stays blocked until its owner can settle it.
                exporting = true
            }
        }
        return object : AutoCloseable {
            private var closed = false
            override fun close() = synchronized(this@LinuxRootfsBackupAccess) {
                if (!closed) {
                    pin.settleAfterTermination()
                    if (export) exporting = false
                    closed = true
                }
            }
        }
    }

    private fun requireNoLegacyPins() {
        fun children(directory: File, allowMissing: Boolean = false): List<File> {
            val path = directory.toPath()
            val before = try {
                Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java,
                    java.nio.file.LinkOption.NOFOLLOW_LINKS)
            } catch (failure: java.nio.file.NoSuchFileException) {
                if (allowMissing) return emptyList()
                throw failure
            }
            check(before.isDirectory && !before.isSymbolicLink && before.fileKey() != null) { "linux_rootfs_unknown" }
            val entries = checkNotNull(directory.listFiles()) { "linux_rootfs_unknown" }.toList()
            check(entries.size <= 4096) { "linux_rootfs_unknown" }
            val after = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java,
                java.nio.file.LinkOption.NOFOLLOW_LINKS)
            check(after.isDirectory && !after.isSymbolicLink && after.fileKey() == before.fileKey()) { "linux_rootfs_unknown" }
            return entries
        }
        fun inspect(directory: File) {
            check(children(directory).none { it.name.startsWith(".runner-pin-") }) { "linux_rootfs_unknown" }
        }
        val terminal = File(files, "user-terminal")
        children(terminal)
        inspect(File(terminal, "linux-selection"))
        children(File(terminal, "workspace-generations"), allowMissing = true).forEach(::inspect)
    }

    companion object {
        private val instances = mutableMapOf<String, LinuxRootfsBackupAccess>()
        @Synchronized fun forFiles(files: File): LinuxRootfsBackupAccess {
            val canonical = files.canonicalFile
            return instances.getOrPut(canonical.path) { LinuxRootfsBackupAccess(canonical) }
        }
    }
}
