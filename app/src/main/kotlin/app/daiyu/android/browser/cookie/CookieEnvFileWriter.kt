package app.daiyu.android.browser.cookie

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions

/** Trusted host file operations; never provided by tool arguments. */
interface CookieEnvFileWriter {
    fun create(part: Path)
    fun write(part: Path, bytes: ByteArray)
    fun verify(part: Path)
}

internal object PosixCookieEnvFileWriter : CookieEnvFileWriter {
    override fun create(part: Path) {
        Files.createFile(part, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
    }
    override fun write(part: Path, bytes: ByteArray) {
        Files.newOutputStream(part, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { it.write(bytes) }
    }
    override fun verify(part: Path) {
        check(Files.getPosixFilePermissions(part, LinkOption.NOFOLLOW_LINKS) == PosixFilePermissions.fromString("rw-------"))
    }
}
