package app.daiyu.android.terminal.install

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

internal interface TarRootfsFileOps {
    fun isSymbolicLink(path: Path): Boolean
    fun isRegularFile(path: Path): Boolean
    fun createPrivateDirectory(path: Path)
    fun setPermissions(path: Path, mode: Set<PosixFilePermission>)
    fun createSymbolicLink(path: Path, target: Path)
}

internal object NioTarRootfsFileOps : TarRootfsFileOps {
    override fun isSymbolicLink(path: Path) = Files.isSymbolicLink(path)
    override fun isRegularFile(path: Path) = Files.isRegularFile(path, NOFOLLOW_LINKS)
    override fun createPrivateDirectory(path: Path) {
        Files.createDirectory(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
    }
    override fun setPermissions(path: Path, mode: Set<PosixFilePermission>) { Files.setPosixFilePermissions(path, mode) }
    override fun createSymbolicLink(path: Path, target: Path) { Files.createSymbolicLink(path, target) }
}
