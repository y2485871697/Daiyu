package app.daiyu.android.terminal.install

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/** Only non-POSIX mode/link metadata is modeled. Archive bytes and hard links stay real. */
internal class HostTarRootfsFileOps(base: Path) : TarRootfsFileOps {
    private val posix = (Files.getFileAttributeView(base, java.nio.file.attribute.PosixFileAttributeView::class.java, NOFOLLOW_LINKS) != null)
    init { check(posix || java.io.File.separatorChar == '\\') }
    private val modes = mutableMapOf<Path, Set<PosixFilePermission>>()
    private val links = mutableMapOf<Path, Path>()
    private fun key(path: Path) = path.toAbsolutePath().normalize()
    override fun isSymbolicLink(path: Path) = Files.isSymbolicLink(path) || links.containsKey(key(path))
    override fun isRegularFile(path: Path) = !isSymbolicLink(path) && Files.isRegularFile(path, NOFOLLOW_LINKS)
    override fun createPrivateDirectory(path: Path) {
        if (posix) NioTarRootfsFileOps.createPrivateDirectory(path)
        else { Files.createDirectory(path); modes[key(path)] = PosixFilePermissions.fromString("rwx------") }
    }
    override fun setPermissions(path: Path, mode: Set<PosixFilePermission>) {
        if (posix) NioTarRootfsFileOps.setPermissions(path, mode)
        else { check(Files.exists(path, NOFOLLOW_LINKS) && !isSymbolicLink(path)); modes[key(path)] = mode.toSet() }
    }
    override fun createSymbolicLink(path: Path, target: Path) {
        if (posix) NioTarRootfsFileOps.createSymbolicLink(path, target)
        else { Files.createFile(path); links[key(path)] = target }
    }
    fun permissions(path: Path): Set<PosixFilePermission> =
        if (posix) Files.getPosixFilePermissions(path, NOFOLLOW_LINKS) else checkNotNull(modes[key(path)])
    fun mode(path: Path): Int {
        if (posix) return (Files.getAttribute(path, "unix:mode", NOFOLLOW_LINKS) as Int) and 4095
        val bits = arrayOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE,
            PosixFilePermission.OTHERS_EXECUTE)
        return bits.withIndex().sumOf { (i, bit) -> if (bit in permissions(path)) 1 shl (8 - i) else 0 }
    }
    fun readSymbolicLink(path: Path): Path = if (posix) Files.readSymbolicLink(path) else checkNotNull(links[key(path)])
    fun delete(path: Path) { Files.delete(path); links.remove(key(path)); modes.remove(key(path)) }
    fun readText(path: Path): String {
        if (posix) return Files.readString(path)
        var current = key(path)
        repeat(64) {
            var prefix = checkNotNull(current.root)
            var replaced = false
            for (part in current) {
                prefix = prefix.resolve(part)
                val target = links[key(prefix)] ?: continue
                current = prefix.parent.resolve(target).resolve(prefix.relativize(current)).normalize()
                replaced = true
                break
            }
            if (!replaced) return Files.readString(current)
        }
        error("host_link_cycle")
    }
}
