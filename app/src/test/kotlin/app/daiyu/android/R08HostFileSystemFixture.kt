package app.daiyu.android

import app.daiyu.android.integration.tools.R08PrivateFileSystem
import app.daiyu.android.integration.tools.StrictR08PrivateFileSystem
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

/** Linux exercises strict POSIX operations. Windows models only mode/link metadata and directory
 * barriers outside the lease tree; bytes, atomic moves, file force and cross-JVM locks remain real.
 * Windows results are not evidence of POSIX permissions or power-loss durability. */
internal class R08HostFileSystemFixture(private val base: Path) : R08PrivateFileSystem, AutoCloseable {
    private val posix = Files.getFileStore(base).supportsFileAttributeView("posix")
    private val metadata = base.resolve("host-fs-model")
    private val channels = mutableListOf<FileChannel>()
    init { if (!posix) Files.createDirectories(metadata) }
    private fun key(path: Path, suffix: String): Path {
        val name = MessageDigest.getInstance("SHA-256").digest(path.toAbsolutePath().normalize().toString().toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return metadata.resolve("$name.$suffix")
    }
    private fun track(channel: FileChannel): FileChannel = synchronized(channels) { channels += channel; channel }
    override fun isSymbolicLink(path: Path) =
        Files.isSymbolicLink(path) || (!posix && Files.exists(key(path, "link")))
    override fun permissions(path: Path): Set<PosixFilePermission> {
        if (posix) return StrictR08PrivateFileSystem.permissions(path)
        val mode = key(path, "mode")
        return PosixFilePermissions.fromString(if (Files.exists(mode)) Files.readString(mode)
            else if (Files.isDirectory(path)) "rwx------" else "rw-------")
    }
    override fun createDirectory(path: Path, mode: Set<PosixFilePermission>) {
        if (posix) StrictR08PrivateFileSystem.createDirectory(path, mode)
        else { Files.createDirectory(path); Files.writeString(key(path, "mode"), PosixFilePermissions.toString(mode)) }
    }
    override fun openNew(path: Path, mode: Set<PosixFilePermission>): FileChannel {
        if (posix) return track(StrictR08PrivateFileSystem.openNew(path, mode))
        val channel = track(FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))
        Files.writeString(key(path, "mode"), PosixFilePermissions.toString(mode))
        return channel
    }
    override fun openExisting(path: Path, vararg options: OpenOption) =
        track(StrictR08PrivateFileSystem.openExisting(path, *options))
    override fun move(source: Path, target: Path) {
        StrictR08PrivateFileSystem.move(source, target)
        if (!posix) Files.move(key(source, "mode"), key(target, "mode"), StandardCopyOption.ATOMIC_MOVE)
    }
    override fun syncDirectory(path: Path) {
        if (posix) StrictR08PrivateFileSystem.syncDirectory(path)
        else {
            check(Files.isDirectory(path) && !isSymbolicLink(path))
            FileChannel.open(key(path, "barrier"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { it.force(true) }
        }
    }
    fun createSymbolicLink(path: Path, target: Path) {
        if (posix) Files.createSymbolicLink(path, target)
        else { Files.createDirectory(path); Files.writeString(key(path, "link"), target.toString()) }
    }
    override fun close() {
        synchronized(channels) { channels.asReversed().forEach { it.close() }; channels.clear() }
    }
}
