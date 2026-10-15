package app.daiyu.android.terminal

import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

internal object TerminalDurability {
    fun ensureDirectory(directory: File) {
        check(directory.mkdirs() || directory.isDirectory) { "Cannot create terminal directory" }
        syncDirectory(directory)
        directory.parentFile?.let(::syncDirectory)
    }

    fun replace(file: File, bytes: ByteArray, beforeDirectorySync: (() -> Unit)? = null) {
        val temp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(temp).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        // The private files directory is one filesystem. Never degrade to non-atomic copying.
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        beforeDirectorySync?.invoke()
        syncDirectory(file.parentFile!!)
    }

    fun syncDirectory(directory: File) {
        FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }
}
