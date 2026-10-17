package app.daiyu.android.integration.tools

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.*
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/** Private-storage primitives. Production always uses the strict POSIX implementation. */
internal interface R08PrivateFileSystem {
    fun isSymbolicLink(path: Path): Boolean
    fun permissions(path: Path): Set<PosixFilePermission>
    fun createDirectory(path: Path, mode: Set<PosixFilePermission>)
    fun openNew(path: Path, mode: Set<PosixFilePermission>): FileChannel
    fun openExisting(path: Path, vararg options: OpenOption): FileChannel
    fun move(source: Path, target: Path)
    fun syncDirectory(path: Path)
}

internal object StrictR08PrivateFileSystem : R08PrivateFileSystem {
    override fun isSymbolicLink(path: Path) = Files.isSymbolicLink(path)
    override fun permissions(path: Path): Set<PosixFilePermission> = Files.getPosixFilePermissions(path, NOFOLLOW_LINKS)
    override fun createDirectory(path: Path, mode: Set<PosixFilePermission>) {
        Files.createDirectory(path, PosixFilePermissions.asFileAttribute(mode))
    }
    override fun openNew(path: Path, mode: Set<PosixFilePermission>): FileChannel =
        FileChannel.open(path, setOf(CREATE_NEW, WRITE), PosixFilePermissions.asFileAttribute(mode))
    override fun openExisting(path: Path, vararg options: OpenOption): FileChannel = FileChannel.open(path, *options)
    override fun move(source: Path, target: Path) { Files.move(source, target, ATOMIC_MOVE) }
    override fun syncDirectory(path: Path) {
        FileChannel.open(path, READ, NOFOLLOW_LINKS).use { it.force(true) }
    }
}
