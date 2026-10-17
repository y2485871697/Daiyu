package app.daiyu.android.terminal.install

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFilePermissions

/** Linux uses actual creation permissions; Windows tests preserve real host ACLs. */
internal object HostInstallFileOps : RootlessLinuxInstaller.FileOps {
    private fun attributes(parent: File): Array<FileAttribute<*>> =
        if ((Files.getFileAttributeView(parent.toPath(), java.nio.file.attribute.PosixFileAttributeView::class.java, java.nio.file.LinkOption.NOFOLLOW_LINKS) != null))
            arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        else { check(java.io.File.separatorChar == '\\'); emptyArray() }
    override fun createPrivateDirectory(directory: File) {
        Files.createDirectory(directory.toPath(), *attributes(checkNotNull(directory.parentFile)))
    }
    override fun createPrivateStaging(parent: File): File =
        Files.createTempDirectory(parent.toPath(), ".staging-", *attributes(parent)).toFile()
    fun permissions(file: File): Any = if ((Files.getFileAttributeView(file.toPath(), java.nio.file.attribute.PosixFileAttributeView::class.java, java.nio.file.LinkOption.NOFOLLOW_LINKS) != null))
        Files.getPosixFilePermissions(file.toPath()).toSet()
    else checkNotNull(Files.getFileAttributeView(file.toPath(), AclFileAttributeView::class.java)).acl.toList()
}

internal class HostInstallLinkFileOps : RootlessLinuxInstaller.FileOps by HostInstallFileOps {
    private val links = mutableMapOf<java.nio.file.Path, java.nio.file.Path>()
    private fun key(path: java.nio.file.Path) = path.toAbsolutePath().normalize()
    override fun isSymbolicLink(path: java.nio.file.Path) = Files.isSymbolicLink(path) || links.containsKey(key(path))
    override fun canonicalFile(file: File): File = links[key(file.toPath())]?.toFile()?.canonicalFile ?: file.canonicalFile
    fun createSymbolicLink(path: java.nio.file.Path, target: java.nio.file.Path) {
        if (File.separatorChar != '\\') Files.createSymbolicLink(path, target)
        else { Files.createFile(path); links[key(path)] = target }
    }
    fun delete(path: java.nio.file.Path) { Files.delete(path); links.remove(key(path)) }
}
