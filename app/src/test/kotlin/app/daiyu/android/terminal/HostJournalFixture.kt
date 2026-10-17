package app.daiyu.android.terminal

import org.json.JSONObject
import java.io.File

// Windows NIO cannot fsync a directory. Only that acknowledgment is simulated;
// ledger bytes, file fsync, atomic replacement and locks still use the host filesystem.
internal fun hostDirectorySync(directory: File) {
    check(directory.isDirectory)
    if (!System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) {
        TerminalDurability.syncDirectory(directory)
    }
}

internal fun hostJournal(directory: File, scope: String, limit: Int = 256, binding: JSONObject? = null) =
    OperationJournal(directory, scope, limit, binding, ::hostDirectorySync)


internal fun hostLinuxStore(directory: File) = LinuxEnvironmentStore(directory, ::hostDirectorySync, ::hostLinuxFileKey)
internal fun hostLinuxCatalog(directory: File, directorySync: (File) -> Unit = ::hostDirectorySync,
    beforeApprovalGate: () -> Unit = {}) = TrustedLinuxCatalog(directory, directorySync, beforeApprovalGate, fileKey = ::hostLinuxFileKey)

// Windows metadata identity is modeled; Android and POSIX hosts retain the strict NIO key.
internal object HostLinuxIdentity {
    private data class Entry(val created: java.nio.file.attribute.FileTime, val token: String)
    private val entries = mutableMapOf<java.nio.file.Path, Entry>()
    @Synchronized fun read(path: java.nio.file.Path): Any? {
        if (File.separatorChar != '\\') return strictLinuxFileKey(path)
        val real = path.toRealPath()
        val created = java.nio.file.Files.readAttributes(real,
            java.nio.file.attribute.BasicFileAttributes::class.java).creationTime()
        val old = entries[real]
        return if (old != null && old.created == created) old.token
            else Entry(created, java.util.UUID.randomUUID().toString()).also { entries[real] = it }.token
    }
    @Synchronized fun replaced(path: java.nio.file.Path) { entries.remove(path.toAbsolutePath().normalize()) }
}
internal fun hostLinuxFileKey(path: java.nio.file.Path): Any? = HostLinuxIdentity.read(path)
